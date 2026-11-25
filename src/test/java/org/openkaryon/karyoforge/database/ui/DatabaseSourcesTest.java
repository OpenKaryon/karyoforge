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
        var writable = new SqliteSource("Writable", root.resolve("other.db"), source.driver(), true);
        String writableId = original.save(null, writable);
        var snapshot = original.getState();
        var malformed = new DatabaseSources.Entry();
        malformed.id = "invalid";
        snapshot.entries.add(malformed);
        var restored = new DatabaseSources();
        restored.loadState(snapshot);

        assertEquals(List.of(new DatabaseSources.Profile(id, source), new DatabaseSources.Profile(writableId, writable)), restored.profiles());
        restored.remove(id);
        restored.remove(writableId);
        assertEquals(List.of(), restored.getState().entries);
    }
}
