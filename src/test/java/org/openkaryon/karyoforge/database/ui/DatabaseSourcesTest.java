package org.openkaryon.karyoforge.database.ui;

import org.junit.Test;
import org.openkaryon.karyoforge.database.core.SqliteSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class DatabaseSourcesTest {
    @Test public void persistenceRoundTripsPathsWithoutLoadingDriversOrCreatingFiles() {
        var original = new DatabaseSources();
        Path root = Path.of("").toAbsolutePath();
        var source = new SqliteSource("Passive", root.resolve("missing.db"), root.resolve("missing.jar"));
        String id = original.save(null, source);
        var snapshot = original.getState();
        var malformed = new DatabaseSources.Entry();
        malformed.id = "invalid";
        snapshot.entries.add(malformed);
        var restored = new DatabaseSources();
        restored.loadState(snapshot);

        assertEquals(List.of(new DatabaseSources.Profile(id, source)), restored.profiles());
        restored.remove(id);
        assertEquals(List.of(), restored.getState().entries);
    }
}
