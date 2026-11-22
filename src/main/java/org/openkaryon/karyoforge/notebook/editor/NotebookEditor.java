package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.command.undo.UndoUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.DocumentsEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
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
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** Native cell projections commit validated edits to the authoritative JSON Document. */
public final class NotebookEditor extends UserDataHolderBase implements DocumentsEditor {
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
    private final List<Disposable> sourceLifetimes = new ArrayList<>();
    private final Map<String, Editor> cellEditors = new LinkedHashMap<>();
    private final List<JButton> editingButtons = new ArrayList<>();
    private final Map<String, NotebookMarkdownPreview> markdownPreviews = new LinkedHashMap<>();
    private NotebookDocument notebook;
    private long renderedStamp;
    private boolean committing;
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
        toolbar.add(addCellButton("Add Code", CellType.CODE));
        toolbar.add(addCellButton("Add Markdown", CellType.MARKDOWN));
        toolbar.add(addCellButton("Add Raw", CellType.RAW));
        toolbar.add(new JLabel("Ctrl+S saves. The Text view edits JSON."));
        status.getAccessibleContext().setAccessibleName("Notebook edit status");
        previous.addActionListener(event -> { pageStart = Math.max(0, pageStart - PAGE_SIZE); render(); });
        next.addActionListener(event -> { pageStart += PAGE_SIZE; render(); });
        var runtime = new org.openkaryon.karyoforge.notebook.runtime.NotebookRuntimePanel(project, file);
        Disposer.register(this, runtime);
        var header = new JPanel(new BorderLayout());
        header.add(runtime, BorderLayout.NORTH);
        header.add(toolbar, BorderLayout.SOUTH);
        component.add(header, BorderLayout.NORTH);
        component.add(new JScrollPane(cells), BorderLayout.CENTER);
        component.add(status, BorderLayout.SOUTH);
        component.getAccessibleContext().setAccessibleName("KaryoForge notebook");
        if (document != null) {
            document.addDocumentListener(new DocumentListener() {
                @Override public void documentChanged(@NotNull DocumentEvent event) {
                    changes.firePropertyChange(FileEditor.getPropModified(), null, isModified());
                    if (committing) return;
                    dirty = true;
                    long expected = ++generation;
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
        generation++;
        dirty = false;
        notebook = null;
        if (document == null || FileDocumentManager.getInstance().isPartialPreviewOfALargeFile(document)) {
            showError("Notebook preview exceeds its 32 MiB file bound. Use the native Text editor to inspect the source.");
            return;
        }
        try {
            notebook = NotebookDocument.parse(document.getText());
            renderedStamp = document.getModificationStamp();
            status.setText("Ready. Cell edits and JSON share native Save and Undo/Redo.");
            render();
        } catch (NotebookFormatException failure) {
            showError(failure.getMessage() + " Open the Text view to inspect or repair the original JSON.");
        }
    }

    private void render() {
        clearSourceEditors();
        cells.removeAll();
        updateEditingButtons();
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
            panel.add(cellControls(cell, index), BorderLayout.NORTH);
            if (cell.type() == CellType.UNKNOWN) {
                panel.add(new JLabel("This cell is preserved read-only. Inspect its data in the Text view."), BorderLayout.CENTER);
            } else {
                String source = StringUtil.convertLineSeparators(cell.source());
                boolean truncated = source.length() > MAX_SOURCE_PREVIEW;
                if (truncated) source = source.substring(0, MAX_SOURCE_PREVIEW) + "\n[Source preview limited; full source remains in JSON.]";
                var type = switch (cell.type()) {
                    case CODE -> PythonFileType.INSTANCE;
                    case MARKDOWN -> FileTypeManager.getInstance().getFileTypeByExtension("md");
                    default -> PlainTextFileType.INSTANCE;
                };
                Document projection = EditorFactory.getInstance().createDocument(source);
                UndoUtil.disableUndoFor(projection);
                projection.putUserData(UndoManager.ORIGINAL_DOCUMENT, document);
                boolean editable = canWrite() && cell.isEditable() && !truncated;
                Editor viewer = EditorFactory.getInstance().createEditor(projection, project, type, !editable);
                sourceEditors.add(viewer);
                cellEditors.put(cell.id(), viewer);
                if (editable) {
                    Disposable lifetime = Disposer.newDisposable("KaryoForge notebook cell");
                    Disposer.register(this, lifetime);
                    sourceLifetimes.add(lifetime);
                    projection.addDocumentListener(new DocumentListener() {
                        @Override public void documentChanged(@NotNull DocumentEvent event) {
                            if (disposed || committing || UndoManager.getInstance(project).isUndoOrRedoInProgress()) return;
                            if (edit("Edit notebook cell", model -> model.setSource(cell.id(), projection.getText()), false)) {
                                var preview = markdownPreviews.get(cell.id());
                                if (preview != null) preview.showSource(projection.getText());
                            }
                        }
                    }, lifetime);
                }
                viewer.getSettings().setLineNumbersShown(true);
                viewer.getSettings().setAdditionalLinesCount(0);
                viewer.getComponent().setPreferredSize(new Dimension(720,
                        Math.min(320, Math.max(80, viewer.getLineHeight() * Math.min(15, viewer.getDocument().getLineCount()) + 20))));
                viewer.getContentComponent().getAccessibleContext().setAccessibleName(kind + " cell " + (index + 1) + " source");
                panel.add(viewer.getComponent(), BorderLayout.CENTER);
                if (cell.type() == CellType.MARKDOWN) {
                    var preview = new NotebookMarkdownPreview();
                    preview.showSource(source);
                    markdownPreviews.put(cell.id(), preview);
                    var scroll = new JScrollPane(preview);
                    scroll.setPreferredSize(new Dimension(720, Math.min(300, Math.max(60, preview.getPreferredSize().height + 8))));
                    panel.add(scroll, BorderLayout.SOUTH);
                }
                if (cell.type() == CellType.CODE && !cell.outputs().isEmpty()) {
                    var saved = outputs.create(cell.outputs());
                    var scroll = new JScrollPane(saved);
                    scroll.setPreferredSize(new Dimension(720, Math.min(300, Math.max(40, saved.getPreferredSize().height + 8))));
                    panel.add(scroll, BorderLayout.SOUTH);
                }
            }
            cells.add(panel);
        }
        if (size == 0) cells.add(new JLabel("Add a Code, Markdown or Raw cell, or edit the complete JSON in the Text view."));
        cells.revalidate();
        cells.repaint();
    }

    private void showError(String message) {
        clearSourceEditors();
        cells.removeAll();
        previous.setEnabled(false);
        next.setEnabled(false);
        pageLabel.setText("Notebook source retained");
        updateEditingButtons();
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

    private boolean canWrite() {
        return notebook != null && document != null && document.isWritable() && file.isWritable();
    }

    private void updateEditingButtons() {
        for (JButton button : editingButtons) button.setEnabled(canWrite());
    }

    private JButton addCellButton(String title, CellType type) {
        var button = new JButton(title);
        editingButtons.add(button);
        button.addActionListener(event -> {
            if (edit(title, model -> model.addCell(type), false)) {
                pageStart = (notebook.cells().size() - 1) / PAGE_SIZE * PAGE_SIZE;
                render();
                focusCell(notebook.cellAt(notebook.cells().size() - 1).id());
            }
        });
        return button;
    }

    private JComponent cellControls(NotebookDocument.Cell cell, int index) {
        var controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        CellType[] kinds = {CellType.CODE, CellType.MARKDOWN, CellType.RAW};
        var type = new JComboBox<>(new String[]{"Code", "Markdown", "Raw"});
        type.getAccessibleContext().setAccessibleName("Cell " + (index + 1) + " type");
        int selectedKind = -1;
        for (int item = 0; item < kinds.length; item++) if (kinds[item] == cell.type()) selectedKind = item;
        type.setSelectedIndex(selectedKind);
        type.setEnabled(canWrite() && cell.isEditable());
        type.addActionListener(event -> {
            int choice = type.getSelectedIndex();
            if (disposed || choice < 0 || kinds[choice] == notebook.cell(cell.id()).type()) return;
            if (edit("Change notebook cell type", model -> model.setCellType(cell.id(), kinds[choice]), true)) focusCell(cell.id());
        });
        controls.add(type);
        var up = new JButton("Move up");
        up.getAccessibleContext().setAccessibleName("Move cell " + (index + 1) + " up");
        up.setEnabled(canWrite() && cell.isEditable() && index > 0);
        up.addActionListener(event -> {
            if (edit("Move notebook cell", model -> model.moveCell(cell.id(), model.indexOf(cell.id()) - 1), true)) focusCell(cell.id());
        });
        controls.add(up);
        var down = new JButton("Move down");
        down.getAccessibleContext().setAccessibleName("Move cell " + (index + 1) + " down");
        down.setEnabled(canWrite() && cell.isEditable() && index + 1 < notebook.cells().size());
        down.addActionListener(event -> {
            if (edit("Move notebook cell", model -> model.moveCell(cell.id(), model.indexOf(cell.id()) + 1), true)) focusCell(cell.id());
        });
        controls.add(down);
        var delete = new JButton("Delete cell");
        delete.getAccessibleContext().setAccessibleName("Delete cell " + (index + 1));
        delete.setEnabled(canWrite() && cell.isDeletable());
        delete.addActionListener(event -> edit("Delete notebook cell", model -> model.removeCell(cell.id()), true));
        controls.add(delete);
        if (cell.type() == CellType.CODE) {
            var clear = new JButton("Clear outputs");
            clear.getAccessibleContext().setAccessibleName("Clear cell " + (index + 1) + " outputs");
            clear.setEnabled(canWrite() && (!cell.outputs().isEmpty() || cell.executionCount() != null));
            clear.addActionListener(event -> {
                if (edit("Clear notebook outputs", model -> model.clearOutputs(cell.id()), true)) focusCell(cell.id());
            });
            controls.add(clear);
        }
        return controls;
    }

    private boolean edit(String title, Consumer<NotebookDocument> operation, boolean redraw) {
        if (disposed || notebook == null || document == null) return false;
        try {
            committing = true;
            notebook = NotebookEdits.commit(project, file, document, notebook, renderedStamp, title, operation);
            renderedStamp = document.getModificationStamp();
            generation++;
            dirty = false;
            status.setText("Notebook updated. Ctrl+S saves; Undo/Redo uses the shared JSON document.");
            if (redraw) render();
            return true;
        } catch (NotebookFormatException | IllegalArgumentException | IllegalStateException rejected) {
            dirty = true;
            long expected = ++generation;
            String message = rejected.getMessage();
            status.setText(message);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && !project.isDisposed() && generation == expected) {
                    reload();
                    status.setText(message);
                }
            });
            return false;
        } finally { committing = false; }
    }

    private void focusCell(String id) {
        Editor editor = cellEditors.get(id);
        if (editor != null && editor.getContentComponent().isShowing()) editor.getContentComponent().requestFocusInWindow();
    }

    private void clearSourceEditors() {
        for (Disposable lifetime : sourceLifetimes) Disposer.dispose(lifetime);
        sourceLifetimes.clear();
        cellEditors.clear();
        markdownPreviews.clear();
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
    @Override public Document @NotNull [] getDocuments() { return document == null ? new Document[0] : new Document[]{document}; }
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
