package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.openkaryon.karyoforge.notebook.model.CellType;
import org.openkaryon.karyoforge.notebook.model.NotebookDocument;
import org.openkaryon.karyoforge.notebook.runtime.LocalNotebookKernel;
import org.openkaryon.karyoforge.notebook.runtime.NotebookDependencyCheck;
import org.openkaryon.karyoforge.notebook.runtime.NotebookInterpreter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** EDT-owned cell identity and off-EDT process lifecycle for one notebook editor. */
final class NotebookKernelController implements Disposable {
    private final Project project;
    private final VirtualFile file;
    private final Supplier<NotebookDocument> model;
    private final Consumer<String> status;
    private final Runnable refresh;
    private final java.util.function.BiPredicate<String, LocalNotebookKernel.Execution> completed;
    private final BiConsumer<String, org.openkaryon.karyoforge.notebook.runtime.NotebookOutputBuffer.Snapshot> preview;
    private final Consumer<LocalNotebookKernel.InputRequest> input;
    private final Runnable clearPreview;
    private final javax.swing.Timer previews;
    private static final class Progress {
        final String id;
        final java.util.concurrent.atomic.AtomicReference<org.openkaryon.karyoforge.notebook.runtime.NotebookOutputBuffer> buffer = new java.util.concurrent.atomic.AtomicReference<>();
        long revision = -1;
        Progress(String id) { this.id = id; }
    }
    private Progress progress;
    private volatile LocalNotebookKernel kernel;
    private volatile NotebookDependencyCheck probe;
    private Path kernelPython;
    private long generation;
    private final java.util.ArrayDeque<Target> queue = new java.util.ArrayDeque<>();
    private final java.util.List<LocalNotebookKernel> ownedKernels = new java.util.ArrayList<>();
    private record Target(String id, String source) {}
    private boolean executing;
    private boolean stopRequested;
    private boolean busy;
    private volatile boolean disposed;

    NotebookKernelController(Project project, VirtualFile file, Supplier<NotebookDocument> model,
                             Consumer<String> status, Runnable refresh,
                             java.util.function.BiPredicate<String, LocalNotebookKernel.Execution> completed,
                             BiConsumer<String, org.openkaryon.karyoforge.notebook.runtime.NotebookOutputBuffer.Snapshot> preview,
                             Consumer<LocalNotebookKernel.InputRequest> input, Runnable clearPreview) {
        this.project = project;
        this.file = file;
        this.model = model;
        this.status = status;
        this.refresh = refresh;
        this.completed = completed;
        this.preview = preview;
        this.input = input;
        this.clearPreview = clearPreview;
        previews = new javax.swing.Timer(200, event -> flushPreview());
    }

    private void flushPreview() {
        Progress current = progress;
        if (disposed || !busy || current == null || current.id == null) return;
        var buffer = current.buffer.get();
        if (buffer == null) return;
        var snapshot = buffer.snapshotAfter(current.revision);
        if (snapshot != null) { current.revision = snapshot.revision(); preview.accept(current.id, snapshot); }
    }

    String activeCell() { return busy && progress != null ? progress.id : null; }

    boolean isBusy() { return busy; }
    boolean hasOwnedProcesses() { return ownedKernels.stream().anyMatch(LocalNotebookKernel::hasOwnedProcesses); }
    boolean hasSession() { return kernel != null && !kernel.isClosed(); }

    void run(String id) {
        if (disposed || busy || model.get() == null) return;
        var cell = model.get().cell(id);
        queue.add(new Target(id, cell.source()));
        stopRequested = false;
        startNext();
    }

    void runAll() {
        if (disposed || busy || model.get() == null) return;
        for (var cell : model.get().cells()) if (cell.type() == CellType.CODE) queue.add(new Target(cell.id(), cell.source()));
        stopRequested = false;
        if (queue.isEmpty()) status.accept("This notebook has no Code cells to run.");
        else startNext();
    }

