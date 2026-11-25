package org.openkaryon.karyoforge.database.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openkaryon.karyoforge.database.driver.DriverLease;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class SqliteJdbcSessionTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void opensRealFileAndPagesTypedReadOnlyResults() throws Exception {
        var source = fixture();
        byte[] original = Files.readAllBytes(source.database());
        try (var session = new JdbcSession(source)) {
            session.connect();
            var schema = session.schema();
            assertEquals(List.of("measure \"sample", "sample_view"), schema.stream().map(JdbcSession.SchemaObject::name).toList());
            assertEquals("SELECT * FROM main.\"measure \"\"sample\"", schema.getFirst().selectSql());
            var first = session.execute(schema.getFirst().selectSql() + " ORDER BY id", new QueryRequest());
            assertEquals(200, first.rows().size());
            assertEquals(1, first.page());
            assertTrue(first.hasNext());
            assertEquals(new QueryPage.Cell(QueryPage.Cell.Kind.INTEGER, "9223372036854775807", false),
                    first.rows().getFirst().cells().get(1));
            assertEquals(new QueryPage.Cell(QueryPage.Cell.Kind.TEXT, "", false), first.rows().getFirst().cells().get(2));
            assertEquals(new QueryPage.Cell(QueryPage.Cell.Kind.NULL, null, false), first.rows().get(1).cells().get(2));
            assertEquals(QueryPage.Cell.Kind.TEXT, first.rows().get(1).cells().get(1).kind());

            var last = session.next(new QueryRequest());
            assertEquals(2, last.page());
            assertEquals(5, last.rows().size());
            assertEquals("201", last.rows().getFirst().cells().getFirst().value());
            assertEquals(205, last.totalRows());
            assertFalse(last.hasNext());
            assertThrows(DatabaseProblem.class, () -> session.next(new QueryRequest()));
        }
        assertArrayEquals(original, Files.readAllBytes(source.database()));
        // On Windows, a retained driver resource handle prevents this rename.
        Files.move(source.driver(), source.driver().resolveSibling("released-driver.jar"));
    }

    @Test public void enforcesReadOnlyModeAndSingleStatementScope() throws Exception {
        var source = fixture();
        try (var session = new JdbcSession(source)) {
            session.connect();
            assertThrows(IllegalArgumentException.class, () -> session.execute("SELECT 1; SELECT 2", new QueryRequest()));
            assertThrows(IllegalArgumentException.class, () -> session.execute("PRAGMA query_only = OFF", new QueryRequest()));
            assertThrows(DatabaseProblem.class, () -> session.execute(
                    "WITH chosen AS (SELECT 1) DELETE FROM \"measure \"\"sample\" WHERE id IN (SELECT * FROM chosen) RETURNING id",
                    new QueryRequest()));
            var result = session.execute("-- leading comment\n SELECT 'x;''y' AS value; /* trailing comment */", new QueryRequest());
            assertEquals("x;'y", result.rows().getFirst().cells().getFirst().value());
        }
        var absent = source.database().resolveSibling("not-created.db");
        try (var invalid = new JdbcSession(new SqliteSource("Missing", absent, source.driver()))) {
            assertThrows(DatabaseProblem.class, invalid::connect);
        }
        assertFalse(Files.exists(absent));
    }

    @Test public void boundsDetachedTextAndBlobWithoutChangingExactScalars() throws Exception {
        try (var session = new JdbcSession(fixture())) {
            session.connect();
            var page = session.execute("SELECT hex(zeroblob(9000)) AS text_value, zeroblob(9000) AS blob_value, " +
                    "9223372036854775807 AS exact_value", new QueryRequest());
            var cells = page.rows().getFirst().cells();
            assertEquals(QueryPage.Cell.Kind.TEXT, cells.get(0).kind());
            assertEquals(QueryPage.Cell.Kind.BINARY, cells.get(1).kind());
            for (var cell : cells.subList(0, 2)) {
                assertEquals(16_384, cell.value().length());
                assertTrue(cell.clipped());
            }
            assertEquals("9223372036854775807", cells.get(2).value());
            assertFalse(cells.get(2).clipped());
        }
    }

    @Test public void stopInterruptsAnOwnedSqliteStatementAndDoesNotReachTheNextRequest() throws Exception {
        try (var session = new JdbcSession(fixture())) {
            session.connect();
            var request = new QueryRequest();
            var work = new FutureTask<>(() -> assertThrows(DatabaseProblem.class, () -> session.execute("""
                    WITH RECURSIVE countup(n) AS (
                      SELECT 1 UNION ALL SELECT n+1 FROM countup WHERE n < 1000000000
                    )
                    SELECT sum(n) FROM countup
                    """, request)));
            Thread query = Thread.ofPlatform().daemon().name("owned SQLite acceptance query").start(work);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline && !work.isDone() && !inDriver(query)) Thread.sleep(10);
                assertTrue("The real SQLite request entered its driver before Stop", inDriver(query));
            } finally {
                request.stop();
                query.join(10_000);
            }
            assertFalse("The owned statement must settle after Stop", query.isAlive());
            assertEquals("Database request stopped.", work.get(2, TimeUnit.SECONDS).getMessage());
            request.stop();
            assertEquals("42", session.execute("SELECT 42", new QueryRequest()).rows().getFirst().cells().getFirst().value());
        }
    }

    @Test public void bindsTypedAndRepeatedSqliteValuesWithoutInterpolatingText() throws Exception {
        try (var session = new JdbcSession(fixture())) {
            session.connect();
            var query = SqlText.compile("SELECT :n, :payload, :n, :missing, :truth, :decimal, :day, :time, :stamp");
            String text = "O'Reilly; SELECT 2";
            var bound = new java.util.HashMap<String, SqlValue>();
            bound.put("n", SqlValue.of(SqlValue.Type.INTEGER, "9223372036854775807", false));
            bound.put("payload", SqlValue.of(SqlValue.Type.TEXT, text, false));
            bound.put("missing", SqlValue.of(SqlValue.Type.TEXT, "", true));
            bound.put("truth", SqlValue.of(SqlValue.Type.BOOLEAN, "true", false));
            bound.put("decimal", SqlValue.of(SqlValue.Type.DECIMAL, "1234567890123456789.000001", false));
            bound.put("day", SqlValue.of(SqlValue.Type.DATE, "2026-11-25", false));
            bound.put("time", SqlValue.of(SqlValue.Type.TIME, "09:30:02", false));
            bound.put("stamp", SqlValue.of(SqlValue.Type.TIMESTAMP, "2026-11-25 09:30:02", false));
            var row = session.execute(query, bound, new QueryRequest()).rows().getFirst().cells();
            assertEquals("9223372036854775807", row.get(0).value());
            assertEquals(text, row.get(1).value());
            assertEquals(row.get(0), row.get(2));
            assertEquals(QueryPage.Cell.Kind.NULL, row.get(3).kind());
            assertEquals("1", row.get(4).value());
            assertEquals("1234567890123456789.000001", row.get(5).value());
            assertEquals("2026-11-25", row.get(6).value());
            assertEquals("09:30:02", row.get(7).value());
            assertEquals("2026-11-25T09:30:02", row.get(8).value());

            var position = SqlText.compile("SELECT ? AS bound");
            var empty = SqlValue.of(SqlValue.Type.TEXT, "", false);
            assertEquals("", session.execute(position, java.util.Map.of("1", empty), new QueryRequest())
                    .rows().getFirst().cells().getFirst().value());
            assertThrows(IllegalArgumentException.class, () -> session.execute(position, java.util.Map.of(), new QueryRequest()));
        }
    }

    private static boolean inDriver(Thread thread) {
        return Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getClassName().equals("org.sqlite.core.NativeDB")
                && frame.getMethodName().equals("step"));
    }

    private SqliteSource fixture() throws Exception {
        Path root = folder.newFolder("local file # fixture").toPath();
        Path jar = Files.copy(Path.of(System.getProperty("karyoforge.database.sqlite.driver")), root.resolve("selected.jar"));
        Path create = root.resolve("samples.db");
        try (var lease = DriverLease.open(jar, "org.sqlite.JDBC");
             var connection = lease.driver().connect("jdbc:sqlite:" + create, new Properties());
             var statement = connection.createStatement()) {
            assertNotNull(connection);
            statement.execute("CREATE TABLE \"measure \"\"sample\" (id INTEGER PRIMARY KEY, exact, label)");
            statement.execute("INSERT INTO \"measure \"\"sample\" VALUES (1, 9223372036854775807, ''), (2, 'text', NULL)");
            statement.execute("WITH RECURSIVE n(i) AS (SELECT 3 UNION ALL SELECT i+1 FROM n WHERE i<205) " +
                    "INSERT INTO \"measure \"\"sample\" SELECT i,i,'sample' FROM n");
            statement.execute("CREATE VIEW sample_view AS SELECT id FROM \"measure \"\"sample\"");
        } catch (SQLException failure) {
            throw new AssertionError("Could not prepare the owned SQLite fixture", failure);
        }
        return new SqliteSource("Owned SQLite", create, jar);
    }
}
