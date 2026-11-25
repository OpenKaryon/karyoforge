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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns the connection and retained cursor. Except for QueryRequest.stop, use one command lane. */
public final class JdbcSession implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("KaryoForge database deadline").factory());
    private static final java.util.concurrent.ExecutorService TIMEOUT_CANCELLATIONS = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("KaryoForge database timeout", 1).factory());

    static {
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    private final DatabaseSource source;
    private final ResultLimits limits;
    private final DatabaseTimeouts timeouts;
    private final JdbcBackend backend;
    private DriverLease lease;
    private Connection connection;
    private Cursor cursor;
    private QueryRequest pendingCancellation;
    private TableSnapshot editableSnapshot;
    private boolean ready;
    private boolean quarantined;

    public JdbcSession(DatabaseSource source) { this(source, ResultLimits.DEFAULT); }

    public JdbcSession(DatabaseSource source, ResultLimits limits) { this(source, limits, DatabaseTimeouts.DEFAULT); }

    public JdbcSession(DatabaseSource source, ResultLimits limits, DatabaseTimeouts timeouts) {
        timeouts.checkSource(source);
        this.source = source;
        this.limits = limits;
        this.timeouts = timeouts;
        backend = JdbcBackend.forSource(source, limits, timeouts);
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

    public TableSnapshot openTable(TableQuery query, QueryRequest request) {
        if (query.filtered() == null) return openTable(query.object(), request);
        return browseTable(query.object(), query.filtered(), request,
                "Filtered rows are read-only. Clear WHERE and ORDER BY to edit an eligible table.");
    }

    public TableSnapshot browseTable(TableQuery query, QueryRequest request) {
        var command = query.filtered();
        if (command == null) command = SqlText.compile(query.object().selectSql(), query.object().dialect());
        return browseTable(query.object(), command, request, "Table editor results are read-only.");
    }

    private TableSnapshot browseTable(SchemaObject object, SqlText.Command command, QueryRequest request, String protection) {
        checkReady();
        request.check();
        TableDefinition definition = describe(object);
        if (!(execute(command, Map.of(), request) instanceof QueryPage page)) {
            throw new DatabaseProblem("Reading the table did not return a result.");
        }
        return new TableSnapshot(definition, page, protection, 0, List.of(), column -> null);
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
            request.check();
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

    public boolean stopPaging() {
        checkReady();
        if (cursor == null) return false;
        closeCursor();
        return true;
    }

    private <T> T requestResult(String operation, QueryRequest request, ReadResult<T> work) {
        T result = null;
        var deadline = DEADLINES.schedule(() -> TIMEOUT_CANCELLATIONS.execute(request::expire),
                timeouts.querySeconds(), TimeUnit.SECONDS);
        try {
            result = work.run();
        } catch (SQLException failure) {
            request.check();
            throw DatabaseProblem.query(operation, failure);
        } catch (IOException failure) {
            request.check();
            throw new DatabaseProblem("Could not read a detached query value.");
        } finally {
            deadline.cancel(false);
            boolean cancelUnconfirmed = request.finish();
            if (cancelUnconfirmed) {
                quarantined = true;
                if (!request.cancellationSettled()) pendingCancellation = request;
            }
            boolean failed = cancelUnconfirmed || request.stopped() || result == null;
            if (pendingCancellation == null && (failed || !(result instanceof QueryPage page) || !page.hasNext())) closeCursor(failed);
            if (cancelUnconfirmed) throw new DatabaseProblem("Cancellation could not be confirmed. Disconnect this data source.");
        }
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

    private List<QueryPage.Column> columns(ResultSetMetaData metadata) throws SQLException {
        int count = metadata.getColumnCount();
        limits.checkColumns(count);
        var columns = new ArrayList<QueryPage.Column>();
        for (int index = 1; index <= count; index++) {
            String label = metadata.getColumnLabel(index);
            if (label.length() > 1024) throw new DatabaseProblem("A result column name exceeds 1024 characters.");
            columns.add(new QueryPage.Column(label, metadata.getColumnTypeName(index)));
        }
        return List.copyOf(columns);
    }

    private QueryPage readPage(Cursor owner, QueryRequest request) throws SQLException, IOException {
        var page = new ArrayList<QueryPage.Row>();
        boolean limited = false;
        while (page.size() < limits.pageSize() && owner.totalRows < limits.maxRows()) {
            request.check();
            boolean available = owner.positioned || owner.rows.next();
            owner.positioned = false;
            if (!available) break;
            var cells = new ArrayList<QueryPage.Cell>();
            int rowBytes = 0;
            for (int index = 1; index <= owner.columns.size(); index++) {
                request.check();
                int remaining = limits.maxBytes() - owner.totalBytes - rowBytes;
                if (remaining < 4) { limited = true; break; }
                var cell = JdbcValues.read(owner.rows, index, Math.min(limits.maxCellChars(), remaining / 4));
                rowBytes += JdbcValues.bytes(cell);
                if (rowBytes > limits.maxBytes() - owner.totalBytes) { limited = true; break; }
                cells.add(cell);
            }
            if (limited) break;
            page.add(new QueryPage.Row(cells));
            owner.totalRows++;
            owner.totalBytes += rowBytes;
        }
        request.check();
        boolean atRowLimit = owner.totalRows == limits.maxRows();
        boolean more = !limited && (page.size() == limits.pageSize() || atRowLimit) && owner.rows.next();
        String notice = limited ? "The result reached its byte limit (" + limits.maxBytes() + " bytes)." : "";
        if (more && atRowLimit) {
            more = false;
            notice = "The result reached its row limit (" + limits.maxRows() + " rows).";
        }
        owner.positioned = more;
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
        if (pendingCancellation != null && !pendingCancellation.cancellationSettled()) {
            throw new DatabaseProblem("The cancelling driver still owns this session. Try disconnecting again after it settles.");
        }
        pendingCancellation = null;
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

    public record SchemaObject(String name, String type, String schema, SqlText.Dialect dialect, Long estimatedRows) {
        public SchemaObject(String name, String type, String schema, SqlText.Dialect dialect) {
            this(name, type, schema, dialect, null);
        }

        static Long estimate(String value) {
            if (value == null) return null;
            try {
                long count = Long.parseLong(value);
                return count < 0 ? null : count;
            } catch (NumberFormatException unavailable) {
                return null;
            }
        }

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
