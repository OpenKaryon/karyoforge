package org.openkaryon.karyoforge.database.core;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class MysqlJdbcSessionTest {
    @Test public void verifiesReadOnlyTypedPagesCatalogAndExplain() {
        try (var session = open(false)) {
            assertEquals("1", scalar(session, "SELECT @@session.transaction_read_only"));
            var catalog = session.schema();
            var table = catalog.stream().filter(object -> object.name().equals("samples")).findFirst().orElseThrow();
            assertEquals("`karyoforge_acceptance`.`samples`", table.qualified());
            var structure = session.describe(table);
            assertEquals(List.of(1,0,0,0,0), structure.columns().stream().map(TableDefinition.Column::keyPosition).toList());
            assertTrue(structure.ddl().contains("CREATE TABLE"));
            var page = (QueryPage) run(session, table.selectSql() + " ORDER BY id");
            assertEquals(200, page.rows().size());
            assertEquals("18446744073709551615", page.rows().getFirst().cells().get(2).value());
            assertEquals("9223372036854775807.000001", page.rows().getFirst().cells().get(3).value());
            assertEquals("00ab", page.rows().getFirst().cells().get(4).value());
            assertEquals("201", session.next(new QueryRequest()).rows().getFirst().cells().getFirst().value());
            assertEquals(200, session.openTable(table, new QueryRequest()).data().rows().size());
            assertFalse(((QueryPage) run(session, "EXPLAIN SELECT * FROM samples WHERE id=1")).rows().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> run(session, "EXPLAIN /* comment */ ANALYZE SELECT * FROM samples"));
            var failure = assertThrows(DatabaseProblem.class, () -> run(session,
                    "WITH chosen AS (SELECT 1 AS id) DELETE FROM samples WHERE id IN (SELECT id FROM chosen)"));
            assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).replace('-', ' ').contains("read only"));
            assertEquals("205", scalar(session, "SELECT count(*) FROM samples"));
        }
    }

    @Test public void keepsWritesPendingAndRejectsImplicitCommitCommands() {
        try (var writer = open(true); var observer = open(false)) {
            var change = compile("UPDATE samples SET note=:note WHERE id=205");
            var values = Map.of("note", SqlValue.of(SqlValue.Type.TEXT, "owned 'literal'; test", false));
            assertEquals(new SqlResult.UpdateCount(1), writer.execute(change, values, new QueryRequest()));
            assertThrows(IllegalArgumentException.class, () -> run(writer, "CREATE TABLE implicit (id int)"));
            assertThrows(IllegalArgumentException.class, () -> run(writer, "SELECT 1 /*! ; SELECT 2 */"));
            assertEquals("sample-205", scalar(observer, "SELECT note FROM samples WHERE id=205"));
            writer.finishTransaction(false);
            assertEquals("sample-205", scalar(writer, "SELECT note FROM samples WHERE id=205"));
            writer.execute(change, values, new QueryRequest());
            writer.finishTransaction(true);
            try (var fresh = open(false)) {
                assertEquals("owned 'literal'; test", scalar(fresh, "SELECT note FROM samples WHERE id=205"));
            }
        }
    }

    @Test public void stopInterruptsTheObservedMysqlQueryAndLeavesOtherSessionsUsable() throws Exception {
        try (var target = open(false); var observer = open(false)) {
            var id = scalar(target, "SELECT CONNECTION_ID()");
            var request = new QueryRequest();
            var execution = new FutureTask<>(() -> assertThrows(DatabaseProblem.class,
                    () -> target.execute(compile("SELECT SLEEP(23)"), Map.of(), request)));
            Thread worker = Thread.ofPlatform().daemon().name("owned MySQL acceptance query").start(execution);
            boolean sawSleep = false;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime() < deadline && !execution.isDone()) {
                    var observed = (QueryPage) run(observer, "SELECT state FROM information_schema.processlist WHERE id=" + Long.parseLong(id));
                    if (!observed.rows().isEmpty() && "User sleep".equalsIgnoreCase(observed.rows().getFirst().cells().getFirst().value())) {
                        sawSleep = true;
                        break;
                    }
                    Thread.sleep(70);
                }
            } finally {
                request.stop();
                worker.join(10_000);
            }
            assertTrue(sawSleep);
            assertFalse(worker.isAlive());
            assertEquals("Database request stopped.", execution.get(1, TimeUnit.SECONDS).getMessage());
            assertEquals("42", scalar(target, "SELECT 42"));
            assertEquals("43", scalar(observer, "SELECT 43"));
        }
    }

    private static JdbcSession open(boolean writable) {
        var source = new MysqlSource("Owned MySQL", "127.0.0.1", Integer.parseInt(System.getenv("KARYOFORGE_TEST_MYSQL_PORT")),
                "karyoforge_acceptance", System.getenv("KARYOFORGE_TEST_MYSQL_USER"),
                Path.of(System.getProperty("karyoforge.database.mysql.driver")), writable);
        var session = new JdbcSession(source);
        char[] password = System.getenv("KARYOFORGE_TEST_MYSQL_PASSWORD").toCharArray();
        try {
            session.connect(password);
            return session;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static SqlText.Command compile(String sql) { return SqlText.compile(sql, SqlText.Dialect.MYSQL); }

    private static SqlResult run(JdbcSession session, String sql) { return session.execute(compile(sql), Map.of(), new QueryRequest()); }

    private static String scalar(JdbcSession session, String sql) {
        return ((QueryPage) run(session, sql)).rows().getFirst().cells().getFirst().value();
    }
}
