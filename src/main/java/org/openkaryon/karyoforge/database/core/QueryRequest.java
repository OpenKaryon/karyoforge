package org.openkaryon.karyoforge.database.core;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/** A stop or timeout belongs to one command, even when its cursor survives the command. */
public final class QueryRequest {
    private static final long CANCEL_SETTLEMENT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private volatile StopReason reason;
    private boolean finished;
    private boolean cancelling;
    private boolean cancelFailed;
    private Statement statement;

    synchronized void attach(Statement target) {
        check();
        if (finished || statement != null) throw new IllegalStateException("The request already owns a statement.");
        statement = target;
    }

    synchronized void detach(Statement target) {
        if (statement != target) return;
        boolean interrupted = false;
        while (cancelling) {
            try {
                wait();
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        statement = null;
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void stop() { cancel(StopReason.USER); }
    void expire() { cancel(StopReason.TIMEOUT); }

    private void cancel(StopReason cause) {
        Statement target;
        synchronized (this) {
            if (finished || reason != null) return;
            reason = cause;
            target = statement;
            if (target == null) return;
            cancelling = true;
        }
        boolean acknowledged = false;
        try {
            target.cancel();
            acknowledged = true;
        } catch (SQLException | RuntimeException unavailable) {
            // The command lane observes unsuccessful cancellation before reuse.
        } finally {
            synchronized (this) {
                cancelFailed = !acknowledged;
                cancelling = false;
                notifyAll();
            }
        }
    }

    void check() {
        var stop = reason;
        if (stop == StopReason.USER) throw new DatabaseProblem("Database request stopped.");
        if (stop == StopReason.TIMEOUT) throw new DatabaseProblem("Database request exceeded its query timeout.");
    }

    synchronized boolean finish() {
        finished = true;
        statement = null;
        long deadline = System.nanoTime() + CANCEL_SETTLEMENT_NANOS;
        while (cancelling) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return true;
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return cancelFailed;
    }

    synchronized boolean cancellationSettled() { return !cancelling; }
    boolean stopped() { return reason != null; }

    private enum StopReason { USER, TIMEOUT }
}
