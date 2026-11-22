package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.python.PythonFileType;
import org.jetbrains.annotations.NotNull;
import org.openkaryon.karyoforge.notebook.model.CellType;
import org.openkaryon.karyoforge.notebook.model.NotebookDocument;
import org.openkaryon.karyoforge.notebook.model.NotebookFormatException;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.List;

/** Passive notebook view sharing the platform's authoritative JSON Document. */
public final class NotebookEditor extends UserDataHolderBase implements FileEditor {
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    private static final int PAGE_SIZE = 20;
    private static final int MAX_SOURCE_PREVIEW = 200_000;
    private final Project project;
    private final VirtualFile file;
    private final Document document;
    private final JPanel component = new JPanel(new BorderLayout());
    private final JPanel cells = new JPanel();
    private final JLabel status = new JLabel();
    private final JLabel pageLabel = new JLabel();
    private final JButton previous = new JButton("Previous cells");
    private final JButton next = new JButton("Next cells");
    private final List<Editor> sourceEditors = new ArrayList<>();
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private NotebookDocument notebook;
    private int pageStart;
    private long generation;
    private boolean dirty = true;
    private boolean selected = true;
    private boolean disposed;

    public NotebookEditor(Project project, VirtualFile file) {
        this.project = project;
        this.file = file;
        document = file.getLength() > MAX_FILE_BYTES ? null : FileDocumentManager.getInstance().getDocument(file);
        cells.setLayout(new BoxLayout(cells, BoxLayout.Y_AXIS));
        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING));
        toolbar.add(previous);
        toolbar.add(pageLabel);
        toolbar.add(next);
        toolbar.add(new JLabel("Edit JSON in the Text view. Opening a notebook starts no kernel."));
        previous.addActionListener(event -> { pageStart = Math.max(0, pageStart - PAGE_SIZE); render(); });
        next.addActionListener(event -> { pageStart += PAGE_SIZE; render(); });
        component.add(toolbar, BorderLayout.NORTH);
        component.add(new JScrollPane(cells), BorderLayout.CENTER);
        component.add(status, BorderLayout.SOUTH);
        component.getAccessibleContext().setAccessibleName("KaryoForge notebook");
        if (document != null) {
            document.addDocumentListener(new DocumentListener() {
                @Override public void documentChanged(@NotNull DocumentEvent event) {
                    dirty = true;
                    long expected = ++generation;
                    changes.firePropertyChange(FileEditor.getPropModified(), null, isModified());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!disposed && !project.isDisposed() && selected && generation == expected) reload();
                    });
                }
            }, this);
        }
        reload();
    }

    private void reload() {
        if (disposed) return;
        dirty = false;
        notebook = null;
        if (document == null || FileDocumentManager.getInstance().isPartialPreviewOfALargeFile(document)) {
            showError("Notebook preview exceeds its 32 MiB file bound. Use the native Text editor to inspect the source.");
            return;
        }
        try {
            notebook = NotebookDocument.parse(document.getText());
            status.setText("Saved outputs are passive. Sources and metadata remain in the shared JSON document.");
            render();
        } catch (NotebookFormatException failure) {
            showError(failure.getMessage() + " Open the Text view to inspect or repair the original JSON.");
        }
    }

    private void render() {
        clearSourceEditors();
        cells.removeAll();
        if (notebook == null) return;
        int size = notebook.cells().size();
        pageStart = size == 0 ? 0 : Math.min(pageStart, (size - 1) / PAGE_SIZE * PAGE_SIZE);
        int end = Math.min(size, pageStart + PAGE_SIZE);
        previous.setEnabled(pageStart > 0);
        next.setEnabled(end < size);
        pageLabel.setText(size == 0 ? "No cells" : "Cells " + (pageStart + 1) + "–" + end + " of " + size);
        var outputs = new NotebookOutputPreview();
        for (int index = pageStart; index < end; index++) {
            NotebookDocument.Cell cell = notebook.cellAt(index);
            var panel = new JPanel(new BorderLayout());
            String kind = switch (cell.type()) {
                case CODE -> "Code";
                case MARKDOWN -> "Markdown";
                case RAW -> "Raw";
                case UNKNOWN -> "Unsupported cell type";
            };
            String count = cell.type() == CellType.CODE ? " · In [" + (cell.executionCount() == null ? " " : cell.executionCount()) + "]" : "";
            panel.setBorder(BorderFactory.createTitledBorder("Cell " + (index + 1) + " · " + kind + count));
            if (cell.type() == CellType.UNKNOWN) {
                panel.add(new JLabel("This cell is preserved read-only. Inspect its data in the Text view."), BorderLayout.CENTER);
            } else {
                String source = cell.source();
                boolean truncated = source.length() > MAX_SOURCE_PREVIEW;
                if (truncated) source = source.substring(0, MAX_SOURCE_PREVIEW) + "\n[Source preview limited; full source remains in JSON.]";
                var type = switch (cell.type()) {
                    case CODE -> PythonFileType.INSTANCE;
                    case MARKDOWN -> FileTypeManager.getInstance().getFileTypeByExtension("md");
                    default -> PlainTextFileType.INSTANCE;
                };
                Editor viewer = EditorFactory.getInstance().createEditor(
                        EditorFactory.getInstance().createDocument(source), project, type, true);
                sourceEditors.add(viewer);
                viewer.getSettings().setLineNumbersShown(true);
                viewer.getSettings().setAdditionalLinesCount(0);
                viewer.getComponent().setPreferredSize(new Dimension(720,
                        Math.min(320, Math.max(80, viewer.getLineHeight() * Math.min(15, viewer.getDocument().getLineCount()) + 20))));
                viewer.getContentComponent().getAccessibleContext().setAccessibleName(kind + " cell " + (index + 1) + " source");
                panel.add(viewer.getComponent(), BorderLayout.CENTER);
                if (cell.type() == CellType.CODE && !cell.outputs().isEmpty()) {
                    var saved = outputs.create(cell.outputs());
                    var scroll = new JScrollPane(saved);
                    scroll.setPreferredSize(new Dimension(720, Math.min(300, Math.max(40, saved.getPreferredSize().height + 8))));
                    panel.add(scroll, BorderLayout.SOUTH);
                }
            }
            cells.add(panel);
        }
        if (size == 0) cells.add(new JLabel("This notebook has no cells. The Text view contains its complete JSON document."));
        cells.revalidate();
        cells.repaint();
    }

    private void showError(String message) {
        clearSourceEditors();
        cells.removeAll();
        previous.setEnabled(false);
        next.setEnabled(false);
        pageLabel.setText("Notebook source retained");
        var text = new JTextArea(message);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.getAccessibleContext().setAccessibleName("Notebook source error");
        cells.add(text);
        status.setText("No notebook content was replaced.");
        cells.revalidate();
        cells.repaint();
    }

    private void clearSourceEditors() {
        for (Editor editor : sourceEditors) EditorFactory.getInstance().releaseEditor(editor);
        sourceEditors.clear();
    }

    @Override public @NotNull JComponent getComponent() { return component; }
    @Override public JComponent getPreferredFocusedComponent() {
        return sourceEditors.isEmpty() ? cells : sourceEditors.getFirst().getContentComponent();
    }
    @Override public @NotNull String getName() { return "Notebook"; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public boolean isModified() { return document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document); }
    @Override public boolean isValid() { return !disposed && file.isValid(); }
    @Override public VirtualFile getFile() { return file; }
    @Override public void selectNotify() { selected = true; if (dirty) reload(); }
    @Override public void deselectNotify() { selected = false; }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) { changes.addPropertyChangeListener(listener); }
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) { changes.removePropertyChangeListener(listener); }
    @Override public void dispose() {
        disposed = true;
        generation++;
        clearSourceEditors();
        changes.firePropertyChange(FileEditor.getPropValid(), true, false);
    }
}
