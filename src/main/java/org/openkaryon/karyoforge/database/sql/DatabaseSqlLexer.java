package org.openkaryon.karyoforge.database.sql;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

final class DatabaseSqlLexer extends LexerBase {
    static final IElementType KEYWORD = token("KEYWORD");
    static final IElementType STRING = token("STRING");
    static final IElementType COMMENT = token("COMMENT");
    static final IElementType NUMBER = token("NUMBER");
    static final IElementType NAME = token("NAME");
    static final IElementType SYMBOL = token("SYMBOL");
    static final Set<String> KEYWORDS = Set.of(
            "ADD", "ALL", "ALTER", "AND", "AS", "ASC", "BETWEEN", "BY", "CASE", "CAST", "CHECK", "COLUMN",
            "CREATE", "CROSS", "DEFAULT", "DELETE", "DESC", "DESCRIBE", "DISTINCT", "DROP", "ELSE", "END",
            "EXCEPT", "EXISTS", "EXPLAIN", "FALSE", "FETCH", "FILTER", "FIRST", "FOLLOWING", "FOREIGN", "FROM",
            "FULL", "GROUP", "HAVING", "IF", "IN", "INDEX", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN",
            "KEY", "LAST", "LATERAL", "LEFT", "LIKE", "LIMIT", "NOT", "NULL", "NULLS", "OFFSET", "ON", "ONLY", "OR", "ORDER",
            "OUTER", "OVER", "PARTITION", "PRECEDING", "PRIMARY", "RECURSIVE", "REFERENCES", "RETURNING",
            "RIGHT", "ROW", "ROWS", "SELECT", "SET", "SHOW", "TABLE", "THEN", "TRUE", "UNBOUNDED", "UNION",
            "UNIQUE", "UPDATE", "USING", "VALUES", "VIEW", "WHEN", "WHERE", "WINDOW", "WITH");
    private CharSequence text = "";
    private int limit;
    private int start;
    private int end;
    private IElementType type;

    @Override public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        text = buffer;
        limit = endOffset;
        end = startOffset;
        advance();
    }

    @Override public int getState() { return 0; }
    @Override public IElementType getTokenType() { return type; }
    @Override public int getTokenStart() { return start; }
    @Override public int getTokenEnd() { return end; }
    @Override public @NotNull CharSequence getBufferSequence() { return text; }
    @Override public int getBufferEnd() { return limit; }

    @Override public void advance() {
        start = end;
        if (end >= limit) {
            type = null;
            return;
        }
        char first = text.charAt(end++);
        if (Character.isWhitespace(first)) {
            while (end < limit && Character.isWhitespace(text.charAt(end))) end++;
            type = TokenType.WHITE_SPACE;
        } else if (first == '-' && match('-')) {
            while (end < limit && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
            type = COMMENT;
        } else if (first == '/' && match('*')) {
            blockComment();
            type = COMMENT;
        } else if (first == '\'' || first == '"' || first == '`' || first == '[') {
            char close = first == '[' ? ']' : first;
            boolean escapes = first == '\'' && start > 0 && (text.charAt(start - 1) == 'E' || text.charAt(start - 1) == 'e')
                    && (start < 2 || !namePart(text.charAt(start - 2)));
            quoted(close, escapes);
            type = first == '\'' ? STRING : NAME;
        } else if (first == '$' && dollarString()) {
            type = STRING;
        } else if (Character.isDigit(first)) {
            number();
            type = NUMBER;
        } else if (Character.isLetter(first) || first == '_') {
            while (end < limit && namePart(text.charAt(end))) end++;
            String word = text.subSequence(start, end).toString().toUpperCase(Locale.ROOT);
            type = KEYWORDS.contains(word) ? KEYWORD : NAME;
        } else type = SYMBOL;
    }

    private void blockComment() {
        int depth = 1;
        while (end < limit && depth > 0) {
            char next = text.charAt(end++);
            if (next == '/' && match('*')) depth++;
            else if (next == '*' && match('/')) depth--;
        }
    }

    private void quoted(char close, boolean escapes) {
        while (end < limit) {
            char next = text.charAt(end++);
            if (escapes && next == '\\' && end < limit) end++;
            else if (next == close && !match(close)) return;
        }
    }

    private boolean dollarString() {
        int delimiterEnd = end;
        if (delimiterEnd < limit && (Character.isLetter(text.charAt(delimiterEnd)) || text.charAt(delimiterEnd) == '_')) {
            do delimiterEnd++; while (delimiterEnd < limit && text.charAt(delimiterEnd) != '$'
                    && namePart(text.charAt(delimiterEnd)));
        }
        if (delimiterEnd >= limit || text.charAt(delimiterEnd) != '$') return false;
        delimiterEnd++;
        int width = delimiterEnd - start;
        for (int candidate = delimiterEnd; candidate + width <= limit; candidate++) {
            if (text.charAt(candidate) != '$') continue;
            int matched = 1;
            while (matched < width && text.charAt(start + matched) == text.charAt(candidate + matched)) matched++;
            if (matched == width) {
                end = candidate + width;
                return true;
            }
        }
        end = limit;
        return true;
    }

    private void number() {
        digits();
        if (end + 1 < limit && text.charAt(end) == '.' && Character.isDigit(text.charAt(end + 1))) {
            end++;
            digits();
        }
        if (end < limit && (text.charAt(end) == 'e' || text.charAt(end) == 'E')) {
            int exponent = end++;
            if (!match('+')) match('-');
            if (end < limit && Character.isDigit(text.charAt(end))) digits();
            else end = exponent;
        }
    }

    private void digits() {
        while (end < limit && Character.isDigit(text.charAt(end))) end++;
    }

    private boolean match(char expected) {
        if (end >= limit || text.charAt(end) != expected) return false;
        end++;
        return true;
    }

    private static boolean namePart(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    private static IElementType token(String name) { return new IElementType(name, DatabaseSqlLanguage.INSTANCE); }
}
