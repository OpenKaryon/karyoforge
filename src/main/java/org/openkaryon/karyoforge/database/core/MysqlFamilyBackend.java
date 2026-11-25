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

final class MysqlFamilyBackend implements JdbcBackend {
    private static final PinnedJdbcBundle.Jar MYSQL_CONNECTOR = new PinnedJdbcBundle.Jar("mysql-connector-j-26.7.0.jar", 2_613_206,
            "69084713593a4aa8d07c383619b9639276f08bccf8faf1c562178147d389b1e1");
    private static final List<PinnedJdbcBundle.Jar> MARIADB_CONNECTOR = List.of(
            new PinnedJdbcBundle.Jar("mariadb-java-client-3.5.10.jar", 791_739,
                    "919b8c1c771d9ee3465811462f242c9543ab401e140c64988ddbf1d8abcb18b2"),
            new PinnedJdbcBundle.Jar("slf4j-api-2.0.17.jar", 69_908,
                    "7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832"),
            new PinnedJdbcBundle.Jar("slf4j-nop-2.0.17.jar", 4_982,
                    "3716f83649ec66161a2edefd4f49df34d1dd1c51cdcf941996c6987260f0a829"));
    private static final Set<String> READ_COMMANDS = Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC");
    private static final Set<String> WRITE_COMMANDS = Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC",
            "INSERT", "UPDATE", "DELETE", "REPLACE");
    private final MysqlSource source;
    private final ResultLimits limits;
    private PinnedJdbcBundle bundle;

    MysqlFamilyBackend(MysqlSource source, ResultLimits limits) {
        this.source = source;
        this.limits = limits;
    }

    @Override public ResultLimits limits() { return limits; }

    private boolean maria() { return source.variant() == MysqlSource.Variant.MARIADB; }

    @Override public String driverClass() { return maria() ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver"; }

    @Override public List<Path> selectDrivers(Path driver) throws IOException {
        bundle = PinnedJdbcBundle.open(driver, maria() ? MARIADB_CONNECTOR : List.of(MYSQL_CONNECTOR));
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
        if (maria()) {
            properties.setProperty("sslMode", "disable");
            properties.setProperty("allowLocalInfile", "false");
            properties.setProperty("restrictedAuth", "mysql_native_password,caching_sha2_password,sha256_password,client_ed25519");
            properties.setProperty("useServerPrepStmts", "true");
        } else {
            mysqlProperties(properties);
        }
        try {
            return driver.connect(source.jdbcUrl(), properties);
        } finally {
            properties.clear();
        }
    }

    private static void mysqlProperties(Properties properties) {
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
    }

    @Override public void initialize(Connection connection) throws SQLException {
        connection.setReadOnly(!source.writable());
        if (maria()) {
            try (var statement = connection.createStatement()) {
                statement.execute(source.writable() ? "SET SESSION TRANSACTION READ WRITE" : "SET SESSION TRANSACTION READ ONLY");
            }
        }
        connection.setAutoCommit(false);
        beforeStatement(connection);
    }

    @Override public void beforeStatement(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            String variable = maria() ? "tx_read_only" : "transaction_read_only";
            try (var row = statement.executeQuery("SELECT @@session." + variable + ", @@session.sql_mode")) {
                if (!row.next() || row.getInt(1) != (source.writable() ? 0 : 1)) {
                    throw new DatabaseProblem(source.engine() + " did not establish the selected transaction access mode.");
                }
                String modes = row.getString(2);
                if (modes != null) {
                    for (String mode : modes.toUpperCase(Locale.ROOT).split(",")) {
                        if (mode.strip().equals("ANSI_QUOTES") || mode.strip().equals("NO_BACKSLASH_ESCAPES")) {
                            throw new DatabaseProblem(source.engine() + " requires standard quote and backslash parsing. Correct the server SQL mode and reconnect.");
                        }
                    }
                }
            }
        }
    }

    @Override public void configureStatement(PreparedStatement statement) throws SQLException {
        statement.setQueryTimeout(30);
        statement.setFetchSize(limits.pageSize());
    }

    @Override public List<JdbcSession.SchemaObject> schema(Connection connection) throws SQLException {
        var objects = new ArrayList<JdbcSession.SchemaObject>();
        try (var statement = connection.prepareStatement("""
                SELECT table_schema, table_name, IF(table_type='VIEW','view','table'), table_rows
                FROM information_schema.tables WHERE table_schema=? AND table_type IN ('BASE TABLE','VIEW')
                ORDER BY table_name LIMIT
                """ + limits.catalogLimit())) {
            statement.setQueryTimeout(30);
            statement.setString(1, source.database());
            try (var rows = statement.executeQuery()) {
                while (rows.next() && objects.size() < limits.catalogLimit()) {
                    objects.add(new JdbcSession.SchemaObject(rows.getString(2), rows.getString(3), rows.getString(1),
                            SqlText.Dialect.MYSQL, JdbcSession.SchemaObject.estimate(rows.getString(4))));
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
                    limits.checkColumns(columns.size() + 1);
                    String name = rows.getString(1);
                    String type = rows.getString(2);
                    if (name.length() > 1024 || type.length() > 1024) throw new DatabaseProblem("A column declaration exceeds 1024 characters.");
                    String extra = rows.getString(5).toUpperCase(Locale.ROOT);
                    int storage = extra.contains("VIRTUAL GENERATED") ? 2 : (extra.contains("STORED GENERATED") || extra.contains("PERSISTENT GENERATED")) ? 3 : 0;
                    columns.add(new TableDefinition.Column(name, type, rows.getString(3).equals("NO"), rows.getInt(4), storage));
                }
            }
        }
        if (columns.isEmpty()) throw new DatabaseProblem("The selected object no longer has visible columns.");
        String ddl;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            String kind = object.type().equals("view") ? "VIEW " : "TABLE ";
            try (var rows = statement.executeQuery("SHOW CREATE " + kind + object.qualified())) {
                if (!rows.next()) throw new DatabaseProblem("The selected object definition is unavailable.");
                ddl = rows.getString(2);
            }
        }
        return TableDefinition.snapshot(object, columns, ddl);
    }

    @Override public void validate(SqlText.Command command) {
        var allowed = source.writable() ? WRITE_COMMANDS : READ_COMMANDS;
        if (command.keyword().equals("EXPLAIN") && Set.of("SELECT", "WITH").contains(SqlText.followingKeyword(command))) return;
        if (!allowed.contains(command.keyword())) {
            throw new IllegalArgumentException("This " + source.engine() + " command is unavailable in a managed transaction.");
        }
    }
}
