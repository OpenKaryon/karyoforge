package org.openkaryon.karyoforge.database.core;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class ReadOnlyTable {

    static TableSnapshot open(Connection connection, TableDefinition definition, ResultLimits limits, QueryRequest request)
            throws SQLException, IOException {
        var columns = definition.columns().stream().map(column -> new QueryPage.Column(column.name(), column.type())).toList();
        var grid = new ArrayList<QueryPage.Row>();
        var object = definition.object();
        var names = columns.stream().map(column -> object.identifier(column.label())).toList();
        String sql = "SELECT " + String.join(", ", names) + " FROM " + object.qualified()
                + " LIMIT " + (limits.pageSize() + 1);
        int bytes = 0;
        boolean more = false;
        try (var command = connection.prepareStatement(sql)) {
            command.setQueryTimeout(30);
            request.attach(command);
            try (var rows = command.executeQuery()) {
                if (rows.getMetaData().getColumnCount() != columns.size()) {
                    throw new DatabaseProblem("The table definition changed. Reload this object.");
                }
                while (rows.next()) {
                    request.check();
                    if (grid.size() == limits.pageSize()) {
                        more = true;
                        break;
                    }
                    var row = new ArrayList<QueryPage.Cell>();
                    for (int column = 0; column < columns.size(); column++) {
                        request.check();
                        var cell = JdbcValues.read(rows, column + 1, limits.maxCellChars());
                        bytes += JdbcValues.bytes(cell);
                        if (bytes > limits.maxBytes()) break;
                        row.add(cell);
                    }
                    if (bytes > limits.maxBytes()) {
                        more = true;
                        break;
                    }
                    grid.add(new QueryPage.Row(row));
                }
            } finally {
                request.detach(command);
            }
        }
        var data = new QueryPage(columns, grid, 1, grid.size(), false, more ? "Only the bounded first table page is displayed." : "");
        return new TableSnapshot(definition, data, "This engine's table grid is read-only.", 0, List.of(), column -> null);
    }
}
