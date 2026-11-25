package org.openkaryon.karyoforge.database.core;

/** Bounds on detached rows and loaded catalog objects. */
public record ResultLimits(int pageSize, int maxRows, int maxBytes, int maxCellChars, int maxColumns, int catalogLimit) {
    public static final ResultLimits DEFAULT = new ResultLimits(200, 10_000, 8 * 1024 * 1024, 16_384, 256, 2_000);

    public ResultLimits {
        if (pageSize < 1 || pageSize > 1000 || maxRows < pageSize || maxRows > 100_000
                || maxBytes < 1024 || maxBytes > 64 * 1024 * 1024 || maxCellChars < 1 || maxCellChars > 1_048_576
                || maxColumns < 1 || maxColumns > 1024 || catalogLimit < 1 || catalogLimit > 10_000) {
            throw new IllegalArgumentException("Enter result limits within the displayed ranges.");
        }
    }

    void checkColumns(int count) {
        if (count > maxColumns) throw new DatabaseProblem("The result or object exceeds " + maxColumns + " columns.");
    }
}
