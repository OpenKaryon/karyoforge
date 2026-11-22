package org.openkaryon.karyoforge.notebook.runtime;

import junit.framework.TestCase;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Opt-in checks use an explicitly supplied, isolated notebook runtime. */
public final class NotebookProbeProcessTest extends TestCase {
    private Path python;
    @Override protected void setUp() {
        String executable = System.getProperty("karyoforge.notebook.python", "");
        assertFalse("Pass -PnotebookPythonExecutable pointing at the prepared notebook runtime", executable.isBlank());
        python = Path.of(executable);
    }

    public void testRealMetadataProbeUsesTheExactExecutableAndFindsBothPackages() throws Exception {
        try (var operation = new NotebookDependencyCheck()) {
            var report = operation.run(python, python.getParent(), Duration.ofSeconds(20));
            assertEquals(python.toRealPath(), Path.of(report.python()).toRealPath());
            assertTrue(report.summary(), report.ready());
        }
    }

    public void testExcessiveOutputAndTimeoutStopOnlyOwnedProbes() throws Exception {
        try (var operation = new NotebookDependencyCheck()) {
            try {
                operation.capture(NotebookDependencyCheck.command(python, python.getParent(),
                        List.of("-I", "-c", "import sys,time;sys.stdout.write('x'*100000);sys.stdout.flush();time.sleep(30)")), Duration.ofSeconds(5));
                fail("Excessive output must fail");
            } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("output bound")); }
        }
        long before = System.nanoTime();
        try (var operation = new NotebookDependencyCheck()) {
            try {
                operation.capture(NotebookDependencyCheck.command(python, python.getParent(),
                        List.of("-I", "-c", "import time;time.sleep(30)")), Duration.ofMillis(200));
                fail("Timeout must fail");
            } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("timed out")); }
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toSeconds() < 5);
    }

    public void testCancellationBeforeAndDuringStartIsBounded() throws Exception {
        var cancelled = new NotebookDependencyCheck();
        cancelled.close();
        try { cancelled.run(python, python.getParent(), Duration.ofSeconds(2)); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
        var active = new NotebookDependencyCheck();
        var result = new CompletableFuture<Boolean>();
        Thread.ofVirtual().start(() -> {
            try {
                active.capture(NotebookDependencyCheck.command(python, python.getParent(),
                        List.of("-I", "-c", "import time;time.sleep(30)")), Duration.ofSeconds(20));
                result.complete(false);
            } catch (Exception expected) { result.complete(true); }
        });
        active.close();
        assertTrue(result.get(5, TimeUnit.SECONDS));
    }
}
