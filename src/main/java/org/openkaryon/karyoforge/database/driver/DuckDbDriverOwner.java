package org.openkaryon.karyoforge.database.driver;

import org.openkaryon.karyoforge.database.core.DatabaseProblem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** A native DuckDB library remains associated with the first verified driver for this IDE process. */
public final class DuckDbDriverOwner {
    private static final PinnedJdbcBundle.Jar DRIVER = new PinnedJdbcBundle.Jar("duckdb_jdbc-1.5.6.0.jar", 85_178_546,
            "04ae3375d7c40dc88dc9bf0014b82ba5e66715564584af4e3abea39c7ba144d3");
    private static Path original;
    private static PinnedJdbcBundle bundle;
    private static DriverLease lease;
    private static int users;
    private static boolean exiting;

    private DuckDbDriverOwner() {}

    public static synchronized DriverLease acquire(Path selected) throws IOException {
        if (exiting) throw new DatabaseProblem("The DuckDB runtime is closing.");
        Path actual = selected.toRealPath();
        if (lease != null) {
            if (!actual.equals(original)) throw new DatabaseProblem("Restart KaryoForge to select a different DuckDB driver file.");
            PinnedJdbcBundle.verify(actual, DRIVER);
            users++;
            return lease;
        }

        var staged = PinnedJdbcBundle.open(actual, List.of(DRIVER));
        DriverLease first = null;
        try {
            first = DriverLease.open(staged.paths(), "org.duckdb.DuckDBDriver");
            Runtime.getRuntime().addShutdownHook(new Thread(DuckDbDriverOwner::atExit, "KaryoForge DuckDB shutdown"));
            original = actual;
            bundle = staged;
            lease = first;
            users = 1;
            return lease;
        } catch (IOException | RuntimeException failure) {
            if (first != null) first.close();
            staged.close();
            throw failure;
        }
    }

    public static synchronized void release(DriverLease returned) throws IOException {
        if (returned != lease || users < 1) throw new IOException("The DuckDB driver lease has no matching owner.");
        users--;
        if (exiting && users == 0) close();
    }

    private static synchronized void atExit() {
        exiting = true;
        if (users == 0) {
            try {
                close();
            } catch (IOException unavailable) {
                // The runtime is exiting and the native owner will not be reused.
            }
        }
    }

    private static void close() throws IOException {
        if (lease != null) {
            lease.close();
            lease = null;
        }
        if (bundle != null) {
            bundle.close();
            bundle = null;
        }
    }
}
