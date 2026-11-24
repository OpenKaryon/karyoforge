package org.openkaryon.karyoforge.data.preview;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.FileEditorManagerTestCase;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.openkaryon.karyoforge.biology.preview.BiologyPreviewProvider;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.stream.Stream;

public final class PreviewEditorIntegrationTest extends FileEditorManagerTestCase {
    private static LightProjectDescriptor descriptor;

    @Override protected LightProjectDescriptor getProjectDescriptor() {
        if (descriptor == null) {
            LightProjectDescriptor nativeDescriptor = super.getProjectDescriptor();
            descriptor = new LightProjectDescriptor() {
                @Override public OpenProjectTask getOpenProjectOptions() { return nativeDescriptor.getOpenProjectOptions(); }
            };
        }
        return descriptor;
    }

    @Override protected TempDirTestFixture createTempDirTestFixture() { return new TempDirTestFixtureImpl(); }

    public void testRegisteredViewerAndNativeTextEditorShareAnOpenedCsv() throws Exception {
        var file = myFixture.getTempDirFixture().createFile("actual.csv", "id,metric\n01,0005\n");
        var manager = FileEditorManager.getInstance(getProject());
        try {
            var editors = manager.openFile(file, true);
            var preview = Arrays.stream(editors).filter(PreviewEditor.class::isInstance)
                    .map(PreviewEditor.class::cast).findFirst().orElseThrow();
            var text = Arrays.stream(editors).filter(TextEditor.class::isInstance)
                    .map(TextEditor.class::cast).findFirst().orElseThrow();
            if (manager.getSelectedEditor(file) != preview) manager.setSelectedEditor(file, DelimitedPreviewProvider.ID);
            var table = (JTable) preview.getPreferredFocusedComponent();
            PlatformTestUtil.waitWithEventsDispatching("Preview displays the saved record", () -> table.getRowCount() == 1, 20);
            assertEquals("0005", table.getValueAt(0, 1));
            assertFalse(table.isCellEditable(0, 1));
            assertEquals("id,metric\n01,0005\n", text.getEditor().getDocument().getText());
            assertEquals("id,metric\n01,0005\n", Files.readString(file.toNioPath()));
        } finally { manager.closeFile(file); }
    }

    public void testPreviewDoesNotCreateADocumentAndRequiresReloadAfterAnEdit() throws Exception {
        String original = "id\tvalue\n" + "old\t001\n".repeat(201);
        var file = myFixture.getTempDirFixture().createFile("changes.tsv", original);
        var provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getExtensionList().stream()
                .filter(DelimitedPreviewProvider.class::isInstance).findFirst().orElseThrow();
        var editor = (PreviewEditor) provider.createEditor(getProject(), file);
        try {
            var manager = FileDocumentManager.getInstance();
            assertNull(manager.getCachedDocument(file));
            editor.selectNotify();
            var table = (JTable) editor.getPreferredFocusedComponent();
            PlatformTestUtil.waitWithEventsDispatching("First page loaded", () -> table.getRowCount() == 200, 20);
            assertFalse(UndoManager.getInstance(getProject()).isUndoAvailable(editor));
            assertNull(manager.getCachedDocument(file));
            var document = manager.getDocument(file);
            assertNotNull(document);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("id\tvalue\nnew\t002\n"));
            button(editor, "Next page").doClick();
            assertTrue(status(editor).contains("Save the Text editor"));
            assertEquals(original, Files.readString(file.toNioPath()));
            manager.saveDocument(document);
            button(editor, "Next page").doClick();
            PlatformTestUtil.waitWithEventsDispatching("Paging rejects the edited version", () -> status(editor).contains("source file changed"), 20);
            assertEquals("old", table.getValueAt(0, 0));
            button(editor, "Reload preview").doClick();
            PlatformTestUtil.waitWithEventsDispatching("Reload shows the current saved record", () -> table.getRowCount() == 1, 20);
            assertEquals("new", table.getValueAt(0, 0));
        } finally { Disposer.dispose(editor); }
    }

    public void testStoppedAndDisposedReadsCannotPublishAStalePage() throws Exception {
        String key = "intellij.progress.task.ignoreHeadless";
        String previous = System.getProperty(key);
        System.setProperty(key, "true");
        try {
            var file = myFixture.getTempDirFixture().createFile("stop.csv", "id,value\n" + "012,x\n".repeat(201));
            var editor = (PreviewEditor) new DelimitedPreviewProvider().createEditor(getProject(), file);
            try {
                var table = (JTable) editor.getPreferredFocusedComponent();
                editor.selectNotify();
                assertTrue(button(editor, "Stop reading").isEnabled());
                button(editor, "Stop reading").doClick(0);
                assertEquals("Reading stopped.", status(editor));
                assertEquals(0, table.getRowCount());
                button(editor, "Reload preview").doClick(0);
                PlatformTestUtil.waitWithEventsDispatching("Replacement read completes", () -> table.getRowCount() == 200, 20);
                button(editor, "Next page").doClick(0);
                Disposer.dispose(editor);
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                assertFalse(editor.isValid());
                assertEquals(200, table.getRowCount());
            } finally {
                if (editor.isValid()) Disposer.dispose(editor);
            }
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    public void testVcfOffersMetadataWithoutReplacingNativeTextOrClaimingCompressedFiles() throws Exception {
        String header = "##fileformat=VCFv4.3\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
        var file = myFixture.getTempDirFixture().createFile("native.vcf", header + "chr1\t17\t.\tA\tC\t.\tPASS\t.\n");
        var compressed = myFixture.getTempDirFixture().createFile("not-plain.vcf.gz", "");
        var provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getExtensionList().stream()
                .filter(BiologyPreviewProvider.class::isInstance).findFirst().orElseThrow();
        assertFalse(provider.accept(getProject(), compressed));
        var manager = FileEditorManager.getInstance(getProject());
        try {
            var editors = manager.openFile(file, true);
            assertTrue(Arrays.stream(editors).anyMatch(TextEditor.class::isInstance));
            var preview = Arrays.stream(editors).filter(PreviewEditor.class::isInstance)
                    .map(PreviewEditor.class::cast).findFirst().orElseThrow();
            if (manager.getSelectedEditor(file) != preview) manager.setSelectedEditor(file, BiologyPreviewProvider.ID);
            var table = (JTable) preview.getPreferredFocusedComponent();
            PlatformTestUtil.waitWithEventsDispatching("VCF appears in the native preview", () -> table.getRowCount() == 1, 20);
            assertEquals("17", table.getValueAt(0, 1));
            button(preview, "Metadata / headers").doClick();
            assertTrue(button(preview, "Record details").isVisible());
            assertEquals(header + "chr1\t17\t.\tA\tC\t.\tPASS\t.\n", Files.readString(file.toNioPath()));
        } finally { manager.closeFile(file); }
    }

    private static JButton button(PreviewEditor editor, String name) {
        return find(editor.getComponent(), JButton.class).filter(button -> name.equals(button.getText())).findFirst().orElseThrow();
    }

    private static String status(PreviewEditor editor) {
        return find(editor.getComponent(), JLabel.class).filter(label -> "Preview status".equals(label.getAccessibleContext().getAccessibleName()))
                .findFirst().orElseThrow().getText();
    }

    private static <T extends Component> Stream<T> find(Component component, Class<T> type) {
        Stream<T> current = type.isInstance(component) ? Stream.of(type.cast(component)) : Stream.empty();
        if (component instanceof Container parent) {
            return Stream.concat(current, Arrays.stream(parent.getComponents()).flatMap(child -> find(child, type)));
        }
        return current;
    }
}
