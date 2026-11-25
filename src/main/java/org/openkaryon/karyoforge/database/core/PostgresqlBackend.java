package org.openkaryon.karyoforge.database.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.openkaryon.karyoforge.database.driver.ForwardSocketFactory;

final class PostgresqlBackend implements JdbcBackend {
    private static final Set<String> READ_COMMANDS = Set.of("SELECT", "WITH", "VALUES", "TABLE", "SHOW", "EXPLAIN");
    private static final Set<String> WRITE_COMMANDS = Set.of("SELECT", "WITH", "VALUES", "TABLE", "SHOW", "EXPLAIN",
            "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "COMMENT", "GRANT", "REVOKE",
            "TRUNCATE", "REINDEX", "ANALYZE", "REFRESH");
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final String FORWARDING_DRIVER_SHA256 =
            "73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c";
    private final PostgresqlSource source;
    private Path verifiedDriver;
    private OpenSshTunnel tunnel;
    private Class<?> forwardSockets;

    PostgresqlBackend(PostgresqlSource source) { this.source = source; }

    @Override public String driverClass() { return "org.postgresql.Driver"; }

    @Override public Path selectDriver(Path driver) throws IOException {
        if (source.forwarding() == null) return driver;
        if (!Files.isRegularFile(driver) || Files.size(driver) > 4 * 1024 * 1024) throw forwardingDriverRequired();
        verifiedDriver = Files.createTempFile("karyoforge-pgjdbc-", ".jar");
        Files.copy(driver, verifiedDriver, StandardCopyOption.REPLACE_EXISTING);
        byte[] digest;
        try (var input = new DigestInputStream(Files.newInputStream(verifiedDriver), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
            digest = input.getMessageDigest().digest();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("Could not verify the PostgreSQL driver.");
        }
        if (!FORWARDING_DRIVER_SHA256.equals(HexFormat.of().formatHex(digest))) throw forwardingDriverRequired();
        return verifiedDriver;
    }

    private static DatabaseProblem forwardingDriverRequired() {
        return new DatabaseProblem("PostgreSQL SSH forwarding requires the verified pgJDBC 42.7.14 JAR.");
    }

    @Override public Connection connect(Driver driver, char[] password) throws SQLException {
        if (source.verifiedTls() && !Files.isRegularFile(source.caFile())) {
            throw new DatabaseProblem("Choose an existing PostgreSQL CA certificate file.");
        }
        var properties = new Properties();
        properties.setProperty("user", source.user());
        properties.setProperty("password", password == null ? "" : new String(password));
        if (source.verifiedTls()) {
            properties.setProperty("sslmode", "verify-full");
            properties.setProperty("sslrootcert", source.caFile().toString());
        } else {
            properties.setProperty("sslmode", "disable");
        }
        properties.setProperty("gssEncMode", "disable");
        properties.setProperty("sslcert", "");
        properties.setProperty("sslkey", "");
        properties.setProperty("readOnlyMode", "always");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "35");
        properties.setProperty("cancelSignalTimeout", "5");
        properties.setProperty("ApplicationName", "KaryoForge Databases");
        try {
            if (source.forwarding() != null) configureForwarding(driver, properties);
            return driver.connect(source.jdbcUrl(), properties);
        } finally {
            properties.clear();
        }
    }

    private void configureForwarding(Driver driver, Properties properties) {
        tunnel = new OpenSshTunnel(source.forwarding());
        tunnel.open();
        try {
            String name = ForwardSocketFactory.class.getName();
            forwardSockets = Class.forName(name, true, driver.getClass().getClassLoader());
            forwardSockets.getMethod("configure", int.class, BooleanSupplier.class)
                    .invoke(null, tunnel.port(), (BooleanSupplier) tunnel::healthy);
            properties.setProperty("socketFactory", name);
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            throw new DatabaseProblem("The PostgreSQL SSH socket adapter could not be initialized.");
        }
    }

    @Override public void closeTransport() throws IOException {
        try {
            if (forwardSockets != null) {
                forwardSockets.getMethod("closeOwned").invoke(null);
                forwardSockets = null;
            }
        } catch (ReflectiveOperationException | LinkageError problem) {
            throw new IOException("Could not close the owned PostgreSQL SSH sockets.");
        } finally {
            if (tunnel != null) {
                tunnel.close();
                tunnel = null;
            }
        }
    }

    @Override public void releaseDriverFiles() throws IOException {
        if (verifiedDriver != null) {
            Files.deleteIfExists(verifiedDriver);
            verifiedDriver = null;
        }
    }

    @Override public void initialize(Connection connection) throws SQLException {
        connection.setReadOnly(!source.writable());
        connection.setAutoCommit(false);
        beforeStatement(connection);
    }

