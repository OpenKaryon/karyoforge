package org.openkaryon.karyoforge.notebook.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** One owned local kernel and bounded stdio transport. Blocking calls run off the IDE EDT. */
public final class LocalNotebookKernel implements AutoCloseable {
    private static final int MAX_FRAME = 16 * 1024 * 1024;
    private final Path python;
    private final Path directory;
    private final String token = UUID.randomUUID().toString();
    private final ArrayBlockingQueue<JsonObject> events = new ArrayBlockingQueue<>(64);
    private final ConcurrentHashMap<Long, Identity> children = new ConcurrentHashMap<>();
    private final Object outputLock = new Object();
    private volatile boolean closed;
    private volatile Process process;
    private volatile Path temporary;
    private BufferedWriter writer;
    private volatile String diagnostics = "";

    public record Execution(JsonArray outputs, Integer count, String status) {}
    private record Identity(ProcessHandle handle, Instant started) {
        Identity(ProcessHandle handle) { this(handle, handle.info().startInstant().orElse(null)); }
        boolean alive() { return started != null && handle.isAlive() && handle.info().startInstant().filter(started::equals).isPresent(); }
        void stop() { if (alive()) handle.destroyForcibly(); }
    }

    public LocalNotebookKernel(Path python, Path directory) { this.python = python; this.directory = directory; }

    public void start() throws Exception {
        synchronized (this) {
            if (closed || process != null) throw new IOException("Kernel startup cancelled or already attempted.");
            temporary = Files.createTempDirectory("karyoforge-notebook-").toAbsolutePath().normalize();
            Path script = temporary.resolve("bridge.py");
            try (var source = LocalNotebookKernel.class.getResourceAsStream("/notebook/kernel_bridge.py")) {
                if (source == null) throw new IOException("The notebook kernel bridge resource is missing.");
                Files.copy(source, script);
            }
            process = NotebookDependencyCheck.command(python, directory,
                    List.of("-I", "-u", script.toString(), token, temporary.toString())).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
        // Java 21 process pipes can pin carriers for the lifetime of a blocking read.
        // Two dedicated daemon readers leave virtual execution and cleanup workers runnable.
        Thread.ofPlatform().daemon(true).name("notebook-kernel-events").start(this::readEvents);
        Thread.ofPlatform().daemon(true).name("notebook-kernel-diagnostics").start(this::readDiagnostics);
        Thread.ofVirtual().name("notebook-owned-processes").start(() -> {
            while (process.isAlive()) {
                observeChildren();
                try { Thread.sleep(25); } catch (InterruptedException ignored) { return; }
            }
        });
        JsonObject ready = take(Duration.ofSeconds(90));
        if (!"ready".equals(ready.get("event").getAsString())) throw new IOException("The local kernel did not become ready.");
    }

    public Execution execute(String code) throws Exception {
        String request = UUID.randomUUID().toString();
        var command = new JsonObject();
        command.addProperty("command", "execute");
        command.addProperty("request", request);
        command.addProperty("code", code);
        send(command);
        var outputs = new JsonArray();
        int size = 0;
        while (true) {
            JsonObject event = take(null);
            if (!event.has("request") || !request.equals(event.get("request").getAsString())) throw new IOException("Kernel returned an unrelated execution event.");
            switch (event.get("event").getAsString()) {
                case "output" -> {
                    var output = event.getAsJsonObject("output");
                    size += output.toString().length();
                    if (size > 9 * 1024 * 1024 || outputs.size() > 1000) throw new IOException("Kernel output exceeds its transport bound.");
                    outputs.add(output);
                }
                case "done" -> {
                    var count = event.get("count");
                    return new Execution(outputs, count == null || count.isJsonNull() ? null : count.getAsInt(), event.get("status").getAsString());
                }
                default -> throw new IOException("Unexpected kernel execution event.");
            }
        }
    }

    private JsonObject take(Duration timeout) throws Exception {
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        while (!closed) {
            JsonObject event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) {
                if ("fatal".equals(event.get("event").getAsString())) throw new IOException(event.get("message").getAsString());
                return event;
            }
            if (!process.isAlive()) throw new IOException("The owned kernel bridge exited. " + diagnostics);
            if (System.nanoTime() > deadline) throw new IOException("Local kernel startup timed out.");
        }
        throw new IOException("The local kernel session was closed.");
    }

