package org.openkaryon.karyoforge.biology.preview;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

public final class VcfPreviewReaderTest {
    private static final String STANDARD = "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO";
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void keepsMetadataCoordinatesAndSampleFieldsAcrossPages() throws Exception {
        String meta = "##fileformat=VCFv4.3\n##INFO=<ID=FLAG,Number=0,Type=Flag,Description=\"A literal, quoted header\">\n";
        String header = STANDARD + "\tFORMAT\talpha\tbeta\n";
        String row = "chr1\t00017\t.\tA\tC,G\t.\tPASS\tFLAG;X=0002\tGT:DP\t1|2:0007\t./.:.\n";
        var path = Files.writeString(files.newFile("local.vcf").toPath(), meta + header + row.repeat(201));
        var reader = new VcfPreviewReader();
        var first = reader.read(path, null, () -> {});
        assertEquals(List.of("##fileformat=VCFv4.3", "##INFO=<ID=FLAG,Number=0,Type=Flag,Description=\"A literal, quoted header\">"), first.start().header().metadata());
        assertEquals(List.of("#CHROM", "POS", "ID", "REF", "ALT", "QUAL", "FILTER", "INFO", "FORMAT", "alpha", "beta"), first.columns());
        var next = reader.read(path, first.next(), () -> {});
        assertEquals(201, next.rows().getFirst().number());
        assertEquals(List.of("chr1", "00017", ".", "A", "C,G", ".", "PASS", "FLAG;X=0002", "GT:DP", "1|2:0007", "./.:."), next.rows().getFirst().values());
        assertEquals(first.start().header(), next.start().header());
        assertNull(next.next());
    }

    @Test public void rejectsMissingHeadersInvalidPositionsAndRaggedRows() throws Exception {
        var path = Files.writeString(files.newFile("bad.vcf").toPath(), "##fileformat=VCFv4.3\n");
        var reader = new VcfPreviewReader();
        assertTrue(assertThrows(IOException.class, () -> reader.read(path, null, () -> {})).getMessage().contains("missing its #CHROM header"));
        Files.writeString(path, STANDARD + "\nchr1\t1\t.\tA\tG\t.\n");
        assertTrue(assertThrows(IOException.class, () -> reader.read(path, null, () -> {})).getMessage().contains("header declares 8"));
        Files.writeString(path, STANDARD + "\nchr1\t2.0\t.\tA\tG\t.\tPASS\t.\n");
        assertTrue(assertThrows(IOException.class, () -> reader.read(path, null, () -> {})).getMessage().contains("POS is not an integer"));
    }

    @Test public void clipsMetadataWithoutSkippingTheActualColumnHeader() throws Exception {
        var path = Files.writeString(files.newFile("many-headers.vcf").toPath(),
                "##custom=kept\n".repeat(300) + STANDARD + "\nchr7\t19\t.\tG\tT\t.\tPASS\t.\n");
        var first = new VcfPreviewReader().read(path, null, () -> {});
        var metadata = first.start().header().metadata();
        assertEquals(257, metadata.size());
        assertEquals("Additional metadata is available in Text.", metadata.getLast());
        assertEquals("19", first.rows().getFirst().values().get(1));
    }
}
