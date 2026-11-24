package org.openkaryon.karyoforge.data.preview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DelimitedPreviewReader extends PreviewReader {
    private final boolean csv;
    private final int delimiter;

    public DelimitedPreviewReader(boolean csv) {
        this.csv = csv;
        delimiter = csv ? ',' : '\t';
    }

    @Override protected Header readHeader(PreviewInput input) throws IOException {
        return new Header(input.peek() == -1 ? List.of() : readFields(input, delimiter, csv));
    }

    @Override protected List<String> readRecord(PreviewInput input, Header header) throws IOException {
        return readFields(input, delimiter, csv);
    }

    /** Read a literal tab-separated record, with the same bounds used for TSV previews. */
    public static List<String> readTabRecord(PreviewInput input) throws IOException {
        return readFields(input, '\t', false);
    }

    private static List<String> readFields(PreviewInput input, int delimiter, boolean csv) throws IOException {
        long start = input.offset();
        var fields = new ArrayList<String>();
        while (true) {
            if (fields.size() == MAX_COLUMNS) throw input.error("Record exceeds the 256-column preview limit");
            fields.add(readField(input, start, delimiter, csv));
            int next = input.peek();
            if (next == -1 || next == '\r' || next == '\n') {
                if (next != -1) input.endLine();
                return fields;
            }
            if (next != delimiter) throw input.error("Expected a field separator after the closing quote");
            consume(input, start);
        }
    }

    private static String readField(PreviewInput input, long start, int delimiter, boolean csv) throws IOException {
        var field = new ByteArrayOutputStream();
        if (csv && input.peek() == '"') {
            consume(input, start);
            while (true) {
                int current = consume(input, start);
                if (current == -1) throw input.error("Unclosed quoted CSV field");
                if (current == '"') {
                    if (input.peek() != '"') break;
                    consume(input, start);
                }
                append(field, current, input);
            }
        } else {
            int current;
            while ((current = input.peek()) != -1 && current != delimiter && current != '\r' && current != '\n') {
                if (csv && current == '"') throw input.error("Unexpected quote in an unquoted CSV field");
                append(field, consume(input, start), input);
            }
        }
        return field.toString(StandardCharsets.UTF_8);
    }

    private static int consume(PreviewInput input, long start) throws IOException {
        if (input.offset() - start >= MAX_RECORD_BYTES) throw input.error("Record exceeds the 128 KiB preview limit");
        return input.take();
    }

    private static void append(ByteArrayOutputStream field, int value, PreviewInput input) throws IOException {
        if (field.size() == MAX_FIELD_BYTES) throw input.error("Field exceeds the 16 KiB preview limit");
        field.write(value);
    }
}
