package org.openkaryon.karyoforge.database.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public record PostgresqlSource(String name, String host, int port, String database, String user, Path driver,
                               boolean writable) implements DatabaseSource {
    public PostgresqlSource {
        name = DatabaseSource.name(name);
        host = DatabaseSource.text(host, "a PostgreSQL host", 255);
        if (host.equalsIgnoreCase("localhost")) host = "127.0.0.1";
        if (!host.equals("127.0.0.1") && !host.equals("::1")) {
            throw new IllegalArgumentException("Plain PostgreSQL connections require a numeric loopback address.");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Enter a port between 1 and 65535.");
        database = DatabaseSource.text(database, "a PostgreSQL database name", 255);
        user = DatabaseSource.text(user, "a PostgreSQL user name", 255);
        driver = DatabaseSource.absolute(driver, "driver");
    }

    String jdbcUrl() {
        String authority = host.equals("::1") ? "[::1]" : host;
        return "jdbc:postgresql://" + authority + ":" + port + "/"
                + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override public String engine() { return "PostgreSQL"; }

    @Override public SqlText.Dialect dialect() { return SqlText.Dialect.POSTGRESQL; }
}
