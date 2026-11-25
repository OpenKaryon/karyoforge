package org.openkaryon.karyoforge.database.core;

import org.openkaryon.karyoforge.database.driver.DriverLease;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Owns the connection and retained cursor. Except for QueryRequest.stop, use one command lane. */
public final class JdbcSession implements AutoCloseable {
    public static final int PAGE_SIZE = 200;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CELL_CHARS = 16_384;
    private static final int MAX_SCHEMA_OBJECTS = 2000;
    private static final Set<String> READ_COMMANDS = Set.of("SELECT", "WITH");
    private static final Set<String> WRITE_COMMANDS = Set.of("SELECT", "WITH", "VALUES", "INSERT", "UPDATE",
            "DELETE", "REPLACE", "CREATE", "ALTER", "DROP", "EXPLAIN", "ANALYZE", "REINDEX");
    private final SqliteSource source;
    private DriverLease lease;
    private Connection connection;
    private Cursor cursor;
    private boolean ready;
    private boolean quarantined;

    public JdbcSession(SqliteSource source) { this.source = source; }

    public void connect() {
        if (lease != null || connection != null) throw new DatabaseProblem("Disconnect before reopening this data source.");
        if (!Files.isRegularFile(source.database())) throw new DatabaseProblem("Choose an existing local SQLite database file.");
        try {
            lease = DriverLease.open(source.driver(), "org.sqlite.JDBC");
        } catch (IOException failure) {
            throw new DatabaseProblem("Choose a trusted local SQLite JDBC JAR that can be loaded.");
        }
        Properties settings = new Properties();
        settings.setProperty("busy_timeout", "5000");
        settings.setProperty("enable_load_extension", "false");
        try {
            connection = lease.driver().connect(source.jdbcUrl(), settings);
            if (connection == null) throw new SQLException("The driver rejected the data source.");
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
            quarantined = false;
            ready = true;
        } catch (SQLException | RuntimeException failure) {
            close();
            throw new DatabaseProblem("Could not open the selected SQLite database in "
                    + (source.writable() ? "read-write" : "read-only") + " mode.");
        } finally {
            settings.clear();
        }
    }

