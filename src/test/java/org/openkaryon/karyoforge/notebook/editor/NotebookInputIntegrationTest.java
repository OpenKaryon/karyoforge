package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.EditorFactory;
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
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class NotebookInputIntegrationTest extends BasePlatformTestCase {
    @Override protected TempDirTestFixture createTempDirTestFixture() { return new TempDirTestFixtureImpl(); }

    public void testInputAndPasswordAreTransientAndNotEchoedIntoTheSavedNotebook() throws Exception {
        var file = fixture("prompts.ipynb", "name=input('Literal <b>name</b>: ')\nimport getpass\nsecret=getpass.getpass('Password: ')\nprint('lengths',len(name),len(secret))");
        var backing = FileDocumentManager.getInstance().getDocument(file);
        String original = backing.getText();
        var editor = new NotebookEditor(getProject(), file);
        try {
            button(editor, "Run cell").doClick();
            await(() -> prompt(editor).isShowing() || prompt(editor).isVisible() && prompt(editor).getText().contains("Literal"));
            var value = values(editor).getFirst();
            assertEquals((char) 0, value.getEchoChar());
            value.setText("alpha");
            button(editor, "Submit input").doClick();
            await(() -> prompt(editor).getText().contains("Password:") && inputVisible(editor));
            assertTrue(value.getEchoChar() != 0);
            assertEquals(original, backing.getText());
            String synthetic = "synthetic-notebook-secret";
            value.setText(synthetic);
            button(editor, "Submit input").doClick();
            await(() -> outputs(file).contains("lengths 5 " + synthetic.length()));
            assertEquals(0, value.getPassword().length);
            assertFalse(backing.getText().contains(synthetic));
            assertFalse(outputs(file).contains("alpha"));
            FileDocumentManager.getInstance().saveDocument(backing);
            assertFalse(Files.readString(Path.of(file.getPath())).contains(synthetic));
        } finally { stop(editor); }
    }

    public void testLivePreviewsKeepEditorsAndOneCompletedResultUsesNativeUndoWithoutReexecution() throws Exception {
        var file = fixture("live.ipynb", "import time\nfrom pathlib import Path\nprint('live-first',flush=True)\nwhile not Path('finish.flag').exists(): time.sleep(0.02)\nfrom IPython.display import display,update_display\ndisplay({'text/plain':'first','text/html':'<script>literal only</script>'},raw=True,display_id='same-cell')\nupdate_display({'text/plain':'updated','text/html':'<b>static only</b>'},raw=True,display_id='same-cell')\nprint('live-last')");
        var backing = FileDocumentManager.getInstance().getDocument(file);
        String original = backing.getText();
        var editor = new NotebookEditor(getProject(), file);
        try {
            var source = java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                    .filter(item -> item.getProject() == getProject() && "Code cell 1 source".equals(item.getContentComponent().getAccessibleContext().getAccessibleName()))
                    .findFirst().orElseThrow();
            button(editor, "Run cell").doClick();
            await(() -> components(editor.getComponent(), JTextArea.class).stream().anyMatch(area -> area.getText().contains("live-first")));
            assertEquals(original, backing.getText());
            assertTrue(java.util.Arrays.asList(EditorFactory.getInstance().getAllEditors()).contains(source));
            Files.writeString(Path.of(file.getParent().getPath()).resolve("finish.flag"), "finish");
            await(() -> outputs(file).contains("live-last") && button(editor, "Run cell").isEnabled());
            String completed = backing.getText();
            assertTrue(outputs(file).contains("updated"));
            assertTrue(outputs(file).contains("<b>static only</b>"));
            assertFalse(outputs(file).contains("literal only"));
            assertTrue(java.util.Arrays.asList(EditorFactory.getInstance().getAllEditors()).contains(source));
            var history = UndoManager.getInstance(getProject());
            assertTrue(history.isUndoAvailable(editor));
            history.undo(editor);
            await(() -> !editor.hasOwnedKernelProcesses());
            assertEquals(original, backing.getText());
            history.redo(editor);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(completed, backing.getText());
            assertFalse(editor.hasOwnedKernelProcesses());
        } finally { stop(editor); }
    }

    public void testCancelInputStopsTheQueueAndClearsTransientFields() throws Exception {
        var model = NotebookDocument.create();
        model.insertCell(0, CellType.CODE, "input('Waiting for input: ')");
        model.insertCell(1, CellType.CODE, "print('must-not-run')");
        var file = myFixture.addFileToProject("cancel.ipynb", model.toJson()).getVirtualFile();
        select(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            button(editor, "Run All").doClick();
            await(() -> prompt(editor).getText().contains("Waiting") && inputVisible(editor));
            values(editor).getFirst().setText("discarded-input");
            button(editor, "Cancel input").doClick();
            await(() -> button(editor, "Run All").isEnabled());
            assertFalse(inputVisible(editor));
            assertEquals(0, values(editor).getFirst().getPassword().length);
            var result = NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText());
            assertTrue(result.cellAt(1).outputs().isEmpty());
            assertFalse(result.toJson().contains("discarded-input"));
        } finally { stop(editor); }
    }

    private com.intellij.openapi.vfs.VirtualFile fixture(String name, String source) throws Exception {
        var model = NotebookDocument.create(); model.insertCell(0, CellType.CODE, source);
        var file = myFixture.addFileToProject(name, model.toJson()).getVirtualFile(); select(file); return file;
    }
    private void select(com.intellij.openapi.vfs.VirtualFile file) {
        String python = System.getProperty("karyoforge.notebook.python", "");
        assertFalse("Choose notebookPythonExecutable", python.isBlank());
        getProject().getService(NotebookRuntimePreferences.class).select(file.getUrl(), python);
    }
    private static String outputs(com.intellij.openapi.vfs.VirtualFile file) {
        try { return NotebookDocument.parse(FileDocumentManager.getInstance().getDocument(file).getText()).cellAt(0).outputs().toString(); }
        catch (Exception pending) { return ""; }
    }
    private static void stop(NotebookEditor editor) { Disposer.dispose(editor); await(() -> !editor.hasOwnedKernelProcesses()); }
    private static void await(BooleanSupplier condition) { PlatformTestUtil.waitWithEventsDispatching("Notebook input/live output condition", condition, 60); }
    private static boolean inputVisible(NotebookEditor editor) { return components(editor.getComponent(), NotebookInputPanel.class).getFirst().isVisible(); }
    private static JTextArea prompt(NotebookEditor editor) { return components(editor.getComponent(), JTextArea.class).stream().filter(area -> "Notebook input prompt".equals(area.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow(); }
    private static List<JPasswordField> values(NotebookEditor editor) { return components(editor.getComponent(), JPasswordField.class); }
    private static JButton button(NotebookEditor editor, String text) { return components(editor.getComponent(), JButton.class).stream().filter(button -> text.equals(button.getText())).findFirst().orElseThrow(); }
    private static <T> List<T> components(Container root, Class<T> type) {
        var result = new ArrayList<T>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container container) result.addAll(components(container, type));
        }
        return result;
    }
}
