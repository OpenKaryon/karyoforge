package org.openkaryon.karyoforge.database.core;

import org.openkaryon.karyoforge.database.driver.DriverLease;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Owns the connection and retained cursor. Except for QueryRequest.stop, use one command lane. */
public final class JdbcSession implements AutoCloseable {
    public static final int PAGE_SIZE = 200;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CELL_CHARS = 16_384;
    private final DatabaseSource source;
    private final JdbcBackend backend;
    private DriverLease lease;
    private Connection connection;
    private Cursor cursor;
    private TableSnapshot editableSnapshot;
    private boolean ready;
    private boolean quarantined;

    public JdbcSession(DatabaseSource source) {
        this.source = source;
        backend = JdbcBackend.forSource(source);
    }

    public void connect() { connect(null); }

    public void connect(char[] password) {
        if (lease != null || connection != null) throw new DatabaseProblem("Disconnect before reopening this data source.");
        try {
            lease = backend.openDriver(source.driver());
        } catch (DatabaseProblem unavailable) {
            close();
            throw unavailable;
        } catch (IOException failure) {
            close();
            throw new DatabaseProblem("Choose a trusted local " + source.engine() + " JDBC JAR that can be loaded.");
        }
        try {
            connection = backend.connect(lease.driver(), password);
            if (connection == null) throw new SQLException("The driver rejected the data source.");
            backend.initialize(connection);
            quarantined = false;
            ready = true;
        } catch (DatabaseProblem policy) {
            close();
            throw policy;
        } catch (SQLException | RuntimeException failure) {
            close();
            throw DatabaseProblem.connection(source, failure);
        }
    }

    public List<SchemaObject> schema() {
        checkReady();
        try {
            return backend.schema(connection);
        } catch (SQLException error) {
            throw DatabaseProblem.query("Reading the schema", error);
        }
    }

    public TableDefinition describe(SchemaObject object) {
        checkReady();
        try {
            return backend.describe(connection, object);
        } catch (SQLException failure) {
            throw DatabaseProblem.query("Reading the object definition", failure);
        }
    }

    public TableSnapshot openTable(SchemaObject object, QueryRequest request) {
        checkReady();
        request.check();
        closeCursor();
        editableSnapshot = null;
        TableDefinition definition = describe(object);
        var result = requestResult("Reading table data", request, () -> backend.openTable(connection, definition, request));
        editableSnapshot = result;
        return result;
    }

    public int applyTableChanges(TableSnapshot snapshot, List<TableSnapshot.Change> changes, QueryRequest request) {
        checkReady();
        request.check();
        if (!source.writable() || snapshot == null || editableSnapshot != snapshot) {
            throw new DatabaseProblem("Reload this editable table before applying changes.");
        }
        editableSnapshot = null;
        closeCursor();
        return requestResult("Applying table changes", request, () -> backend.applyTableChanges(connection, snapshot, changes, request,
                () -> quarantined = true));
    }

    public SqlResult execute(SqlText.Command command, Map<String, SqlValue> values, QueryRequest request) {
        checkReady();
        if (command.dialect() != source.dialect()) throw new IllegalArgumentException("The SQL dialect differs from the selected source.");
        backend.validate(command);
        var bindings = Map.copyOf(values);
        if (!bindings.keySet().containsAll(command.parameters()) || bindings.size() != command.parameters().size()) {
            throw new IllegalArgumentException("Provide one value for each SQL parameter.");
        }
        request.check();
        closeCursor();
        editableSnapshot = null;
        return this.<SqlResult>requestResult("Executing SQL", request, () -> {
            backend.beforeStatement(connection, request);
            cursor = new Cursor();
            if (backend.statementSavepoints()) cursor.savepoint = connection.setSavepoint();
            var statement = connection.prepareStatement(command.sql());
            cursor.statement = statement;
            request.attach(statement);
            backend.configureStatement(statement);
            int index = 1;
            for (String name : command.bindingOrder()) {
                request.check();
                bindings.get(name).bind(statement, index++);
            }
            if (statement.getParameterMetaData().getParameterCount() != command.bindingOrder().size()) {
                throw new DatabaseProblem("The driver's SQL parameter count differs from the console's bindings.");
            }
            if (!statement.execute()) return new SqlResult.UpdateCount(statement.getUpdateCount());
            cursor.rows = statement.getResultSet();
            cursor.columns = columns(cursor.rows.getMetaData());
            return readPage(cursor, request);
        });
    }

