package org.openkaryon.karyoforge.notebook.editor;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.Font;

/** Small literal Markdown preview: styled text only, with no HTML or URL loading. */
final class NotebookMarkdownPreview extends JTextPane {
    private static final int MAX_CHARACTERS = 20_000;
    private static final int MAX_LINES = 500;

    NotebookMarkdownPreview() {
        setEditable(false);
        getAccessibleContext().setAccessibleName("Markdown cell preview");
    }

    void showSource(String source) {
        var document = getStyledDocument();
        String bounded = source.substring(0, Math.min(source.length(), MAX_CHARACTERS));
        int lines = 0, start = 0;
        boolean code = false;
        try {
            document.remove(0, document.getLength());
            while (start < bounded.length() && lines++ < MAX_LINES) {
                int end = bounded.indexOf('\n', start);
                if (end < 0) end = bounded.length();
                String line = bounded.substring(start, end);
                var attributes = new SimpleAttributeSet();
                int baseSize = getFont() == null ? 12 : getFont().getSize();
                if (line.startsWith("```")) {
                    code = !code;
                } else {
                    if (code) {
                        StyleConstants.setFontFamily(attributes, Font.MONOSPACED);
                    } else {
                        int heading = 0;
                        while (heading < line.length() && heading < 6 && line.charAt(heading) == '#') heading++;
                        if (heading > 0 && line.length() > heading && line.charAt(heading) == ' ') {
                            line = line.substring(heading + 1);
                            StyleConstants.setBold(attributes, true);
                            StyleConstants.setFontSize(attributes, baseSize + (7 - heading) * 2);
                        } else if (line.startsWith("> ")) {
                            StyleConstants.setItalic(attributes, true);
                        }
                    }
                    document.insertString(document.getLength(), line + "\n", attributes);
                }
                start = end + 1;
            }
            if (source.length() > bounded.length() || start < bounded.length()) {
                document.insertString(document.getLength(), "[Markdown preview limited; complete source is retained.]\n", null);
            }
            setCaretPosition(0);
        } catch (BadLocationException impossible) {
            throw new IllegalStateException("Cannot update the native Markdown preview", impossible);
        }
    }
}
