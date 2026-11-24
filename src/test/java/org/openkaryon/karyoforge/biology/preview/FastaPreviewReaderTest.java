package org.openkaryon.karyoforge.biology.preview;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openkaryon.karyoforge.data.preview.PreviewReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public final class FastaPreviewReaderTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void streamsLongWrappedAndUnwrappedSequencesBeforeReadingNextPage() throws Exception {
        String firstRecord = "\ufeff>sample описание\r\n" + "ACGT".repeat(600_000) + "\r\nAA A\tC\r";
        var file = Files.writeString(files.newFile("sequence.FA").toPath(), firstRecord + ">second\nTT\nGG");
        var reader = new FastaPreviewReader();
        var first = reader.read(file, null, () -> {});
        assertEquals(1, first.rows().size());
        assertEquals(List.of("sample", "описание", "2400004", "ACGT".repeat(128) + "…"), first.rows().getFirst().values());
        assertEquals(firstRecord.getBytes(StandardCharsets.UTF_8).length, first.next().offset());
        var second = reader.read(file, first.next(), () -> {});
        assertEquals(List.of("second", "", "4", "TTGG"), second.rows().getFirst().values());
        assertNull(second.next());
    }

    @Test public void rejectsMissingIdentifierAndOversizedHeader() throws Exception {
        var file = Files.writeString(files.newFile("broken.fa").toPath(), "> \nACGT\n");
        var reader = new FastaPreviewReader();
        assertTrue(assertThrows(IOException.class, () -> reader.read(file, null, () -> {})).getMessage().contains("missing its identifier"));
        Files.writeString(file, ">" + "x".repeat(PreviewReader.MAX_FIELD_BYTES + 1) + "\nACGT\n");
        assertTrue(assertThrows(IOException.class, () -> reader.read(file, null, () -> {})).getMessage().contains("FASTA header exceeds"));
    }
}
