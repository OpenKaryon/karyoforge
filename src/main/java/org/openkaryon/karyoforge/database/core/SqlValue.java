package org.openkaryon.karyoforge.database.core;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Values are parsed before scheduling a query and are never interpolated into SQL text. */
public final class SqlValue {
    private static final int MAX_CHARS = 32 * 1024;

    public enum Type {
        TEXT("Text", Types.VARCHAR), INTEGER("Integer", Types.BIGINT), DECIMAL("Decimal", Types.DECIMAL),
        BOOLEAN("Boolean", Types.BOOLEAN), DATE("Date", Types.DATE), TIME("Time", Types.TIME),
        TIMESTAMP("Timestamp", Types.TIMESTAMP);

        private final String label;
        private final int jdbcType;

        Type(String label, int jdbcType) { this.label = label; this.jdbcType = jdbcType; }
        @Override public String toString() { return label; }
    }

    private final Type type;
    private final Object parsed;

    private SqlValue(Type type, Object parsed) {
        this.type = type;
        this.parsed = parsed;
    }

    public static SqlValue of(Type type, String input, boolean isNull) {
        Objects.requireNonNull(type);
        if (isNull) return new SqlValue(type, null);
        if (input.length() > MAX_CHARS) throw new IllegalArgumentException("A parameter can contain up to 32,768 characters.");
        String value = input.strip();
        try {
            return new SqlValue(type, switch (type) {
                case TEXT -> input;
                case INTEGER -> Long.parseLong(value);
                case DECIMAL -> decimal(value);
                case BOOLEAN -> booleanValue(value);
                case DATE -> LocalDate.parse(value);
                case TIME -> LocalTime.parse(value);
                case TIMESTAMP -> LocalDateTime.parse(value.replace(' ', 'T'));
            });
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Enter a valid " + type + " parameter.");
        }
    }

    private static BigDecimal decimal(String value) {
        var decimal = new BigDecimal(value);
        if (decimal.precision() > 1000 || Math.abs((long) decimal.scale()) > 1000) {
            throw new IllegalArgumentException("Decimal precision or scale exceeds 1000 digits.");
        }
        return decimal;
    }

    private static boolean booleanValue(String value) {
        if (value.equalsIgnoreCase("true") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equals("0")) return false;
        throw new IllegalArgumentException("Use true, false, 1 or 0.");
    }

    void bind(PreparedStatement statement, int index) throws SQLException {
        if (parsed == null) {
            statement.setNull(index, type.jdbcType);
            return;
        }
        switch (type) {
            case TEXT -> statement.setString(index, (String) parsed);
            case INTEGER -> statement.setLong(index, (Long) parsed);
            case DECIMAL -> statement.setBigDecimal(index, (BigDecimal) parsed);
            case BOOLEAN -> statement.setBoolean(index, (Boolean) parsed);
            case DATE, TIME, TIMESTAMP -> statement.setObject(index, parsed, type.jdbcType);
        }
    }
}