    public List<SchemaObject> schema() {
        checkReady();
        var result = new ArrayList<SchemaObject>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT name, type FROM main.sqlite_schema
                     WHERE type IN ('table', 'view') AND name NOT GLOB 'sqlite_*'
                     ORDER BY name COLLATE NOCASE
                     LIMIT 2000
                     """)) {
            while (rows.next() && result.size() < MAX_SCHEMA_OBJECTS) {
                String name = rows.getString(1);
                if (name.length() <= 1024 && name.indexOf('\0') < 0) result.add(new SchemaObject(name, rows.getString(2)));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw DatabaseProblem.query("Reading the schema", error);
        }
    }

    public TableDefinition describe(SchemaObject object) {
        checkReady();
        try {
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
                        if (columns.size() == MAX_COLUMNS) throw new DatabaseProblem("The object exceeds 256 columns.");
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
        } catch (SQLException failure) {
            throw DatabaseProblem.query("Reading the object definition", failure);
        }
    }

    public SqlResult execute(SqlText.Command command, Map<String, SqlValue> values, QueryRequest request) {
        checkReady();
        if (!allowedCommands().contains(command.keyword())) {
            throw new IllegalArgumentException(source.writable()
                    ? "This SQLite command is unavailable. Use Commit and Rollback to control the transaction."
                    : "Enter one SELECT or WITH query.");
        }
        var bindings = Map.copyOf(values);
        if (!bindings.keySet().containsAll(command.parameters()) || bindings.size() != command.parameters().size()) {
            throw new IllegalArgumentException("Provide one value for each SQL parameter.");
        }
        request.check();
        closeCursor();
        return this.<SqlResult>requestResult(request, () -> {
            var statement = connection.prepareStatement(command.sql());
            cursor = new Cursor(statement);
            request.attach(statement);
            if (statement.getParameterMetaData().getParameterCount() != command.bindingOrder().size()) {
                throw new DatabaseProblem("The driver's SQL parameter count differs from the console's bindings.");
            }
            int index = 1;
            for (String name : command.bindingOrder()) {
                request.check();
                bindings.get(name).bind(statement, index++);
            }
            if (!statement.execute()) return new SqlResult.UpdateCount(statement.getUpdateCount());
            cursor.rows = statement.getResultSet();
            cursor.columns = columns(cursor.rows.getMetaData());
            return readPage(cursor, request);
        });
    }

    private Set<String> allowedCommands() { return source.writable() ? WRITE_COMMANDS : READ_COMMANDS; }

    public QueryPage next(QueryRequest request) {
        checkReady();
        if (cursor == null) throw new DatabaseProblem("This query has no next page.");
        return requestResult(request, () -> {
            request.attach(cursor.statement);
            return readPage(cursor, request);
        });
    }

    private <T extends SqlResult> T requestResult(QueryRequest request, ReadResult<T> work) {
        T result = null;
        try {
            result = work.run();
        } catch (SQLException failure) {
            request.check();
            throw DatabaseProblem.query("Reading query results", failure);
        } catch (IOException failure) {
            request.check();
            throw new DatabaseProblem("Could not read a detached query value.");
        } finally {
            if (request.finish()) quarantined = true;
            if (request.stopped() || !(result instanceof QueryPage page) || !page.hasNext()) closeCursor();
        }
        if (quarantined) throw new DatabaseProblem("Stop could not be confirmed. Disconnect this data source.");
        request.check();
        return result;
    }

    public void finishTransaction(boolean commit) {
        checkReady();
        if (!source.writable()) throw new DatabaseProblem("This SQLite source is read-only.");
        closeCursor();
        try {
            if (commit) connection.commit();
            else connection.rollback();
        } catch (SQLException failure) {
            throw DatabaseProblem.query(commit ? "Committing the transaction" : "Rolling back the transaction", failure);
        }
    }

    private static List<QueryPage.Column> columns(ResultSetMetaData metadata) throws SQLException {
        int count = metadata.getColumnCount();
        if (count > MAX_COLUMNS) throw new DatabaseProblem("The result exceeds 256 columns.");
        var columns = new ArrayList<QueryPage.Column>();
        for (int index = 1; index <= count; index++) {
            String label = metadata.getColumnLabel(index);
            if (label.length() > 1024) throw new DatabaseProblem("A result column name exceeds 1024 characters.");
            columns.add(new QueryPage.Column(label, metadata.getColumnTypeName(index)));
        }
        return List.copyOf(columns);
    }

    private static QueryPage readPage(Cursor owner, QueryRequest request) throws SQLException, IOException {
        var page = new ArrayList<QueryPage.Row>();
        boolean limited = false;
        while (page.size() < PAGE_SIZE && owner.totalRows < MAX_ROWS) {
            request.check();
            boolean available = owner.positioned || owner.rows.next();
            owner.positioned = false;
            if (!available) break;
            var cells = new ArrayList<QueryPage.Cell>();
            int rowBytes = 0;
            for (int index = 1; index <= owner.columns.size(); index++) {
                request.check();
                int remaining = MAX_BYTES - owner.totalBytes - rowBytes;
                if (remaining < 4) { limited = true; break; }
                var cell = JdbcValues.sqlite(owner.rows, index, Math.min(MAX_CELL_CHARS, remaining / 4));
                rowBytes += JdbcValues.bytes(cell);
                if (rowBytes > MAX_BYTES - owner.totalBytes) { limited = true; break; }
                cells.add(cell);
            }
            if (limited) break;
            page.add(new QueryPage.Row(cells));
            owner.totalRows++;
            owner.totalBytes += rowBytes;
        }
        request.check();
        boolean more = !limited && page.size() == PAGE_SIZE && owner.rows.next();
        owner.positioned = more;
        String notice = limited ? "The 8 MiB result limit was reached." : "";
        if (more && owner.totalRows >= MAX_ROWS) {
            more = false;
            notice = "The 10,000-row result limit was reached.";
        }
        return new QueryPage(owner.columns, page, ++owner.page, owner.totalRows, more, notice);
    }

    private void checkReady() {
        if (!ready || quarantined) throw new DatabaseProblem("Connect an available data source first.");
    }

    private void closeCursor() {
        if (cursor == null) return;
        try {
            if (cursor.rows != null) {
                cursor.rows.close();
                cursor.rows = null;
            }
            cursor.statement.close();
            cursor = null;
        } catch (SQLException failure) {
            quarantined = true;
            throw new DatabaseProblem("The result cursor could not be released. Disconnect this data source.");
        }
    }

    @Override public void close() {
        ready = false;
        closeCursor();
        try {
            if (connection != null) {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.close();
                connection = null;
            }
            if (lease != null) {
                lease.close();
                lease = null;
            }
        } catch (SQLException | IOException failure) {
            throw new DatabaseProblem("The database connection could not be released. Try disconnecting again.");
        }
    }

    public record SchemaObject(String name, String type) {
        public String selectSql() { return "SELECT * FROM main.\"" + name.replace("\"", "\"\"") + "\""; }
        @Override public String toString() { return name + " (" + type + ")"; }
    }

    @FunctionalInterface
    private interface ReadResult<T extends SqlResult> { T run() throws SQLException, IOException; }

    private static final class Cursor {
        private final Statement statement;
        private ResultSet rows;
        private List<QueryPage.Column> columns;
        private boolean positioned;
        private int totalRows;
        private int totalBytes;
        private int page;

        private Cursor(Statement statement) { this.statement = statement; }
    }
}
