package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import org.jetbrains.annotations.NotNull;
import org.openkaryon.karyoforge.database.core.QueryPage;
import org.openkaryon.karyoforge.database.core.TableQuery;
import org.openkaryon.karyoforge.database.core.TableSnapshot;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;

import static org.openkaryon.karyoforge.database.ui.DatabaseSessionPanel.button;
import static org.openkaryon.karyoforge.database.ui.DatabaseSessionPanel.controls;
import static org.openkaryon.karyoforge.database.ui.DatabaseSessionPanel.literalRenderer;

final class DatabaseTableEditor extends UserDataHolderBase implements FileEditor {
    private final DatabaseTableFile file;
    private final DatabaseSessionPanel owner;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JBLabel status = new JBLabel("Select Refresh Rows to load data.");
    private final JBTable data = new JBTable();
    private final JBTable structure = new JBTable();
    private final JBTextArea ddl = new JBTextArea();
    private final JBTextField where = new JBTextField(17);
    private final JBTextField order = new JBTextField(13);
    private final JButton reload = button("Refresh Rows", this::reload);
    private final JButton next = button("Next Page", this::next);
    private final JButton stop = button("Stop Table", this::stop);
    private final JButton console = button("Open Table SQL", this::openSql);
    private final JButton edit = button("Edit in Databases", this::editTable);
    private QueryPage page;
    private boolean attempted;
    private boolean disposed;

    DatabaseTableEditor(DatabaseTableFile file) {
        this.file = file;
        owner = file.workspace();
        var tabs = new JTabbedPane();
        var dataTab = new JPanel(new BorderLayout());
        where.getAccessibleContext().setAccessibleName("Table editor WHERE expression");
        order.getAccessibleContext().setAccessibleName("Table editor ORDER BY expression");
        where.addActionListener(event -> { if (reload.isEnabled()) reload(); });
        order.addActionListener(event -> { if (reload.isEnabled()) reload(); });
        data.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        data.getAccessibleContext().setAccessibleName("Table editor rows");
        data.setDefaultRenderer(Object.class, literalRenderer());
        data.getTableHeader().setDefaultRenderer(literalRenderer());
        structure.getAccessibleContext().setAccessibleName("Table editor columns");
        structure.setDefaultRenderer(Object.class, literalRenderer());
        ddl.getAccessibleContext().setAccessibleName("Table editor definition");
        ddl.setEditable(false);
        status.getAccessibleContext().setAccessibleName("Table editor status");
        status.putClientProperty("html.disable", true);
        dataTab.add(controls(new JBLabel("WHERE"), where, new JBLabel("ORDER BY"), order, reload, next, stop, console, edit), BorderLayout.NORTH);
        dataTab.add(new JBScrollPane(data), BorderLayout.CENTER);
        tabs.addTab("Data", dataTab);
        tabs.addTab("Structure", new JBScrollPane(structure));
        tabs.addTab("DDL", new JBScrollPane(ddl));
        panel.add(tabs, BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);
        owner.registerTableEditor(this);
        updateControls();
    }

    private void reload() {
        try {
            var query = new TableQuery(file.object(), where.getText(), order.getText());
            attempted = owner.loadTableEditor(this, query);
        } catch (IllegalArgumentException invalid) {
            report(invalid.getMessage());
        }
    }

    private void next() { owner.nextTableEditorPage(this); }
    private void stop() { owner.stopTableEditor(this); }
    private void openSql() { owner.openTableSql(file.object()); }
    private void editTable() { owner.editTableInDatabases(file.object()); }

    void load(TableSnapshot snapshot) {
        DatabaseSessionPanel.fillStructure(structure, snapshot.definition());
        ddl.setText(snapshot.definition().ddl());
        ddl.setCaretPosition(0);
        showPage(snapshot.data());
    }

    void showPage(QueryPage result) {
        page = result;
        data.setModel(new AbstractTableModel() {
            @Override public int getRowCount() { return result.rows().size(); }
            @Override public int getColumnCount() { return result.columns().size(); }
            @Override public String getColumnName(int index) { return result.columns().get(index).label(); }
            @Override public Object getValueAt(int row, int column) { return result.rows().get(row).cells().get(column).display(); }
        });
        for (int index = 0; index < data.getColumnCount(); index++) data.getColumnModel().getColumn(index).setPreferredWidth(150);
        report(file.object().name() + " · Page " + result.page() + " · " + result.rows().size() + " rows"
                + (result.notice().isEmpty() ? "" : " · " + result.notice()));
        updateControls();
    }

    void report(String message) {
        if (disposed) return;
        status.setText(message);
        status.setToolTipText(message);
    }

    boolean hasNext() { return page != null && page.hasNext(); }

    void endPaging() {
        if (hasNext()) {
            page = new QueryPage(page.columns(), page.rows(), page.page(), page.totalRows(), false, page.notice());
        }
    }

    void updateControls() {
        boolean available = owner.canBrowseTable();
        reload.setEnabled(available);
        console.setEnabled(available);
        edit.setEnabled(available && owner.canEditTable(file.object()));
        next.setEnabled(available && owner.ownsTableEditor(this) && hasNext());
        stop.setEnabled(owner.canStopTableEditor(this));
        where.setEnabled(available);
        order.setEnabled(available);
    }

    @Override public void selectNotify() {
        if (!attempted && !disposed && owner.canBrowseTable()) reload();
    }

    @Override public @NotNull JComponent getComponent() { return panel; }
    @Override public @NotNull JComponent getPreferredFocusedComponent() { return data; }
    @Override public @NotNull String getName() { return "Table"; }
    @Override public @NotNull DatabaseTableFile getFile() { return file; }
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return !disposed && file.isValid(); }
    @Override public void setState(@NotNull FileEditorState state) {}
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}

    @Override public void dispose() {
        disposed = true;
        owner.closeTableEditor(this);
    }
}
