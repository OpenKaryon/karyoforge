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

    @Override protected List<String> readColumns(PreviewInput input) throws IOException {
        return input.peek() == -1 ? List.of() : readFields(input);
    }

    @Override protected List<String> readRecord(PreviewInput input, List<String> columns) throws IOException {
        return readFields(input);
    }

    private List<String> readFields(PreviewInput input) throws IOException {
        long start = input.offset();
        var fields = new ArrayList<String>();
        while (true) {
            if (fields.size() == MAX_COLUMNS) throw input.error("Record exceeds the 256-column preview limit");
            fields.add(readField(input, start));
            int next = input.peek();
            if (next == -1 || next == '\r' || next == '\n') {
                if (next != -1) input.endLine();
                return fields;
            }
            if (next != delimiter) throw input.error("Expected a field separator after the closing quote");
            consume(input, start);
        }
    }

    private String readField(PreviewInput input, long start) throws IOException {
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
        if (input.offset() - start >= MAX_RECORD_BYTES) throw input.error("CSV/TSV record exceeds the 128 KiB preview limit");
        return input.take();
    }

    private static void append(ByteArrayOutputStream field, int value, PreviewInput input) throws IOException {
        if (field.size() == MAX_FIELD_BYTES) throw input.error("CSV/TSV field exceeds the 16 KiB preview limit");
        field.write(value);
    }
}
