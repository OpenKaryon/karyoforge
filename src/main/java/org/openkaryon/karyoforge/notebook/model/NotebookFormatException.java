package org.openkaryon.karyoforge.notebook.model;

/** A parse error that retains the source for a read-only/raw editor fallback. */
public final class NotebookFormatException extends Exception {
    private final String originalText;

    public NotebookFormatException(String message, String originalText) {
        super(message);
        this.originalText = originalText;
    }

    public NotebookFormatException(String message, String originalText, Throwable cause) {
        super(message, cause);
        this.originalText = originalText;
    }

    public String originalText() {
        return originalText;
    }
}