    private void startNext() {
        if (disposed || busy || queue.isEmpty()) return;
        Target target = queue.removeFirst();
        if (target.id() != null) {
            try {
                var cell = model.get().cell(target.id());
                if (cell.type() != CellType.CODE || !target.source().equals(cell.source())) throw new IllegalArgumentException();
            } catch (RuntimeException changed) {
                queue.clear();
                status.accept("Queued execution cancelled because a cell changed.");
                refresh.run();
                return;
            }
        }
        runTarget(target);
    }

    private void runTarget(Target target) {
        String id = target.id();
        NotebookDocument document = model.get();
        if (document == null) { queue.clear(); return; }
        String source = target.source();
        if (source.length() > 1_000_000) { queue.clear(); status.accept("Cell source exceeds the execution transport bound."); return; }
        var metadata = document.metadata();
        var language = metadata.has("language_info") && metadata.get("language_info").isJsonObject()
                ? metadata.getAsJsonObject("language_info").get("name") : null;
        var specLanguage = metadata.has("kernelspec") && metadata.get("kernelspec").isJsonObject()
                ? metadata.getAsJsonObject("kernelspec").get("language") : null;
        if (!pythonLanguage(language) || !pythonLanguage(specLanguage)) {
            queue.clear();
            status.accept("Only local Python notebooks can execute. Notebook language metadata was preserved.");
            return;
        }
        var interpreter = NotebookInterpreter.resolve(project, file);
        if (!interpreter.available()) { queue.clear(); status.accept(interpreter.problem()); return; }
        if (file.getParent() == null || !file.isInLocalFileSystem()) {
            queue.clear();
            status.accept("Kernel execution requires a local notebook directory."); return;
        }
        busy = true;
        long expected = ++generation;
        Progress visible = progress = new Progress(id);
        previews.start();
        status.accept("Starting local Python for the selected cell...");
        refresh.run();
        LocalNotebookKernel existing = kernel;
        if (existing != null && (existing.isClosed() || !interpreter.executable().equals(kernelPython))) {
            existing.close();
            existing = null;
        }
        ownedKernels.removeIf(owned -> owned.isClosed() && !owned.hasOwnedProcesses());
        var retired = java.util.List.copyOf(ownedKernels);
        LocalNotebookKernel reusable = existing;
        var operation = new NotebookDependencyCheck();
        probe = operation;
        Thread.ofVirtual().name("notebook-cell-execution").start(() -> {
            LocalNotebookKernel session = reusable;
            try {
                if (session == null) {
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                    while (retired.stream().anyMatch(LocalNotebookKernel::hasOwnedProcesses)) {
                        if (System.nanoTime() > deadline) throw new java.io.IOException("The previous owned kernel has not stopped yet.");
                        Thread.sleep(25);
                    }
                    var dependencies = operation.run(interpreter.executable(), Path.of(file.getParent().getPath()), Duration.ofSeconds(20));
                    if (!dependencies.ready()) throw new java.io.IOException(dependencies.summary());
                    var next = new LocalNotebookKernel(interpreter.executable(), Path.of(file.getParent().getPath()));
                    session = next;
                    if (!onEdt(() -> {
                        if (!current(expected, id, source, interpreter.executable())) return false;
                        kernel = next;
                        ownedKernels.add(next);
                        kernelPython = interpreter.executable();
                        return true;
                    })) {
                        next.close(); return;
                    }
                    next.start();
                }
                if (id == null) {
                    onEdt(() -> {
                        if (generation != expected) return false;
                        status.accept("Local Python kernel restarted. No cells were executed.");
                        return true;
                    });
                    return;
                }
                if (!onEdt(() -> {
                    if (!current(expected, id, source, interpreter.executable())) { queue.clear(); return false; }
                    status.accept("Submitting the selected cell to local Python...");
                    return true;
                })) return;
                var result = session.execute(source, () -> onEdt(() -> {
                    if (generation != expected) return false;
                    executing = true;
                    status.accept("Running the selected cell in local Python...");
                    return true;
                }), visible.buffer::set, prompt -> onEdt(() -> {
                    if (!current(expected, id, source, interpreter.executable()) || stopRequested) return false;
                    flushPreview();
                    input.accept(prompt);
                    return true;
                }));
                onEdt(() -> {
                    if (!current(expected, id, source, interpreter.executable())) {
                        queue.clear();
                        status.accept("Cell results were discarded because the source, interpreter or document changed.");
                        return false;
                    }
                    boolean saved = completed.test(id, result);
                    if (!saved || !"ok".equals(result.status())) queue.clear();
                    if (saved && stopRequested) status.accept("Cell stopped. The responsive local kernel keeps its namespace.");
                    return saved;
                });
            } catch (Exception failure) {
                if (session != null) session.close();
                onEdt(() -> {
                    if (generation == expected) {
                        queue.clear();
                        status.accept("Notebook execution failed: " + failure.getMessage());
                    }
                    return false;
                });
            } finally {
                operation.close();
                onEdt(() -> {
                    if (generation == expected) {
                        previews.stop(); progress = null; clearPreview.run(); input.accept(null);
                        probe = null; busy = false; executing = false; refresh.run();
                        if (!stopRequested) startNext();
                    }
                    return true;
                });
            }
        });
    }

