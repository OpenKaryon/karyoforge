package org.openkaryon.karyoforge.data.preview;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class DelimitedPreviewReaderTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private static final Runnable NOT_CANCELLED = () -> {};

    @Test public void quotedCsvRetainsStringsAndByteAlignedPages() throws Exception {
        var source = new StringBuilder("\ufeffid,description,quantity\r\n");
        var firstRow = "001,\"текст,\r\n\"\"quoted\"\"\",99999999999999999999\r\n";
        source.append(firstRow.repeat(200)).append("002,last,\n");
        var file = write("table.csv", source.toString());
        var reader = new DelimitedPreviewReader(true);
        var first = reader.read(file, null, NOT_CANCELLED);
        assertEquals(List.of("id", "description", "quantity"), first.columns());
        assertEquals(List.of("001", "текст,\r\n\"quoted\"", "99999999999999999999"), first.rows().getFirst().values());
        assertEquals(200, first.rows().size());
        assertEquals(source.substring(0, source.lastIndexOf("002")).getBytes(StandardCharsets.UTF_8).length, first.next().offset());
        var second = reader.read(file, first.next(), NOT_CANCELLED);
        assertEquals(201, second.rows().getFirst().number());
        assertEquals(List.of("002", "last", ""), second.rows().getFirst().values());
        assertNull(second.next());
        assertEquals(first, reader.read(file, first.start(), NOT_CANCELLED));
        assertEquals(source.toString(), Files.readString(file));
    }

    @Test public void tsvUsesLiteralQuotesAndAcceptsStandaloneCarriageReturns() throws Exception {
        var table = new DelimitedPreviewReader(false).read(write("table.tsv", "id\tvalue\r001\t\"quoted\"\r002\t"), null, NOT_CANCELLED);
        assertEquals(List.of("001", "\"quoted\""), table.rows().get(0).values());
        assertEquals(List.of("002", ""), table.rows().get(1).values());
        assertNull(table.next());
    }

    @Test public void malformedTextAndOversizedFieldsFailWithAByteOffset() throws Exception {
        var reader = new DelimitedPreviewReader(true);
        var file = write("broken.csv", "header\n\"unclosed\n");
        assertTrue(assertThrows(IOException.class, () -> reader.read(file, null, NOT_CANCELLED)).getMessage().contains("Unclosed quoted CSV field"));
        Files.write(file, new byte[]{'x', '\n', (byte) 0xe0, (byte) 0x80});
        assertTrue(assertThrows(IOException.class, () -> reader.read(file, null, NOT_CANCELLED)).getMessage().contains("Invalid UTF-8"));
        Files.writeString(file, "header\n" + "x".repeat(PreviewReader.MAX_FIELD_BYTES + 1));
        assertTrue(assertThrows(IOException.class, () -> reader.read(file, null, NOT_CANCELLED)).getMessage().contains("16 KiB preview limit"));
    }

    @Test public void boundedReadCanBeCancelledAndRejectsACursorFromOldFileContents() throws Exception {
        var file = write("large.csv", "header\n" + ("x".repeat(2048) + "\n").repeat(600));
        var reader = new DelimitedPreviewReader(true);
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> reader.read(file, null, () -> {
            if (checks.incrementAndGet() == 4) throw new CancellationException();
        }));
        var first = reader.read(file, null, NOT_CANCELLED);
        assertTrue(first.scannedBytes() < Files.size(file));
        Files.writeString(file, "header\nreplacement\n");
        assertTrue(assertThrows(IOException.class, () -> reader.read(file, first.next(), NOT_CANCELLED)).getMessage().contains("source file changed"));
        assertThrows(IOException.class, () -> reader.read(file, first.start(), NOT_CANCELLED));
    }

    private Path write(String name, String contents) throws IOException {
        return Files.writeString(files.newFile(name).toPath(), contents);
    }
}
