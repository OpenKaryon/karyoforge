package org.openkaryon.karyoforge.notebook.editor;

import com.google.gson.JsonParser;
import junit.framework.TestCase;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class NotebookOutputPreviewTest extends TestCase {
    public void testLiteralOutputAndRichMarkupDoNotCreateAnHtmlRenderer() {
        var outputs = JsonParser.parseString("""
                [{"output_type":"stream","name":"stdout","text":["<html><img src='https://invalid.example/never-fetch'>"," literal"]},
                 {"output_type":"display_data","metadata":{},"data":{"text/html":"<script>neverExecute()</script>","application/json":{"preserved":true}}}]
                """).getAsJsonArray();
        String original = outputs.toString();
        var panel = new NotebookOutputPreview().create(outputs);
        assertTrue(NotebookEditorIntegrationTest.components(panel, JEditorPane.class).isEmpty());
        assertTrue(NotebookEditorIntegrationTest.components(panel, JTextArea.class).getFirst().getText().contains("<html><img"));
        assertEquals(original, outputs.toString());
    }

    public void testTextAndOutputCountsAreBoundedWithoutMutatingSavedData() {
        var outputs = JsonParser.parseString("[{\"output_type\":\"stream\",\"name\":\"stdout\",\"text\":\"\"}]").getAsJsonArray();
        outputs.get(0).getAsJsonObject().addProperty("text", "x".repeat(NotebookOutputPreview.MAX_TEXT + 100));
        for (int index = 0; index < 60; index++) outputs.add(JsonParser.parseString("{\"output_type\":\"future_output\"}"));
        String original = outputs.toString();
        var panel = new NotebookOutputPreview().create(outputs);
        assertEquals(NotebookOutputPreview.MAX_TEXT, NotebookEditorIntegrationTest.components(panel, JTextArea.class).getFirst().getText().length());
        assertTrue(NotebookEditorIntegrationTest.components(panel, JLabel.class).stream().anyMatch(label -> label.getText().contains("first 50")));
        assertEquals(original, outputs.toString());
    }

    public void testSavedPngImagesAreDecodedLocallyAndThePageBudgetIsShared() throws Exception {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes));
        String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
        var outputs = JsonParser.parseString("[{\"output_type\":\"display_data\",\"metadata\":{},\"data\":{\"image/png\":\"" + encoded + "\"}}]").getAsJsonArray();
        var renderer = new NotebookOutputPreview();
        for (int index = 0; index < 4; index++) {
            var panel = renderer.create(outputs);
            assertEquals(1, NotebookEditorIntegrationTest.components(panel, JLabel.class).stream().filter(label -> label.getIcon() instanceof ImageIcon).count());
        }
        var largeBytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB), "png", largeBytes));
        var large = outputs.deepCopy();
        large.get(0).getAsJsonObject().getAsJsonObject("data").addProperty("image/png",
                Base64.getEncoder().encodeToString(largeBytes.toByteArray()));
        String originalLarge = large.toString();
        var bounded = new NotebookOutputPreview().create(large);
        var icon = (ImageIcon) NotebookEditorIntegrationTest.components(bounded, JLabel.class).stream()
                .filter(label -> label.getIcon() instanceof ImageIcon).findFirst().orElseThrow().getIcon();
        assertTrue((long) icon.getIconWidth() * icon.getIconHeight() <= 2L * 1024 * 1024);
        assertTrue(icon.getIconWidth() < 2048);
        assertEquals(originalLarge, large.toString());
        var limited = renderer.create(outputs);
        assertTrue(NotebookEditorIntegrationTest.components(limited, JLabel.class).stream().noneMatch(label -> label.getIcon() != null));
        var invalid = JsonParser.parseString("[{\"output_type\":\"display_data\",\"metadata\":{},\"data\":{\"image/png\":\"invalid base64\"}}]").getAsJsonArray();
        var failure = new NotebookOutputPreview().create(invalid);
        assertTrue(NotebookEditorIntegrationTest.components(failure, JLabel.class).stream().anyMatch(label -> label.getText().contains("unavailable")));
    }
}
