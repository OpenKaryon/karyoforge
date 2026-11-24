package org.openkaryon.karyoforge.biology.preview;

import org.openkaryon.karyoforge.data.preview.PreviewInput;
import org.openkaryon.karyoforge.data.preview.PreviewReader;

import java.io.IOException;

final class SequencePreview {
    private static final int DISPLAY_LIMIT = 512;
    private final StringBuilder text = new StringBuilder();
    private long length;

    void append(int symbol) {
        if (length < DISPLAY_LIMIT) text.append((char) symbol);
        length++;
    }

    long length() { return length; }

    String text() { return length <= DISPLAY_LIMIT ? text.toString() : text + "…"; }

    static Name readName(PreviewInput input, int marker, String format) throws IOException {
        if (input.peek() != marker) throw input.error("Expected a " + format + " header starting with " + (char) marker);
        input.take();
        String value = input.readLine(PreviewReader.MAX_FIELD_BYTES, format + " header").strip();
        if (value.isEmpty()) throw input.error(format + " header is missing its identifier");
        int split = 0;
        while (split < value.length() && !Character.isWhitespace(value.charAt(split))) split++;
        return new Name(value.substring(0, split), value.substring(split).stripLeading());
    }

    record Name(String id, String description) {}
}
