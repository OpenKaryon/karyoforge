package org.openkaryon.karyoforge.database.ui;

import org.junit.Test;
import org.openkaryon.karyoforge.database.core.DatabaseProblem;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class DatabaseDriverCleanupTest {
    @Test public void retriesOnlyTheRetiredOwnersOfTheSelectedSource() {
        var cleanup = new DatabaseDriverCleanup();
        var attempts = new AtomicInteger();
        var other = new AtomicInteger();
        assertThrows(DatabaseProblem.class, () -> cleanup.release("first", () -> {
            if (attempts.incrementAndGet() <= 2) throw new DatabaseProblem("Driver still closing.");
        }));
        assertThrows(DatabaseProblem.class, () -> cleanup.release("second", () -> {
            other.incrementAndGet();
            throw new DatabaseProblem("Different driver still closing.");
        }));
        assertTrue(cleanup.pending("first"));
        assertFalse(cleanup.retry("first"));
        assertTrue(cleanup.pending("first"));
        assertTrue(cleanup.retry("first"));
        assertFalse(cleanup.pending("first"));
        assertTrue(cleanup.pending("second"));
        assertEquals(3, attempts.get());
        assertEquals(1, other.get());
    }
}
