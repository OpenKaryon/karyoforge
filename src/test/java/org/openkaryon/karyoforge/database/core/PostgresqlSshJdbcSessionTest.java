package org.openkaryon.karyoforge.database.core;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

public final class PostgresqlSshJdbcSessionTest {
    @Test public void verifiesDatabaseTlsThroughAnOwnedAuthenticatedSshListener() {
        try (var session = connect("localhost", "KARYOFORGE_TEST_SSH_KNOWN")) {
            var command = SqlText.compile("""
                    SELECT ssl, current_database()
                    FROM pg_catalog.pg_stat_ssl WHERE pid=pg_catalog.pg_backend_pid()
                    """, SqlText.Dialect.POSTGRESQL);
            var rows = ((QueryPage) session.execute(command, Map.of(), new QueryRequest())).rows();
            assertEquals("t", rows.getFirst().cells().getFirst().value());
            assertEquals("karyoforge_acceptance", rows.getFirst().cells().get(1).value());
        }
        assertThrows(DatabaseProblem.class, () -> connect("127.0.0.1", "KARYOFORGE_TEST_SSH_KNOWN"));
        var wrong = assertThrows(DatabaseProblem.class, () -> connect("localhost", "KARYOFORGE_TEST_SSH_WRONG"));
        assertTrue(wrong.getMessage().contains("SSH forward did not start"));
        try (var retry = connect("localhost", "KARYOFORGE_TEST_SSH_KNOWN")) {
            assertNotNull(retry.schema());
        }
    }

    private static JdbcSession connect(String dbHost, String knownHosts) {
        var route = new SshForwarding("127.0.0.1", Integer.parseInt(System.getenv("KARYOFORGE_TEST_SSH_PORT")),
                "karyoforge_ssh", envPath("KARYOFORGE_TEST_SSH_EXECUTABLE"), envPath("KARYOFORGE_TEST_SSH_KEY"),
                envPath(knownHosts), "127.0.0.1", 5432);
        var source = new PostgresqlSource("Owned SSH database", dbHost, 5432, "karyoforge_acceptance", "postgres",
                Path.of(System.getProperty("karyoforge.database.postgresql.driver")), false, envPath("KARYOFORGE_TEST_PG_CA"), route);
        var session = new JdbcSession(source);
        char[] password = System.getenv("KARYOFORGE_TEST_PG_PASSWORD").toCharArray();
        try {
            session.connect(password);
            return session;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static Path envPath(String name) { return Path.of(System.getenv(name)); }
}
