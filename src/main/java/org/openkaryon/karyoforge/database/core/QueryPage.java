package org.openkaryon.karyoforge.database.core;

import java.util.List;
import java.util.Objects;

public record QueryPage(List<Column> columns, List<Row> rows, int page, int totalRows, boolean hasNext, String notice) {
    public QueryPage {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public record Column(String label, String typeName) {}

    public record Row(List<Cell> cells) {
        public Row { cells = List.copyOf(cells); }
    }

    public record Cell(Kind kind, String value, boolean clipped) {
        public enum Kind { NULL, INTEGER, DECIMAL, BOOLEAN, TEXT, DATE, TIME, TIMESTAMP, BINARY, OTHER }

        public Cell {
            Objects.requireNonNull(kind);
            if ((kind == Kind.NULL) != (value == null)) throw new IllegalArgumentException("Invalid result cell.");
        }

        public String display() {
            if (kind == Kind.NULL) return "NULL";
            return clipped ? value + "…" : value;
        }
    }
}