    public QueryPage next(QueryRequest request) {
        checkReady();
        if (cursor == null) throw new DatabaseProblem("This query has no next page.");
        return requestResult("Reading query results", request, () -> {
            request.attach(cursor.statement);
            return readPage(cursor, request);
        });
    }

    private <T> T requestResult(String operation, QueryRequest request, ReadResult<T> work) {
        T result = null;
        try {
            result = work.run();
        } catch (SQLException failure) {
            request.check();
            throw DatabaseProblem.query(operation, failure);
        } catch (IOException failure) {
            request.check();
            throw new DatabaseProblem("Could not read a detached query value.");
        } finally {
            boolean cancelUnconfirmed = request.finish();
            if (cancelUnconfirmed) quarantined = true;
            boolean failed = cancelUnconfirmed || request.stopped() || result == null;
            if (failed || !(result instanceof QueryPage page) || !page.hasNext()) closeCursor(failed);
        }
        if (quarantined) throw new DatabaseProblem("Stop could not be confirmed. Disconnect this data source.");
        request.check();
        return result;
    }

    public void finishTransaction(boolean commit) {
        checkReady();
        if (!source.writable()) throw new DatabaseProblem("This data source is read-only.");
        closeCursor();
        editableSnapshot = null;
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
                var cell = JdbcValues.read(owner.rows, index, Math.min(MAX_CELL_CHARS, remaining / 4));
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

    private void closeCursor() { closeCursor(false); }

    private void closeCursor(boolean failed) {
        if (cursor == null) return;
        try {
            if (cursor.rows != null) {
                cursor.rows.close();
                cursor.rows = null;
            }
            if (cursor.statement != null) {
                cursor.statement.close();
                cursor.statement = null;
            }
            if (cursor.savepoint != null) {
                if (failed) connection.rollback(cursor.savepoint);
                connection.releaseSavepoint(cursor.savepoint);
                cursor.savepoint = null;
            }
            cursor = null;
        } catch (SQLException failure) {
            quarantined = true;
            throw new DatabaseProblem("The result cursor could not be released. Disconnect this data source.");
        }
    }

    @Override public void close() {
        ready = false;
        editableSnapshot = null;
        try {
            try {
                closeCursor();
            } catch (DatabaseProblem cursorFailure) {
                // Closing the owning JDBC connection also retires its cursor.
            }
            if (connection != null) {
                try {
                    if (!connection.isClosed() && !connection.getAutoCommit()) connection.rollback();
                } catch (SQLException rollbackFailure) {
                    // Closing the owned connection rolls back the server-side transaction.
                }
                connection.close();
                connection = null;
            }
            cursor = null;
            backend.closeTransport();
            if (lease != null) {
                backend.releaseDriver(lease);
                lease = null;
            }
            backend.releaseDriverFiles();
        } catch (SQLException | IOException failure) {
            throw new DatabaseProblem("The database connection could not be released. Try disconnecting again.");
        }
    }

    public record SchemaObject(String name, String type, String schema, SqlText.Dialect dialect) {
        public SchemaObject(String name, String type) { this(name, type, "main", SqlText.Dialect.SQLITE); }
        public SchemaObject(String name, String type, String schema) { this(name, type, schema, SqlText.Dialect.POSTGRESQL); }

        public String qualified() {
            return (schema.equals("main") && dialect != SqlText.Dialect.MYSQL ? "main" : identifier(schema)) + "." + identifier(name);
        }

        String identifier(String value) {
            if (dialect == SqlText.Dialect.CLICKHOUSE) {
                String tick = Character.toString(96);
                return tick + value.replace("\\", "\\\\").replace(tick, "\\" + tick) + tick;
            }
            return dialect == SqlText.Dialect.MYSQL ? mysqlQuote(value) : quote(value);
        }
        public String selectSql() { return "SELECT * FROM " + qualified(); }

        private static String mysqlQuote(String identifier) {
            String quote = Character.toString(96);
            return quote + identifier.replace(quote, quote + quote) + quote;
        }

        static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }

        @Override public String toString() {
            return (schema.equals("main") ? name : schema + "." + name) + " (" + type + ")";
        }
    }

    @FunctionalInterface
    private interface ReadResult<T> { T run() throws SQLException, IOException; }

    private static final class Cursor {
        private Statement statement;
        private Savepoint savepoint;
        private ResultSet rows;
        private List<QueryPage.Column> columns;
        private boolean positioned;
        private int totalRows;
        private int totalBytes;
        private int page;

    }
}
