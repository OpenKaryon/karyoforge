package org.openkaryon.karyoforge.notebook.editor;

import com.google.gson.JsonParser;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.openkaryon.karyoforge.notebook.model.CellType;
import org.openkaryon.karyoforge.notebook.model.NotebookDocument;

import javax.swing.*;

public final class NotebookCellEditingIntegrationTest extends BasePlatformTestCase {
    private static final String ORIGINAL = """
            {"nbformat":4,"nbformat_minor":0,"metadata":{"custom":{"number":1.2300e+02}},"cells":[
              {"cell_type":"code","metadata":{"tags":["retained"]},"source":["value = 1\\n","print(value)"],
               "execution_count":7,"outputs":[{"output_type":"display_data","metadata":{},
               "data":{"text/plain":"old result","application/vnd.custom+json":{"kept":true}}}]}]}
            """;

    public void testCellTypingSavesThroughTheBackingDocumentAndNativeUndoRedo() throws Exception {
        VirtualFile file = fixture("editing.ipynb", ORIGINAL);
        Document backing = FileDocumentManager.getInstance().getDocument(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            Editor source = sourceEditor(backing, "value = 1\nprint(value)");
            assertFalse(source.isViewer());
            WriteCommandAction.runWriteCommandAction(getProject(), "First cell change", "notebook-first-change",
                    () -> source.getDocument().setText("value = 42\nprint(value)"));
            String stableId = model(backing).cellAt(0).id();
            WriteCommandAction.runWriteCommandAction(getProject(), "Second cell change", "notebook-second-change",
                    () -> source.getDocument().setText("value = 43\nprint(value)"));
            assertEquals("value = 43\nprint(value)", model(backing).cell(stableId).source());
            assertEquals(5, model(backing).formatMinor());
            assertEquals(1, model(backing).cell(stableId).outputs().size());
            assertEquals("1.2300e+02", model(backing).metadata().getAsJsonObject("custom").get("number").getAsString());
            assertTrue(editor.isModified());
            FileDocumentManager.getInstance().saveDocument(backing);
            assertFalse(editor.isModified());
            assertEquals("value = 43\nprint(value)", NotebookDocument.parse(new String(file.contentsToByteArray(), file.getCharset())).cell(stableId).source());
            var undo = UndoManager.getInstance(getProject());
            assertTrue(undo.isUndoAvailable(editor));
            undo.undo(editor);
            events();
            assertEquals("value = 42\nprint(value)", model(backing).cell(stableId).source());
            undo.undo(editor);
            events();
            assertEquals(ORIGINAL, backing.getText());
            undo.redo(editor);
            events();
            assertEquals("value = 42\nprint(value)", model(backing).cell(stableId).source());
            assertEquals("value = 42\nprint(value)", sourceEditor(backing, "value = 42\nprint(value)").getDocument().getText());
        } finally { Disposer.dispose(editor); }
    }

    public void testStructuralActionsPreservePayloadAndRejectedConversionIsAtomic() throws Exception {
        VirtualFile file = fixture("operations.ipynb", ORIGINAL);
        Document backing = FileDocumentManager.getInstance().getDocument(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            type(editor, "Cell 1 type").setSelectedItem("Markdown");
            events();
            assertEquals(ORIGINAL, backing.getText());
            assertTrue(NotebookEditorIntegrationTest.components(editor.getComponent(), JLabel.class).stream()
                    .anyMatch(label -> label.getText() != null && label.getText().contains("Clear this cell")));
            button(editor, "Clear cell 1 outputs").doClick();
            assertTrue(model(backing).cellAt(0).outputs().isEmpty());
            UndoManager.getInstance(getProject()).undo(editor);
            events();
            assertEquals(ORIGINAL, backing.getText());
            button(editor, "Clear cell 1 outputs").doClick();
            type(editor, "Cell 1 type").setSelectedItem("Markdown");
            assertEquals(CellType.MARKDOWN, model(backing).cellAt(0).type());
            assertEquals("retained", model(backing).cellAt(0).metadata().getAsJsonArray("tags").get(0).getAsString());
            NotebookEditorIntegrationTest.components(editor.getComponent(), JButton.class).stream()
                    .filter(button -> button.getText().equals("Add Raw")).findFirst().orElseThrow().doClick();
            assertEquals(2, model(backing).cells().size());
            button(editor, "Move cell 2 up").doClick();
            assertEquals(CellType.RAW, model(backing).cellAt(0).type());
            button(editor, "Delete cell 1").doClick();
            assertEquals(1, model(backing).cells().size());
            assertEquals(CellType.MARKDOWN, model(backing).cellAt(0).type());
            UndoManager.getInstance(getProject()).undo(editor);
            events();
            assertEquals(2, model(backing).cells().size());
            assertEquals(CellType.RAW, model(backing).cellAt(0).type());
        } finally { Disposer.dispose(editor); }
    }

