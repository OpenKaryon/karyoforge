package org.openkaryon.karyoforge.database.core;

import java.sql.SQLException;
import java.util.regex.Pattern;

/** A diagnostic suitable for the UI, without the original driver's cause chain. */
public final class DatabaseProblem extends RuntimeException {
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[^\\s'\"]+");
    private static final Pattern URL_USER = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/\\s@]+@");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b([a-z0-9_.-]*(?:password|passwd|pwd|secret|token|sslkey|sslcert)[a-z0-9_.-]*)\\s*=\\s*('[^']*'?|\"[^\"]*\"?|[^\\s;&,)]+)");

    public DatabaseProblem(String message) { super(message, null, false, false); }

    static DatabaseProblem connection(String engine, boolean writable, Exception failure) {
        if (failure instanceof SQLException sql && sql.getSQLState() != null) {
            String state = sql.getSQLState();
            if (state.startsWith("28")) return new DatabaseProblem(engine + " rejected the selected credentials.");
            if (state.equals("3D000")) return new DatabaseProblem("The selected " + engine + " database does not exist.");
            if (state.startsWith("08")) return new DatabaseProblem("Could not reach the selected " + engine + " database.");
        }
        return new DatabaseProblem("Could not open the selected " + engine + " database in "
                + (writable ? "read-write" : "read-only") + " mode.");
    }

    static DatabaseProblem query(String operation, SQLException exception) {
        String message = exception.getMessage();
        if (message == null) message = "";
        if (message.length() > 8192) message = message.substring(0, 8192);
        message = message.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
        message = JDBC_URL.matcher(message).replaceAll("[JDBC URL]");
        message = URL_USER.matcher(message).replaceAll("$1[redacted]@");
        message = SECRET.matcher(message).replaceAll("$1=[redacted]").strip();
        if (message.length() > 480) message = message.substring(0, 480) + "…";
        String state = exception.getSQLState();
        if (state != null && state.matches("[A-Z0-9]{5}")) message += " (SQL state " + state + ")";
        return new DatabaseProblem(message.isBlank() ? operation + " failed." : operation + " failed. " + message);
    }
}
