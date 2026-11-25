package org.openkaryon.karyoforge.database.sql;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.openkaryon.karyoforge.database.core.JdbcSession.SchemaObject;
import org.openkaryon.karyoforge.database.core.SqlText;
import org.openkaryon.karyoforge.database.core.TableDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves straightforward SELECT references within an already loaded schema snapshot. */
final class DatabaseSqlReferences {
    private static final Pattern SIMPLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> COMPLEX = Set.of("WITH", "SELECT", "UNION", "INTERSECT", "EXCEPT", "LATERAL", "ONLY");

    static List<Suggestion> complete(String text, int caret, LoadedDatabaseCatalog catalog) {
        var scope = scope(text, caret);
        if (scope == null) return List.of();
        var tokens = scope.tokens();
        int before = scope.beforeWord(caret);
        if (relationKeyword(tokens, before)) return tableSuggestions(catalog, null);
        String qualifier = null;
        if (at(tokens, before, ".")) {
            if (before <= 0 || !tokens.get(before - 1).name()) return List.of();
            qualifier = tokens.get(before - 1).text();
            if (relationKeyword(tokens, before - 2)) return tableSuggestions(catalog, qualifier);
        }
        return columns(scope, catalog, qualifier).values().stream()
                .filter(matches -> matches.size() == 1)
                .map(List::getFirst)
                .map(ref -> new Suggestion(ref.column().name(), ref.column().name(), ref.column().type()))
                .toList();
    }

    static SchemaObject resolve(String text, int offset, LoadedDatabaseCatalog catalog) {
        var scope = scope(text, offset);
        if (scope == null || !scope.completeRelations()) return null;
        var tokens = scope.tokens();
        int index = scope.wordAt(offset);
        if (index < 0 || !tokens.get(index).name()) return null;
        var resolved = bind(scope.relations(), catalog);
        for (var item : resolved) {
            if (item.nameIndex() == index) return item.object();
            if (item.aliasIndex() == index || index + 2 == item.nameIndex() && at(tokens, index + 1, ".")) return null;
        }
        if (resolved.size() != scope.relations().size() || at(tokens, index + 1, "(") || at(tokens, index - 1, "AS")) return null;
        String qualifier = index >= 2 && at(tokens, index - 1, ".") && tokens.get(index - 2).name() ? tokens.get(index - 2).text() : null;
        String name = tokens.get(index).text();
        var matching = columns(scope, catalog, qualifier).values().stream().flatMap(List::stream)
                .filter(ref -> matches(name, ref.column().name(), ref.object().dialect())).toList();
        return matching.size() == 1 ? matching.getFirst().object() : null;
    }

    private static List<Suggestion> tableSuggestions(LoadedDatabaseCatalog catalog, String schema) {
        var suggestions = new ArrayList<Suggestion>();
        for (var object : catalog.objects()) {
            if (!usable(object.name(), object.dialect()) || !usable(object.schema(), object.dialect())
                    || schema != null && !matches(schema, object.schema(), object.dialect())) continue;
            String value = schema == null ? object.schema() + "." + object.name() : object.name();
            suggestions.add(new Suggestion(value, object.name(), object.type() + " in " + object.schema()));
        }
        return suggestions;
    }

