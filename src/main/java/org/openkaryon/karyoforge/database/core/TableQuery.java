package org.openkaryon.karyoforge.database.core;

import java.util.Objects;

/** The optional WHERE and ORDER BY expressions for one catalog object. */
public final class TableQuery {
    private final JdbcSession.SchemaObject object;
    private final SqlText.Command filtered;

    public TableQuery(JdbcSession.SchemaObject object, String condition, String sort) {
        this.object = Objects.requireNonNull(object);
        var dialect = object.dialect();
        String where = SqlText.tableExpression(condition, dialect, "WHERE");
        String order = SqlText.tableExpression(sort, dialect, "ORDER BY");
        if (where.isEmpty() && order.isEmpty()) {
            filtered = null;
            return;
        }
        var sql = new StringBuilder(object.selectSql());
        if (!where.isEmpty()) sql.append(" WHERE (").append(where).append(')');
        if (!order.isEmpty()) sql.append(" ORDER BY ").append(order);
        filtered = SqlText.compile(sql.toString(), dialect);
        if (!filtered.parameters().isEmpty()) {
            throw new IllegalArgumentException("Use the SQL console to bind parameters in a table query.");
        }
    }

    JdbcSession.SchemaObject object() { return object; }
    SqlText.Command filtered() { return filtered; }
}
