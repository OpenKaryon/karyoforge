package org.openkaryon.karyoforge.biology.preview;

import org.openkaryon.karyoforge.data.preview.PreviewInput;
import org.openkaryon.karyoforge.data.preview.PreviewReader;

import java.io.IOException;
import java.util.List;

final class FastqPreviewReader extends PreviewReader {
    @Override protected Header readHeader(PreviewInput input) {
        return new Header(List.of("Identifier", "Description", "Length", "Sequence", "Quality"));
    }

    @Override protected List<String> readRecord(PreviewInput input, Header documentHeader) throws IOException {
        var header = SequencePreview.readName(input, '@', "FASTQ");
        var sequence = new SequencePreview();
        while (input.peek() != '+') {
            if (input.peek() == -1) throw input.error("FASTQ record is missing its + separator");
            readSymbols(input, sequence, Long.MAX_VALUE, "sequence");
        }
        if (sequence.length() == 0) throw input.error("FASTQ record has no sequence");
        input.readLine(MAX_FIELD_BYTES, "FASTQ separator");

        var quality = new SequencePreview();
        // Count quality characters before looking for another header. '@' and '+' are valid quality values.
        while (quality.length() < sequence.length()) {
            if (input.peek() == -1) throw input.error("FASTQ quality is shorter than its sequence");
            readSymbols(input, quality, sequence.length(), "quality");
        }
        return List.of(header.id(), header.description(), Long.toString(sequence.length()), sequence.text(), quality.text());
    }

    private static void readSymbols(PreviewInput input, SequencePreview target, long countLimit, String kind) throws IOException {
        int value;
        while ((value = input.peek()) != -1 && value != '\r' && value != '\n') {
            if (target.length() == countLimit) throw input.error("FASTQ quality is longer than its sequence");
            if (value < 33 || value > 126) throw input.error("Expected a printable ASCII FASTQ " + kind + " character");
            target.append(input.take());
        }
        if (value != -1) input.endLine();
    }
}
