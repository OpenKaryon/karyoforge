package org.openkaryon.karyoforge.database.core;

import java.nio.file.Path;

public record SqliteSource(String name, Path database, Path driver, boolean writable) implements DatabaseSource {
    public SqliteSource(String name, Path database, Path driver) {
        this(name, database, driver, false);
    }

    public SqliteSource {
        name = DatabaseSource.name(name);
        database = DatabaseSource.absolute(database, "database");
        driver = DatabaseSource.absolute(driver, "driver");
    }

    public String jdbcUrl() {
        return "jdbc:sqlite:" + database.toUri().toASCIIString() + (writable ? "?mode=rw" : "?mode=ro");
    }

    @Override public String engine() { return "SQLite"; }

    @Override public SqlText.Dialect dialect() { return SqlText.Dialect.SQLITE; }
}
