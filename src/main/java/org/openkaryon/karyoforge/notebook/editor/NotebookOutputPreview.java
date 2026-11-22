package org.openkaryon.karyoforge.notebook.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.*;
import java.awt.BorderLayout;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/** Passive, bounded native rendering. Rich markup is always treated as data. */
final class NotebookOutputPreview {
    static final int MAX_TEXT = 200_000;
    private static final int MAX_ENCODED_IMAGE = 4 * 1024 * 1024;
    private static final long MAX_IMAGE_SOURCE_PIXELS = 16L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 2L * 1024 * 1024;
    private int imagesRemaining = 4;

    JComponent create(JsonArray outputs) {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        var budget = new TextBudget();
        int count = Math.min(outputs.size(), 50);
        for (int index = 0; index < count; index++) {
            JsonObject output = outputs.get(index).getAsJsonObject();
            String type = output.get("output_type").getAsString();
            switch (type) {
                case "stream" -> addText(panel, output.get("text"), budget);
                case "error" -> {
                    addText(panel, output.get("ename"), budget);
                    addText(panel, output.get("evalue"), budget);
                    addText(panel, output.get("traceback"), budget, true);
                }
                case "execute_result", "display_data" -> {
                    JsonObject data = output.getAsJsonObject("data");
                    if (data.has("text/plain")) addText(panel, data.get("text/plain"), budget);
                    if (data.has("image/png")) addImage(panel, data.get("image/png"));
                    else if (data.has("image/jpeg")) addImage(panel, data.get("image/jpeg"));
                    if (data.keySet().stream().anyMatch(key -> !key.equals("text/plain")
                            && !key.equals("image/png") && !key.equals("image/jpeg"))) {
                        panel.add(notice("Additional MIME data is retained in JSON; markup is not executed."));
                    }
                }
                default -> panel.add(notice("Unsupported saved output is retained in JSON."));
            }
        }
        if (outputs.size() > count) panel.add(notice("Showing the first 50 outputs; all saved outputs remain in JSON."));
        if (budget.truncated) panel.add(notice("Text preview limit reached; full saved output remains in JSON."));
        return panel;
    }

    private static void addText(JPanel panel, JsonElement value, TextBudget budget) {
        addText(panel, value, budget, false);
    }

    private static void addText(JPanel panel, JsonElement value, TextBudget budget, boolean separateLines) {
        var text = new StringBuilder();
        if (value != null && value.isJsonArray()) {
            for (JsonElement part : value.getAsJsonArray()) {
                budget.append(text, part);
                if (separateLines) budget.append(text, new com.google.gson.JsonPrimitive("\n"));
            }
        } else budget.append(text, value);
        if (text.isEmpty()) return;
        var area = new JTextArea(text.toString());
        area.setRows(Math.min(12, area.getLineCount()));
        area.setColumns(80);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.getAccessibleContext().setAccessibleName("Saved notebook output");
        panel.add(area);
    }

    private void addImage(JPanel panel, JsonElement value) {
        if (imagesRemaining == 0) {
            panel.add(notice("Image preview limit reached; saved image remains in JSON."));
            return;
        }
        var encoded = new StringBuilder();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            if (value.getAsString().length() <= MAX_ENCODED_IMAGE) encoded.append(value.getAsString());
        } else if (value.isJsonArray()) {
            for (JsonElement part : value.getAsJsonArray()) {
                if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isString()
                        || part.getAsString().length() > MAX_ENCODED_IMAGE - encoded.length()) {
                    encoded.setLength(0);
                    break;
                }
                encoded.append(part.getAsString());
            }
        }
        try {
            if (encoded.isEmpty()) throw new IOException("No bounded image data");
            byte[] bytes = Base64.getDecoder().decode(encoded.toString().replace("\r", "").replace("\n", ""));
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Unsupported image encoding");
                var reader = readers.next();
                try {
                    if (!java.util.Set.of("png", "jpeg", "jpg").contains(reader.getFormatName().toLowerCase(java.util.Locale.ROOT))) {
                        throw new IOException("Only PNG and JPEG previews are supported");
                    }
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_SOURCE_PIXELS) {
                        throw new IOException("Image source dimensions exceed the preview bound");
                    }
                    int scale = Math.max(1, (int) Math.ceil(Math.sqrt((double) width * height / MAX_IMAGE_PIXELS)));
                    while (((long) width + scale - 1) / scale * (((long) height + scale - 1) / scale) > MAX_IMAGE_PIXELS) scale++;
                    var parameters = reader.getDefaultReadParam();
                    parameters.setSourceSubsampling(scale, scale, 0, 0);
                    var image = reader.read(0, parameters);
                    if (image == null || (long) image.getWidth() * image.getHeight() > MAX_IMAGE_PIXELS) {
                        throw new IOException("Decoded image exceeds the preview bound");
                    }
                    var label = new JLabel(new ImageIcon(image));
                    label.getAccessibleContext().setAccessibleName("Saved notebook image");
                    panel.add(label);
                    imagesRemaining--;
                } finally { reader.dispose(); }
            }
        } catch (IOException | RuntimeException invalid) {
            panel.add(notice("Image preview unavailable; complete saved MIME data remains in JSON."));
        }
    }

    private static JComponent notice(String text) {
        var panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(text), BorderLayout.WEST);
        return panel;
    }

    private static final class TextBudget {
        private int remaining = MAX_TEXT;
        private boolean truncated;
        private void append(StringBuilder text, JsonElement value) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return;
            String part = value.getAsString();
            int count = Math.min(part.length(), remaining);
            text.append(part, 0, count);
            remaining -= count;
            truncated |= count < part.length();
        }
    }
}
