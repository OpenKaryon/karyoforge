package org.openkaryon.karyoforge.notebook.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Explicit, bounded metadata-only probe; close cancels only this operation's process. */
public final class NotebookDependencyCheck implements AutoCloseable {
    private static final int OUTPUT_LIMIT = 65_536;
    private static final String SCRIPT = """
            import importlib.util, importlib.metadata, json, sys
            packages = {}
            for name in ('jupyter_client', 'ipykernel'):
                try:
                    packages[name] = importlib.metadata.version(name) if importlib.util.find_spec(name) else None
                except (ImportError, ValueError, importlib.metadata.PackageNotFoundError):
                    packages[name] = None
            print(json.dumps({'python': sys.executable, 'version': sys.version.split()[0], 'packages': packages}))
            """;
    private final java.util.Map<Long, OwnedHandle> children = new java.util.HashMap<>();
    private Process process;
    private boolean closed;

    public record Result(String python, String version, String client, String kernel) {
        public boolean ready() { return client != null && kernel != null; }
        public String summary() {
            if (ready()) return "Dependencies ready: Python " + version + ", jupyter_client " + client + ", ipykernel " + kernel + ".";
            return "Missing " + (client == null ? "jupyter_client " : "") + (kernel == null ? "ipykernel" : "")
                    + ". Install into the selected interpreter, then check again.";
        }
    }

    public Result run(Path python, Path directory, Duration timeout) throws Exception {
        return inspect(capture(command(python, directory, List.of("-I", "-c", SCRIPT)), timeout));
    }

    static Result inspect(String text) throws IOException {
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            JsonObject packages = json.getAsJsonObject("packages");
            return new Result(json.get("python").getAsString(), json.get("version").getAsString(),
                    version(packages, "jupyter_client"), version(packages, "ipykernel"));
        } catch (RuntimeException failure) { throw new IOException("Python returned an invalid dependency report.", failure); }
    }

    private static String version(JsonObject packages, String name) {
        var value = packages.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static ProcessBuilder command(Path python, Path directory, List<String> arguments) {
        var argv = new ArrayList<String>();
        argv.add(python.toString());
        argv.addAll(arguments);
        var builder = new ProcessBuilder(argv).directory(directory.toFile());
        var environment = builder.environment();
        environment.clear();
        for (String name : List.of("SystemRoot", "WINDIR", "TEMP", "TMP", "PATH", "PATHEXT")) {
            String value = System.getenv(name);
            if (value != null) environment.put(name, value);
        }
        environment.put("PYTHONNOUSERSITE", "1");
        environment.put("PYTHONUNBUFFERED", "1");
        environment.put("PYTHONIOENCODING", "utf-8");
        return builder;
    }

    String capture(ProcessBuilder builder, Duration timeout) throws Exception {
        Process owned;
        synchronized (this) {
            if (closed || process != null) throw new IOException("Dependency check was cancelled or already started.");
            owned = process = builder.start();
        }
        var stdout = drain(owned.getInputStream());
        var stderr = drain(owned.getErrorStream());
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                synchronized (this) {
                    owned.descendants().forEach(handle -> children.putIfAbsent(handle.pid(), new OwnedHandle(handle)));
                }
                if (owned.waitFor(25, TimeUnit.MILLISECONDS)) break;
                synchronized (this) { if (closed) throw new IOException("Dependency check cancelled."); }
                if (stdout.isCompletedExceptionally() || stderr.isCompletedExceptionally()) throw new IOException("Dependency check exceeded its output bound.");
                if (System.nanoTime() >= deadline) throw new IOException("Dependency check timed out.");
            }
            String output = captured(stdout);
            String errors = captured(stderr);
            synchronized (this) { if (closed) throw new IOException("Dependency check cancelled."); }
            if (owned.exitValue() != 0) throw new IOException("Dependency check failed (exit " + owned.exitValue() + "): " + errors);
            return output;
        } finally {
            close();
            owned.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private static String captured(CompletableFuture<String> result) throws Exception {
        try { return result.get(2, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("Dependency check exceeded its output bound or failed to read output.", failure);
        }
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        var result = new CompletableFuture<String>();
        Thread.ofVirtual().name("notebook-dependency-output").start(() -> {
            try (stream; var bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (bytes.size() + count > OUTPUT_LIMIT) throw new IOException("Dependency output limit exceeded.");
                    bytes.write(buffer, 0, count);
                }
                result.complete(bytes.toString(StandardCharsets.UTF_8));
            } catch (IOException failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    @Override public synchronized void close() {
        closed = true;
        if (process != null && process.isAlive()) {
            // Capture identities while the parent is owned; never search by executable name.
            process.descendants().forEach(handle -> children.putIfAbsent(handle.pid(), new OwnedHandle(handle)));
            process.destroyForcibly();
        }
        for (var child : children.values()) child.stop();
    }

    private record OwnedHandle(ProcessHandle handle, Instant started) {
        OwnedHandle(ProcessHandle handle) { this(handle, handle.info().startInstant().orElse(null)); }
        void stop() {
            if (started != null && handle.info().startInstant().filter(started::equals).isPresent()) handle.destroyForcibly();
        }
    }
}
