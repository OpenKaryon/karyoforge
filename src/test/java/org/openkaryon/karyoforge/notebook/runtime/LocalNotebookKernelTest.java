package org.openkaryon.karyoforge.notebook.runtime;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Real local-kernel acceptance, excluded unless notebookRuntimeTests is requested. */
public final class LocalNotebookKernelTest extends TestCase {
    private Path python;
    @Override protected void setUp() {
        String executable = System.getProperty("karyoforge.notebook.python", "");
        assertFalse("Pass -PnotebookPythonExecutable for real kernel checks", executable.isBlank());
        python = Path.of(executable);
    }

    public void testColdBridgeCanStartAfterFortySecondsAndStartupCanStillBeCancelled() throws Exception {
        try (var runtime = slowRuntime("bridge", 41)) {
            var kernel = new LocalNotebookKernel(runtime.executable(), runtime.root());
            try {
                kernel.start();
                assertTrue(kernel.execute("6 * 7").outputs().toString().contains("42"));
            } finally { stop(kernel); }

            var cancelled = new LocalNotebookKernel(runtime.executable(), runtime.root());
            var ended = new CompletableFuture<Boolean>();
            Thread.ofVirtual().name("cancel-slow-kernel-acceptance").start(() -> {
                try { cancelled.start(); ended.complete(false); }
                catch (Exception expected) { ended.complete(true); }
            });
            try {
                await(cancelled::hasOwnedProcesses, 5);
                stop(cancelled);
                assertTrue("Closing an unfinished startup must release its waiting worker", ended.get(5, TimeUnit.SECONDS));
            } finally { stop(cancelled); }
        }
    }

    public void testColdKernelCanBecomeReadyAfterThirtySeconds() throws Exception {
        try (var runtime = slowRuntime("kernel", 31)) {
            var kernel = new LocalNotebookKernel(runtime.executable(), runtime.root());
            try {
                kernel.start();
                var result = kernel.execute("6 * 7");
                assertEquals("ok", result.status());
                assertEquals(Integer.valueOf(1), result.count());
                assertTrue(result.outputs().toString().contains("42"));
            } finally { stop(kernel); }
        }
    }

    /** Isolate startup delays from the caller's installed interpreter and package files. */
    private SlowRuntime slowRuntime(String role, int seconds) throws Exception {
        Path root = Files.createTempDirectory("notebook-cold-start-acceptance-").toAbsolutePath().normalize();
        var owned = new SlowRuntime(root, root.resolve(System.getProperty("os.name").startsWith("Windows")
                ? "Scripts/python.exe" : "bin/python"));
        try {
            capture(python, root, java.util.List.of("-I", "-m", "venv", "--without-pip", root.toString()));
            String query = "import sysconfig; print(sysconfig.get_paths()['purelib'])";
            String packages = capture(python, root, java.util.List.of("-I", "-c", query)).strip();
            Path site = Path.of(capture(owned.executable(), root, java.util.List.of("-I", "-c", query)).strip());
            assertTrue("The delayed startup hook must stay inside its owned environment", site.toAbsolutePath().normalize().startsWith(root));
            Files.writeString(site.resolve("notebook-acceptance-packages.pth"), packages + System.lineSeparator());
            String condition = "bridge".equals(role)
                    ? "sys.argv[0].replace(chr(92), '/').endswith('/bridge.py')" : "sys.argv[0] == '-m'";
            Files.writeString(site.resolve("sitecustomize.py"), "import sys, time\nif sys.argv and (" + condition
                    + "):\n    time.sleep(" + seconds + ")\n");
            return owned;
        } catch (Exception failure) { owned.close(); throw failure; }
    }

    private static String capture(Path executable, Path directory, java.util.List<String> arguments) throws Exception {
        try (var operation = new NotebookDependencyCheck()) {
            return operation.capture(NotebookDependencyCheck.command(executable, directory, arguments), Duration.ofSeconds(60));
        }
    }

