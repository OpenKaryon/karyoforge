package org.openkaryon.karyoforge.database.core;

import java.util.Locale;

/** Restricts this console to one read request. SQLite's file mode enforces the read-only boundary. */
final class SqliteReadStatement {
    private static final int MAX_SQL_CHARS = 256 * 1024;

    static void validate(String sql) {
        if (sql.length() > MAX_SQL_CHARS || sql.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The SQL text exceeds the supported statement limits.");
        }
        int offset = skipTrivia(sql, 0);
        int start = offset;
        while (offset < sql.length() && word(sql.charAt(offset))) offset++;
        String keyword = sql.substring(start, offset).toUpperCase(Locale.ROOT);
        if (!keyword.equals("SELECT") && !keyword.equals("WITH")) {
            throw new IllegalArgumentException("Enter one SELECT or WITH query.");
        }
        while ((offset = skipTrivia(sql, offset)) < sql.length()) {
            char current = sql.charAt(offset++);
            if (current == ';') {
                if (skipTrivia(sql, offset) != sql.length()) {
                    throw new IllegalArgumentException("Run one SQL statement at a time.");
                }
                return;
            }
            if (current == '\'' || current == '"' || current == '`' || current == '[') {
                offset = skipQuoted(sql, offset, current);
            }
        }
    }

    private static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int skipTrivia(String sql, int offset) {
        while (offset < sql.length()) {
            char c = sql.charAt(offset);
            if (Character.isWhitespace(c)) {
                offset++;
            } else if (sql.startsWith("--", offset)) {
                while (offset < sql.length() && sql.charAt(offset) != '\n') offset++;
            } else if (sql.startsWith("/*", offset)) {
                int end = sql.indexOf("*/", offset + 2);
                if (end < 0) throw new IllegalArgumentException("Unterminated SQL comment.");
                offset = end + 2;
            } else {
                break;
            }
        }
        return offset;
    }

    private static int skipQuoted(String sql, int offset, char opening) {
        char closing = opening == '[' ? ']' : opening;
        while (offset < sql.length()) {
            if (sql.charAt(offset++) != closing) continue;
            if (opening == '[' || offset == sql.length() || sql.charAt(offset) != closing) return offset;
            offset++;
        }
        throw new IllegalArgumentException("Unterminated SQL quote.");
    }
}
