package org.openkaryon.karyoforge.database.core;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public record PostgresqlSource(String name, String host, int port, String database, String user, Path driver,
                               boolean writable, Path caFile) implements DatabaseSource {
    public PostgresqlSource(String name, String host, int port, String database, String user, Path driver, boolean writable) {
        this(name, host, port, database, user, driver, writable, null);
    }

    public PostgresqlSource {
        name = DatabaseSource.name(name);
        host = validateHost(host, caFile != null);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Enter a port between 1 and 65535.");
        database = DatabaseSource.text(database, "a PostgreSQL database name", 255);
        user = DatabaseSource.text(user, "a PostgreSQL user name", 255);
        driver = DatabaseSource.absolute(driver, "driver");
        if (caFile != null) caFile = DatabaseSource.absolute(caFile, "CA certificate");
    }

    private static String validateHost(String value, boolean tls) {
        String host = DatabaseSource.text(value, "a PostgreSQL host", 255);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty()) throw new IllegalArgumentException("Enter a PostgreSQL host.");
        if (!tls && host.equalsIgnoreCase("localhost")) return "127.0.0.1";
        if (!tls && !host.equals("127.0.0.1") && !host.equals("::1")) {
            throw new IllegalArgumentException("Plain PostgreSQL connections require a numeric loopback address.");
        }
        try {
            if (host.indexOf(':') < 0) return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (!host.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException();
            InetAddress.getByName(host);
            return host;
        } catch (IllegalArgumentException | UnknownHostException invalid) {
            throw new IllegalArgumentException("Enter one valid PostgreSQL host name or IP address.");
        }
    }

    public boolean verifiedTls() { return caFile != null; }

    String jdbcUrl() {
        String authority = host.indexOf(':') < 0 ? host : "[" + host + "]";
        return "jdbc:postgresql://" + authority + ":" + port + "/"
                + URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override public String engine() { return "PostgreSQL"; }

    @Override public SqlText.Dialect dialect() { return SqlText.Dialect.POSTGRESQL; }
}
