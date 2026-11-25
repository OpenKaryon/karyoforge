package org.openkaryon.karyoforge.database.core;

public sealed interface SqlResult permits QueryPage, SqlResult.UpdateCount {
    record UpdateCount(int rows) implements SqlResult {}
}
