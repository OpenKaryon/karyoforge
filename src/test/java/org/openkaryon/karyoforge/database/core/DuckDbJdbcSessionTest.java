package org.openkaryon.karyoforge.database.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class DuckDbJdbcSessionTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void browsesAnOwnedLocalFileWithExactTypesAndRestrictedExternalAccess() throws Exception {
        var file = fixture();
        var csv = file.resolveSibling("owned input.csv");
        Files.writeString(csv, "name,amount\nfirst,1\n");
        var csvName = csv.toString().replace("\\", "/").replace("'", "''");
        try (var reader = open(file, false)) {
            assertEquals("READ_ONLY", scalar(reader, "SELECT upper(current_setting('access_mode'))"));
            assertEquals("false", scalar(reader, "SELECT current_setting('enable_external_access')"));
            var table = reader.schema().stream().filter(object -> object.name().equals("samples")).findFirst().orElseThrow();
            assertEquals(1, reader.describe(table).columns().getFirst().keyPosition());
            assertTrue(reader.describe(table).ddl().contains("CREATE TABLE"));
            var page = (QueryPage) run(reader, table.selectSql() + " ORDER BY id");
            assertEquals(200, page.rows().size());
            assertEquals("18446744073709551615", page.rows().getFirst().cells().get(2).value());
            assertEquals("9223372036854775807.000001", page.rows().getFirst().cells().get(3).value());
            assertEquals("00ab", page.rows().getFirst().cells().get(4).value());
            assertEquals(5, reader.next(new QueryRequest()).rows().size());
            var snapshot = reader.openTable(table, new QueryRequest());
            assertEquals(200, snapshot.data().rows().size());
            assertFalse(snapshot.protection().isEmpty());

            assertThrows(IllegalArgumentException.class, () -> run(reader, "LOAD httpfs"));
            assertThrows(IllegalArgumentException.class, () -> run(reader, "ATTACH ':memory:' AS another"));
            var denied = assertThrows(DatabaseProblem.class, () -> run(reader, "SELECT * FROM read_csv_auto('" + csvName + "')"));
            assertTrue(denied.getMessage().toLowerCase(Locale.ROOT).contains("disabled"));
            assertEquals("205", scalar(reader, "SELECT count(*) FROM samples"));
        }
    }

    @Test public void rollsBackAnAbortedWritableTransactionAndReusesTheNativeDriver() throws Exception {
        var file = fixture();
        try (var writer = open(file, true)) {
            run(writer, "INSERT INTO samples(id,note) VALUES (206,'pending')");
            assertThrows(DatabaseProblem.class, () -> run(writer, "INSERT INTO samples(id) VALUES (1)"));
            assertThrows(DatabaseProblem.class, () -> run(writer, "SELECT count(*) FROM samples"));
            writer.finishTransaction(false);
            assertEquals("205", scalar(writer, "SELECT count(*) FROM samples"));
            run(writer, "INSERT INTO samples(id,note) VALUES (206,'committed')");
            writer.finishTransaction(true);
        }
        try (var reader = open(file, false)) {
            assertEquals("206", scalar(reader, "SELECT count(*) FROM samples"));
        }
    }

    @Test public void cancelsAnExecutingNativeDuckDbStatement() throws Exception {
        var file = fixture();
        try (var session = open(file, false)) {
            var request = new QueryRequest();
            var sql = compile("SELECT sum(sin(i) * cos(j)) FROM range(10000000) a(i) CROSS JOIN range(10000) b(j)");
            var execution = new FutureTask<>(() -> assertThrows(DatabaseProblem.class,
                    () -> session.execute(sql, Map.of(), request)));
            Thread worker = Thread.ofPlatform().daemon().name("owned DuckDB acceptance query").start(execution);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime() < deadline && !execution.isDone() && !inDuckDb(worker)) Thread.sleep(10);
                assertTrue(inDuckDb(worker));
            } finally {
                request.stop();
                worker.join(10_000);
            }
            assertFalse(worker.isAlive());
            assertEquals("Database request stopped.", execution.get(1, TimeUnit.SECONDS).getMessage());
            assertEquals("42", scalar(session, "SELECT 42"));
        }
    }

    private static boolean inDuckDb(Thread worker) {
        return Arrays.stream(worker.getStackTrace()).anyMatch(frame -> frame.getClassName().equals("org.duckdb.DuckDBNative")
                && frame.getMethodName().contains("execute"));
    }

    private Path fixture() throws Exception {
        Path file = folder.newFolder("owned DuckDB files").toPath().resolve("samples # data.duckdb");
        try (var writer = open(file, true)) {
            run(writer, """
                    CREATE TABLE samples (
                      id BIGINT PRIMARY KEY, note VARCHAR, exact_value UBIGINT, decimal_value DECIMAL(38,6), payload BLOB
                    )
                    """);
            run(writer, """
                    INSERT INTO samples SELECT id, 'sample-' || id, 18446744073709551615::UBIGINT,
                    9223372036854775807.000001::DECIMAL(38,6), from_hex('00AB') FROM range(1,206) AS rows(id)
                    """);
            writer.finishTransaction(true);
        }
        return file;
    }

    private static JdbcSession open(Path file, boolean writable) {
        var source = new DuckDbSource("Owned DuckDB", file,
                Path.of(System.getProperty("karyoforge.database.duckdb.driver")), writable);
        var session = new JdbcSession(source);
        session.connect();
        return session;
    }

    private static SqlText.Command compile(String sql) { return SqlText.compile(sql, SqlText.Dialect.DUCKDB); }

    private static SqlResult run(JdbcSession session, String sql) { return session.execute(compile(sql), Map.of(), new QueryRequest()); }

    private static String scalar(JdbcSession session, String sql) {
        return ((QueryPage) run(session, sql)).rows().getFirst().cells().getFirst().value();
    }
}
