package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.openkaryon.karyoforge.notebook.model.CellType;
import org.openkaryon.karyoforge.notebook.model.NotebookDocument;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class NotebookEditorIntegrationTest extends BasePlatformTestCase {
    public void testRegisteredProvidersKeepRawJsonAndTheNativeTextEditorAvailable() throws Exception {
        var provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getExtensionList().stream()
                .filter(value -> value instanceof NotebookEditorProvider).findFirst().orElseThrow();
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.getPolicy());
        assertNotNull(ActionManager.getInstance().getAction("org.openkaryon.karyoforge.notebook.new"));
        String source = sample(1);
        VirtualFile file = fixture("example.IPYNB", source);
        assertTrue(provider.accept(getProject(), file));
        assertEquals("JSON", file.getFileType().getName());
        assertEquals(source, FileDocumentManager.getInstance().getDocument(file).getText());
        assertFalse(provider.accept(getProject(), fixture("ordinary.json", "{}")));
    }

    public void testRegisteredNotebookProvidesTheNativeTextEditorContractForProjectRestore() throws Exception {
        var provider = new NotebookEditorProvider();
        VirtualFile file = fixture("restore.ipynb", sample(1));
        var backing = FileDocumentManager.getInstance().getDocument(file);
        String original = backing.getText();
        int before = EditorFactory.getInstance().getAllEditors().length;
        var editor = provider.createEditor(getProject(), file);
        try {
            assertTrue("The platform restores nonbinary files through TextEditor", editor instanceof com.intellij.openapi.fileEditor.TextEditor);
            var text = (com.intellij.openapi.fileEditor.TextEditor) editor;
            assertSame(backing, text.getEditor().getDocument());
            var state = editor.getState(com.intellij.openapi.fileEditor.FileEditorStateLevel.FULL);
            var serialized = new org.jdom.Element("state");
            provider.writeState(state, getProject(), serialized);
            editor.setState(provider.readState(serialized, getProject(), file));
            assertSame(backing, text.getEditor().getDocument());
            assertEquals(original, backing.getText());
        } finally { Disposer.dispose(editor); }
        assertEquals(before, EditorFactory.getInstance().getAllEditors().length);
        var restored = provider.createEditor(getProject(), file);
        try {
            assertTrue(restored instanceof com.intellij.openapi.fileEditor.TextEditor);
            assertSame(backing, ((com.intellij.openapi.fileEditor.TextEditor) restored).getEditor().getDocument());
            assertEquals(original, backing.getText());
        } finally { Disposer.dispose(restored); }
        assertEquals(before, EditorFactory.getInstance().getAllEditors().length);
    }

    public void testOpeningAValidNotebookDoesNotChangeSourceOrLeakOwnedViewers() throws Exception {
        NotebookDocument mixed = NotebookDocument.create();
        mixed.insertCell(0, CellType.CODE, "value = 42");
        mixed.insertCell(1, CellType.MARKDOWN, "# Saved heading");
        mixed.insertCell(2, CellType.RAW, "Raw content");
        var json = mixed.json();
        json.getAsJsonArray("cells").add(com.google.gson.JsonParser.parseString(
                "{\"id\":\"opaque\",\"cell_type\":\"future_cell\",\"metadata\":{},\"source\":{\"preserved\":true}}"));
        String source = json.toString();
        VirtualFile file = fixture("view.ipynb", source);
        byte[] original = file.contentsToByteArray();
        int before = EditorFactory.getInstance().getAllEditors().length;
        var editor = new NotebookEditor(getProject(), file);
        try {
            assertEquals(before + 3, EditorFactory.getInstance().getAllEditors().length);
            assertTrue(editor.isValid());
            assertEquals("Notebook", editor.getName());
            assertTrue(components(editor.getComponent(), JLabel.class).stream().anyMatch(label -> label.getText().contains("Cells 1–4 of 4")));
            assertEquals(source, FileDocumentManager.getInstance().getDocument(file).getText());
            assertTrue(java.util.Arrays.equals(original, file.contentsToByteArray()));
        } finally { Disposer.dispose(editor); }
        assertFalse(editor.isValid());
        assertEquals(before, EditorFactory.getInstance().getAllEditors().length);
    }

    public void testPaginationAndExternalJsonReloadDisposeReplacedViewers() throws Exception {
        VirtualFile file = fixture("pages.ipynb", sample(25));
        int before = EditorFactory.getInstance().getAllEditors().length;
        var editor = new NotebookEditor(getProject(), file);
        try {
            assertEquals(before + 20, EditorFactory.getInstance().getAllEditors().length);
            button(editor, "Next cells").doClick();
            assertEquals(before + 5, EditorFactory.getInstance().getAllEditors().length);
            assertFalse(button(editor, "Next cells").isEnabled());
            button(editor, "Previous cells").doClick();
            assertEquals(before + 20, EditorFactory.getInstance().getAllEditors().length);
            var document = FileDocumentManager.getInstance().getDocument(file);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(sample(2)));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(before + 2, EditorFactory.getInstance().getAllEditors().length);
            assertTrue(editor.isModified());
            FileDocumentManager.getInstance().saveDocument(document);
            assertFalse(editor.isModified());
        } finally { Disposer.dispose(editor); }
        assertEquals(before, EditorFactory.getInstance().getAllEditors().length);
    }

    public void testMalformedInputAndPendingReloadNeverReplaceTheRawDocument() throws Exception {
        String source = "{ broken notebook";
        VirtualFile file = fixture("broken.ipynb", source);
        int before = EditorFactory.getInstance().getAllEditors().length;
        var editor = new NotebookEditor(getProject(), file);
        try {
            assertTrue(components(editor.getComponent(), JTextArea.class).stream()
                    .anyMatch(area -> area.getText().contains("Cannot edit notebook")));
            assertEquals(source, FileDocumentManager.getInstance().getDocument(file).getText());
            editor.deselectNotify();
            var document = FileDocumentManager.getInstance().getDocument(file);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(sample(1)));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(before, EditorFactory.getInstance().getAllEditors().length);
            editor.selectNotify();
            assertEquals(before + 1, EditorFactory.getInstance().getAllEditors().length);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText(sample(2)));
        } finally { Disposer.dispose(editor); }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(before, EditorFactory.getInstance().getAllEditors().length);
    }

    public void testNewNotebookUsesExclusiveUtf8CreationAndRetainsExistingFiles() throws Exception {
        var directory = Files.createTempDirectory("karyoforge notebook ");
        var target = directory.resolve("analysis.ipynb");
        try {
            NewNotebookAction.create(target);
            String source = Files.readString(target, StandardCharsets.UTF_8);
            NotebookDocument document = NotebookDocument.parse(source);
            assertEquals(1, document.cells().size());
            assertEquals(CellType.CODE, document.cellAt(0).type());
            assertTrue(document.cellAt(0).source().isEmpty());
            assertTrue(document.cellAt(0).outputs().isEmpty());
            try { NewNotebookAction.create(target); fail("An existing notebook must not be overwritten"); }
            catch (java.nio.file.FileAlreadyExistsException expected) { assertEquals(source, Files.readString(target)); }
        } finally {
            Files.deleteIfExists(target);
            Files.deleteIfExists(directory);
        }
    }

    private VirtualFile fixture(String name, String source) {
        VirtualFile file = myFixture.addFileToProject(name, source).getVirtualFile();
        FileDocumentManager.getInstance().saveDocument(FileDocumentManager.getInstance().getDocument(file));
        return file;
    }

    private static String sample(int count) {
        NotebookDocument document = NotebookDocument.create();
        for (int index = 0; index < count; index++) document.insertCell(index, CellType.CODE, "value = " + index);
        return document.toJson();
    }

    private static JButton button(NotebookEditor editor, String text) {
        return components(editor.getComponent(), JButton.class).stream().filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    static <T> List<T> components(Component root, Class<T> type) {
        var found = new ArrayList<T>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof Container parent) for (Component child : parent.getComponents()) found.addAll(components(child, type));
        return found;
    }
}