    private boolean current(long expected, String id, String source, Path python) {
        if (disposed || project.isDisposed() || generation != expected || !file.isValid() || !file.isWritable()) return false;
        var selected = NotebookInterpreter.resolve(project, file);
        if (!selected.available() || !python.equals(selected.executable())) return false;
        var document = model.get();
        if (document == null) return false;
        if (id == null) return true;
        try { var cell = document.cell(id); return cell.type() == CellType.CODE && source.equals(cell.source()); }
        catch (IllegalArgumentException removed) { return false; }
    }

    private static boolean pythonLanguage(com.google.gson.JsonElement language) {
        return language == null || language.isJsonNull() || language.isJsonPrimitive()
                && language.getAsJsonPrimitive().isString() && (language.getAsString().isBlank() || "python".equalsIgnoreCase(language.getAsString()));
    }

    private boolean onEdt(Supplier<Boolean> operation) {
        if (disposed || project.isDisposed()) return false;
        var result = new CompletableFuture<Boolean>();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (result.isDone()) return;
            try { result.complete(!disposed && !project.isDisposed() && operation.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        try { return result.get(30, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception failure) { result.cancel(false); return false; }
    }

    void stop() {
        if (disposed || !busy) return;
        queue.clear();
        stopRequested = true;
        input.accept(null);
        LocalNotebookKernel owned = kernel;
        if (!executing || owned == null) { shutdown("Kernel startup cancelled."); return; }
        long expected = generation;
        status.accept("Interrupting the running cell...");
        Thread.ofVirtual().name("notebook-kernel-interrupt").start(() -> {
            try { owned.interrupt(); } catch (java.io.IOException failure) { owned.abort(); }
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
            if (onEdt(() -> {
                if (generation != expected || !busy || !stopRequested) return false;
                invalidate();
                status.accept("The kernel did not stop within five seconds and was reset. Run explicitly to start another kernel.");
                refresh.run();
                return true;
            })) owned.abort();
        });
    }

    void restart() {
        if (disposed) return;
        invalidate();
        queue.add(new Target(null, ""));
        stopRequested = false;
        startNext();
    }

    void shutdown(String message) {
        invalidate();
        status.accept(message);
        refresh.run();
    }

    void documentEdited() {
        if (!busy) return;
        invalidate();
        status.accept("Running and queued cells cancelled because the notebook was edited.");
        ApplicationManager.getApplication().invokeLater(() -> { if (!disposed && !project.isDisposed()) refresh.run(); });
    }

    /** Raw/external JSON changes invalidate pending execution rather than overwriting them. */
    void invalidate() {
        generation++;
        previews.stop(); progress = null; clearPreview.run(); input.accept(null);
        busy = false;
        executing = false;
        queue.clear();
        var operation = probe;
        if (operation != null) operation.close();
        for (var owned : ownedKernels) owned.close();
        kernel = null;
    }

    @Override public void dispose() { disposed = true; invalidate(); }
}
