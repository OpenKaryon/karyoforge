package org.openkaryon.karyoforge.database.results;

import org.openkaryon.karyoforge.database.core.QueryPage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

record ResultValue(Domain domain, Object value) {
    enum Domain { NUMBER, BOOLEAN, DATE, TIME, TIMESTAMP, TEXT, BINARY }
    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final Pattern HEX = Pattern.compile("[a-fA-F0-9]*");

    static ResultValue parse(QueryPage.Cell cell, boolean caseSensitive) {
        String text = cell.value();
        try {
            if (cell.clipped() && !isText(cell.kind()) && cell.kind() != QueryPage.Cell.Kind.BINARY) {
                throw new IllegalArgumentException("Incomplete typed value.");
            }
            return switch (cell.kind()) {
                case NULL -> null;
                case INTEGER, DECIMAL -> new ResultValue(Domain.NUMBER, numeric(cell));
                case BOOLEAN -> new ResultValue(Domain.BOOLEAN, bool(text));
                case DATE -> new ResultValue(Domain.DATE, LocalDate.parse(text));
                case TIME -> new ResultValue(Domain.TIME, time(text));
                case TIMESTAMP -> new ResultValue(Domain.TIMESTAMP, timestamp(text.replace(' ', 'T')));
                case TEXT, OTHER -> new ResultValue(Domain.TEXT, caseSensitive ? text : text.toLowerCase(Locale.ROOT));
                case BINARY -> {
                    if (text.length() % 2 != 0 || !HEX.matcher(text).matches()) throw new IllegalArgumentException("Invalid hexadecimal cell.");
                    yield new ResultValue(Domain.BINARY, text.toUpperCase(Locale.ROOT));
                }
            };
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            throw new IllegalArgumentException("A selected column contains an unsupported " + cell.kind().name().toLowerCase(Locale.ROOT) + " value.");
        }
    }

    static boolean isText(QueryPage.Cell.Kind kind) {
        return kind == QueryPage.Cell.Kind.TEXT || kind == QueryPage.Cell.Kind.OTHER;
    }

    static boolean isExactNumber(QueryPage.Cell cell) {
        if (cell.clipped() || cell.kind() != QueryPage.Cell.Kind.INTEGER && cell.kind() != QueryPage.Cell.Kind.DECIMAL) return false;
        try {
            numeric(cell);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static BigDecimal numeric(QueryPage.Cell cell) {
        String text = cell.value();
        var pattern = cell.kind() == QueryPage.Cell.Kind.INTEGER ? INTEGER : DECIMAL;
        if (text.length() > 4096 || !pattern.matcher(text).matches()) throw new IllegalArgumentException("Invalid exact number.");
        var number = new BigDecimal(text);
        if (Math.abs((long) number.scale()) > 100_000) throw new IllegalArgumentException("Unsupported decimal scale.");
        return number;
    }

    private static boolean bool(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "1", "t" -> true;
            case "false", "0", "f" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean.");
        };
    }

    private static Object time(String text) {
        try { return LocalTime.parse(text); }
        catch (DateTimeParseException offset) { return OffsetTime.parse(text); }
    }

    private static Object timestamp(String text) {
        try { return LocalDateTime.parse(text); }
        catch (DateTimeParseException offset) { return OffsetDateTime.parse(text).toInstant(); }
    }

    static void checkTemporalForms(List<ResultValue> values) {
        var types = new EnumMap<Domain, Class<?>>(Domain.class);
        for (var parsed : values) {
            if (parsed == null || parsed.domain != Domain.TIME && parsed.domain != Domain.TIMESTAMP) continue;
            var previous = types.putIfAbsent(parsed.domain, parsed.value.getClass());
            if (previous != null && previous != parsed.value.getClass()) throw mixedTime();
        }
    }

    boolean contains(ResultValue other) { return ((String) value).contains((String) other.value); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    int compare(ResultValue other) {
        if (domain != other.domain) return domain.compareTo(other.domain);
        if (value.getClass() != other.value.getClass()) throw mixedTime();
        if (value instanceof OffsetTime left && other.value instanceof OffsetTime right && left.isEqual(right)) return 0;
        return ((Comparable) value).compareTo(other.value);
    }

    private static IllegalArgumentException mixedTime() {
        return new IllegalArgumentException("Local and offset temporal values cannot be compared in one column.");
    }
}
