package org.openkaryon.karyoforge.database.core;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class ClickHouseJdbcSessionTest {
    @Test public void verifiesReadOnlyGrantsAndReadsExactTypedPages() {
        try (var session = open(false)) {
            assertEquals("1", scalar(session, "SELECT value FROM system.settings WHERE name='readonly'"));
            var objects = session.schema();
            assertEquals(2, objects.size());
            var table = objects.stream().filter(item -> item.name().equals("samples")).findFirst().orElseThrow();
            assertEquals(Long.valueOf(205), table.estimatedRows());
            var exact = session.describe(table);
            assertEquals(1, exact.columns().getFirst().keyPosition());
            assertTrue(exact.ddl().contains("CREATE TABLE"));
            var page = (QueryPage) run(session, table.selectSql() + " ORDER BY id");
            assertEquals(200, page.rows().size());
            var first = page.rows().getFirst().cells();
            assertEquals(QueryPage.Cell.Kind.NULL, first.get(1).kind());
            assertEquals("18446744073709551615", first.get(2).value());
            assertEquals("9223372036854775807.000001", first.get(3).value());
            assertEquals("201", session.next(new QueryRequest()).rows().getFirst().cells().getFirst().value());
            assertFalse(session.openTable(table, new QueryRequest()).protection().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> run(session, "EXPLAIN SELECT 1"));
            assertThrows(IllegalArgumentException.class, () -> run(session, "INSERT INTO samples VALUES (1, NULL, 1, 1)"));
            assertThrows(DatabaseProblem.class, () -> session.finishTransaction(true));
        }
        assertThrows(DatabaseProblem.class, () -> open(true));
    }

    @Test public void interruptsOneObservedQueryAndReusesTheGuardedSession() throws Exception {
        try (var target = open(false); var observer = open(false)) {
            var request = new QueryRequest();
            var execution = new FutureTask<>(() -> assertThrows(DatabaseProblem.class, () -> target.execute(compile(
                    "SELECT sum(cityHash64(number)) AS ownedClickHouseStop FROM numbers(10000000000)"), Map.of(), request)));
            Thread worker = Thread.ofPlatform().daemon().name("owned ClickHouse acceptance query").start(execution);
            String queryId = null;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime() < deadline && !execution.isDone()) {
                    var page = (QueryPage) run(observer, """
                            SELECT query_id FROM system.processes WHERE query LIKE '%ownedClickHouseStop%'
                            AND query_id != currentQueryID() LIMIT 1
                            """);
                    if (!page.rows().isEmpty()) {
                        queryId = page.rows().getFirst().cells().getFirst().value();
                        break;
                    }
                    Thread.sleep(50);
                }
            } finally {
                request.stop();
                worker.join(12_000);
            }
            assertNotNull("The second session must observe the actual server query", queryId);
            assertFalse(worker.isAlive());
            assertEquals("Database request stopped.", execution.get(1, TimeUnit.SECONDS).getMessage());
            var count = compile("SELECT count() FROM system.processes WHERE query_id=:id");
            var state = (QueryPage) observer.execute(count, Map.of("id", SqlValue.of(SqlValue.Type.TEXT, queryId, false)),
                    new QueryRequest());
            assertEquals("0", state.rows().getFirst().cells().getFirst().value());
            assertEquals("42", scalar(target, "SELECT 42"));
        }
    }

    private static JdbcSession open(boolean elevated) {
        String prefix = "KARYOFORGE_TEST_CLICKHOUSE_";
        var profile = new ClickHouseSource("Owned ClickHouse", Integer.parseInt(System.getenv(prefix + "PORT")),
                "karyoforge_acceptance", System.getenv(prefix + (elevated ? "ADMIN" : "USER")),
                Path.of(System.getProperty("karyoforge.database.clickhouse.driver")));
        var session = new JdbcSession(profile);
        var password = System.getenv(prefix + (elevated ? "ADMIN_PASSWORD" : "PASSWORD")).toCharArray();
        try {
            session.connect(password);
            return session;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static SqlText.Command compile(String sql) { return SqlText.compile(sql, SqlText.Dialect.CLICKHOUSE); }
    private static SqlResult run(JdbcSession session, String sql) { return session.execute(compile(sql), Map.of(), new QueryRequest()); }
    private static String scalar(JdbcSession session, String sql) {
        return ((QueryPage) run(session, sql)).rows().getFirst().cells().getFirst().value();
    }
}