    private record SlowRuntime(Path root, Path executable) implements AutoCloseable {
        @Override public void close() throws java.io.IOException {
            // Walk does not follow links; remove only this randomly created test environment.
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root)) throw new java.io.IOException("Invalid owned test cleanup path");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    public void testVirtualWorkerStartupExecutionAndCleanupWithOnlyTwoCarrierThreads() throws Exception {
        Path directory = Files.createTempDirectory("notebook-two-carrier-acceptance-");
        try {
            String classpath = java.util.stream.Stream.of(KernelWorkerAcceptance.class,
                            LocalNotebookKernel.class, com.google.gson.JsonObject.class)
                    .map(type -> {
                        String relative = type.getName().replace('.', '/') + ".class";
                        var resource = java.util.Objects.requireNonNull(type.getResource("/" + relative));
                        String url = resource.toExternalForm();
                        String location = "jar".equals(resource.getProtocol())
                                ? url.substring(4, url.indexOf("!/")) : url.substring(0, url.length() - relative.length());
                        return Path.of(java.net.URI.create(location)).toString();
                    }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            Path launcher = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            var command = NotebookDependencyCheck.command(launcher, directory, java.util.List.of(
                    "-XX:ActiveProcessorCount=2", "-Djdk.virtualThreadScheduler.parallelism=2",
                    "-Djdk.virtualThreadScheduler.maxPoolSize=2", "-cp", classpath,
                    KernelWorkerAcceptance.class.getName(), python.toString(), directory.toString()));
            try (var operation = new NotebookDependencyCheck()) {
                String output = operation.capture(command, Duration.ofSeconds(90));
                assertTrue(output, output.contains("TWO_CARRIER_KERNEL_ACCEPTANCE_PASSED"));
            }
        } finally { Files.deleteIfExists(directory); }
    }

    public void testSelectedRuntimeDirectoryNamespaceCountsStreamsResultsAndErrors() throws Exception {
        Path directory = Files.createTempDirectory("notebook-runtime-acceptance-");
        var kernel = new LocalNotebookKernel(python, directory);
        try {
            kernel.start();
            assertFalse(Files.exists(directory.resolve("side-effect")));
            var first = kernel.execute("import os, sys\nfrom pathlib import Path\nvalue = 40\nprint('STDOUT', sys.executable, os.getcwd())\nprint('STDERR', file=sys.stderr)\nvalue + 2");
            assertEquals("ok", first.status());
            assertEquals(Integer.valueOf(1), first.count());
            String output = first.outputs().toString().replace("\\\\", "\\");
            assertTrue(output, output.contains(python.toString().replace("\\", "/")) || output.contains(python.toString()));
            assertTrue(output, output.contains(directory.toString().replace("\\", "/")) || output.contains(directory.toString()));
            assertTrue(output, output.contains("STDOUT") && output.contains("STDERR") && output.contains("execute_result") && output.contains("42"));
            var second = kernel.execute("value += 2\nvalue");
            assertEquals(Integer.valueOf(2), second.count());
            assertTrue(second.outputs().toString().contains("42"));
            var error = kernel.execute("raise ValueError('owned-error')");
            assertEquals("error", error.status());
            assertEquals(Integer.valueOf(3), error.count());
            assertTrue(error.outputs().toString().contains("ValueError"));
            assertTrue(kernel.execute("value").outputs().toString().contains("42"));
        } finally { stop(kernel); Files.deleteIfExists(directory); }
    }

    public void testClosingBusyKernelStopsItsObservedChildrenAndLeavesAnUnrelatedProcessAlone() throws Exception {
        Path directory = Files.createTempDirectory("notebook-close-acceptance-");
        var unrelated = NotebookDependencyCheck.command(python, directory, java.util.List.of("-I", "-c", "import time;time.sleep(60)")).start();
        var kernel = new LocalNotebookKernel(python, directory);
        var result = new CompletableFuture<Boolean>();
        try {
            kernel.start();
            Thread.ofVirtual().start(() -> {
                try {
                    kernel.execute("import subprocess, sys, time\nfrom pathlib import Path\nchild = subprocess.Popen([sys.executable, '-I', '-c', 'import time;time.sleep(60)'])\nPath('owned-child.pid').write_text(str(child.pid))\ntime.sleep(60)");
                    result.complete(false);
                } catch (Exception expected) { result.complete(true); }
            });
            await(() -> Files.isRegularFile(directory.resolve("owned-child.pid")), 10);
            long pid = Long.parseLong(Files.readString(directory.resolve("owned-child.pid")));
            var child = ProcessHandle.of(pid).orElseThrow();
            Thread.sleep(100); // Give the ownership observer time to capture the child identity.
            stop(kernel);
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertFalse(child.isAlive());
            assertTrue("Kernel cleanup must leave an independently launched process running", unrelated.isAlive());
        } finally {
            stop(kernel);
            var independentChildren = unrelated.descendants().toList();
            unrelated.destroyForcibly(); unrelated.waitFor(5, TimeUnit.SECONDS);
            for (var child : independentChildren) child.destroyForcibly();
            await(() -> independentChildren.stream().noneMatch(ProcessHandle::isAlive), 5);
            Files.deleteIfExists(directory.resolve("owned-child.pid"));
            Files.deleteIfExists(directory);
        }
    }

    private static void stop(LocalNotebookKernel kernel) throws Exception {
        Path runtime = kernel.runtimeDirectory();
        kernel.close();
        await(() -> !kernel.hasOwnedProcesses() && (runtime == null || !Files.exists(runtime)), 12);
    }

    private static void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue("Owned kernel condition did not complete before its deadline", condition.getAsBoolean());
    }
}
