package org.openkaryon.karyoforge.database.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Statement ranges and prepared bindings. Access mode is enforced by the connection. */
public final class SqlText {
    private static final int MAX_CHARS = 256 * 1024;
    private static final int MAX_BINDINGS = 64;

    public enum Dialect { SQLITE, POSTGRESQL, MYSQL, DUCKDB }

    public static String activeStatement(String text, int start, int end) {
        return activeStatement(text, start, end, Dialect.SQLITE);
    }

    public static String activeStatement(String text, int start, int end, Dialect dialect) {
        if (start < 0 || start > end || end > text.length()) throw new IllegalArgumentException("Invalid SQL selection.");
        var scan = scan(text, dialect, true);
        if (start != end) {
            for (var span : scan.spans()) {
                if (span.cuts(start) || span.cuts(end)) {
                    throw new IllegalArgumentException("The selection cuts a quoted token or comment.");
                }
            }
            return single(scan(text.substring(start, end), dialect, false));
        }
        Segment chosen = null;
        for (var segment : scan.segments()) {
            chosen = segment;
            if (start <= segment.end()) break;
        }
        if (scan.incompleteAt() >= 0 && (start >= scan.incompleteAt() ||
                chosen != null && chosen.end() > scan.incompleteAt())) {
            throw new IllegalArgumentException("Complete the SQL token before running this statement.");
        }
        if (chosen == null) throw new IllegalArgumentException("Enter a SQL statement.");
        return scan.text().substring(chosen.start(), chosen.end());
    }

    public static Command compile(String text) {
        return compile(text, Dialect.SQLITE);
    }

    public static Command compile(String text, Dialect dialect) {
        var initial = scan(text, dialect, false);
        var segment = requireSingle(initial);
        text = initial.text().substring(segment.start(), segment.end());
        var compiled = new StringBuilder(text.length());
        var names = new LinkedHashSet<String>();
        var order = new ArrayList<String>();
        int positions = 0;
        var spans = initial.spans();
        int spanIndex = 0;
        while (spanIndex < spans.size() && spans.get(spanIndex).start() < segment.start()) spanIndex++;
        for (int offset = 0; offset < text.length();) {
            if (spanIndex < spans.size() && spans.get(spanIndex).start() == offset + segment.start()) {
                int spanEnd = spans.get(spanIndex++).end() - segment.start();
                compiled.append(text, offset, spanEnd);
                offset = spanEnd;
                continue;
            }
            char current = text.charAt(offset);
            if (dialect == Dialect.POSTGRESQL && current == '?' && offset + 1 < text.length()) {
                char next = text.charAt(offset + 1);
                if (next == '?' || next == '|' || next == '&') {
                    compiled.append("??");
                    if (next != '?') compiled.append(next);
                    offset += 2;
                    continue;
                }
            }
            if (current == '?' || current == ':' && (offset == 0 || text.charAt(offset - 1) != ':')
                    && identifierStart(text, offset + 1)) {
                String name;
                if (current == '?') {
                    if (offset + 1 < text.length() && Character.isDigit(text.charAt(offset + 1))) {
                        throw new IllegalArgumentException("Use unnumbered ? or named :name SQL parameters.");
                    }
                    name = Integer.toString(++positions);
                    offset++;
                } else {
                    int start = ++offset;
                    while (offset < text.length() && identifierPart(text.charAt(offset))) offset++;
                    name = text.substring(start, offset);
                    names.add(name);
                }
                order.add(name);
                if (order.size() > MAX_BINDINGS) throw new IllegalArgumentException("A statement can use up to 64 parameters.");
                compiled.append('?');
            } else {
                boolean startsToken = offset == 0 || !identifierPart(text.charAt(offset - 1));
                if (dialect == Dialect.SQLITE && (current == '$' || current == '@')
                        && identifierStart(text, offset + 1) && startsToken) {
                    throw new IllegalArgumentException("Use :name for a named SQL parameter.");
                }
                if (dialect == Dialect.POSTGRESQL && current == '$' && startsToken && offset + 1 < text.length()
                        && Character.isDigit(text.charAt(offset + 1))) {
                    throw new IllegalArgumentException("Use JDBC ? placeholders or :name for PostgreSQL parameters.");
                }
                compiled.append(current);
                offset++;
            }
        }
        if (!names.isEmpty() && positions != 0) {
            throw new IllegalArgumentException("Use either named or positional SQL parameters in one statement.");
        }
        return new Command(dialect, compiled.toString(), segment.keyword(),
                names.isEmpty() ? order : new ArrayList<>(names), order);
    }

    static String followingKeyword(Command command) {
        int start = command.keyword().length();
        String sql = command.sql();
        while (start < sql.length()) {
            if (whitespace(sql.charAt(start))) {
                start++;
                continue;
            }
            int comment = commentEnd(sql, start, command.dialect());
            if (comment <= start) break;
            start = comment;
        }
        int end = start;
        while (end < sql.length() && identifierPart(sql.charAt(end))) end++;
        return sql.substring(start, end).toUpperCase(Locale.ROOT);
    }

    private static String single(Scan scan) {
        Segment segment = requireSingle(scan);
        return scan.text().substring(segment.start(), segment.end());
    }

    private static Segment requireSingle(Scan scan) {
        if (scan.segments().size() != 1) throw new IllegalArgumentException("Run one SQL statement at a time.");
        return scan.segments().getFirst();
    }