    public void testReadOnlyFilesAndMetadataLocksKeepTheirSourceAndFields() throws Exception {
        var json = JsonParser.parseString(ORIGINAL).getAsJsonObject();
        json.getAsJsonArray("cells").get(0).getAsJsonObject().getAsJsonObject("metadata").addProperty("editable", false);
        VirtualFile file = fixture("locked.ipynb", json.toString());
        Document backing = FileDocumentManager.getInstance().getDocument(file);
        String original = backing.getText();
        var editor = new NotebookEditor(getProject(), file);
        try {
            assertTrue(sourceEditor(backing, "value = 1\nprint(value)").isViewer());
            assertFalse(type(editor, "Cell 1 type").isEnabled());
            assertFalse(button(editor, "Delete cell 1").isEnabled());
            button(editor, "Delete cell 1").doClick();
            assertEquals(original, backing.getText());
        } finally { Disposer.dispose(editor); }
        backing.setReadOnly(true);
        editor = new NotebookEditor(getProject(), file);
        try {
            assertTrue(NotebookEditorIntegrationTest.components(editor.getComponent(), JButton.class).stream()
                    .filter(button -> button.getText().startsWith("Add ")).noneMatch(JButton::isEnabled));
            assertTrue(sourceEditor(backing, "value = 1\nprint(value)").isViewer());
            assertEquals(original, backing.getText());
        } finally { Disposer.dispose(editor); backing.setReadOnly(false); }
    }

    public void testAnExternalJsonChangeCannotBeOverwrittenByAStaleCellProjection() throws Exception {
        VirtualFile file = fixture("external.ipynb", ORIGINAL);
        Document backing = FileDocumentManager.getInstance().getDocument(file);
        var editor = new NotebookEditor(getProject(), file);
        try {
            Editor stale = sourceEditor(backing, "value = 1\nprint(value)");
            String external = ORIGINAL.replace("value = 1", "value = 99");
            WriteCommandAction.runWriteCommandAction(getProject(), "External JSON edit", "notebook-external-change", () -> {
                backing.setText(external);
                stale.getDocument().setText("stale source must not win");
            });
            events();
            assertEquals(external, backing.getText());
            assertEquals("value = 99\nprint(value)", model(backing).cellAt(0).source());
            assertEquals("value = 99\nprint(value)", sourceEditor(backing, "value = 99\nprint(value)").getDocument().getText());
        } finally { Disposer.dispose(editor); }
    }

    private VirtualFile fixture(String name, String text) {
        var file = myFixture.addFileToProject(name, text).getVirtualFile();
        FileDocumentManager.getInstance().saveDocument(FileDocumentManager.getInstance().getDocument(file));
        return file;
    }
    private Editor sourceEditor(Document backing, String source) {
        return java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                .filter(editor -> editor.getProject() == getProject() && editor.getDocument() != backing
                        && editor.getDocument().getText().equals(source)).findFirst().orElseThrow();
    }
    private static NotebookDocument model(Document document) throws Exception { return NotebookDocument.parse(document.getText()); }
    private static void events() { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); }
    private static JButton button(NotebookEditor editor, String accessibleName) {
        return NotebookEditorIntegrationTest.components(editor.getComponent(), JButton.class).stream()
                .filter(button -> accessibleName.equals(button.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
    }
    private static JComboBox<?> type(NotebookEditor editor, String accessibleName) {
        return NotebookEditorIntegrationTest.components(editor.getComponent(), JComboBox.class).stream()
                .filter(combo -> accessibleName.equals(combo.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
    }
}
