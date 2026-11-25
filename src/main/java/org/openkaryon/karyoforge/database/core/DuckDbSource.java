package org.openkaryon.karyoforge.database.core;

import java.nio.file.Path;

public record DuckDbSource(String name, Path database, Path driver, boolean writable) implements DatabaseSource {
    public DuckDbSource {
        name = DatabaseSource.name(name);
        database = DatabaseSource.absolute(database, "database");
        driver = DatabaseSource.absolute(driver, "driver");
    }

    String jdbcUrl() {
        String path = java.io.File.separatorChar == '\\' ? database.toString().replace('\\', '/') : database.toString();
        return "jdbc:duckdb:" + path;
    }

    @Override public String engine() { return "DuckDB"; }
    @Override public SqlText.Dialect dialect() { return SqlText.Dialect.DUCKDB; }
}
