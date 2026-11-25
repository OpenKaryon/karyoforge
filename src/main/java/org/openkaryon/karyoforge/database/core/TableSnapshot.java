package org.openkaryon.karyoforge.database.core;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class TableSnapshot {
    private final TableDefinition definition;
    private final QueryPage data;
    private final String protection;
    private final List<EditorColumn> editing;
    final long objectVersion;
    final List<OriginalRow> originals;

    TableSnapshot(TableDefinition definition, QueryPage data, String protection, long objectVersion, List<OriginalRow> originals,
                  Function<TableDefinition.Column, SqlValue.Type> editorTypes) {
        this.definition = definition;
        this.data = data;
        this.protection = protection;
        this.objectVersion = objectVersion;
        this.editing = definition.columns().stream().map(column -> new EditorColumn(editorTypes.apply(column))).toList();
        this.originals = List.copyOf(originals);
    }

    public TableDefinition definition() { return definition; }
    public QueryPage data() { return data; }
    public String protection() { return protection; }

    public String rowProtection(int index) {
        return protection.isEmpty() ? originals.get(index).protection() : protection;
    }

    public SqlValue.Type columnType(int index) {
        return editing.get(index).type();
    }

    public record Change(int row, boolean delete, Map<Integer, SqlValue> values) {
        public Change {
            values = Map.copyOf(values);
            if (delete != values.isEmpty()) throw new IllegalArgumentException("Choose either deletion or updated values.");
        }
    }

    private record EditorColumn(SqlValue.Type type) {}

    record OriginalRow(List<OriginalCell> cells, String protection, String rowVersion) {
        OriginalRow { cells = List.copyOf(cells); }
        OriginalRow(List<OriginalCell> cells, String protection) { this(cells, protection, null); }
    }

    record OriginalCell(QueryPage.Cell value, String textBytes) {}
}
