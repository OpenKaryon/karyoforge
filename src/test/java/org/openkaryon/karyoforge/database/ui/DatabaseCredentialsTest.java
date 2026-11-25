package org.openkaryon.karyoforge.database.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.openkaryon.karyoforge.database.core.PostgresqlSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public final class DatabaseCredentialsTest extends BasePlatformTestCase {
    public void testRememberedPasswordFollowsTheDestinationAndCanBeCleared() {
        String id = UUID.randomUUID().toString();
        Path driver = Path.of(getProject().getBasePath()).resolve("owned-driver.jar");
        var original = profile(id, "Local", 5432, driver, null);
        var renamed = profile(id, "Renamed", 5432, driver, null);
        var differentPort = profile(id, "Local", 15432, driver, null);
        var differentCa = profile(id, "Local", 5432, driver, driver.resolveSibling("owned-ca.pem"));
        var differentDriver = profile(id, "Local", 5432, driver.resolveSibling("different.jar"), null);
        var anotherProfile = profile(UUID.randomUUID().toString(), "Local", 5432, driver, null);
        assertTrue(DatabaseCredentials.sameDestination(original, renamed));
        assertFalse(DatabaseCredentials.sameDestination(original, differentPort));
        assertFalse(DatabaseCredentials.sameDestination(original, differentCa));
        assertFalse(DatabaseCredentials.sameDestination(original, differentDriver));
        char[] secret = "temporary owned database credential".toCharArray();
        try {
            DatabaseCredentials.remember(original, secret);
            Arrays.fill(secret, '\0');
            char[] recalled = DatabaseCredentials.recall(renamed);
            assertTrue(Arrays.equals("temporary owned database credential".toCharArray(), recalled));
            Arrays.fill(recalled, '\0');
            assertTrue(Arrays.equals("temporary owned database credential".toCharArray(), DatabaseCredentials.recall(original)));
            assertEquals(0, DatabaseCredentials.recall(differentPort).length);
            assertEquals(0, DatabaseCredentials.recall(differentCa).length);
            assertEquals(0, DatabaseCredentials.recall(differentDriver).length);
            assertEquals(0, DatabaseCredentials.recall(anotherProfile).length);
        } finally {
            Arrays.fill(secret, '\0');
            DatabaseCredentials.forget(original);
        }
        assertEquals(0, DatabaseCredentials.recall(renamed).length);
    }

    private static DatabaseSources.Profile profile(String id, String name, int port, Path driver, Path ca) {
        return new DatabaseSources.Profile(id, new PostgresqlSource(name, "127.0.0.1", port,
                "owned", "fixture", driver, false, ca, null));
    }
}
