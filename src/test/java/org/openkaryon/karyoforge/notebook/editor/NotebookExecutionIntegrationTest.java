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
