package org.openkaryon.karyoforge.database.core;

import java.nio.file.Path;
import java.util.Objects;

public record SqliteSource(String name, Path database, Path driver, boolean writable) {
    public SqliteSource(String name, Path database, Path driver) {
        this(name, database, driver, false);
    }

    public SqliteSource {
        name = Objects.requireNonNull(name).strip();
        if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Enter a data source name of up to 100 characters.");
        }
        database = absolute(database, "database");
        driver = absolute(driver, "driver");
    }

    private static Path absolute(Path path, String label) {
        if (path == null || !path.isAbsolute()) throw new IllegalArgumentException("Choose an absolute " + label + " path.");
        return path.normalize();
    }

    public String jdbcUrl() {
        return "jdbc:sqlite:" + database.toUri().toASCIIString() + (writable ? "?mode=rw" : "?mode=ro");
    }
}
