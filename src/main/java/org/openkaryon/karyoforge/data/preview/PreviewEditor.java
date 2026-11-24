package org.openkaryon.karyoforge.data.preview;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.DocumentsEditor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

public final class PreviewEditor extends UserDataHolderBase implements DocumentsEditor {
    private static final int HISTORY_LIMIT = 64;
    private static final int DETAILS_LIMIT = 32 * 1024;
    private final Project project;
    private final VirtualFile file;
    private final String format;
    private final PreviewReader reader;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JBTable table = new JBTable();
    private final JTextArea details = new JTextArea();
    private final JLabel status = new JLabel();
    private final JButton reload = button("Reload preview", () -> load(null, Navigation.RELOAD));
    private final JButton previous = button("Previous page", this::previousPage);
    private final JButton next = button("Next page", this::nextPage);
    private final JButton stop = button("Stop reading", this::stopReading);
    private final ArrayList<PreviewReader.Cursor> history = new ArrayList<>();
    private PreviewReader.Page page;
    private Read active;
    private boolean started;
    private boolean disposed;

    public PreviewEditor(Project project, VirtualFile file, String format, PreviewReader reader) {
        this.project = project;
        this.file = file;
        this.format = format;
        this.reader = reader;

        var controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        controls.add(reload);
        controls.add(previous);
        controls.add(next);
        controls.add(stop);
        panel.add(controls, BorderLayout.NORTH);

        table.getAccessibleContext().setAccessibleName("Preview records");
        table.setAutoResizeMode(JBTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, literalRenderer());
        table.getTableHeader().setDefaultRenderer(literalRenderer());
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showDetails();
        });

        details.getAccessibleContext().setAccessibleName("Preview record details");
        details.setEditable(false);
        details.setLineWrap(true);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JBScrollPane(table), new JBScrollPane(details));
        split.setResizeWeight(0.75);
        panel.add(split, BorderLayout.CENTER);

        status.getAccessibleContext().setAccessibleName("Preview status");
        status.putClientProperty("html.disable", true);
        status.setText("Preview reads the saved file.");
        panel.add(status, BorderLayout.SOUTH);
        updateControls();
    }

    @Override public void selectNotify() {
        if (!started && !disposed) {
            started = true;
            load(null, Navigation.RELOAD);
        }
    }

    private void previousPage() {
        if (!history.isEmpty()) load(history.getLast(), Navigation.PREVIOUS);
    }

    private void nextPage() {
        if (page != null && page.next() != null) load(page.next(), Navigation.NEXT);
    }

    private boolean sourceIsUnsaved() {
        var documents = FileDocumentManager.getInstance();
        var document = documents.getCachedDocument(file);
        return document != null && documents.isDocumentUnsaved(document);
    }

    private void load(PreviewReader.Cursor cursor, Navigation navigation) {
        if (!isValid() || active != null) return;
        if (sourceIsUnsaved()) {
            report("Save the Text editor, then reload the preview.");
            return;
        }
        var operation = new Read(file.getModificationStamp());
        Path path = file.toNioPath();
        active = operation;
        report("Reading " + format + " preview…");
        new Task.Backgroundable(project, "Read " + format + " preview", true) {
            private PreviewReader.Page result;
            private IOException failure;

            @Override public void run(@NotNull ProgressIndicator indicator) {
                operation.indicator = indicator;
                try {
                    result = reader.read(path, cursor, () -> {
                        if (operation.cancelled || project.isDisposed()) throw new ProcessCanceledException();
                        indicator.checkCanceled();
                    });
                } catch (IOException error) {
                    failure = error;
                }
            }

            @Override public void onSuccess() {
                if (!finish(operation)) return;
                if (failure != null) {
                    report("Cannot read preview: " + failure.getMessage());
                    return;
                }
                if (sourceIsUnsaved() || operation.fileStamp != file.getModificationStamp()) {
                    report("The source changed. Save the Text editor if needed, then reload the preview.");
                    return;
                }
                switch (navigation) {
                    case RELOAD -> history.clear();
                    case NEXT -> {
                        if (history.size() == HISTORY_LIMIT) history.removeFirst();
                        history.add(page.start());
                    }
                    case PREVIOUS -> history.removeLast();
                }
                page = result;
                showPage();
            }

            @Override public void onCancel() {
                if (finish(operation)) report("Reading stopped.");
            }

            @Override public void onThrowable(@NotNull Throwable error) {
                if (finish(operation)) report("Cannot read preview: " + error.getMessage());
            }
        }.queue();
    }

    private boolean finish(Read operation) {
        if (active != operation) return false;
        active = null;
        return isValid() && !project.isDisposed();
    }

    private void stopReading() {
        var operation = active;
        active = null;
        if (operation == null) return;
        operation.cancelled = true;
        var indicator = operation.indicator;
        if (indicator != null) indicator.cancel();
        if (!disposed) report("Reading stopped.");
    }

    private void showPage() {
        var shown = page;
        int width = shown.columns().size();
        for (var row : shown.rows()) width = Math.max(width, row.values().size());
        int columns = width;
        table.setModel(new AbstractTableModel() {
            @Override public int getRowCount() { return shown.rows().size(); }
            @Override public int getColumnCount() { return columns; }
            @Override public String getColumnName(int column) { return columnLabel(shown, column); }
            @Override public Object getValueAt(int row, int column) {
                var values = shown.rows().get(row).values();
                return column < values.size() ? values.get(column) : "";
            }
        });
        for (int index = 0; index < columns; index++) {
            table.getColumnModel().getColumn(index).setPreferredWidth(150);
        }
        if (!shown.rows().isEmpty()) {
            table.setRowSelectionInterval(0, 0);
            long first = shown.rows().getFirst().number();
            long last = shown.rows().getLast().number();
            report(format + " records " + first + "–" + last + (shown.next() == null ? " · End of file" : ""));
        } else {
            details.setText("");
            report(format + " · No records");
        }
    }

    private void showDetails() {
        int selected = table.getSelectedRow();
        if (page == null || selected < 0 || selected >= page.rows().size()) {
            details.setText("");
            return;
        }
        var row = page.rows().get(selected);
        var text = new StringBuilder("Record " + row.number() + " · Byte " + row.offset());
        for (int index = 0; index < row.values().size(); index++) {
            text.append('\n').append(columnLabel(page, index)).append(": ").append(row.values().get(index));
            if (text.length() > DETAILS_LIMIT) {
                int end = DETAILS_LIMIT;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                text.setLength(end);
                text.append("\nRecord details are clipped.");
                break;
            }
        }
        details.setText(text.toString());
        details.setCaretPosition(0);
    }

    private static String columnLabel(PreviewReader.Page page, int column) {
        if (column < page.columns().size()) return page.columns().get(column);
        return "Column " + (column + 1);
    }

    private static DefaultTableCellRenderer literalRenderer() {
        var renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", true);
        return renderer;
    }

    private void report(String message) {
        status.setText(message);
        updateControls();
    }

    private void updateControls() {
        boolean idle = active == null;
        reload.setEnabled(idle);
        previous.setEnabled(idle && !history.isEmpty());
        next.setEnabled(idle && page != null && page.next() != null);
        stop.setEnabled(!idle);
    }

    private static JButton button(String label, Runnable action) {
        var control = new JButton(label);
        control.addActionListener(event -> action.run());
        return control;
    }

    @Override public @NotNull JComponent getComponent() { return panel; }
    @Override public @NotNull JComponent getPreferredFocusedComponent() { return table; }
    @Override public @NotNull String getName() { return "Preview"; }
    @Override public VirtualFile getFile() { return file; }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return !disposed && file.isValid(); }
    @Override public Document @NotNull [] getDocuments() { return Document.EMPTY_ARRAY; }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}

    @Override public void dispose() {
        disposed = true;
        stopReading();
        history.clear();
        page = null;
        panel.removeAll();
    }

    private enum Navigation { RELOAD, NEXT, PREVIOUS }

    private static final class Read {
        private final long fileStamp;
        private volatile ProgressIndicator indicator;
        private volatile boolean cancelled;

        private Read(long fileStamp) { this.fileStamp = fileStamp; }
    }
}
