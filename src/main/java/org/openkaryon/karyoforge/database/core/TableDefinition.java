package org.openkaryon.karyoforge.database.core;

import java.util.List;

public record TableDefinition(JdbcSession.SchemaObject object, List<Column> columns, String ddl, boolean clipped) {
    private static final int MAX_DDL_CHARS = 65_536;

    public TableDefinition { columns = List.copyOf(columns); }

    static TableDefinition snapshot(JdbcSession.SchemaObject object, List<Column> columns, String ddl) {
        if (ddl == null) ddl = "";
        boolean clipped = ddl.length() > MAX_DDL_CHARS;
        if (clipped) {
            int end = MAX_DDL_CHARS;
            if (Character.isHighSurrogate(ddl.charAt(end - 1))) end--;
            ddl = ddl.substring(0, end);
        }
        return new TableDefinition(object, columns, ddl, clipped);
    }

    public record Column(String name, String type, boolean notNull, int keyPosition, int hidden) {
        public String storage() {
            return switch (hidden) {
                case 1 -> "Hidden";
                case 2 -> "Generated (virtual)";
                case 3 -> "Generated (stored)";
                default -> "";
            };
        }
    }
}
