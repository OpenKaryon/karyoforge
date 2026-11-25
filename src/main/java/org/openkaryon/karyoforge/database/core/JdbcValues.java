package org.openkaryon.karyoforge.database.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HexFormat;

import static org.openkaryon.karyoforge.database.core.QueryPage.Cell.Kind;

final class JdbcValues {
    private JdbcValues() {}

    static QueryPage.Cell read(ResultSet rows, int index, int limit) throws SQLException, IOException {
        // SQLite column affinities do not determine every row's value type.
        int type = rows.getMetaData().getColumnType(index);
        return switch (type) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> binary(rows, index, limit);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR -> text(rows, index, limit);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> scalar(rows, index, Kind.INTEGER);
            case Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> scalar(rows, index, Kind.DECIMAL);
            case Types.BOOLEAN, Types.BIT -> scalar(rows, index, Kind.BOOLEAN);
            case Types.DATE -> scalar(rows, index, Kind.DATE);
            case Types.TIME -> scalar(rows, index, Kind.TIME);
            case Types.TIMESTAMP -> scalar(rows, index, Kind.TIMESTAMP);
            case Types.NULL -> nullCell();
            default -> bounded(rows.getString(index), Kind.OTHER, limit);
        };
    }

    private static QueryPage.Cell scalar(ResultSet rows, int index, Kind kind) throws SQLException {
        String value = rows.getString(index);
        if (value == null) return nullCell();
        if (value.length() > 4096) throw new DatabaseProblem("A scalar result exceeds its exact-value limit.");
        return new QueryPage.Cell(kind, value, false);
    }

    private static QueryPage.Cell text(ResultSet rows, int index, int limit) throws SQLException, IOException {
        try (var reader = rows.getCharacterStream(index)) {
            if (reader == null) return nullCell();
            char[] buffer = new char[limit + 1];
            int used = 0;
            while (used < buffer.length) {
                int count = reader.read(buffer, used, buffer.length - used);
                if (count <= 0) break;
                used += count;
            }
            return bounded(new String(buffer, 0, used), Kind.TEXT, limit);
        }
    }

    private static QueryPage.Cell binary(ResultSet rows, int index, int limit) throws SQLException, IOException {
        try (var stream = rows.getBinaryStream(index)) {
            if (stream == null) return nullCell();
            int bytes = limit / 2;
            byte[] data = stream.readNBytes(bytes + 1);
            return new QueryPage.Cell(Kind.BINARY, HexFormat.of().formatHex(data, 0, Math.min(bytes, data.length)),
                    data.length > bytes);
        }
    }

    private static QueryPage.Cell bounded(String value, Kind kind, int limit) {
        if (value == null) return nullCell();
        if (value.length() <= limit) return new QueryPage.Cell(kind, value, false);
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return new QueryPage.Cell(kind, value.substring(0, end), true);
    }

    private static QueryPage.Cell nullCell() { return new QueryPage.Cell(Kind.NULL, null, false); }

    static int bytes(QueryPage.Cell cell) {
        return cell.value() == null ? 4 : cell.value().getBytes(StandardCharsets.UTF_8).length;
    }
}
