package org.openkaryon.karyoforge.database.results;

import org.openkaryon.karyoforge.database.core.QueryPage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ResultExport {
    public enum Format {
        CSV(','), TSV('\t');
        private final char separator;
        Format(char separator) { this.separator = separator; }
    }

    public enum Spreadsheet {
        PREFIX_RISKY("Prefix formula-like text"), EXACT("Keep displayed values"), ALL_TEXT("Prefix every value");
        private final String label;
        Spreadsheet(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public record Options(Format format, Charset charset, String nullToken, Spreadsheet spreadsheet,
                          boolean includeHeader, boolean allowClipped) {
        public Options {
            Objects.requireNonNull(format);
            Objects.requireNonNull(charset);
            Objects.requireNonNull(nullToken);
            Objects.requireNonNull(spreadsheet);
            if (nullToken.isEmpty() || nullToken.length() > 64 || risky(nullToken)
                    || nullToken.indexOf(format.separator) >= 0 || nullToken.indexOf('"') >= 0
                    || nullToken.codePoints().anyMatch(ResultExport::nonPrinting)) {
                throw new IllegalArgumentException("Choose a short unquoted NULL token without spaces, quotes or separators.");
            }
        }
    }

    /** Atomically publishes a new file after encoding every field. Existing paths are preserved. */
    public static int write(Path destination, FetchedResults.View view, Options options) throws IOException {
        if (!destination.isAbsolute()) throw new IllegalArgumentException("Choose an absolute output file path.");
        Path selected = destination.normalize();
        if (selected.getFileName() == null || selected.getParent() == null) {
            throw new IllegalArgumentException("Choose a new output file.");
        }
        Path folder = selected.getParent().toRealPath();
        Path target = folder.resolve(selected.getFileName());
        if (!options.allowClipped() && view.rows().stream().flatMap(row -> row.cells().stream()).anyMatch(QueryPage.Cell::clipped)) {
            throw new IllegalArgumentException("Some cells are clipped. Allow displayed previews to export them.");
        }
        Path temp = Files.createTempFile(folder, ".karyoforge-result-", ".part");
        try {
            try (var stream = Files.newOutputStream(temp);
                 var writer = new BufferedWriter(new OutputStreamWriter(stream, options.charset().newEncoder()))) {
                if (options.includeHeader()) writeHeader(writer, view, options);
                for (var row : view.rows()) writeRow(writer, row, options);
            }
            Files.createLink(target, temp);
            return view.rows().size();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeHeader(BufferedWriter writer, FetchedResults.View view, Options options) throws IOException {
        for (int index = 0; index < view.columns().size(); index++) {
            if (index > 0) writer.write(options.format().separator);
            writeQuoted(writer, view.columns().get(index).label(), options.spreadsheet(), false);
        }
        writer.write("\r\n");
    }

    private static void writeRow(BufferedWriter writer, QueryPage.Row row, Options options) throws IOException {
        for (int index = 0; index < row.cells().size(); index++) {
            if (index > 0) writer.write(options.format().separator);
            var cell = row.cells().get(index);
            if (cell.kind() == QueryPage.Cell.Kind.NULL) writer.write(options.nullToken());
            else writeQuoted(writer, cell.display(), options.spreadsheet(), ResultValue.isExactNumber(cell));
        }
        writer.write("\r\n");
    }

    private static void writeQuoted(BufferedWriter writer, String value, Spreadsheet mode, boolean exactNumber) throws IOException {
        writer.write('"');
        if (mode == Spreadsheet.ALL_TEXT || mode == Spreadsheet.PREFIX_RISKY && !exactNumber && risky(value)) {
            writer.write('\'');
        }
        writer.write(value.replace("\"", "\"\""));
        writer.write('"');
    }

    private static boolean risky(String value) {
        for (int index = 0; index < value.length();) {
            int c = value.codePointAt(index);
            if (c == '\t' || c == '\r' || c == '\n' || "=+-@".indexOf(c) >= 0) return true;
            if (!nonPrinting(c)) return false;
            index += Character.charCount(c);
        }
        return false;
    }

    private static boolean nonPrinting(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT;
    }
}
