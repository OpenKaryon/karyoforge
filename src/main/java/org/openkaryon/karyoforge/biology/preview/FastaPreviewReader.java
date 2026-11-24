package org.openkaryon.karyoforge.biology.preview;

import org.openkaryon.karyoforge.data.preview.PreviewInput;
import org.openkaryon.karyoforge.data.preview.PreviewReader;

import java.io.IOException;
import java.util.List;

final class FastaPreviewReader extends PreviewReader {
    @Override protected List<String> readColumns(PreviewInput input) {
        return List.of("Identifier", "Description", "Length", "Sequence");
    }

    @Override protected List<String> readRecord(PreviewInput input, List<String> columns) throws IOException {
        var header = SequencePreview.readName(input, '>', "FASTA");
        var sequence = new SequencePreview();
        while (input.peek() != -1 && input.peek() != '>') {
            int value;
            while ((value = input.peek()) != -1 && value != '\r' && value != '\n') {
                input.take();
                if (value == ' ' || value == '\t') continue;
                if (value < 33 || value > 126) throw input.error("Expected an ASCII FASTA sequence character");
                sequence.append(value);
            }
            if (value != -1) input.endLine();
        }
        if (sequence.length() == 0) throw input.error("FASTA record has no sequence");
        return List.of(header.id(), header.description(), Long.toString(sequence.length()), sequence.text());
    }
}
