package org.openkaryon.karyoforge.biology.preview;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public final class FastqPreviewReaderTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void wrappedQualityWithHeaderLikeCharactersDoesNotStartARecord() throws Exception {
        var file = Files.writeString(files.newFile("reads.fq").toPath(), "@read unmodified\r\nAC\r\nGT\r\n+read\r\n@!\r\n+?\r\n@next\nTT\n+\n##");
        var page = new FastqPreviewReader().read(file, null, () -> {});
        assertEquals(List.of("read", "unmodified", "4", "ACGT", "@!+?"), page.rows().getFirst().values());
        assertEquals(List.of("next", "", "2", "TT", "##"), page.rows().getLast().values());
        assertNull(page.next());
    }

    @Test public void validatesFullQualityBeyondTheVisibleFragment() throws Exception {
        String sequence = "ACGT".repeat(256);
        var file = Files.writeString(files.newFile("length.fastq").toPath(), "@read\n" + sequence + "\n+\n" + "!".repeat(1023));
        var reader = new FastqPreviewReader();
        assertTrue(assertThrows(IOException.class, () -> reader.read(file, null, () -> {})).getMessage().contains("quality is shorter"));
        Files.writeString(file, "@read\n" + sequence + "\n+\n" + "!".repeat(1025));
        assertTrue(assertThrows(IOException.class, () -> reader.read(file, null, () -> {})).getMessage().contains("quality is longer"));
        Files.writeString(file, "@read\n" + sequence + "\n+\n" + "!".repeat(1024));
        var row = reader.read(file, null, () -> {}).rows().getFirst();
        assertEquals("1024", row.values().get(2));
        assertEquals("!".repeat(512) + "…", row.values().get(4));
    }
}
