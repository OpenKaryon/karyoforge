package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.openkaryon.karyoforge.notebook.model.CellType;
import org.openkaryon.karyoforge.notebook.model.NotebookDocument;
import org.openkaryon.karyoforge.notebook.runtime.NotebookRuntimePreferences;

import javax.swing.*;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class NotebookExecutionIntegrationTest extends BasePlatformTestCase {
    @Override protected TempDirTestFixture createTempDirTestFixture() { return new TempDirTestFixtureImpl(); }

    public void testExplicitCellExecutionWritesOnlyMatchingResultsAndRetainsTheKernelNamespace() throws Exception {
        var model = NotebookDocument.create();
        model.insertCell(0, CellType.CODE, "value = 40\nprint('first-result')");
        model.insertCell(1, CellType.MARKDOWN, "# Preserved note");
        model.insertCell(2, CellType.CODE, "value + 2");
        var metadata = new com.google.gson.JsonObject(); metadata.addProperty("retained", true); model.setMetadata(metadata);
        var file = myFixture.addFileToProject("actual.ipynb", model.toJson()).getVirtualFile();
        String original = FileDocumentManager.getInstance().getDocument(file).getText();
        select(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            assertEquals(original, FileDocumentManager.getInstance().getDocument(file).getText());
            assertFalse(editor.hasOwnedKernelProcesses());
            button(editor, "Run cell 1").doClick();
            awaitOutputs(file, 0, "first-result");
            var saved = NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText());
            assertEquals(model.cellAt(0).source(), saved.cellAt(0).source());
            assertTrue(saved.metadata().get("retained").getAsBoolean());
            assertEquals(Integer.valueOf(1), saved.cellAt(0).executionCount());
            assertTrue(saved.cellAt(2).outputs().isEmpty());
            PlatformTestUtil.waitWithEventsDispatching("The cell Run action becomes available again", () -> button(editor, "Run cell 3").isEnabled(), 5);
            button(editor, "Run cell 3").doClick();
            awaitOutputs(file, 2, "42");
            saved = NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText());
            assertEquals(Integer.valueOf(2), saved.cellAt(2).executionCount());
            assertEquals("# Preserved note", saved.cellAt(1).source());
            FileDocumentManager.getInstance().saveAllDocuments();
            assertTrue(Files.readString(Path.of(file.getPath())).contains("execute_result"));
        } finally { stop(editor); }
    }

    public void testRawJsonReplacementInvalidatesAnActiveResultAndClosureReleasesOwnedProcesses() throws Exception {
        var model = NotebookDocument.create();
        model.insertCell(0, CellType.CODE, "import time\nprint('old-request')\ntime.sleep(60)");
        var file = myFixture.addFileToProject("stale.ipynb", model.toJson()).getVirtualFile();
        select(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            button(editor, "Run cell 1").doClick();
            PlatformTestUtil.waitWithEventsDispatching("The owned kernel starts", editor::hasOwnedKernelProcesses, 45);
            String replacement = NotebookDocument.create().toJson();
            var backing = FileDocumentManager.getInstance().getDocument(file);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> backing.setText(replacement));
            PlatformTestUtil.waitWithEventsDispatching("External JSON changes stop the invalidated kernel", () -> !editor.hasOwnedKernelProcesses(), 12);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(replacement, backing.getText());
        } finally { stop(editor); }
    }

    public void testRunAllUsesNotebookOrderSkipsOtherCellsAndStopsOnError() throws Exception {
        var model = NotebookDocument.create();
        model.insertCell(0, CellType.CODE, "value = 42\nprint('ordered-first')");
        model.insertCell(1, CellType.MARKDOWN, "# Not executable");
        model.insertCell(2, CellType.CODE, "raise ValueError('queue-error')");
        model.insertCell(3, CellType.RAW, "Not executable either");
        model.insertCell(4, CellType.CODE, "print('must-not-run')");
        var file = myFixture.addFileToProject("queue.ipynb", model.toJson()).getVirtualFile();
        select(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            textButton(editor, "Run All").doClick();
            awaitOutputs(file, 2, "ValueError");
            PlatformTestUtil.waitWithEventsDispatching("Run All stops after an error", () -> textButton(editor, "Run All").isEnabled(), 5);
            var result = NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText());
            assertEquals(Integer.valueOf(1), result.cellAt(0).executionCount());
            assertEquals(Integer.valueOf(2), result.cellAt(2).executionCount());
            assertTrue(result.cellAt(4).outputs().isEmpty());
            assertNull(result.cellAt(4).executionCount());
            assertEquals("# Not executable", result.cellAt(1).source());
        } finally { stop(editor); }
    }

    public void testStopPreservesAResponsiveNamespaceAndRestartAndShutdownResetIt() throws Exception {
        var model = NotebookDocument.create();
        model.insertCell(0, CellType.CODE, "value = 42\nprint('namespace-ready')");
        model.insertCell(1, CellType.CODE, "import time\nprint('started')\nwhile True: time.sleep(0.02)");
        model.insertCell(2, CellType.CODE, "value");
        var file = myFixture.addFileToProject("lifecycle.ipynb", model.toJson()).getVirtualFile();
        select(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            button(editor, "Run cell 1").doClick();
            awaitOutputs(file, 0, "namespace-ready");
            PlatformTestUtil.waitWithEventsDispatching("First cell completes", () -> button(editor, "Run cell 2").isEnabled(), 5);
            button(editor, "Run cell 2").doClick();
            awaitStatus(editor, "Running the selected cell");
            textButton(editor, "Stop").doClick();
            PlatformTestUtil.waitWithEventsDispatching("Interrupt finishes and releases Run", () -> button(editor, "Run cell 3").isEnabled(), 12);
            button(editor, "Run cell 3").doClick();
            awaitOutputs(file, 2, "42");
            PlatformTestUtil.waitWithEventsDispatching("Third cell completes", () -> textButton(editor, "Run All").isEnabled(), 5);
            textButton(editor, "Restart kernel").doClick();
            awaitStatus(editor, "kernel restarted");
            PlatformTestUtil.waitWithEventsDispatching("Restart leaves the kernel ready without executing code", () -> button(editor, "Run cell 3").isEnabled(), 5);
            button(editor, "Run cell 3").doClick();
            awaitOutputs(file, 2, "NameError");
            var result = NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText());
            assertEquals(Integer.valueOf(1), result.cellAt(2).executionCount());
            textButton(editor, "Shutdown kernel").doClick();
            PlatformTestUtil.waitWithEventsDispatching("Shutdown leaves no owned process", () -> !editor.hasOwnedKernelProcesses(), 12);
            assertFalse(textButton(editor, "Shutdown kernel").isEnabled());
        } finally { stop(editor); }
    }

    public void testEditingQueuedCellSourceCancelsTheCapturedQueueAndRejectsOldResults() throws Exception {
        var model = NotebookDocument.create();
        model.insertCell(0, CellType.CODE, "import time\ntime.sleep(60)\nprint('old-result')");
        model.insertCell(1, CellType.CODE, "print('old-queued-source')");
        var file = myFixture.addFileToProject("edited-queue.ipynb", model.toJson()).getVirtualFile();
        select(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            textButton(editor, "Run All").doClick();
            awaitStatus(editor, "Running the selected cell");
            var projection = java.util.Arrays.stream(com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors())
                    .filter(value -> value.getProject() == getProject() && "Code cell 2 source".equals(value.getContentComponent().getAccessibleContext().getAccessibleName()))
                    .findFirst().orElseThrow().getDocument();
            WriteCommandAction.runWriteCommandAction(getProject(), () -> projection.setText("print('new-queued-source')"));
            PlatformTestUtil.waitWithEventsDispatching("Editing invalidates running and queued work", () -> !editor.hasOwnedKernelProcesses(), 12);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            var result = NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText());
            assertEquals("print('new-queued-source')", result.cellAt(1).source());
            assertTrue(result.cellAt(0).outputs().isEmpty());
            assertTrue(result.cellAt(1).outputs().isEmpty());
        } finally { stop(editor); }
    }

    public void testStopResetsAKernelThatExplicitlyIgnoresInterrupts() throws Exception {
        var model = NotebookDocument.create();
        model.insertCell(0, CellType.CODE, "import signal, time\nfrom pathlib import Path\nvalue = 42\nsignal.signal(signal.SIGINT, signal.SIG_IGN)\nPath('ignore-signal.ready').write_text('ready')\nwhile True: time.sleep(0.02)");
        model.insertCell(1, CellType.CODE, "value");
        var file = myFixture.addFileToProject("unresponsive.ipynb", model.toJson()).getVirtualFile();
        select(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            button(editor, "Run cell 1").doClick();
            Path marker = Path.of(file.getParent().getPath()).resolve("ignore-signal.ready");
            PlatformTestUtil.waitWithEventsDispatching("The owned kernel explicitly ignores interrupts", () -> Files.isRegularFile(marker), 45);
            awaitStatus(editor, "Running the selected cell");
            textButton(editor, "Stop").doClick();
            awaitStatus(editor, "did not stop within five seconds");
            PlatformTestUtil.waitWithEventsDispatching("The unresponsive owned process is forcibly stopped", () -> !editor.hasOwnedKernelProcesses(), 12);
            button(editor, "Run cell 2").doClick();
            awaitOutputs(file, 1, "NameError");
            var result = NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText());
            assertEquals(Integer.valueOf(1), result.cellAt(1).executionCount());
        } finally { stop(editor); }
    }

    public void testRunAllStopsWhenTheCompletedResultCannotBeWritten() throws Exception {
        var model = NotebookDocument.create();
        model.insertCell(0, CellType.CODE, "from pathlib import Path\nimport time\nPath('result-running.ready').write_text('ready')\nwhile not Path('result-finish.ready').exists(): time.sleep(0.02)\nprint('completed-but-read-only')");
        model.insertCell(1, CellType.CODE, "Path('rejected-result-must-stop-queue').write_text('unexpected')");
        var file = myFixture.addFileToProject("result-write.ipynb", model.toJson()).getVirtualFile();
        select(file);
        var backing = FileDocumentManager.getInstance().getDocument(file);
        String original = backing.getText();
        Path directory = Path.of(file.getParent().getPath());
        var editor = new NotebookEditor(getProject(), file);
        try {
            textButton(editor, "Run All").doClick();
            PlatformTestUtil.waitWithEventsDispatching("The first queued cell is active", () -> Files.isRegularFile(directory.resolve("result-running.ready")), 45);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> backing.setReadOnly(true));
            assertTrue(file.isWritable());
            Files.writeString(directory.resolve("result-finish.ready"), "finish");
            PlatformTestUtil.waitWithEventsDispatching("The rejected completed result finishes the queued run", () -> !textButton(editor, "Stop").isEnabled(), 15);
            assertEquals(original, backing.getText());
            assertFalse("A rejected result must stop the remaining queued code", Files.exists(directory.resolve("rejected-result-must-stop-queue")));
            assertTrue(labels(editor.getComponent()).contains("read-only"));
        } finally {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> backing.setReadOnly(false));
            stop(editor);
        }
    }

    private static void awaitStatus(NotebookEditor editor, String expected) {
        PlatformTestUtil.waitWithEventsDispatching("Kernel status: " + expected, () -> labels(editor.getComponent()).contains(expected), 60);
    }

    private static String labels(Container root) {
        StringBuilder result = new StringBuilder();
        for (var child : root.getComponents()) {
            if (child instanceof JLabel label) result.append(label.getText());
            if (child instanceof Container container) result.append(labels(container));
        }
        return result.toString();
    }

    private static JButton textButton(NotebookEditor editor, String text) {
        return buttons(editor.getComponent()).stream().filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private void select(com.intellij.openapi.vfs.VirtualFile file) {
        String selected = System.getProperty("karyoforge.notebook.python", "");
        assertFalse("Choose notebookPythonExecutable", selected.isBlank());
        getProject().getService(NotebookRuntimePreferences.class).select(file.getUrl(), selected);
    }

    private static void stop(NotebookEditor editor) {
        Disposer.dispose(editor);
        PlatformTestUtil.waitWithEventsDispatching("Disposal stops this editor's owned kernel", () -> !editor.hasOwnedKernelProcesses(), 12);
    }

    private static void awaitOutputs(com.intellij.openapi.vfs.VirtualFile file, int index, String text) {
        PlatformTestUtil.waitWithEventsDispatching("Matching execution output reaches the backing notebook document", () -> {
            try {
                return NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText()).cellAt(index).outputs().toString().contains(text);
            } catch (Exception notReady) { return false; }
        }, 60);
    }

    private static JButton button(NotebookEditor editor, String name) {
        return buttons(editor.getComponent()).stream().filter(button -> name.equals(button.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
    }

    private static List<JButton> buttons(Container root) {
        var result = new java.util.ArrayList<JButton>();
        for (var child : root.getComponents()) {
            if (child instanceof JButton button) result.add(button);
            if (child instanceof Container container) result.addAll(buttons(container));
        }
        return result;
    }
}
