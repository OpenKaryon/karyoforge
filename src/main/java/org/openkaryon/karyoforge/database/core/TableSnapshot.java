package org.openkaryon.karyoforge.database.core;

import java.util.List;
import java.util.Map;

public final class TableSnapshot {
    private final TableDefinition definition;
    private final QueryPage data;
    private final String protection;
    final int schemaVersion;
    final List<OriginalRow> originals;

    TableSnapshot(TableDefinition definition, QueryPage data, String protection, int schemaVersion, List<OriginalRow> originals) {
        this.definition = definition;
        this.data = data;
        this.protection = protection;
        this.schemaVersion = schemaVersion;
        this.originals = List.copyOf(originals);
    }

    public TableDefinition definition() { return definition; }
    public QueryPage data() { return data; }
    public String protection() { return protection; }

    public String rowProtection(int index) {
        return protection.isEmpty() ? originals.get(index).protection() : protection;
    }

    public SqlValue.Type columnType(int index) {
        return SqliteTableEditor.editableType(definition.columns().get(index));
    }

    public record Change(int row, boolean delete, Map<Integer, SqlValue> values) {
        public Change {
            values = Map.copyOf(values);
            if (delete != values.isEmpty()) throw new IllegalArgumentException("Choose either deletion or updated values.");
        }
    }

    record OriginalRow(List<OriginalCell> cells, String protection) {
        OriginalRow { cells = List.copyOf(cells); }
    }

    record OriginalCell(QueryPage.Cell value, String textBytes) {}
}
