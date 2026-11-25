package org.openkaryon.karyoforge.database.core;

import java.nio.file.Path;

public record ClickHouseSource(String name, int port, String database, String user, Path driver) implements DatabaseSource {
    public ClickHouseSource {
        name = DatabaseSource.name(name);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Enter a ClickHouse port between 1 and 65535.");
        database = simple(database, "database");
        user = simple(user, "user");
        driver = DatabaseSource.absolute(driver, "driver");
    }

    private static String simple(String text, String field) {
        String value = text == null ? "" : text.strip();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Enter a simple ClickHouse " + field + " name.");
        }
        return value;
    }

    @Override public boolean writable() { return false; }
    @Override public boolean usesPassword() { return true; }
    @Override public String engine() { return "ClickHouse"; }
    @Override public SqlText.Dialect dialect() { return SqlText.Dialect.CLICKHOUSE; }
}
