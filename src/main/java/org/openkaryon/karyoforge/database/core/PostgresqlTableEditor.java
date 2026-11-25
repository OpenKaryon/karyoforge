package org.openkaryon.karyoforge.database.core;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static org.openkaryon.karyoforge.database.core.JdbcSession.SchemaObject.quote;
import static org.openkaryon.karyoforge.database.core.QueryPage.Cell.Kind;

final class PostgresqlTableEditor {
    private static final int MAX_CELL = 16_384;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    static TableSnapshot browse(Connection connection, TableDefinition definition, boolean writable,
                                QueryRequest request) throws SQLException, IOException {
        Relation relation = identify(connection, definition.object());
        var columns = definition.columns();
        if (columns.isEmpty()) throw new DatabaseProblem("The object has no visible columns.");
        List<TableDefinition.Column> keys = keys(definition);
        String protection = protection(definition, relation, keys, writable);
        boolean editable = protection.isEmpty();
        String fields = String.join(", ", columns.stream().map(column -> quote(column.name())).toList());
        String from = editable ? " FROM ONLY " : " FROM ";
        String order = keys.isEmpty() ? "" : " ORDER BY " + String.join(", ", keys.stream().map(key -> quote(key.name())).toList());
        String sql = "SELECT " + fields + (editable ? ", xmin::text" : "") + from + definition.object().qualified()
                + order + " LIMIT " + (JdbcSession.PAGE_SIZE + 1);
        var originalRows = new ArrayList<TableSnapshot.OriginalRow>();
        var grid = new ArrayList<QueryPage.Row>();
        int bytes = 0;
        boolean more = false;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(30);
            request.attach(statement);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    request.check();
                    if (grid.size() == JdbcSession.PAGE_SIZE) {
                        more = true;
                        break;
                    }
                    var originals = new ArrayList<TableSnapshot.OriginalCell>();
                    var displayed = new ArrayList<QueryPage.Cell>();
                    String rowProtection = "";
                    for (int index = 0; index < columns.size(); index++) {
                        request.check();
                        var type = valueType(columns.get(index));
                        var cell = JdbcValues.read(rows, index + 1, MAX_CELL);
                        if (editable) {
                            try {
                                cell = exactTemporal(rows, index + 1, type, cell);
                                if (cell.clipped() || cell.kind() == Kind.NULL && columns.get(index).keyPosition() > 0) {
                                    rowProtection = "The row has no complete editable original value.";
                                } else {
                                    originalValue(type, cell);
                                }
                            } catch (IllegalArgumentException unsupported) {
                                rowProtection = "The row includes a value without a supported exact editor codec.";
                            }
                        }
                        bytes += JdbcValues.bytes(cell);
                        originals.add(new TableSnapshot.OriginalCell(cell, null));
                        displayed.add(cell);
                    }
                    if (bytes > MAX_BYTES) {
                        more = true;
                        break;
                    }
                    String version = editable ? rows.getString(columns.size() + 1) : null;
                    originalRows.add(new TableSnapshot.OriginalRow(originals, rowProtection, version));
                    grid.add(new QueryPage.Row(displayed));
                }
            } finally {
                request.detach(statement);
            }
        }
        var headers = columns.stream().map(column -> new QueryPage.Column(column.name(), column.type())).toList();
        var page = new QueryPage(headers, grid, 1, grid.size(), false, more ? "Only the bounded first table page is displayed." : "");
        return new TableSnapshot(definition, page, protection, relation.oid(), originalRows, PostgresqlTableEditor::editableType);
    }

    private static QueryPage.Cell exactTemporal(ResultSet rows, int index, SqlValue.Type type, QueryPage.Cell original) throws SQLException {
        if (original.kind() == Kind.NULL) return original;
        if (type != SqlValue.Type.DATE && type != SqlValue.Type.TIMESTAMP && type != SqlValue.Type.TIMESTAMPTZ) return original;
        if (original.value().equals("infinity") || original.value().equals("-infinity")) {
            throw new IllegalArgumentException("Infinite temporal values are view-only.");
        }
        String value = switch (type) {
            case DATE -> rows.getObject(index, LocalDate.class).toString();
            case TIMESTAMP -> rows.getObject(index, LocalDateTime.class).toString();
            case TIMESTAMPTZ -> rows.getObject(index, OffsetDateTime.class).toString();
            default -> throw new IllegalArgumentException("Unsupported temporal value.");
        };
        return new QueryPage.Cell(type == SqlValue.Type.DATE ? Kind.DATE : Kind.TIMESTAMP, value, false);
    }

    private static String protection(TableDefinition definition, Relation relation, List<TableDefinition.Column> keys, boolean writable) {
        if (!writable) return "The data source is read-only.";
        if (!relation.ordinary()) return "Views, foreign, inherited and partitioned tables are view-only.";
        if (keys.isEmpty()) return "This table has no declared primary key.";
        if (definition.clipped() || definition.columns().stream().anyMatch(column -> valueType(column) == null)) {
            return "A table definition or column has no complete supported editor representation.";
        }
        return "";
    }

    static int apply(Connection connection, PostgresqlBackend backend, TableSnapshot snapshot, List<TableSnapshot.Change> changes,
                     QueryRequest request, Runnable quarantine) throws SQLException {
        if (!snapshot.protection().isEmpty()) throw new DatabaseProblem(snapshot.protection());
        if (changes.isEmpty() || changes.size() > snapshot.originals.size()) {
            throw new IllegalArgumentException("Choose at least one row from this table snapshot.");
        }
        return JdbcEditBatch.apply(connection, request, quarantine, () -> {
            var object = snapshot.definition().object();
            Relation relation = identify(connection, object);
            if (!relation.ordinary() || relation.oid() != snapshot.objectVersion ||
                    !backend.describe(connection, object).equals(snapshot.definition())) {
                throw new DatabaseProblem("The PostgreSQL table definition changed. Reload the table.");
            }
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
            return plans;
        });
    }

    private static JdbcEditBatch.Mutation mutation(TableSnapshot snapshot, TableSnapshot.Change change) {
        var columns = snapshot.definition().columns();
        var original = snapshot.originals.get(change.row());
        var assignments = new ArrayList<String>();
        var predicates = new ArrayList<String>();
        var bindings = new ArrayList<SqlValue>();
        for (int index : change.values().keySet().stream().sorted().toList()) {
            if (index < 0 || index >= columns.size()) throw new IllegalArgumentException("Unknown table column.");
            var value = change.values().get(index);
            if (value.type() != editableType(columns.get(index))) throw new IllegalArgumentException("A selected column does not accept that edit.");
            assignments.add(quote(columns.get(index).name()) + " = ?");
            bindings.add(value);
        }
        predicates.add("xmin::text = ?");
        bindings.add(SqlValue.of(SqlValue.Type.TEXT, original.rowVersion(), false));
        for (int index = 0; index < columns.size(); index++) {
            var column = columns.get(index);
            predicates.add(quote(column.name()) + (column.keyPosition() > 0 ? " = ?" : " IS NOT DISTINCT FROM ?"));
            bindings.add(originalValue(valueType(column), original.cells().get(index).value()));
        }
        String target = "ONLY " + snapshot.definition().object().qualified();
        String sql = change.delete() ? "DELETE FROM " + target : "UPDATE " + target + " SET " + String.join(", ", assignments);
        return new JdbcEditBatch.Mutation(sql + " WHERE " + String.join(" AND ", predicates), bindings);
    }

    private static SqlValue originalValue(SqlValue.Type type, QueryPage.Cell cell) {
        return SqlValue.of(type, cell.value(), cell.kind() == Kind.NULL);
    }

    private static SqlValue.Type editableType(TableDefinition.Column column) {
        return column.keyPosition() > 0 || column.hidden() != 0 ? null : valueType(column);
    }

    private static SqlValue.Type valueType(TableDefinition.Column column) {
        String type = column.type();
        if (type.startsWith("numeric(") || type.equals("numeric")) return SqlValue.Type.DECIMAL;
        if (type.startsWith("character varying(") || type.equals("character varying")) return SqlValue.Type.TEXT;
        if (type.startsWith("timestamp") && type.endsWith(" with time zone")) return SqlValue.Type.TIMESTAMPTZ;
        if (type.startsWith("timestamp") && type.endsWith(" without time zone")) return SqlValue.Type.TIMESTAMP;
        return switch (type) {
            case "smallint", "integer", "bigint" -> SqlValue.Type.INTEGER;
            case "boolean" -> SqlValue.Type.BOOLEAN;
            case "text" -> SqlValue.Type.TEXT;
            case "bytea" -> SqlValue.Type.BINARY;
            case "date" -> SqlValue.Type.DATE;
            default -> null;
        };
    }

    private static List<TableDefinition.Column> keys(TableDefinition definition) {
        return definition.columns().stream().filter(column -> column.keyPosition() > 0)
                .sorted(Comparator.comparingInt(TableDefinition.Column::keyPosition)).toList();
    }

    private static Relation identify(Connection connection, JdbcSession.SchemaObject object) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT c.oid::bigint, c.relkind = 'r' AND NOT c.relispartition AND NOT EXISTS (
                    SELECT FROM pg_catalog.pg_inherits i WHERE i.inhparent=c.oid OR i.inhrelid=c.oid
                )
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p','v','m','f')
                """)) {
            statement.setQueryTimeout(30);
            statement.setString(1, object.schema());
            statement.setString(2, object.name());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new DatabaseProblem("The selected PostgreSQL relation no longer exists.");
                return new Relation(rows.getLong(1), rows.getBoolean(2));
            }
        }
    }

    private record Relation(long oid, boolean ordinary) {}
}
