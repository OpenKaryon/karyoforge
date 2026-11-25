package org.openkaryon.karyoforge.database.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public record MysqlSource(String name, String host, int port, String database, String user, Path driver,
                          boolean writable) implements DatabaseSource {
    public MysqlSource {
        name = DatabaseSource.name(name);
        host = DatabaseSource.text(host, "a MySQL host", 255);
        if (host.equalsIgnoreCase("localhost")) host = "127.0.0.1";
        if (!host.equals("127.0.0.1") && !host.equals("::1")) {
            throw new IllegalArgumentException("MySQL connections require a numeric loopback address.");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Enter a port between 1 and 65535.");
        database = DatabaseSource.text(database, "a MySQL database name", 255);
        user = DatabaseSource.text(user, "a MySQL user name", 255);
        driver = DatabaseSource.absolute(driver, "driver");
    }

    String jdbcUrl() {
        String authority = host.equals("::1") ? "[::1]" : host;
        return "jdbc:mysql://" + authority + ":" + port + "/"
                + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override public String engine() { return "MySQL"; }
    @Override public SqlText.Dialect dialect() { return SqlText.Dialect.MYSQL; }
    @Override public boolean usesPassword() { return true; }
}
