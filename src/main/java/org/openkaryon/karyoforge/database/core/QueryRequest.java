package org.openkaryon.karyoforge.database.core;

import java.sql.SQLException;
import java.sql.Statement;

/** A stop request belongs to one command, even when its result cursor survives that command. */
public final class QueryRequest {
    private volatile boolean stopped;
    private boolean finished;
    private boolean cancelFailed;
    private Statement statement;

    synchronized void attach(Statement target) {
        check();
        if (finished || statement != null) throw new IllegalStateException("The request already owns a statement.");
        statement = target;
    }

    synchronized void detach(Statement target) {
        if (statement == target) statement = null;
    }

    public synchronized void stop() {
        if (finished || stopped) return;
        stopped = true;
        if (statement == null) return;
        try {
            statement.cancel();
        } catch (SQLException | RuntimeException failure) {
            cancelFailed = true;
        }
    }

    void check() {
        if (stopped) throw new DatabaseProblem("Database request stopped.");
    }

    synchronized boolean finish() {
        finished = true;
        statement = null;
        return cancelFailed;
    }

    boolean stopped() { return stopped; }
}
