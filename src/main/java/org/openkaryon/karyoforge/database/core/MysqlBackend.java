package org.openkaryon.karyoforge.database.core;

import org.openkaryon.karyoforge.database.driver.PinnedJdbcBundle;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

final class MysqlBackend implements JdbcBackend {
    private static final PinnedJdbcBundle.Jar CONNECTOR = new PinnedJdbcBundle.Jar("mysql-connector-j-26.7.0.jar", 2_613_206,
            "69084713593a4aa8d07c383619b9639276f08bccf8faf1c562178147d389b1e1");
    private static final Set<String> READ_COMMANDS = Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC");
    private static final Set<String> WRITE_COMMANDS = Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC",
            "INSERT", "UPDATE", "DELETE", "REPLACE");
    private final MysqlSource source;
    private PinnedJdbcBundle bundle;

    MysqlBackend(MysqlSource source) { this.source = source; }

    @Override public String driverClass() { return "com.mysql.cj.jdbc.Driver"; }

    @Override public List<Path> selectDrivers(Path driver) throws IOException {
        bundle = PinnedJdbcBundle.open(driver, List.of(CONNECTOR));
        return bundle.paths();
    }

    @Override public void releaseDriverFiles() throws IOException {
        if (bundle != null) {
            bundle.close();
            bundle = null;
        }
    }

    @Override public Connection connect(Driver driver, char[] password) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", source.user());
        properties.setProperty("password", password == null ? "" : new String(password));
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "35000");
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("allowLoadLocalInfile", "false");
        properties.setProperty("allowUrlInLocalInfile", "false");
        properties.setProperty("allowPublicKeyRetrieval", "true");
        properties.setProperty("sslMode", "DISABLED");
        properties.setProperty("fallbackToSystemKeyStore", "false");
        properties.setProperty("fallbackToSystemTrustStore", "false");
        properties.setProperty("readOnlyPropagatesToServer", "true");
        properties.setProperty("useCursorFetch", "true");
        properties.setProperty("disabledAuthenticationPlugins",
                "authentication_oci_client,authentication_kerberos_client,authentication_ldap_sasl_client,"
                + "authentication_webauthn_client,authentication_openid_connect_client,mysql_clear_password");
        properties.setProperty("logger", "com.mysql.cj.log.NullLogger");
        try {
            return driver.connect(source.jdbcUrl(), properties);
        } finally {
            properties.clear();
        }
    }

    @Override public void initialize(Connection connection) throws SQLException {
        connection.setReadOnly(!source.writable());
        connection.setAutoCommit(false);
        beforeStatement(connection);
    }

    @Override public void beforeStatement(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            try (var row = statement.executeQuery("SELECT @@session.transaction_read_only, @@session.sql_mode")) {
                if (!row.next() || row.getInt(1) != (source.writable() ? 0 : 1)) {
                    throw new DatabaseProblem("MySQL did not establish the selected transaction access mode.");
                }
                String modes = row.getString(2);
                if (modes != null) {
                    for (String mode : modes.toUpperCase(Locale.ROOT).split(",")) {
                        if (mode.strip().equals("ANSI_QUOTES") || mode.strip().equals("NO_BACKSLASH_ESCAPES")) {
                            throw new DatabaseProblem("MySQL requires standard quote and backslash parsing. Correct the server SQL mode and reconnect.");
                        }
                    }
                }
            }
        }
    }

    @Override public void configureStatement(PreparedStatement statement) throws SQLException {
        statement.setQueryTimeout(30);
        statement.setFetchSize(JdbcSession.PAGE_SIZE);
    }

    @Override public List<JdbcSession.SchemaObject> schema(Connection connection) throws SQLException {
        var objects = new ArrayList<JdbcSession.SchemaObject>();
        try (var statement = connection.prepareStatement("""
                SELECT table_schema, table_name, IF(table_type='VIEW','view','table')
                FROM information_schema.tables WHERE table_schema=? AND table_type IN ('BASE TABLE','VIEW')
                ORDER BY table_name LIMIT 2000
                """)) {
            statement.setQueryTimeout(30);
            statement.setString(1, source.database());
            try (var rows = statement.executeQuery()) {
                while (rows.next() && objects.size() < CATALOG_LIMIT) {
                    objects.add(new JdbcSession.SchemaObject(rows.getString(2), rows.getString(3), rows.getString(1), SqlText.Dialect.MYSQL));
                }
            }
        }
        return List.copyOf(objects);
    }

    @Override public TableDefinition describe(Connection connection, JdbcSession.SchemaObject object) throws SQLException {
        if (!object.schema().equals(source.database())) throw new DatabaseProblem("Choose an object in the selected data source.");
        var columns = new ArrayList<TableDefinition.Column>();
        try (var statement = connection.prepareStatement("""
                SELECT c.column_name, c.column_type, c.is_nullable, COALESCE(k.ordinal_position,0), c.extra
                FROM information_schema.columns c
                LEFT JOIN information_schema.key_column_usage k ON k.table_schema=c.table_schema
                     AND k.table_name=c.table_name AND k.column_name=c.column_name AND k.constraint_name='PRIMARY'
                WHERE c.table_schema=? AND c.table_name=? ORDER BY c.ordinal_position
                """)) {
            statement.setQueryTimeout(30);
            statement.setString(1, object.schema());
            statement.setString(2, object.name());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() >= COLUMN_LIMIT) throw new DatabaseProblem("The object exceeds 256 columns.");
                    String name = rows.getString(1);
                    String type = rows.getString(2);
                    if (name.length() > 1024 || type.length() > 1024) throw new DatabaseProblem("A column declaration exceeds 1024 characters.");
                    String extra = rows.getString(5).toUpperCase(Locale.ROOT);
                    int storage = extra.contains("VIRTUAL GENERATED") ? 2 : extra.contains("STORED GENERATED") ? 3 : 0;
                    columns.add(new TableDefinition.Column(name, type, rows.getString(3).equals("NO"), rows.getInt(4), storage));
                }
            }
        }
        if (columns.isEmpty()) throw new DatabaseProblem("The selected MySQL object no longer has visible columns.");
        String ddl;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            String kind = object.type().equals("view") ? "VIEW " : "TABLE ";
            try (var rows = statement.executeQuery("SHOW CREATE " + kind + object.qualified())) {
                if (!rows.next()) throw new DatabaseProblem("The selected MySQL definition is unavailable.");
                ddl = rows.getString(2);
            }
        }
        return TableDefinition.snapshot(object, columns, ddl);
    }

    @Override public void validate(SqlText.Command command) {
        var allowed = source.writable() ? WRITE_COMMANDS : READ_COMMANDS;
        if (command.keyword().equals("EXPLAIN") && Set.of("SELECT", "WITH").contains(SqlText.followingKeyword(command))) return;
        if (!allowed.contains(command.keyword())) {
            throw new IllegalArgumentException("This MySQL command is unavailable in a managed transaction.");
        }
    }
}
