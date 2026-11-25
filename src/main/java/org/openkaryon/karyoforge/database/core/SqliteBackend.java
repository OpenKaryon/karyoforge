package org.openkaryon.karyoforge.database.core;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

final class SqliteBackend implements JdbcBackend {
    private static final Set<String> READ_COMMANDS = Set.of("SELECT", "WITH", "EXPLAIN");
    private static final Set<String> WRITE_COMMANDS = Set.of("SELECT", "WITH", "VALUES", "INSERT", "UPDATE",
            "DELETE", "REPLACE", "CREATE", "ALTER", "DROP", "EXPLAIN", "ANALYZE", "REINDEX");
    private final SqliteSource source;
    private final ResultLimits limits;

    SqliteBackend(SqliteSource source, ResultLimits limits) {
        this.source = source;
        this.limits = limits;
    }

    @Override public ResultLimits limits() { return limits; }

    @Override public String driverClass() { return "org.sqlite.JDBC"; }

    @Override public Connection connect(Driver driver, char[] password) throws SQLException {
        if (!Files.isRegularFile(source.database())) throw new DatabaseProblem("Choose an existing local SQLite database file.");
        var settings = new Properties();
        settings.setProperty("busy_timeout", "5000");
        settings.setProperty("enable_load_extension", "false");
        return driver.connect(source.jdbcUrl(), settings);
    }

    @Override public void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            int expectedQueryOnly = source.writable() ? 0 : 1;
            statement.execute("PRAGMA query_only = " + expectedQueryOnly);
            try (var rows = statement.executeQuery("PRAGMA query_only")) {
                if (!rows.next() || rows.getInt(1) != expectedQueryOnly) {
                    throw new SQLException("Could not establish the selected SQLite access mode.");
                }
            }
        }
        if (source.writable()) connection.setAutoCommit(false);
    }

    @Override public List<JdbcSession.SchemaObject> schema(Connection connection) throws SQLException {
        var result = new ArrayList<JdbcSession.SchemaObject>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT name, type FROM main.sqlite_schema
                     WHERE type IN ('table', 'view') AND name NOT GLOB 'sqlite_*'
                     ORDER BY name COLLATE NOCASE
                     LIMIT
                     """ + limits.catalogLimit())) {
            while (rows.next() && result.size() < limits.catalogLimit()) {
                String name = rows.getString(1);
                if (name.length() <= 1024 && name.indexOf('\0') < 0) result.add(new JdbcSession.SchemaObject(name, rows.getString(2)));
            }
        }
        return List.copyOf(result);
    }

    @Override public TableDefinition describe(Connection connection, JdbcSession.SchemaObject object) throws SQLException {
        String ddl;
        try (var statement = connection.prepareStatement("""
                SELECT substr(sql, 1, 65537) FROM main.sqlite_schema
                WHERE name = ? AND type = ?
                """)) {
            statement.setString(1, object.name());
            statement.setString(2, object.type());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new DatabaseProblem("The selected table or view no longer exists.");
                ddl = rows.getString(1);
            }
        }
        var columns = new ArrayList<TableDefinition.Column>();
        try (var statement = connection.prepareStatement("""
                SELECT name, type, "notnull", pk, hidden FROM pragma_table_xinfo(?, 'main') ORDER BY cid
                """)) {
            statement.setString(1, object.name());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    limits.checkColumns(columns.size() + 1);
                    String name = rows.getString(1);
                    String type = rows.getString(2);
                    if (name.length() > 1024 || type.length() > 1024) {
                        throw new DatabaseProblem("A column name or declaration exceeds 1024 characters.");
                    }
                    columns.add(new TableDefinition.Column(name, type, rows.getInt(3) != 0, rows.getInt(4), rows.getInt(5)));
                }
            }
        }
        return TableDefinition.snapshot(object, columns, ddl);
    }

    @Override public TableSnapshot openTable(Connection connection, TableDefinition definition, QueryRequest request) throws SQLException {
        return SqliteTableEditor.browse(connection, definition, source.writable(), limits, request);
    }

    @Override public int applyTableChanges(Connection connection, TableSnapshot snapshot, List<TableSnapshot.Change> changes,
                                           QueryRequest request, Runnable quarantine) throws SQLException {
        return SqliteTableEditor.apply(connection, snapshot, changes, request, quarantine);
    }

    @Override public void validate(SqlText.Command command) {
        var allowed = source.writable() ? WRITE_COMMANDS : READ_COMMANDS;
        if (!allowed.contains(command.keyword())) {
            throw new IllegalArgumentException(source.writable()
                    ? "This SQLite command is unavailable. Use Commit and Rollback to control the transaction."
                    : "Enter one SELECT, WITH, or plan-only EXPLAIN query.");
        }
    }
}
