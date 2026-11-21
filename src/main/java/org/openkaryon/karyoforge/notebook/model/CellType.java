package org.openkaryon.karyoforge.notebook.model;

import java.util.Locale;

public enum CellType {
    CODE, MARKDOWN, RAW, UNKNOWN;

    public String jsonName() {
        if (this == UNKNOWN) throw new IllegalStateException("Unknown cell types have their own JSON name");
        return name().toLowerCase(Locale.ROOT);
    }

    static CellType fromJsonName(String name) {
        return switch (name) {
            case "code" -> CODE;
            case "markdown" -> MARKDOWN;
            case "raw" -> RAW;
            default -> UNKNOWN;
        };
    }
}
