package org.openkaryon.karyoforge.database.core;

public record DatabaseTimeouts(int connectSeconds, int querySeconds) {
    public static final DatabaseTimeouts DEFAULT = new DatabaseTimeouts(15, 30);

    public DatabaseTimeouts {
        if (connectSeconds < 1 || connectSeconds > 120 || querySeconds < 1 || querySeconds > 3600) {
            throw new IllegalArgumentException("Enter a connection timeout from 1 to 120 seconds and a query timeout from 1 to 3600 seconds.");
        }
    }

    public static int maximumQuerySeconds(DatabaseSource source) { return source instanceof ClickHouseSource ? 300 : 3600; }

    void checkSource(DatabaseSource source) {
        if (querySeconds > maximumQuerySeconds(source)) {
            throw new DatabaseProblem("ClickHouse supports a query timeout of at most 300 seconds.");
        }
    }
}
