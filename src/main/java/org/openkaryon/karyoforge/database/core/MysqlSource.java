package org.openkaryon.karyoforge.database.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public record MysqlSource(String name, String host, int port, String database, String user, Path driver,
                          boolean writable, Variant variant) implements DatabaseSource {
    public enum Variant {
        MYSQL("MySQL", "mysql", "Connector/J 26.7.0"),
        MARIADB("MariaDB", "mariadb", "Connector/J 3.5.10");

        private final String title;
        private final String protocol;
        private final String connector;

        Variant(String title, String protocol, String connector) {
            this.title = title;
            this.protocol = protocol;
            this.connector = connector;
        }

        public String title() { return title; }
        public String connector() { return title + " " + connector; }
    }

    public MysqlSource(String name, String host, int port, String database, String user, Path driver, boolean writable) {
        this(name, host, port, database, user, driver, writable, Variant.MYSQL);
    }
    public MysqlSource {
        java.util.Objects.requireNonNull(variant);
        name = DatabaseSource.name(name);
        host = DatabaseSource.text(host, "a database host", 255);
        if (host.equalsIgnoreCase("localhost")) host = "127.0.0.1";
        if (!host.equals("127.0.0.1") && !host.equals("::1")) {
            throw new IllegalArgumentException(variant.title() + " connections require a numeric loopback address.");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Enter a port between 1 and 65535.");
        database = DatabaseSource.text(database, "a database name", 255);
        user = DatabaseSource.text(user, "a database user name", 255);
        driver = DatabaseSource.absolute(driver, "driver");
    }

    String jdbcUrl() {
        String authority = host.equals("::1") ? "[::1]" : host;
        return "jdbc:" + variant.protocol + "://" + authority + ":" + port + "/"
                + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override public String engine() { return variant.title(); }
    @Override public SqlText.Dialect dialect() { return SqlText.Dialect.MYSQL; }
    @Override public boolean usesPassword() { return true; }
}