    private static LinkedHashMap<String, List<ColumnRef>> columns(Scope scope, LoadedDatabaseCatalog catalog, String qualifier) {
        var result = new LinkedHashMap<String, List<ColumnRef>>();
        if (!scope.completeRelations()) return result;
        var objects = bind(scope.relations(), catalog);
        if (objects.size() != scope.relations().size()) return result;
        var relevant = objects.stream().filter(item -> qualifier == null
                || matches(qualifier, item.qualifier(), item.object().dialect())).toList();
        if (qualifier != null && relevant.size() != 1) return result;
        for (var item : relevant) {
            var definition = catalog.definitions().get(item.object());
            if (definition == null) return new LinkedHashMap<>();
            for (var column : definition.columns()) {
                if (!usable(column.name(), item.object().dialect())) continue;
                String key = folded(column.name(), item.object().dialect());
                result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new ColumnRef(item.object(), column));
            }
        }
        return result;
    }

    private static List<BoundRelation> bind(List<Relation> relations, LoadedDatabaseCatalog catalog) {
        var resolved = new ArrayList<BoundRelation>();
        for (var relation : relations) {
            var candidates = catalog.objects().stream().filter(object -> usable(object.name(), object.dialect())
                            && matches(relation.name(), object.name(), object.dialect())
                            && (relation.schema() == null || usable(object.schema(), object.dialect())
                            && matches(relation.schema(), object.schema(), object.dialect())))
                    .limit(2).toList();
            if (candidates.size() == 1) {
                resolved.add(new BoundRelation(candidates.getFirst(), relation.alias() == null ? relation.name() : relation.alias(),
                        relation.nameIndex(), relation.aliasIndex()));
            }
        }
        return resolved;
    }

    private static Scope scope(String text, int offset) {
        if (text.length() > 256 * 1024 || offset < 0 || offset > text.length()) return null;
        var lexer = new DatabaseSqlLexer();
        lexer.start(text);
        var tokens = new ArrayList<Token>();
        while (lexer.getTokenType() != null) {
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            var type = lexer.getTokenType();
            String value = text.substring(start, end);
            if (type == DatabaseSqlLexer.SYMBOL && value.equals(";")) {
                if (end > offset) break;
                tokens.clear();
            } else if (type != TokenType.WHITE_SPACE) {
                if ((type == DatabaseSqlLexer.STRING || type == DatabaseSqlLexer.COMMENT) && start <= offset && offset < end) return null;
                if (type != DatabaseSqlLexer.COMMENT) tokens.add(new Token(type, value, start, end));
                if (tokens.size() > 8192) return null;
            }
            lexer.advance();
        }
        if (tokens.isEmpty() || !at(tokens, 0, "SELECT")) return null;
        int depth = 0;
        boolean completeRelations = true;
        var relations = new ArrayList<Relation>();
        for (int i = 1; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.type() == DatabaseSqlLexer.KEYWORD && COMPLEX.contains(token.upper())) return null;
            if (token.text().equals("(")) depth++;
            if (token.text().equals(")") && --depth < 0) return null;
            if (depth != 0 || !relationKeyword(tokens, i)) continue;
            var relation = relation(tokens, i + 1);
            if (relation != null) {
                int last = Math.max(relation.nameIndex(), relation.aliasIndex());
                if (at(tokens, last + 1, ",") || at(tokens, relation.nameIndex() + 1, "(")) return null;
                relations.add(relation);
            } else {
                if (at(tokens, i + 1, "(")) return null;
                completeRelations = false;
            }
        }
        return depth == 0 ? new Scope(tokens, relations, completeRelations) : null;
    }

    private static Relation relation(List<Token> tokens, int start) {
        if (start >= tokens.size() || !tokens.get(start).name()) return null;
        int name = start;
        String schema = null;
        if (at(tokens, start + 1, ".")) {
            if (start + 2 >= tokens.size() || !tokens.get(start + 2).name()) return null;
            schema = tokens.get(start).text();
            name = start + 2;
        }
        int alias = name + (at(tokens, name + 1, "AS") ? 2 : 1);
        if (alias >= tokens.size() || !tokens.get(alias).name()) alias = -1;
        return new Relation(schema, tokens.get(name).text(), alias < 0 ? null : tokens.get(alias).text(), name, alias);
    }

    private static boolean usable(String name, SqlText.Dialect dialect) {
        return SIMPLE.matcher(name).matches() && !DatabaseSqlLexer.KEYWORDS.contains(name.toUpperCase(Locale.ROOT))
                && (dialect != SqlText.Dialect.POSTGRESQL || name.equals(folded(name, dialect)));
    }

    private static String folded(String name, SqlText.Dialect dialect) {
        return dialect == SqlText.Dialect.CLICKHOUSE || dialect == SqlText.Dialect.MYSQL ? name : name.toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String input, String name, SqlText.Dialect dialect) {
        return folded(input, dialect).equals(folded(name, dialect));
    }

    private static boolean relationKeyword(List<Token> tokens, int index) {
        return at(tokens, index, "FROM") || at(tokens, index, "JOIN");
    }

    private static boolean at(List<Token> tokens, int index, String expected) {
        if (index < 0 || index >= tokens.size()) return false;
        var token = tokens.get(index);
        return (token.type() == DatabaseSqlLexer.KEYWORD || token.type() == DatabaseSqlLexer.SYMBOL)
                && token.text().equalsIgnoreCase(expected);
    }

    record Suggestion(String insertion, String lookup, String detail) {}
    private record Relation(String schema, String name, String alias, int nameIndex, int aliasIndex) {}
    private record BoundRelation(SchemaObject object, String qualifier, int nameIndex, int aliasIndex) {}
    private record ColumnRef(SchemaObject object, TableDefinition.Column column) {}

    private record Token(IElementType type, String text, int start, int end) {
        boolean name() { return type == DatabaseSqlLexer.NAME && SIMPLE.matcher(text).matches(); }
        String upper() { return text.toUpperCase(Locale.ROOT); }
    }

    private record Scope(List<Token> tokens, List<Relation> relations, boolean completeRelations) {
        int beforeWord(int offset) {
            int before = -1;
            for (var token : tokens) {
                if (token.start() >= offset || token.end() > offset || token.end() == offset && token.type() != DatabaseSqlLexer.SYMBOL) break;
                before++;
            }
            return before;
        }

        int wordAt(int offset) {
            for (int index = 0; index < tokens.size(); index++) {
                var token = tokens.get(index);
                if (token.start() <= offset && offset < token.end()) return index;
            }
            return -1;
        }
    }
}
