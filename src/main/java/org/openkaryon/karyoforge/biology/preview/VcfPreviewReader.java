package org.openkaryon.karyoforge.biology.preview;

import org.openkaryon.karyoforge.data.preview.DelimitedPreviewReader;
import org.openkaryon.karyoforge.data.preview.PreviewInput;
import org.openkaryon.karyoforge.data.preview.PreviewReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class VcfPreviewReader extends PreviewReader {
    private static final int MAX_METADATA_CHARS = 64 * 1024;
    private static final int MAX_METADATA_LINES = 256;
    private static final List<String> STANDARD_COLUMNS = List.of("CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO");

    @Override protected Header readHeader(PreviewInput input) throws IOException {
        var metadata = new ArrayList<String>();
        int chars = 0;
        boolean clipped = false;
        while (input.peek() == '#') {
            input.take();
            if (input.peek() != '#') {
                var columns = new ArrayList<>(DelimitedPreviewReader.readTabRecord(input));
                validateColumns(columns, input);
                columns.set(0, "#CHROM");
                if (clipped) metadata.add("Additional metadata is available in Text.");
                return new Header(columns, metadata);
            }

            String line = "#" + input.readLine(MAX_FIELD_BYTES - 1, "VCF metadata line");
            if (!clipped && metadata.size() < MAX_METADATA_LINES && chars + line.length() + 1 <= MAX_METADATA_CHARS) {
                metadata.add(line);
                chars += line.length() + 1;
            } else {
                clipped = true;
            }
        }
        throw input.error("VCF is missing its #CHROM header");
    }

    @Override protected List<String> readRecord(PreviewInput input, Header header) throws IOException {
        long offset = input.offset();
        var values = DelimitedPreviewReader.readTabRecord(input);
        if (values.size() != header.columns().size()) {
            throw new IOException("VCF record at byte " + offset + " has " + values.size()
                    + " fields. The header declares " + header.columns().size() + ".");
        }
        String position = values.get(1);
        if (position.isEmpty() || !position.chars().allMatch(value -> value >= '0' && value <= '9')) {
            throw new IOException("VCF POS is not an integer at byte " + offset + ".");
        }
        return values;
    }

    private static void validateColumns(List<String> columns, PreviewInput input) throws IOException {
        int count = columns.size();
        if (count < STANDARD_COLUMNS.size() || !columns.subList(0, STANDARD_COLUMNS.size()).equals(STANDARD_COLUMNS)) {
            throw input.error("VCF #CHROM header must name the eight standard columns in order");
        }
        if (count > STANDARD_COLUMNS.size()) {
            if (count < 10 || !columns.get(8).equals("FORMAT") || columns.subList(9, count).stream().anyMatch(String::isEmpty)) {
                throw input.error("VCF sample columns require FORMAT and nonempty sample names");
            }
        }
    }
}
