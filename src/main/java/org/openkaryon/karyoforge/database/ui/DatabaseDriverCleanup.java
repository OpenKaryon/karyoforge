package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.components.Service;
import org.openkaryon.karyoforge.database.core.DatabaseProblem;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Holds retired session owners across workspace closure so cleanup remains retryable. */
@Service(Service.Level.APP)
public final class DatabaseDriverCleanup {
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<RetiredOwner>> retired = new ConcurrentHashMap<>();

    boolean pending(String sourceId) {
        var owners = retired.get(sourceId);
        return owners != null && !owners.isEmpty();
    }

    void release(String sourceId, Runnable close) {
        var owner = new RetiredOwner(close);
        if (owner.tryClose()) return;
        retired.compute(sourceId, (id, owners) -> {
            var queue = owners == null ? new ConcurrentLinkedQueue<RetiredOwner>() : owners;
            queue.add(owner);
            return queue;
        });
        throw new DatabaseProblem("Database driver cleanup is pending. Choose Retry Driver Cleanup.");
    }

    boolean retry(String sourceId) {
        var owners = retired.get(sourceId);
        if (owners == null) return true;
        for (var owner : owners) {
            if (owner.tryClose()) owners.remove(owner);
        }
        retired.computeIfPresent(sourceId, (id, current) -> current.isEmpty() ? null : current);
        return !pending(sourceId);
    }

    private static final class RetiredOwner {
        private Runnable close;

        RetiredOwner(Runnable close) { this.close = close; }

        synchronized boolean tryClose() {
            if (close == null) return true;
            try {
                close.run();
                close = null;
                return true;
            } catch (RuntimeException | LinkageError unavailable) {
                return false;
            }
        }
    }
}
