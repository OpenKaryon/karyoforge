package org.openkaryon.karyoforge.database.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static org.openkaryon.karyoforge.database.core.QueryPage.Cell.Kind;

/** Uses saved primary keys and original SQLite storage values, never an unkeyed table grid. */
final class SqliteTableEditor {

    static TableSnapshot browse(Connection connection, TableDefinition definition, boolean writable,
                                ResultLimits limits, QueryRequest request) throws SQLException {
        int version = schemaVersion(connection);
        var columns = definition.columns();
        if (columns.isEmpty()) throw new DatabaseProblem("The object has no visible columns.");
        List<TableDefinition.Column> keys = keys(definition);
        String protection = protection(connection, definition, keys, writable);
        int cellChars = limits.maxCellChars();
        int inspectedBytes = cellChars / 2 + 1;
        var fields = new ArrayList<String>();
        for (var column : columns) {
            String identifier = quote(column.name());
            String type = "typeof(" + identifier + ")";
            fields.add(type);
            fields.add("CASE " + type + " WHEN 'blob' THEN hex(substr(" + identifier + ",1," + inspectedBytes + "))"
                    + " ELSE substr(CAST(" + identifier + " AS TEXT),1," + (cellChars + 1) + ") END");
            fields.add("CASE WHEN " + type + " = 'text' THEN hex(substr(CAST(" + identifier + " AS BLOB),1," + inspectedBytes + ")) END");
            fields.add("CASE WHEN " + type + " = 'text' THEN instr(" + identifier + ",char(0)) ELSE 0 END");
        }
        String order = keys.isEmpty() ? "" : " ORDER BY " + String.join(", ", keys.stream().map(key -> quote(key.name())).toList());
        String sql = "SELECT " + String.join(", ", fields) + " FROM " + table(definition) + order
                + " LIMIT " + (limits.pageSize() + 1);
        var originals = new ArrayList<TableSnapshot.OriginalRow>();
        var displayRows = new ArrayList<QueryPage.Row>();
        boolean more = false;
        int bytes = 0;
        try (var statement = connection.prepareStatement(sql)) {
            request.attach(statement);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    request.check();
                    if (displayRows.size() == limits.pageSize()) {
                        more = true;
                        break;
                    }
                    var cells = new ArrayList<TableSnapshot.OriginalCell>();
                    var values = new ArrayList<QueryPage.Cell>();
                    String reason = "";
                    for (int column = 0; column < columns.size(); column++) {
                        request.check();
                        int offset = column * 4 + 1;
                        var original = original(rows, offset, cellChars);
                        var value = original.value();
                        cells.add(original);
                        values.add(value);
                        String witness = original.textBytes();
                        bytes += JdbcValues.bytes(value) + (witness == null ? 0 : witness.length());
                        if (bytes > limits.maxBytes()) break;
                        if (value.clipped() || rows.getInt(offset + 3) != 0) reason = "An original value has no exact editable preview.";
                        if (value.kind() == Kind.DECIMAL || value.kind() == Kind.OTHER) reason = "This row has an unsupported original storage value.";
                        if (value.kind() == Kind.NULL && columns.get(column).keyPosition() > 0) reason = "This row has a NULL key component.";
                    }
                    if (bytes > limits.maxBytes()) {
                        more = true;
                        break;
                    }
                    originals.add(new TableSnapshot.OriginalRow(cells, reason));
                    displayRows.add(new QueryPage.Row(values));
                }
            } finally {
                request.detach(statement);
            }
        }
        var headers = columns.stream().map(column -> new QueryPage.Column(column.name(), column.type())).toList();
        var grid = new QueryPage(headers, displayRows, 1, displayRows.size(), false,
                more ? "Only the bounded first table page is displayed." : "");
        return new TableSnapshot(definition, grid, protection, version, originals, SqliteTableEditor::editableType);
    }

    private static TableSnapshot.OriginalCell original(ResultSet rows, int offset, int cellChars) throws SQLException {
        Kind kind = switch (rows.getString(offset)) {
            case "null" -> Kind.NULL;
            case "integer" -> Kind.INTEGER;
            case "real" -> Kind.DECIMAL;
            case "text" -> Kind.TEXT;
            case "blob" -> Kind.BINARY;
            default -> Kind.OTHER;
        };
        String raw = rows.getString(offset + 1);
        String witness = rows.getString(offset + 2);
        boolean clipped = raw != null && raw.length() > cellChars || witness != null && witness.length() > cellChars;
        if (raw != null && raw.length() > cellChars) {
            int end = Character.isHighSurrogate(raw.charAt(cellChars - 1)) ? cellChars - 1 : cellChars;
            raw = raw.substring(0, end);
        }
        return new TableSnapshot.OriginalCell(new QueryPage.Cell(kind, raw, clipped), witness);
    }

    private static String protection(Connection connection, TableDefinition definition, List<TableDefinition.Column> keys,
                                     boolean writable) throws SQLException {
        if (!writable) return "The data source is read-only.";
        if (!definition.object().type().equals("table") || definition.object().name().startsWith("sqlite_")) {
            return "Only keyed tables in the main database can be edited.";
        }
        if (keys.isEmpty()) return "This table has no declared primary key.";
        try (var statement = connection.prepareStatement("SELECT rootpage FROM main.sqlite_schema WHERE type='table' AND name=?")) {
            statement.setString(1, definition.object().name());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) <= 0) return "Virtual and missing tables are read-only.";
            }
        }
        return "";
    }

    static SqlValue.Type editableType(TableDefinition.Column column) {
        if (column.keyPosition() > 0 || column.hidden() != 0) return null;
        String type = column.type().toUpperCase(Locale.ROOT);
        if (type.contains("INT")) return SqlValue.Type.INTEGER;
        if (type.contains("CHAR") || type.contains("CLOB") || type.contains("TEXT")) return SqlValue.Type.TEXT;
        if (type.isBlank() || type.contains("BLOB")) return SqlValue.Type.BINARY;
        if (type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB")) return null;
        return SqlValue.Type.INTEGER;
    }

    static int apply(Connection connection, TableSnapshot snapshot, List<TableSnapshot.Change> changes,
                     QueryRequest request, Runnable quarantine) throws SQLException {
        if (!snapshot.protection().isEmpty()) throw new DatabaseProblem(snapshot.protection());
        if (changes.isEmpty() || changes.size() > snapshot.originals.size()) {
            throw new IllegalArgumentException("Choose at least one row from this table snapshot.");
        }
        if (schemaVersion(connection) != snapshot.objectVersion) throw new DatabaseProblem("The table definition changed. Reload the table.");
        var plans = new ArrayList<JdbcEditBatch.Mutation>();
        var seen = new HashSet<Integer>();
        for (var change : changes) {
            request.check();
            int row = change.row();
            if (row < 0 || row >= snapshot.originals.size() || !seen.add(row) || !snapshot.rowProtection(row).isEmpty()) {
                throw new IllegalArgumentException("Each edit must target a distinct row with a complete original key and values.");
            }
            plans.add(mutation(snapshot, change));
        }
        return JdbcEditBatch.apply(connection, request, quarantine, () -> plans);
    }

    private static JdbcEditBatch.Mutation mutation(TableSnapshot snapshot, TableSnapshot.Change change) {
        var columns = snapshot.definition().columns();
        var assignments = new ArrayList<String>();
        var predicates = new ArrayList<String>();
        var values = new ArrayList<SqlValue>();
        var original = snapshot.originals.get(change.row()).cells();
        for (int index : change.values().keySet().stream().sorted().toList()) {
            if (index < 0 || index >= columns.size()) throw new IllegalArgumentException("Unknown table column.");
            var column = columns.get(index);
            var value = change.values().get(index);
            if (value.type() != editableType(column)) throw new IllegalArgumentException("A selected column does not accept that edit.");
            assignments.add(quote(column.name()) + " = ?");
            values.add(value);
        }
        for (int index = 0; index < columns.size(); index++) {
            var column = columns.get(index);
            if (column.keyPosition() == 0) continue;
            predicates.add(quote(column.name()) + " IS ?");
            values.add(nativeValue(original.get(index).value()));
        }
        for (int index = 0; index < columns.size(); index++) {
            var cell = original.get(index);
            var value = cell.value();
            String name = quote(columns.get(index).name());
            switch (value.kind()) {
                case NULL -> predicates.add(name + " IS NULL");
                case INTEGER, BINARY -> {
                    String storage = value.kind() == Kind.INTEGER ? "integer" : "blob";
                    predicates.add("typeof(" + name + ") = '" + storage + "' AND +" + name + " IS ?");
                    values.add(nativeValue(value));
                }
                case TEXT -> {
                    predicates.add("typeof(" + name + ") = 'text' AND hex(CAST(" + name + " AS BLOB)) = ?");
                    values.add(SqlValue.of(SqlValue.Type.TEXT, cell.textBytes(), false));
                }
                default -> throw new IllegalArgumentException("The original row includes a protected value.");
            }
        }
        String target = table(snapshot.definition());
        String command = change.delete() ? "DELETE FROM " + target : "UPDATE " + target + " SET " + String.join(", ", assignments);
        return new JdbcEditBatch.Mutation(command + " WHERE " + String.join(" AND ", predicates), values);
    }

    private static SqlValue nativeValue(QueryPage.Cell value) {
        SqlValue.Type type = switch (value.kind()) {
            case INTEGER -> SqlValue.Type.INTEGER;
            case TEXT -> SqlValue.Type.TEXT;
            case BINARY -> SqlValue.Type.BINARY;
            default -> throw new IllegalArgumentException("A complete SQLite key is required.");
        };
        return SqlValue.of(type, value.value(), false);
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA main.schema_version")) {
            if (!row.next()) throw new SQLException("SQLite schema version unavailable");
            return row.getInt(1);
        }
    }

    private static List<TableDefinition.Column> keys(TableDefinition definition) {
        return definition.columns().stream().filter(column -> column.keyPosition() > 0)
                .sorted(Comparator.comparingInt(TableDefinition.Column::keyPosition)).toList();
    }

    private static String table(TableDefinition definition) {
        return "main." + quote(definition.object().name());
    }

    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
