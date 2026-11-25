package org.openkaryon.karyoforge.database.core;

import java.nio.file.Path;
import java.util.Objects;

public sealed interface DatabaseSource permits SqliteSource, PostgresqlSource, MysqlSource, DuckDbSource, ClickHouseSource {
    String name();
    Path driver();
    boolean writable();
    String engine();
    SqlText.Dialect dialect();
    default boolean usesPassword() { return false; }

    static String name(String value) {
        return text(value, "a data source name", 100);
    }

    static String text(String value, String label, int limit) {
        String checked = Objects.requireNonNull(value).strip();
        if (checked.isEmpty() || checked.length() > limit || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Enter " + label + " of up to " + limit + " characters.");
        }
        return checked;
    }

    static Path absolute(Path path, String label) {
        if (path == null || !path.isAbsolute()) throw new IllegalArgumentException("Choose an absolute " + label + " path.");
        return path.normalize();
    }
}
