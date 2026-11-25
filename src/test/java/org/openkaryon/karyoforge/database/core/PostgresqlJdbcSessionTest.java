package org.openkaryon.karyoforge.database.core;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class PostgresqlJdbcSessionTest {
    @Test public void browsesPostgresqlWithTypedPagesAndServerReadOnlyEnforcement() {
        try (var setup = open(true); var readonly = open(false)) {
            run(setup, "CREATE SCHEMA \"Owned catalog\"");
            run(setup, """
                    CREATE TABLE "Owned catalog"."typed rows" (
                        id bigint,
                        note text NOT NULL,
                        amount numeric(40,6),
                        payload bytea,
                        PRIMARY KEY (id, note)
                    )
                    """);
            run(setup, """
                    INSERT INTO "Owned catalog"."typed rows"
                    SELECT id, 'sample-' || id, 9223372036854775807.000001, decode('00abcd','hex')
                    FROM pg_catalog.generate_series(1,205) AS id
                    """);
            setup.finishTransaction(true);

            var object = readonly.schema().stream().filter(item -> item.name().equals("typed rows")).findFirst().orElseThrow();
            assertEquals("Owned catalog", object.schema());
            var definition = readonly.describe(object);
            assertEquals(List.of(1, 2, 0, 0), definition.columns().stream().map(TableDefinition.Column::keyPosition).toList());
            assertTrue(definition.ddl().contains("PRIMARY KEY (id, note)"));
            var page = (QueryPage) run(readonly, object.selectSql() + " ORDER BY id");
            assertEquals(200, page.rows().size());
            assertEquals("9223372036854775807.000001", page.rows().getFirst().cells().get(2).value());
            assertEquals("00abcd", page.rows().getFirst().cells().get(3).value());
            assertEquals("201", readonly.next(new QueryRequest()).rows().getFirst().cells().getFirst().value());

            assertThrows(DatabaseProblem.class, () -> run(readonly,
                    "WITH updated AS (DELETE FROM \"Owned catalog\".\"typed rows\" RETURNING id) SELECT id FROM updated"));
            assertEquals("on", scalar(readonly, "SHOW transaction_read_only"));
            assertEquals("205", scalar(readonly, "SELECT count(*) FROM \"Owned catalog\".\"typed rows\""));
        }
    }

    @Test public void rollsBackOnlyTheFailedStatementAndCommitsEarlierWork() {
        try (var writer = open(true); var observer = open(false)) {
            run(writer, "CREATE TABLE owned_pending (id bigint PRIMARY KEY, label text)");
            writer.finishTransaction(true);
            run(writer, "INSERT INTO owned_pending VALUES (1, 'before failure')");
            assertThrows(DatabaseProblem.class, () -> run(writer, "SELECT nonexistent FROM owned_pending"));
            assertEquals("before failure", scalar(writer, "SELECT label FROM owned_pending"));
            assertEquals("0", scalar(observer, "SELECT count(*) FROM owned_pending"));
            writer.finishTransaction(true);
            assertEquals("1", scalar(observer, "SELECT count(*) FROM owned_pending"));

            var command = compile("SELECT :value::bigint, $label$?:literal;$label$, '{\"x\":1}'::jsonb ?? 'x'");
            var value = SqlValue.of(SqlValue.Type.INTEGER, "9223372036854775807", false);
            var row = ((QueryPage) writer.execute(command, Map.of("value", value), new QueryRequest())).rows().getFirst().cells();
            assertEquals("9223372036854775807", row.get(0).value());
            assertEquals("?:literal;", row.get(1).value());
            assertEquals("t", row.get(2).value());
        }
    }

    @Test public void cancelsTheOwnedBackendAndKeepsTheSessionUsable() throws Exception {
        try (var query = open(true); var observer = open(false)) {
            long pid = Long.parseLong(scalar(query, "SELECT pg_backend_pid()"));
            var request = new QueryRequest();
            var executing = new FutureTask<>(() -> assertThrows(DatabaseProblem.class,
                    () -> query.execute(compile("SELECT 42 FROM pg_catalog.pg_sleep(24)"), Map.of(), request)));
            Thread worker = Thread.ofPlatform().daemon().name("owned PostgreSQL acceptance query").start(executing);
            boolean observed = false;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline && !executing.isDone()) {
                    var byPid = ((QueryPage) observer.execute(compile("""
                            SELECT wait_event FROM pg_catalog.pg_stat_activity WHERE pid = :pid
                            """), Map.of("pid", SqlValue.of(SqlValue.Type.INTEGER, Long.toString(pid), false)), new QueryRequest()));
                    if (!byPid.rows().isEmpty() && "PgSleep".equals(byPid.rows().getFirst().cells().getFirst().value())) {
                        observed = true;
                        break;
                    }
                    run(observer, "SELECT pg_catalog.pg_stat_clear_snapshot()");
                    Thread.sleep(80);
                }
            } finally {
                request.stop();
                worker.join(10_000);
            }
            assertTrue("The fixture must observe pg_sleep on this backend before Stop", observed);
            assertFalse("The owned backend query must settle after Stop", worker.isAlive());
            assertEquals("Database request stopped.", executing.get(1, TimeUnit.SECONDS).getMessage());
            assertEquals("42", scalar(query, "SELECT 42"));
            request.stop();
            assertEquals("still connected", scalar(observer, "SELECT 'still connected'"));
        }
    }



    @Test public void guardsKeyedRowsAgainstAConcurrentUpdateAndKeepsEarlierPendingWork() {
        try (var writer = open(true); var concurrent = open(true); var observer = open(false)) {
            run(writer, """
                    CREATE TABLE edited_rows (
                        project integer NOT NULL,
                        code text NOT NULL,
                        value text,
                        changed_at timestamp with time zone,
                        payload bytea,
                        PRIMARY KEY (project, code)
                    )
                    """);
            run(writer, "CREATE TABLE unrelated_pending (value text)");
            run(writer, """
                    INSERT INTO edited_rows VALUES (1, 'a', 'one', '2026-11-25T12:00:00+00', decode('00aa','hex')),
                         (1, 'b', 'two', '2026-11-25T13:00:00+00', decode('00bb','hex'))
                    """);
            writer.finishTransaction(true);
            run(writer, "INSERT INTO unrelated_pending VALUES ('keep this')");
            var table = new JdbcSession.SchemaObject("edited_rows", "table", "public");
            var snapshot = writer.openTable(table, new QueryRequest());
            assertEquals("", snapshot.protection());
            assertEquals("", snapshot.rowProtection(0));
            assertNull(snapshot.columnType(0));
            assertEquals(SqlValue.Type.TIMESTAMPTZ, snapshot.columnType(3));
            assertEquals("00aa", snapshot.data().rows().getFirst().cells().get(4).value());

            run(concurrent, "UPDATE edited_rows SET value='from other session' WHERE project=1 AND code='b'");
            concurrent.finishTransaction(true);
            var update = new TableSnapshot.Change(0, false, Map.of(2, SqlValue.of(SqlValue.Type.TEXT, "reviewed", false)));
            var remove = new TableSnapshot.Change(1, true, Map.of());
            var conflict = assertThrows(DatabaseProblem.class,
                    () -> writer.applyTableChanges(snapshot, List.of(update, remove), new QueryRequest()));
            assertTrue(conflict.getMessage().contains("original row changed"));
            assertEquals("one", scalar(writer, "SELECT value FROM edited_rows WHERE code='a'"));
            assertEquals("keep this", scalar(writer, "SELECT value FROM unrelated_pending"));

            var fresh = writer.openTable(table, new QueryRequest());
            assertEquals(2, writer.applyTableChanges(fresh, List.of(update, remove), new QueryRequest()));
            assertEquals("2", scalar(observer, "SELECT count(*) FROM edited_rows"));
            writer.finishTransaction(true);
            assertEquals("1", scalar(observer, "SELECT count(*) FROM edited_rows"));
            assertEquals("reviewed", scalar(observer, "SELECT value FROM edited_rows WHERE code='a'"));
            assertEquals("keep this", scalar(observer, "SELECT value FROM unrelated_pending"));
        }
    }

    @Test public void verifiesTheExplicitTlsCaAndServerNameWithoutPlaintextFallback() {
        Path trusted = Path.of(System.getenv("KARYOFORGE_TEST_PG_CA"));
        Path unrelated = Path.of(System.getenv("KARYOFORGE_TEST_PG_OTHER_CA"));
        try (var tls = open(false, "localhost", trusted)) {
            assertEquals("t", scalar(tls, "SELECT ssl FROM pg_catalog.pg_stat_ssl WHERE pid=pg_catalog.pg_backend_pid()"));
            assertEquals("on", scalar(tls, "SHOW transaction_read_only"));
        }
        assertThrows(DatabaseProblem.class, () -> open(false, "127.0.0.1", trusted));
        assertThrows(DatabaseProblem.class, () -> open(false, "localhost", unrelated));
    }

    private static JdbcSession open(boolean writable) { return open(writable, "127.0.0.1", null); }

    private static JdbcSession open(boolean writable, String host, Path ca) {
        var source = new PostgresqlSource("Owned PostgreSQL", host,
                Integer.parseInt(System.getenv("KARYOFORGE_TEST_PG_PORT")), "karyoforge_acceptance", "postgres",
                Path.of(System.getProperty("karyoforge.database.postgresql.driver")), writable, ca);
        var session = new JdbcSession(source);
        var password = passwordForTest();
        try {
            session.connect(password);
            return session;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] passwordForTest() {
        return System.getenv("KARYOFORGE_TEST_PG_PASSWORD").toCharArray();
    }

    private static SqlText.Command compile(String sql) { return SqlText.compile(sql, SqlText.Dialect.POSTGRESQL); }

    private static SqlResult run(JdbcSession session, String sql) { return session.execute(compile(sql), Map.of(), new QueryRequest()); }

    private static String scalar(JdbcSession session, String sql) {
        return ((QueryPage) run(session, sql)).rows().getFirst().cells().getFirst().value();
    }
}