    @Override public void beforeStatement(Connection connection) throws SQLException {
        if (tunnel != null && !tunnel.healthy()) throw new DatabaseProblem("The owned SSH tunnel stopped. Disconnect and reconnect the source.");
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (var rows = statement.executeQuery("""
                    SELECT pg_catalog.current_setting('transaction_read_only'),
                           pg_catalog.current_setting('standard_conforming_strings')
                    """)) {
                if (!rows.next() || !(source.writable() ? "off" : "on").equals(rows.getString(1))) {
                    throw new DatabaseProblem("PostgreSQL did not establish the selected transaction access mode.");
                }
                if (!"on".equals(rows.getString(2))) {
                    throw new DatabaseProblem("PostgreSQL requires standard_conforming_strings. Disconnect and correct the session settings.");
                }
            }
        }
    }

    @Override public void configureStatement(PreparedStatement statement) throws SQLException {
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        statement.setFetchSize(JdbcSession.PAGE_SIZE);
    }

    @Override public boolean statementSavepoints() { return true; }

    @Override public List<JdbcSession.SchemaObject> schema(Connection connection) throws SQLException {
        var result = new ArrayList<JdbcSession.SchemaObject>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (var rows = statement.executeQuery("""
                    SELECT n.nspname, c.relname,
                           CASE WHEN c.relkind IN ('v','m') THEN 'view' ELSE 'table' END,
                           CASE WHEN c.relkind IN ('r','p') AND c.reltuples >= 0 THEN c.reltuples::bigint END
                    FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind IN ('r','p','v','m','f')
                      AND n.nspname <> 'information_schema'
                      AND pg_catalog.left(n.nspname, 3) <> 'pg_'
                      AND pg_catalog.has_schema_privilege(n.oid, 'USAGE')
                    ORDER BY n.nspname, c.relname LIMIT 2000
                    """)) {
                while (rows.next() && result.size() < CATALOG_LIMIT) {
                    result.add(new JdbcSession.SchemaObject(rows.getString(2), rows.getString(3), rows.getString(1),
                            SqlText.Dialect.POSTGRESQL, JdbcSession.SchemaObject.estimate(rows.getString(4))));
                }
            }
        }
        return List.copyOf(result);
    }

    @Override public TableDefinition describe(Connection connection, JdbcSession.SchemaObject object) throws SQLException {
        long oid;
        String kind;
        String viewDefinition;
        try (var statement = connection.prepareStatement("""
                SELECT c.oid::bigint, c.relkind, CASE WHEN c.relkind IN ('v','m')
                    THEN pg_catalog.left(pg_catalog.pg_get_viewdef(c.oid, true), 65537) ELSE NULL END
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r','p','v','m','f')
                """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, object.schema());
            statement.setString(2, object.name());
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new DatabaseProblem("The selected PostgreSQL object no longer exists.");
                oid = row.getLong(1);
                kind = row.getString(2);
                viewDefinition = row.getString(3);
            }
        }

        var columns = new ArrayList<TableDefinition.Column>();
        var declarations = new ArrayList<String>();
        try (var statement = connection.prepareStatement("""
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),
                       a.attnotnull, COALESCE(pg_catalog.array_position(p.conkey, a.attnum), 0),
                       a.attgenerated, a.attidentity,
                       pg_catalog.left(pg_catalog.pg_get_expr(d.adbin, d.adrelid), 4096)
                FROM pg_catalog.pg_attribute a
                LEFT JOIN pg_catalog.pg_constraint p ON p.conrelid = a.attrelid AND p.contype = 'p'
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = CAST(? AS oid) AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() == COLUMN_LIMIT) throw new DatabaseProblem("The object exceeds 256 columns.");
                    String name = rows.getString(1);
                    String type = rows.getString(2);
                    if (name.length() > 1024 || type.length() > 1024) {
                        throw new DatabaseProblem("A column name or declaration exceeds 1024 characters.");
                    }
                    boolean notNull = rows.getBoolean(3);
                    boolean generated = !rows.getString(5).isEmpty();
                    String identity = rows.getString(6);
                    String expression = rows.getString(7);
                    columns.add(new TableDefinition.Column(name, type, notNull, rows.getInt(4), generated ? 3 : !identity.isEmpty() ? 4 : 0));
                    String declaration = JdbcSession.SchemaObject.quote(name) + " " + type;
                    if (generated && expression != null) declaration += " GENERATED ALWAYS AS (" + expression + ") STORED";
                    else if (!identity.isEmpty()) declaration += " GENERATED " + (identity.equals("a") ? "ALWAYS" : "BY DEFAULT") + " AS IDENTITY";
                    else if (expression != null) declaration += " DEFAULT " + expression;
                    if (notNull) declaration += " NOT NULL";
                    declarations.add(declaration);
                }
            }
        }
        if (viewDefinition != null) {
            String prefix = kind.equals("m") ? "CREATE MATERIALIZED VIEW " : "CREATE VIEW ";
            return TableDefinition.snapshot(object, columns, "-- PostgreSQL catalog description\n"
                    + prefix + object.qualified() + " AS\n" + viewDefinition);
        }
        try (var statement = connection.prepareStatement("""
                SELECT conname, pg_catalog.left(pg_catalog.pg_get_constraintdef(oid, true), 4096)
                FROM pg_catalog.pg_constraint WHERE conrelid = CAST(? AS oid) ORDER BY conname LIMIT 256
                """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) declarations.add("CONSTRAINT " + JdbcSession.SchemaObject.quote(rows.getString(1)) + " " + rows.getString(2));
            }
        }
        String ddl = "-- PostgreSQL table description from catalog metadata\nCREATE TABLE " + object.qualified()
                + " (\n    " + String.join(",\n    ", declarations) + "\n);";
        return TableDefinition.snapshot(object, columns, ddl);
    }

    @Override public TableSnapshot openTable(Connection connection, TableDefinition definition, QueryRequest request)
            throws SQLException, IOException {
        return PostgresqlTableEditor.browse(connection, definition, source.writable(), request);
    }

    @Override public int applyTableChanges(Connection connection, TableSnapshot snapshot, List<TableSnapshot.Change> changes,
                                           QueryRequest request, Runnable quarantine) throws SQLException {
        return PostgresqlTableEditor.apply(connection, this, snapshot, changes, request, quarantine);
    }

    @Override public void validate(SqlText.Command command) {
        var allowed = source.writable() ? WRITE_COMMANDS : READ_COMMANDS;
        if (!allowed.contains(command.keyword())) {
            throw new IllegalArgumentException("This PostgreSQL command is unavailable. Connection and transaction settings are managed by the source.");
        }
    }
}
