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
    private final BiConsumer<String, LocalNotebookKernel.Execution> completed;
    private volatile LocalNotebookKernel kernel;
    private volatile NotebookDependencyCheck probe;
    private Path kernelPython;
    private long generation;
    private boolean busy;
    private volatile boolean disposed;

    NotebookKernelController(Project project, VirtualFile file, Supplier<NotebookDocument> model,
                             Consumer<String> status, Runnable refresh,
                             BiConsumer<String, LocalNotebookKernel.Execution> completed) {
        this.project = project;
        this.file = file;
        this.model = model;
        this.status = status;
        this.refresh = refresh;
        this.completed = completed;
    }

    boolean isBusy() { return busy; }
    boolean hasOwnedProcesses() { return kernel != null && kernel.hasOwnedProcesses(); }

    void run(String id) {
        if (disposed || busy) return;
        NotebookDocument document = model.get();
        if (document == null) return;
        var cell = document.cell(id);
        if (cell.type() != CellType.CODE) return;
        String source = cell.source();
        var metadata = document.metadata();
        var language = metadata.has("language_info") && metadata.get("language_info").isJsonObject()
                ? metadata.getAsJsonObject("language_info").get("name") : null;
        var specLanguage = metadata.has("kernelspec") && metadata.get("kernelspec").isJsonObject()
                ? metadata.getAsJsonObject("kernelspec").get("language") : null;
        if (!pythonLanguage(language) || !pythonLanguage(specLanguage)) {
            status.accept("Only local Python notebooks can execute. Notebook language metadata was preserved.");
            return;
        }
        var interpreter = NotebookInterpreter.resolve(project, file);
        if (!interpreter.available()) { status.accept(interpreter.problem()); return; }
        if (file.getParent() == null || !file.isInLocalFileSystem()) {
            status.accept("Kernel execution requires a local notebook directory."); return;
        }
        busy = true;
        long expected = ++generation;
        status.accept("Starting local Python for the selected cell...");
        refresh.run();
        LocalNotebookKernel existing = kernel;
        if (existing != null && (existing.isClosed() || !interpreter.executable().equals(kernelPython))) {
            existing.close();
            existing = null;
        }
        LocalNotebookKernel reusable = existing;
        var operation = new NotebookDependencyCheck();
        probe = operation;
        Thread.ofVirtual().name("notebook-cell-execution").start(() -> {
            LocalNotebookKernel session = reusable;
            try {
                if (session == null) {
                    var dependencies = operation.run(interpreter.executable(), Path.of(file.getParent().getPath()), Duration.ofSeconds(20));
                    if (!dependencies.ready()) throw new java.io.IOException(dependencies.summary());
                    var next = new LocalNotebookKernel(interpreter.executable(), Path.of(file.getParent().getPath()));
                    session = next;
                    if (!onEdt(() -> {
                        if (!current(expected, id, source, interpreter.executable())) return false;
                        kernel = next;
                        kernelPython = interpreter.executable();
                        return true;
                    })) {
                        next.close(); return;
                    }
                    next.start();
                }
                if (!onEdt(() -> {
                    if (!current(expected, id, source, interpreter.executable())) return false;
                    status.accept("Running the selected cell in local Python...");
                    return true;
                })) return;
                var result = session.execute(source);
                onEdt(() -> {
                    if (!current(expected, id, source, interpreter.executable())) {
                        status.accept("Cell results were discarded because the source, interpreter or document changed.");
                        return false;
                    }
                    completed.accept(id, result);
                    return true;
                });
            } catch (Exception failure) {
                if (session != null) session.close();
                onEdt(() -> {
                    if (generation == expected) status.accept("Notebook execution failed: " + failure.getMessage());
                    return false;
                });
            } finally {
                operation.close();
                onEdt(() -> {
                    if (generation == expected) { probe = null; busy = false; refresh.run(); }
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

    /** Raw/external JSON changes invalidate pending execution rather than overwriting them. */
    void invalidate() {
        generation++;
        busy = false;
        var operation = probe;
        if (operation != null) operation.close();
        var owned = kernel;
        if (owned != null) owned.close();
    }

    @Override public void dispose() { disposed = true; invalidate(); }
}
