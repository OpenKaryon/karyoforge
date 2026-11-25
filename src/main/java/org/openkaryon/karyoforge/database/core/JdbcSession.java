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
import java.util.Properties;

/** Owns the connection and retained cursor. Except for QueryRequest.stop, use one command lane. */
public final class JdbcSession implements AutoCloseable {
    public static final int PAGE_SIZE = 200;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CELL_CHARS = 16_384;
    private static final int MAX_SCHEMA_OBJECTS = 2000;
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
                statement.execute("PRAGMA query_only = ON");
                try (var rows = statement.executeQuery("PRAGMA query_only")) {
                    if (!rows.next() || rows.getInt(1) != 1) throw new SQLException("Read-only mode was not established.");
                }
            }
            quarantined = false;
            ready = true;
        } catch (SQLException | RuntimeException failure) {
            close();
            throw new DatabaseProblem("Could not open the selected SQLite database in read-only mode.");
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

    public QueryPage execute(String sql, QueryRequest request) {
        checkReady();
        SqliteReadStatement.validate(sql);
        request.check();
        closeCursor();
        return requestPage(request, () -> {
            cursor = new Cursor(connection.createStatement());
            request.attach(cursor.statement);
            cursor.rows = cursor.statement.executeQuery(sql);
            cursor.columns = columns(cursor.rows.getMetaData());
            return readPage(cursor, request);
        });
    }

    public QueryPage next(QueryRequest request) {
        checkReady();
        if (cursor == null) throw new DatabaseProblem("This query has no next page.");
        return requestPage(request, () -> {
            request.attach(cursor.statement);
            return readPage(cursor, request);
        });
    }

    private QueryPage requestPage(QueryRequest request, ReadPage work) {
        QueryPage page = null;
        try {
            page = work.run();
        } catch (SQLException failure) {
            request.check();
            throw DatabaseProblem.query("Reading query results", failure);
        } catch (IOException failure) {
            request.check();
            throw new DatabaseProblem("Could not read a detached query value.");
        } finally {
            if (request.finish()) quarantined = true;
            if (page == null || request.stopped() || !page.hasNext()) closeCursor();
        }
        if (quarantined) throw new DatabaseProblem("Stop could not be confirmed. Disconnect this data source.");
        request.check();
        return page;
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
    private interface ReadPage { QueryPage run() throws SQLException, IOException; }

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