    private void send(JsonObject command) throws IOException {
        if (closed) throw new IOException("The local kernel session is closed.");
        command.addProperty("protocol", 1);
        command.addProperty("token", token);
        String frame = command.toString();
        if (frame.length() > MAX_FRAME) throw new IOException("Notebook command exceeds its transport bound.");
        synchronized (outputLock) { writer.write(frame); writer.newLine(); writer.flush(); }
    }

    private void readEvents() {
        try (var input = new BufferedInputStream(process.getInputStream())) {
            while (!closed) {
                var bytes = new ByteArrayOutputStream();
                int value;
                while ((value = input.read()) != -1 && value != '\n') {
                    if (bytes.size() >= MAX_FRAME) throw new IOException("Kernel event exceeds its frame bound.");
                    bytes.write(value);
                }
                if (value == -1) throw new IOException("The kernel event channel closed. " + diagnostics);
                var event = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject();
                if (event.get("protocol").getAsInt() != 1 || !token.equals(event.get("token").getAsString()))
                    throw new IOException("Invalid kernel protocol identity.");
                while (!closed && !events.offer(event, 100, TimeUnit.MILLISECONDS)) { /* bounded backpressure */ }
            }
        } catch (Exception failure) {
            var event = new JsonObject();
            event.addProperty("event", "fatal");
            event.addProperty("message", "Kernel communication failed: " + failure.getMessage());
            events.clear();
            events.offer(event);
        }
    }

    private void readDiagnostics() {
        try (var reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                String combined = diagnostics + new String(buffer, 0, count);
                diagnostics = combined.substring(Math.max(0, combined.length() - 8192));
            }
        } catch (IOException ignored) { /* Closing the owned process closes its pipes. */ }
    }

    private void observeChildren() {
        process.descendants().forEach(handle -> children.putIfAbsent(handle.pid(), new Identity(handle)));
    }

    public boolean isClosed() { return closed; }
    public boolean hasOwnedProcesses() { return process != null && process.isAlive() || children.values().stream().anyMatch(Identity::alive); }
    public Path runtimeDirectory() { return temporary; }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        Thread.ofVirtual().name("notebook-kernel-shutdown").start(() -> {
            try {
                synchronized (outputLock) {
                    if (writer != null) {
                        var shutdown = new JsonObject();
                        shutdown.addProperty("protocol", 1);
                        shutdown.addProperty("token", token);
                        shutdown.addProperty("command", "shutdown");
                        writer.write(shutdown.toString()); writer.newLine(); writer.flush();
                    }
                }
            } catch (IOException ignored) { /* The bridge may already have exited. */ }
        });
        Thread.ofVirtual().name("notebook-kernel-cleanup").start(() -> {
            try {
                Process owned = process;
                if (owned != null) {
                    if (!owned.waitFor(2500, TimeUnit.MILLISECONDS)) { observeChildren(); owned.destroyForcibly(); }
                    for (var child : children.values()) child.stop();
                    owned.waitFor(2, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (hasOwnedProcesses() && System.nanoTime() < deadline) Thread.sleep(25);
                }
                Path root = temporary;
                if (root != null && !hasOwnedProcesses()) {
                    // Walk never follows links. Delete only this randomly created owned directory.
                    try (var entries = Files.walk(root)) {
                        for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                            if (!path.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Invalid runtime cleanup path.");
                            Files.deleteIfExists(path);
                        }
                    }
                }
            } catch (IOException | InterruptedException ignored) { /* Preserve files if cleanup cannot safely finish. */ }
        });
    }
}
