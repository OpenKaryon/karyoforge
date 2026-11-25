package org.openkaryon.karyoforge.database.results;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openkaryon.karyoforge.database.core.QueryPage;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;
import static org.openkaryon.karyoforge.database.core.QueryPage.Cell.Kind;

public final class FetchedResultsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void sortsExactValuesAcrossFetchedPagesAndCombinesTypedFilters() {
        var columns = List.of(new QueryPage.Column("amount", "NUMERIC"), new QueryPage.Column("label", "TEXT"));
        var first = new QueryPage(columns, List.of(row(Kind.INTEGER, "9223372036854775807", "ALPHA"),
                row(Kind.NULL, null, "NULL label")), 1, 2, true, "");
        var next = new QueryPage(columns, List.of(row(Kind.DECIMAL, "9223372036854775806.9", "alpha"),
                row(Kind.TEXT, "9223372036854775808", "lexical")), 2, 4, false, "");
        var result = FetchedResults.first(first).append(next);
        var sorted = result.view(List.of(), List.of(new FetchedResults.Sort(0, false, true)));
        assertEquals(List.of("NULL label", "alpha", "ALPHA", "lexical"), labels(sorted));

        var threshold = new FetchedResults.Filter(0, FetchedResults.Operator.GREATER,
                cell(Kind.DECIMAL, "9223372036854775806.95"), true);
        var text = new FetchedResults.Filter(1, FetchedResults.Operator.CONTAINS, cell(Kind.TEXT, "alpha"), false);
        assertEquals(List.of("ALPHA"), labels(result.view(List.of(threshold, text), List.of())));
        var isNull = new FetchedResults.Filter(0, FetchedResults.Operator.IS_NULL, null, true);
        assertEquals(List.of("NULL label"), labels(result.view(List.of(isNull), List.of())));
        assertThrows(IllegalArgumentException.class, () -> result.append(next));
    }

    @Test public void failsExplicitlyWhenReportedTemporalValuesCannotBeSortedExactly() {
        var first = new QueryPage(List.of(new QueryPage.Column("day", "DATE")),
                List.of(new QueryPage.Row(List.of(cell(Kind.DATE, "stored SQLite text")))), 1, 1, false, "");
        var result = FetchedResults.first(first);
        assertThrows(IllegalArgumentException.class,
                () -> result.view(List.of(), List.of(new FetchedResults.Sort(0, false, false))));
        assertEquals(1, result.view(List.of(), List.of()).rows().size());
    }

    @Test public void exportsOnlyDetachedRowsWithExactNullAndFormulaHandling() throws Exception {
        var columns = List.of(new QueryPage.Column("=risk", "TEXT"), new QueryPage.Column("amount", "INTEGER"),
                new QueryPage.Column("optional", "TEXT"), new QueryPage.Column("quoted", "TEXT"));
        var data = new QueryPage.Row(List.of(cell(Kind.INTEGER, "-12+3"), cell(Kind.INTEGER, "-9223372036854775807"),
                cell(Kind.NULL, null), cell(Kind.TEXT, "\\N")));
        var empty = new QueryPage.Row(List.of(cell(Kind.TEXT, ""), cell(Kind.INTEGER, "1"),
                cell(Kind.TEXT, "\t=1"), cell(Kind.TEXT, "say \"hi\"\nnext")));
        var view = new FetchedResults.View(columns, List.of(data, empty), 200);
        var options = new ResultExport.Options(ResultExport.Format.TSV, StandardCharsets.UTF_8, "\\N",
                ResultExport.Spreadsheet.PREFIX_RISKY, true, false);
        var path = folder.getRoot().toPath().resolve("result.tsv");
        assertEquals(2, ResultExport.write(path, view, options));
        var original = Files.readString(path);
        assertEquals("\"'=risk\"\t\"amount\"\t\"optional\"\t\"quoted\"\r\n"
                + "\"'-12+3\"\t\"-9223372036854775807\"\t\\N\t\"\\N\"\r\n"
                + "\"\"\t\"1\"\t\"'\t=1\"\t\"say \"\"hi\"\"\nnext\"\r\n", original);
        assertThrows(FileAlreadyExistsException.class, () -> ResultExport.write(path, view, options));
        assertEquals(original, Files.readString(path));
        try (var files = Files.list(path.getParent())) {
            assertEquals(List.of(path), files.toList());
        }
    }

    @Test public void aRejectedPreviewOrEncodingLeavesNoExport() throws Exception {
        var columns = List.of(new QueryPage.Column("value", "TEXT"));
        var view = new FetchedResults.View(columns, List.of(new QueryPage.Row(List.of(new QueryPage.Cell(Kind.TEXT, "cut", true)))), 1);
        var options = new ResultExport.Options(ResultExport.Format.CSV, StandardCharsets.UTF_8, "\\N",
                ResultExport.Spreadsheet.EXACT, false, false);
        var path = folder.getRoot().toPath().resolve("result.csv");
        assertThrows(IllegalArgumentException.class, () -> ResultExport.write(path, view, options));
        assertFalse(Files.exists(path));
        var narrow = new ResultExport.Options(ResultExport.Format.CSV, StandardCharsets.US_ASCII, "\\N",
                ResultExport.Spreadsheet.EXACT, false, false);
        var other = new FetchedResults.View(columns, List.of(new QueryPage.Row(List.of(cell(Kind.TEXT, "\u2206")))), 1);
        assertThrows(CharacterCodingException.class, () -> ResultExport.write(path, other, narrow));
        assertFalse(Files.exists(path));
        try (var files = Files.list(path.getParent())) { assertEquals(0, files.count()); }
    }

    private static QueryPage.Cell cell(Kind type, String value) { return new QueryPage.Cell(type, value, false); }

    private static QueryPage.Row row(Kind type, String value, String label) {
        return new QueryPage.Row(List.of(cell(type, value), cell(Kind.TEXT, label)));
    }

    private static List<String> labels(FetchedResults.View view) {
        return view.rows().stream().map(row -> row.cells().get(1).value()).toList();
    }
}