    private static Scan scan(String text, Dialect dialect, boolean allowIncomplete) {
        Objects.requireNonNull(text);
        if (text.length() > MAX_CHARS || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The SQL text exceeds the supported statement limits.");
        }
        var spans = new ArrayList<Span>();
        var segments = new ArrayList<Segment>();
        int first = -1;
        String keyword = "";
        int incompleteAt = -1;
        for (int index = 0; index < text.length();) {
            char c = text.charAt(index);
            if (whitespace(c)) {
                index++;
                continue;
            }
            int commentEnd = commentEnd(text, index, dialect);
            if (commentEnd < 0) {
                if (!allowIncomplete) throw new IllegalArgumentException("Unterminated SQL comment.");
                incompleteAt = index;
                spans.add(new Span(index, text.length()));
                break;
            }
            if (commentEnd > index) {
                spans.add(new Span(index, commentEnd));
                index = commentEnd;
                continue;
            }
            if (c == ';') {
                if (first >= 0) segments.add(new Segment(first, index, keyword));
                first = -1;
                keyword = "";
                index++;
                continue;
            }
            if (first < 0) {
                first = index;
                int end = index;
                while (end < text.length() && identifierPart(text.charAt(end))) end++;
                keyword = text.substring(index, end).toUpperCase(Locale.ROOT);
            }
            boolean postgresLike = dialect == Dialect.POSTGRESQL || dialect == Dialect.DUCKDB;
            String dollar = postgresLike ? dollarDelimiter(text, index) : null;
            boolean quote = c == '\'' || c == '"' || !postgresLike && c == '`'
                    || dialect == Dialect.SQLITE && c == '[';
            if (quote || dollar != null) {
                int end;
                if (dollar != null) {
                    int closing = text.indexOf(dollar, index + dollar.length());
                    end = closing < 0 ? -1 : closing + dollar.length();
                } else {
                    boolean escapes = dialect == Dialect.MYSQL && (c == '\'' || c == '"')
                            || postgresLike && c == '\'' && index > 0
                            && (text.charAt(index - 1) == 'E' || text.charAt(index - 1) == 'e')
                            && (index < 2 || !identifierPart(text.charAt(index - 2)));
                    end = quotedEnd(text, index, c, escapes);
                }
                if (end < 0) {
                    if (!allowIncomplete) throw new IllegalArgumentException("Unterminated SQL quote.");
                    incompleteAt = index;
                    spans.add(new Span(index, text.length()));
                    break;
                }
                spans.add(new Span(index, end));
                index = end;
            } else {
                index++;
            }
        }
        if (first >= 0) segments.add(new Segment(first, text.length(), keyword));
        return new Scan(text, spans, segments, incompleteAt);
    }

    private static int commentEnd(String text, int index, Dialect dialect) {
        boolean mysql = dialect == Dialect.MYSQL;
        if ((text.startsWith("--", index) && (!mysql || index + 2 == text.length() || text.charAt(index + 2) <= ' '))
                || mysql && text.charAt(index) == '#') {
            int end = text.indexOf('\n', index + 2);
            return end < 0 ? text.length() : end;
        }
        if (text.startsWith("/*", index)) {
            if (mysql && (text.startsWith("/*!", index) || text.regionMatches(true, index, "/*M!", 0, 4))) {
                throw new IllegalArgumentException("Executable MySQL or MariaDB comments are unavailable.");
            }
            if (dialect == Dialect.SQLITE || dialect == Dialect.MYSQL) {
                int end = text.indexOf("*/", index + 2);
                return end < 0 ? -1 : end + 2;
            }
            int depth = 1;
            for (int offset = index + 2; offset + 1 < text.length(); offset++) {
                if (text.startsWith("/*", offset)) {
                    depth++;
                    offset++;
                } else if (text.startsWith("*/", offset)) {
                    if (--depth == 0) return offset + 2;
                    offset++;
                }
            }
            return -1;
        }
        return index;
    }

    private static String dollarDelimiter(String text, int index) {
        if (text.charAt(index) != '$' || index > 0 && identifierPart(text.charAt(index - 1))) return null;
        if (index + 1 < text.length() && text.charAt(index + 1) == '$') return "$$";
        if (!identifierStart(text, index + 1)) return null;
        int end = index + 2;
        while (end < text.length() && text.charAt(end) != '$' && identifierPart(text.charAt(end))) end++;
        return end < text.length() && text.charAt(end) == '$' ? text.substring(index, end + 1) : null;
    }

    private static int quotedEnd(String text, int index, char opening, boolean escapes) {
        char closing = opening == '[' ? ']' : opening;
        while (++index < text.length()) {
            if (escapes && text.charAt(index) == '\\') {
                index++;
                continue;
            }
            if (text.charAt(index) != closing) continue;
            int next = index + 1;
            if (opening == '[' || next == text.length() || text.charAt(next) != closing) return next;
            index = next;
        }
        return -1;
    }

    private static boolean whitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
    }

    private static boolean identifierStart(String text, int index) {
        return index < text.length() && (Character.isLetter(text.charAt(index)) || text.charAt(index) == '_');
    }

    private static boolean identifierPart(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    public static final class Command {
        private final Dialect dialect;
        private final String sql;
        private final String keyword;
        private final List<String> parameters;
        private final List<String> bindingOrder;

        private Command(Dialect dialect, String sql, String keyword, List<String> parameters, List<String> bindingOrder) {
            this.dialect = dialect;
            this.sql = sql;
            this.keyword = keyword;
            this.parameters = List.copyOf(parameters);
            this.bindingOrder = List.copyOf(bindingOrder);
        }

        Dialect dialect() { return dialect; }
        String sql() { return sql; }
        String keyword() { return keyword; }
        public List<String> parameters() { return parameters; }
        List<String> bindingOrder() { return bindingOrder; }
    }

    private record Scan(String text, List<Span> spans, List<Segment> segments, int incompleteAt) {}
    private record Segment(int start, int end, String keyword) {}
    private record Span(int start, int end) {
        boolean cuts(int offset) { return offset > start && offset < end; }
    }
}
