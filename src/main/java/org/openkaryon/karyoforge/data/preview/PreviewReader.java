package org.openkaryon.karyoforge.data.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/** Pages from exact byte offsets without retaining a stream or loading the entire file. */
public abstract class PreviewReader {
    public static final int MAX_ROWS = 200;
    public static final int MAX_COLUMNS = 256;
    public static final int MAX_FIELD_BYTES = 16 * 1024;
    public static final int MAX_RECORD_BYTES = 128 * 1024;
    private static final int PAGE_CHAR_TARGET = 768 * 1024;
    private static final long PAGE_BYTE_TARGET = 2L * 1024 * 1024;

    protected abstract Header readHeader(PreviewInput input) throws IOException;
    protected abstract List<String> readRecord(PreviewInput input, Header header) throws IOException;

    /** A null cursor starts a new snapshot. Later pages must carry their original snapshot. */
    public final Page read(Path path, Cursor cursor, Runnable cancellation) throws IOException {
        cancellation.run();
        Stamp stamp = stamp(path);
        long start = cursor == null ? 0 : cursor.offset();
        long number = cursor == null ? 1 : cursor.number();
        if (cursor != null && !stamp.equals(cursor.stamp())) {
            throw new IOException("The source file changed. Reload the preview before paging.");
        }
        if (start < 0 || start > stamp.size()) throw new IOException("The preview cursor is outside the source file.");

        try (var input = new PreviewInput(path, start, cancellation)) {
            Header header;
            if (start == 0) {
                input.skipBom();
                header = readHeader(input);
            } else {
                header = cursor.header();
            }
            var rows = new ArrayList<Row>();
            int chars = 0;
            while (rows.size() < MAX_ROWS && input.peek() != -1) {
                long rowStart = input.offset();
                var fields = readRecord(input, header);
                if (input.offset() <= rowStart) throw new IllegalStateException("A preview record must consume source bytes.");
                rows.add(new Row(number + rows.size(), rowStart, fields));
                for (String field : fields) chars += field.length();
                if (chars >= PAGE_CHAR_TARGET || input.offset() - start >= PAGE_BYTE_TARGET) break;
            }
            Cursor next = input.peek() == -1 ? null : new Cursor(input.offset(), number + rows.size(), header, stamp);
            cancellation.run();
            if (!stamp.equals(stamp(path))) throw new IOException("The source file changed during reading. Reload the preview.");
            var first = new Cursor(start, number, header, stamp);
            return new Page(first, rows, next, input.offset() - start);
        }
    }

    private static Stamp stamp(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("Preview requires a regular local file.");
        return new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.fileKey());
    }

    public record Stamp(long size, FileTime modified, FileTime created, Object key) {}
    public record Header(List<String> columns, List<String> metadata) {
        public Header(List<String> columns) { this(columns, List.of()); }

        public Header {
            columns = List.copyOf(columns);
            metadata = List.copyOf(metadata);
        }
    }

    public record Cursor(long offset, long number, Header header, Stamp stamp) {}
    public record Row(long number, long offset, List<String> values) {
        public Row { values = List.copyOf(values); }
    }
    public record Page(Cursor start, List<Row> rows, Cursor next, long scannedBytes) {
        public Page { rows = List.copyOf(rows); }
        public List<String> columns() { return start.header().columns(); }
    }
}
