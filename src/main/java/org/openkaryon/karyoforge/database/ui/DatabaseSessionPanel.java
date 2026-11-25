package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import org.openkaryon.karyoforge.database.core.DatabaseProblem;
import org.openkaryon.karyoforge.database.core.DatabaseSource;
import org.openkaryon.karyoforge.database.core.JdbcSession;
import org.openkaryon.karyoforge.database.core.QueryPage;
import org.openkaryon.karyoforge.database.core.QueryRequest;
import org.openkaryon.karyoforge.database.core.SqlResult;
import org.openkaryon.karyoforge.database.core.SqlText;
import org.openkaryon.karyoforge.database.core.SqlValue;
import org.openkaryon.karyoforge.database.core.TableDefinition;
import org.openkaryon.karyoforge.database.core.TableSnapshot;
import org.openkaryon.karyoforge.database.core.TableQuery;
import org.openkaryon.karyoforge.database.results.FetchedResults;
import org.openkaryon.karyoforge.database.results.ResultExport;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One preserved source view, connection and command lane in the project. */
final class DatabaseSessionPanel extends JPanel implements Disposable {
    private final Project project;
    private final DatabaseEditorBridge editorBridge;
    private final DatabaseDriverCleanup cleanup = ApplicationManager.getApplication().getService(DatabaseDriverCleanup.class);
    private final Runnable onStateChanged;
    private DatabaseSources.Profile profile;
    private final DatabaseSchemaTree schema = new DatabaseSchemaTree(this::loadTreeColumns);
    private final JBTextField filterTables = new JBTextField(16);
    private final JBTextArea sql = new JBTextArea(5, 48);
    private final JBTable table = new JBTable();
    private final JBTable structure = new JBTable();
    private final JBTable dataGrid = new JBTable();
    private final JBTextField where = new JBTextField(17);
    private final JBTextField orderBy = new JBTextField(13);
    private final JPanel tableDataTab = new JPanel(new BorderLayout());
    private final JBTextArea definition = new JBTextArea();
    private final JTabbedPane details = new JTabbedPane();
    private final JPanel resultsTab = new JPanel(new BorderLayout());
    private final JScrollPane structureTab = new JBScrollPane(structure);
    private final JBLabel status = new JBLabel("Choose or add a data source.");
    private final JButton connect = button("Connect", this::connect);
    private final JButton testConnection = button("Test Connection", this::testConnection);
    private final JButton disconnect = button("Disconnect", this::disconnect);
    private final JButton retryCleanup = button("Retry Driver Cleanup", this::retryDriverCleanup);
    private final JButton show = button("Show Rows", this::showRows);
    private final JButton inspect = button("Inspect", this::inspect);
    private final JButton openTable = button("Open Table", this::openTable);
    private final JButton editRow = button("Edit Row", this::editRow);
    private final JButton deleteRow = button("Delete Row", this::deleteRow);
    private final JButton undoRow = button("Discard Row Draft", this::undoRow);
    private final JButton applyRows = button("Review & Apply", this::applyRows);
    private final JButton reloadTable = button("Reload Table", this::reloadTable);
    private final JButton nextTablePage = button("Next Table Page", this::nextTablePage);
    private final JButton stopTable = button("Stop Table Query", this::stop);
    private final JButton refresh = button("Refresh Schema", this::refreshSchema);
    private final JButton commit = button("Commit", () -> finishTransaction(true));
    private final JButton rollback = button("Rollback", () -> finishTransaction(false));
    private final JButton run = button("Run SQL", this::runSql);
    private final JButton explain = button("Explain SQL", () -> runInline(true));
    private final JButton openConsole = button("Open SQL Console", this::openConsole);
    private final JButton next = button("Next Result Page", this::next);
    private final JButton stop = button("Stop", this::stop);
    private final JButton resultOptions = button("Result Options", this::showResultOptions);
    private final JButton export = button("Export Results", this::exportResults);
    private final JPanel actionButtons = controls(connect, testConnection, disconnect, retryCleanup, commit, rollback);
    private final java.util.concurrent.ExecutorService commands =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("KaryoForge database command").factory());
    private final java.util.concurrent.ExecutorService cancels =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("KaryoForge database Stop").factory());
    private JdbcSession session; // Accessed only by the command executor.
    private boolean connected;
    private boolean writable;
    private DatabaseSource activeSource;
    private boolean busy;
    private boolean disposed;
    private QueryRequest active;
    private final QueryResults inlineResults = new QueryResults();
    private final Map<VirtualFile, QueryResults> editorResults = new IdentityHashMap<>();
    private QueryResults shownResults = inlineResults;
    private QueryResults cursorOwner;
    private final Map<String, DatabaseTableFile> tableFiles = new LinkedHashMap<>();
    private final Set<DatabaseTableEditor> tableEditors = new LinkedHashSet<>();
    private DatabaseTableEditor tableEditorOwner;
    private TableSnapshot editableTable;
    private JdbcSession.SchemaObject tableObject;
    private QueryPage tablePage;
    private final TreeMap<Integer, RowDraft> drafts = new TreeMap<>();

    DatabaseSessionPanel(Project project, DatabaseSources.Profile profile, DatabaseEditorBridge editorBridge,
                         Runnable onStateChanged) {
        super(new BorderLayout(0, 4));
        this.project = project;
        this.profile = profile;
        this.editorBridge = editorBridge;
        this.onStateChanged = onStateChanged;
        filterTables.getAccessibleContext().setAccessibleName("Filter tables");
        filterTables.getEmptyText().setText("Filter tables");
        filterTables.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(DocumentEvent event) { schema.filter(filterTables.getText()); }
        });
        sql.getAccessibleContext().setAccessibleName("SQL statement");
        table.getAccessibleContext().setAccessibleName("Database query results");
        structure.getAccessibleContext().setAccessibleName("Database object columns");
        definition.getAccessibleContext().setAccessibleName("Database object definition");
        definition.setEditable(false);
        structure.setDefaultRenderer(Object.class, literalRenderer());
        dataGrid.getAccessibleContext().setAccessibleName("Database table data");
        dataGrid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dataGrid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        dataGrid.setDefaultRenderer(Object.class, literalRenderer());
        dataGrid.getTableHeader().setDefaultRenderer(literalRenderer());
        status.getAccessibleContext().setAccessibleName("Database status");
        status.putClientProperty("html.disable", true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, literalRenderer());
        table.getTableHeader().setDefaultRenderer(literalRenderer());

        var objectPanel = new JPanel(new BorderLayout());
        objectPanel.add(filterTables, BorderLayout.NORTH);
        objectPanel.add(new JBScrollPane(schema), BorderLayout.CENTER);
        objectPanel.add(controls(show, openTable, inspect, refresh), BorderLayout.SOUTH);
        var queryPanel = new JPanel(new BorderLayout());
        queryPanel.add(new JBScrollPane(sql), BorderLayout.CENTER);
        queryPanel.add(controls(run, explain, openConsole, next, stop, resultOptions, export), BorderLayout.SOUTH);
        var queryAndResult = new JSplitPane(JSplitPane.VERTICAL_SPLIT, queryPanel, new JBScrollPane(table));
        queryAndResult.setResizeWeight(0.25);
        resultsTab.add(queryAndResult, BorderLayout.CENTER);
        details.addTab("Results", resultsTab);
        details.addTab("Structure", structureTab);
        details.addTab("DDL", new JBScrollPane(definition));
        details.setEnabledAt(1, false);
        details.setEnabledAt(2, false);
        where.getAccessibleContext().setAccessibleName("Table WHERE expression");
        orderBy.getAccessibleContext().setAccessibleName("Table ORDER BY expression");
        where.addActionListener(event -> { if (reloadTable.isEnabled()) reloadTable(); });
        orderBy.addActionListener(event -> { if (reloadTable.isEnabled()) reloadTable(); });
        tableDataTab.add(controls(new JBLabel("WHERE"), where, new JBLabel("ORDER BY"), orderBy, reloadTable, nextTablePage, stopTable),
                BorderLayout.NORTH);
        tableDataTab.add(new JBScrollPane(dataGrid), BorderLayout.CENTER);
        tableDataTab.add(controls(editRow, deleteRow, undoRow, applyRows), BorderLayout.SOUTH);
        details.addTab("Table Data", tableDataTab);
        details.setEnabledAt(3, false);
        var explorer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, objectPanel, details);
        explorer.setResizeWeight(0.25);
        add(explorer, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        schema.addTreeSelectionListener(event -> updateControls());
        schema.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                var path = schema.getPathForLocation(event.getX(), event.getY());
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)
                        && path != null && path.equals(schema.getSelectionPath()) && openTable.isEnabled()) {
                    openTableInEditor();
                }
            }

            @Override public void mousePressed(MouseEvent event) { showTableMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { showTableMenu(event); }
        });
        dataGrid.getSelectionModel().addListSelectionListener(event -> updateControls());
        sql.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "runSql");
        sql.getActionMap().put("runSql", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (run.isEnabled()) runSql();
            }
        });
        updateControls();
    }

    static DefaultListCellRenderer literalListRenderer() {
        var renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", true);
        return renderer;
    }

    static DefaultTableCellRenderer literalRenderer() {
        var renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", true);
        return renderer;
    }

    static JPanel controls(JComponent... components) {
        var bar = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 2));
        for (var component : components) bar.add(component);
        return bar;
    }

    JPanel actionButtons() { return actionButtons; }
    boolean busy() { return busy; }
    boolean connected() { return connected; }
    boolean cleanupPending() { return cleanup.pending(profile.id()); }

    void queueCredentialCleanup(Runnable cleanup) { commands.execute(cleanup); }

    void updateProfile(DatabaseSources.Profile profile) {
        this.profile = profile;
    }

    private ConnectionPasswordDialog.Input askForPassword(DatabaseSources.Profile profile, boolean test) {
        if (!profile.source().usesPassword()) return new ConnectionPasswordDialog.Input(new char[0], false);
        var dialog = new ConnectionPasswordDialog(project, profile.source().engine(), test);
        return dialog.showAndGet() ? dialog.takeInput() : null;
    }

    private static char[] resolvePassword(DatabaseSources.Profile profile, char[] entered) {
        if (!profile.source().usesPassword() || entered.length != 0) return entered;
        try {
            return DatabaseCredentials.recall(profile);
        } catch (RuntimeException unavailable) {
            throw new DatabaseProblem("PasswordSafe could not read the remembered password. Enter the password explicitly.");
        }
    }

    private void testConnection() {
        var requested = profile;
        if (disposed || busy) return;
        var input = askForPassword(requested, true);
        if (input == null) return;
        if (disposed || busy) {
            Arrays.fill(input.password(), '\0');
            return;
        }
        submit("Testing " + requested.source().engine() + " connection…", null, () -> {
            char[] password = input.password();
            JdbcSession probe = null;
            try {
                probe = new JdbcSession(requested.source(), requested.limits(), requested.timeouts());
                password = resolvePassword(requested, password);
                probe.connect(password);
                return true;
            } finally {
                Arrays.fill(password, '\0');
                if (probe != null) cleanup.release(requested.id(), probe::close);
            }
        }, ignored -> report("Connection test passed. The temporary session was closed."));
    }

    private void connect() {
        var requested = profile;
        if (disposed || busy) return;
        var input = askForPassword(requested, false);
        if (input == null) return;
        if (disposed || busy) {
            Arrays.fill(input.password(), '\0');
            return;
        }
        submit("Connecting to " + requested.source().engine() + "…", null, () -> {
            char[] password = input.password();
            try {
                releaseSession(requested.id());
                if (cleanup.pending(requested.id())) {
                    throw new DatabaseProblem("Retry Driver Cleanup before connecting this source.");
                }
                password = resolvePassword(requested, password);
                session = new JdbcSession(requested.source(), requested.limits(), requested.timeouts());
                session.connect(password);
                var objects = session.schema();
                boolean saved = true;
                if (input.remember() && input.password().length != 0) {
                    try {
                        DatabaseCredentials.remember(requested, password);
                    } catch (RuntimeException unavailable) {
                        saved = false;
                    }
                }
                return new Connected(objects, saved);
            } catch (RuntimeException | LinkageError unavailable) {
                releaseSession(requested.id());
                throw unavailable;
            } finally {
                Arrays.fill(password, '\0');
            }
        }, opened -> {
            connected = true;
            activeSource = requested.source();
            writable = requested.source().writable();
            showSchema(opened.objects());
            String message = writable ? "Connected in read-write mode. Use Commit or Rollback to finish changes."
                    : "Connected in read-only mode. " + opened.objects().size() + " schema objects.";
            report(opened.passwordSaved() ? message : message + " PasswordSafe could not remember the password.");
        });
    }

    private record Connected(List<JdbcSession.SchemaObject> objects, boolean passwordSaved) {}

    private void releaseSession(String sourceId) {
        JdbcSession owner = session;
        session = null;
        if (owner != null) cleanup.release(sourceId, owner::close);
    }

    private void retryDriverCleanup() {
        String sourceId = profile.id();
        submit("Retrying driver cleanup…", null, () -> cleanup.retry(sourceId),
                released -> report(released ? "Database driver cleanup completed."
                        : "Database driver cleanup is still pending."));
    }

    private void disconnect() {
        if (!discardTableDraft()) return;
        String sourceId = profile.id();
        submit("Disconnecting…", null, () -> {
            try {
                releaseSession(sourceId);
                return true;
            } catch (DatabaseProblem pending) {
                return false;
            }
        }, released -> {
            connected = false;
            writable = false;
            activeSource = null;
            endPaging();
            editorBridge.forgetCatalog(profile.id());
            showSchema(List.of());
            clearAllResults();
            details.setSelectedComponent(resultsTab);
            details.setEnabledAt(1, false);
            details.setEnabledAt(2, false);
            definition.setText("");
            structure.setModel(new javax.swing.table.DefaultTableModel());
            report(released ? "Disconnected." : "Source closed for new queries. Driver cleanup is pending.");
        });
    }

    private void showRows() {
        var object = schema.selectedObject();
        if (object != null) {
            sql.setText(object.selectSql());
            runSql();
        }
    }

    private void openConsole() {
        if (!connected || busy) return;
        openConsole(sql.getText());
    }

    private void openConsole(String content) {
        var console = editorBridge.console(profile.id(), activeSource.name(), content);
        FileEditorManager.getInstance(project).openFile(console, true);
    }

    void openTableSql(JdbcSession.SchemaObject object) {
        if (canBrowseTable()) openConsole(object.selectSql());
    }

    void runFromEditor(VirtualFile editor, String text, int start, int end, boolean plan) {
        if (!connected) {
            report("Connect a data source before running SQL from the editor.");
            return;
        }
        if (busy) {
            report("Wait for the current database operation to finish or choose Stop.");
            return;
        }
        runSql(text, start, end, plan, editor);
    }

    private void runSql() { runInline(false); }

    private void runInline(boolean plan) {
        runSql(sql.getText(), sql.getSelectionStart(), sql.getSelectionEnd(), plan, null);
    }

    private void runSql(String text, int start, int end, boolean plan, VirtualFile editor) {
        if (!connected || busy) return;
        SqlText.Command command;
        try {
            var dialect = activeSource.dialect();
            String statement = SqlText.activeStatement(text, start, end, dialect);
            command = plan ? SqlText.explain(statement, dialect) : SqlText.compile(statement, dialect);
        } catch (IllegalArgumentException malformed) {
            report(malformed.getMessage());
            return;
        }
        Map<String, SqlValue> values = Map.of();
        if (!command.parameters().isEmpty()) {
            var dialog = new SqlParametersDialog(project, command.parameters());
            if (!dialog.showAndGet()) return;
            values = dialog.values();
        }
        if (!discardTableDraft()) return;
        var request = new QueryRequest();
        var bindings = values;
        endPaging();
        QueryResults target = editor == null ? inlineResults : editorResults.computeIfAbsent(editor, ignored -> new QueryResults());
        selectResults(target);
        cursorOwner = target;
        submit(plan ? "Planning SQL…" : "Running SQL…", request,
                () -> session.execute(command, bindings, request), result -> showResult(target, result));
    }

    private void showTableMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        var path = schema.getPathForLocation(event.getX(), event.getY());
        if (path == null) return;
        schema.setSelectionPath(path);
        if (!canBrowseTable() || schema.selectedObject() == null) return;
        var menu = new JPopupMenu();
        var open = new JMenuItem("Open Table in Editor");
        open.addActionListener(action -> openTableInEditor());
        menu.add(open);
        menu.show(schema, event.getX(), event.getY());
    }

    private void openTableInEditor() {
        var object = schema.selectedObject();
        if (object == null || !canBrowseTable()) return;
        var file = tableFiles.computeIfAbsent(object.qualified(), ignored -> new DatabaseTableFile(this, object));
        FileEditorManager.getInstance(project).openFile(file, true);
    }

    void registerTableEditor(DatabaseTableEditor editor) { tableEditors.add(editor); }

    void closeTableEditor(DatabaseTableEditor editor) {
        tableEditors.remove(editor);
        if (tableEditors.stream().noneMatch(other -> other.getFile() == editor.getFile())) {
            tableFiles.remove(editor.getFile().object().qualified(), editor.getFile());
        }
        if (tableEditorOwner == editor && !disposed && !project.isDisposed()) {
            if (busy && active != null) stop();
            else if (!busy) stopRetained();
        }
    }

    boolean canBrowseTable() { return !disposed && !busy && connected; }

    boolean canEditTable(JdbcSession.SchemaObject object) {
        return canBrowseTable() && writable && object.type().equals("table")
                && (activeSource.dialect() == SqlText.Dialect.SQLITE || activeSource.dialect() == SqlText.Dialect.POSTGRESQL);
    }

    boolean ownsTableEditor(DatabaseTableEditor editor) { return tableEditorOwner == editor; }

    boolean canStopTableEditor(DatabaseTableEditor editor) {
        return ownsTableEditor(editor) && (busy ? active != null : editor.hasNext());
    }

    boolean loadTableEditor(DatabaseTableEditor editor, TableQuery query) {
        if (!canBrowseTable() || !discardTableDraft()) return false;
        endPaging();
        tableEditorOwner = editor;
        var request = new QueryRequest();
        editor.report("Reading table rows…");
        submit("Reading table rows…", request, () -> session.browseTable(query, request), snapshot -> {
            if (!tableEditors.contains(editor)) {
                editor.endPaging();
                submit("Closing the table cursor…", null, () -> session.stopPaging(), ignored -> endPaging());
                return;
            }
            learnDefinition(snapshot.definition());
            editor.load(snapshot);
            report("Table data loaded in the editor.");
        });
        return true;
    }

    void nextTableEditorPage(DatabaseTableEditor editor) {
        if (!canBrowseTable() || !ownsTableEditor(editor) || !editor.hasNext()) return;
        var request = new QueryRequest();
        editor.report("Reading the next table page…");
        submit("Reading the next table page…", request, () -> session.next(request), rows -> {
            if (tableEditors.contains(editor)) editor.showPage(rows);
            else submit("Closing the table cursor…", null, () -> session.stopPaging(), ignored -> endPaging());
        });
    }

    void stopTableEditor(DatabaseTableEditor editor) {
        if (canStopTableEditor(editor)) {
            stop();
            editor.report("Stopping the table request…");
        }
    }

    void editTableInDatabases(JdbcSession.SchemaObject object) {
        if (canEditTable(object)) editorBridge.editTable(profile.id(), object);
    }

    private void openTable() { openInlineTable(schema.selectedObject()); }

    void openInlineTable(JdbcSession.SchemaObject object) {
        if (object == null || !canBrowseTable() || !discardTableDraft()) return;
        where.setText("");
        orderBy.setText("");
        loadTable(new TableQuery(object, "", ""));
    }

    private void reloadTable() {
        if (tableObject == null) return;
        TableQuery query;
        try {
            query = new TableQuery(tableObject, where.getText(), orderBy.getText());
        } catch (IllegalArgumentException invalid) {
            report(invalid.getMessage());
            return;
        }
        if (!confirmDiscardTableDraft()) return;
        drafts.clear();
        editableTable = null;
        tablePage = null;
        loadTable(query);
    }

    private void loadTable(TableQuery query) {
        endPaging();
        var request = new QueryRequest();
        submit("Opening table data…", request, () -> session.openTable(query, request), this::showTable);
    }

    private void showTable(TableSnapshot snapshot) {
        showDefinition(snapshot.definition());
        editableTable = snapshot;
        tableObject = snapshot.definition().object();
        showTablePage(snapshot.data());
    }

    private void nextTablePage() {
        if (tablePage == null || !tablePage.hasNext()) return;
        var request = new QueryRequest();
        submit("Reading the next table page…", request, () -> session.next(request), this::showTablePage);
    }

    private void showTablePage(QueryPage result) {
        tablePage = result;
        dataGrid.setModel(new AbstractTableModel() {
            @Override public int getRowCount() { return result.rows().size(); }
            @Override public int getColumnCount() { return result.columns().size() + 1; }
            @Override public String getColumnName(int column) {
                return column == 0 ? "Draft" : result.columns().get(column - 1).label();
            }
            @Override public Object getValueAt(int row, int column) {
                var draft = drafts.get(row);
                if (column == 0) return draft == null ? "" : draft.delete() ? "Delete" : "Update";
                var changed = draft == null ? null : draft.values().get(column - 1);
                return changed == null ? result.rows().get(row).cells().get(column - 1).display() : changed.display();
            }
        });
        details.setEnabledAt(3, true);
        details.setSelectedComponent(tableDataTab);
        String protection = editableTable == null ? "" : editableTable.protection();
        report(tableObject.name() + " · " + result.rows().size() + " rows"
                + (result.page() == 1 ? "" : " · Page " + result.page())
                + (result.notice().isEmpty() ? "" : " · " + result.notice())
                + (protection.isEmpty() ? "" : " · " + protection));
    }

    private void editRow() {
        int row = dataGrid.getSelectedRow();
        if (!canEditRow(row)) return;
        var previous = drafts.get(row);
        var editor = new TableRowDialog(project, editableTable, row, previous == null ? Map.of() : previous.values());
        if (!editor.showAndGet()) return;
        var values = editor.values();
        if (values.isEmpty()) drafts.remove(row);
        else drafts.put(row, new RowDraft(false, values));
        refreshDraft(row);
    }

    private void deleteRow() {
        int row = dataGrid.getSelectedRow();
        if (!canEditRow(row)) return;
        drafts.put(row, new RowDraft(true, Map.of()));
        refreshDraft(row);
    }

    private void undoRow() {
        int row = dataGrid.getSelectedRow();
        drafts.remove(row);
        refreshDraft(row);
    }

    private void refreshDraft(int row) {
        ((AbstractTableModel) dataGrid.getModel()).fireTableRowsUpdated(row, row);
        report(drafts.size() + " local row drafts.");
    }

    private void applyRows() {
        if (editableTable == null || drafts.isEmpty()) return;
        var snapshot = editableTable;
        var changes = new ArrayList<TableSnapshot.Change>();
        var preview = new StringBuilder();
        for (var entry : drafts.entrySet()) {
            int row = entry.getKey();
            var draft = entry.getValue();
            var original = snapshot.data().rows().get(row).cells();
            var columns = snapshot.definition().columns();
            preview.append(draft.delete() ? "Delete row " : "Update row ").append(row + 1).append('\n');
            var values = new TreeMap<Integer, SqlValue>();
            for (int index = 0; index < columns.size(); index++) {
                var column = columns.get(index);
                if (column.keyPosition() > 0) {
                    preview.append("  Key ").append(column.name()).append(" = ").append(excerpt(original.get(index).display())).append('\n');
                }
                var changed = draft.values().get(index);
                if (changed != null) {
                    values.put(index, changed.value());
                    preview.append("  ").append(column.name()).append(": ").append(excerpt(original.get(index).display()))
                            .append(" → ").append(excerpt(changed.display())).append('\n');
                }
            }
            changes.add(new TableSnapshot.Change(row, draft.delete(), values));
        }
        if (!new TableReviewDialog(project, preview.toString()).showAndGet()) return;
        resetTableData();
        var request = new QueryRequest();
        submit("Applying the table batch…", request, () -> session.applyTableChanges(snapshot, changes, request),
                affected -> report(affected + " rows staged in the transaction. Choose Commit or Rollback."));
    }

    private static String excerpt(String value) {
        String rendered = value.replace("\r", "\\r").replace("\n", "\\n");
        return rendered.length() > 120 ? rendered.substring(0, 119) + "…" : rendered;
    }

    private boolean canEditRow(int row) {
        return row >= 0 && editableTable != null && editableTable.rowProtection(row).isEmpty();
    }

    private boolean confirmDiscardTableDraft() {
        return drafts.isEmpty() || Messages.showYesNoDialog(project, "Discard unapplied table drafts?",
                "Discard Table Drafts", Messages.getQuestionIcon()) == Messages.YES;
    }

    private boolean discardTableDraft() {
        if (!confirmDiscardTableDraft()) return false;
        resetTableData();
        return true;
    }

    private void resetTableData() {
        editableTable = null;
        tablePage = null;
        tableObject = null;
        drafts.clear();
        if (details.getSelectedComponent() == tableDataTab) details.setSelectedComponent(resultsTab);
        details.setEnabledAt(3, false);
        dataGrid.setModel(new javax.swing.table.DefaultTableModel());
    }

    private record RowDraft(boolean delete, Map<Integer, TableRowDialog.DraftValue> values) {}

    private void inspect() {
        var object = schema.selectedObject();
        if (object == null) return;
        submit("Reading object definition…", null, () -> session.describe(object), this::showDefinition);
    }

    private boolean loadTreeColumns(JdbcSession.SchemaObject object) {
        if (busy || !connected) return false;
        submit("Reading columns…", null, () -> session.describe(object), definition -> {
            learnDefinition(definition);
            report(definition.object().name() + " · " + definition.columns().size() + " columns.");
        });
        return true;
    }

    private void learnDefinition(TableDefinition object) {
        editorBridge.publishDefinition(profile.id(), object);
        schema.learn(object);
    }

    private void showDefinition(TableDefinition object) {
        learnDefinition(object);
        fillStructure(structure, object);
        definition.setText(object.ddl());
        definition.setCaretPosition(0);
        details.setEnabledAt(1, true);
        details.setEnabledAt(2, true);
        details.setSelectedComponent(structureTab);
        report("Definition of " + object.object().name() + (object.clipped() ? " · DDL preview clipped." : "."));
    }

    static void fillStructure(JTable structure, TableDefinition object) {
        structure.setModel(new AbstractTableModel() {
            private final List<String> headers = List.of("Column", "Declared type", "Declared NOT NULL", "Primary key position", "Storage");
            @Override public int getRowCount() { return object.columns().size(); }
            @Override public int getColumnCount() { return headers.size(); }
            @Override public String getColumnName(int column) { return headers.get(column); }
            @Override public Object getValueAt(int row, int index) {
                var column = object.columns().get(row);
                return switch (index) {
                    case 0 -> column.name();
                    case 1 -> column.type();
                    case 2 -> column.notNull() ? "Yes" : "";
                    case 3 -> column.keyPosition() == 0 ? "" : Integer.toString(column.keyPosition());
                    case 4 -> column.storage();
                    default -> throw new IndexOutOfBoundsException(index);
                };
            }
        });
    }

    private void refreshSchema() {
        submit("Refreshing the schema…", null, () -> session.schema(), objects -> {
            showSchema(objects);
            report("Schema refreshed. " + objects.size() + " objects.");
        });
    }

    private void showSchema(List<JdbcSession.SchemaObject> objects) {
        if (connected) editorBridge.publishCatalog(profile.id(), objects);
        schema.reset(objects);
    }

    private void finishTransaction(boolean commitChanges) {
        if (!discardTableDraft()) return;
        submit(commitChanges ? "Committing…" : "Rolling back…", null, () -> {
            session.finishTransaction(commitChanges);
            return Boolean.TRUE;
        }, ignored -> {
            endPaging();
            report(commitChanges ? "Transaction committed." : "Transaction rolled back.");
        });
    }

    private void clearAllResults() {
        cursorOwner = null;
        editorResults.clear();
        inlineResults.clear();
        shownResults = inlineResults;
        renderResults();
    }

    void selectEditorResult(VirtualFile editor) {
        selectResults(editorResults.getOrDefault(editor, inlineResults));
    }

    void closeEditorResult(VirtualFile editor) {
        var removed = editorResults.remove(editor);
        if (removed == null) return;
        if (shownResults == removed) selectResults(inlineResults);
        if (cursorOwner == removed) {
            if (busy && active != null) stop();
            else if (!busy) stopRetained();
        }
    }

    private void selectResults(QueryResults target) {
        if (target == shownResults) return;
        shownResults = target;
        renderResults();
        updateControls();
    }

    private void showResult(QueryResults target, SqlResult result) {
        if (result instanceof QueryPage query) {
            showPage(target, query);
        } else if (result instanceof SqlResult.UpdateCount count) {
            target.clear();
            cursorOwner = null;
            if (target == shownResults) {
                renderResults();
                details.setSelectedComponent(resultsTab);
            }
            report(count.rows() < 0 ? "Statement completed. Changes are pending."
                    : count.rows() + " affected rows. Changes are pending.");
        }
    }

    private void next() {
        var target = shownResults;
        if (cursorOwner != target || !hasNext(target.page)) return;
        var request = new QueryRequest();
        submit("Reading the next result page…", request, () -> session.next(request), result -> showPage(target, result));
    }

    private <T> void submit(String pending, QueryRequest request, Supplier<T> operation, Consumer<T> complete) {
        if (disposed || busy) return;
        busy = true;
        active = request;
        report(pending);
        commands.execute(() -> {
            try {
                T result = operation.get();
                onUi(() -> complete.accept(result));
            } catch (DatabaseProblem | IllegalArgumentException failure) {
                onUi(() -> showFailure(failure.getMessage(), request != null));
            } catch (ProcessCanceledException failure) {
                onUi(() -> showFailure("Database request stopped.", request != null));
            } catch (RuntimeException | LinkageError failure) {
                onUi(() -> showFailure("The database operation failed.", request != null));
            }
        });
    }

    private void onUi(Runnable result) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed()) return;
            busy = false;
            active = null;
            result.run();
            updateControls();
        });
    }

    private void showFailure(String message, boolean cursorMayHaveClosed) {
        // A failed or stopped read may have closed its retained cursor. Keep its detached page.
        if (cursorMayHaveClosed) {
            if (tableEditorOwner != null) tableEditorOwner.report(message);
            endPaging();
        }
        report(message);
    }

    private void stop() {
        QueryRequest request = active;
        if (request == null) {
            if (!busy) stopRetained();
            return;
        }
        stop.setEnabled(false);
        stopTable.setEnabled(false);
        status.setText("Stopping the current database request…");
        cancels.execute(request::stop);
    }

    private void stopRetained() {
        if ((cursorOwner == null || !hasNext(cursorOwner.page)) && !hasNext(tablePage)
                && (tableEditorOwner == null || !tableEditorOwner.hasNext())) return;
        var editor = tableEditorOwner;
        endPaging();
        submit("Closing the retained result…", null, () -> session.stopPaging(), closed -> {
            String message = closed ? "Stopped paging. Fetched rows are still available." : "This result has no next page.";
            if (editor != null) editor.report(message);
            report(message);
        });
    }

    private void endPaging() {
        if (cursorOwner != null) cursorOwner.page = withoutNextPage(cursorOwner.page);
        cursorOwner = null;
        tablePage = withoutNextPage(tablePage);
        if (tableEditorOwner != null) tableEditorOwner.endPaging();
        tableEditorOwner = null;
    }

    private static QueryPage withoutNextPage(QueryPage page) {
        return hasNext(page) ? new QueryPage(page.columns(), page.rows(), page.page(), page.totalRows(), false, page.notice()) : page;
    }

    private static boolean hasNext(QueryPage page) { return page != null && page.hasNext(); }

    private void showPage(QueryResults target, QueryPage result) {
        target.page = result;
        if (result.page() == 1) {
            target.fetched = FetchedResults.first(result);
            target.filters = List.of();
            target.ordering = List.of();
        } else {
            target.fetched = target.fetched.append(result);
        }
        cursorOwner = result.hasNext() ? target : null;
        if (target != shownResults) {
            report("Fetched " + result.rows().size() + " rows in another editor.");
            if (target != inlineResults && !editorResults.containsValue(target)) stopRetained();
            return;
        }
        details.setSelectedComponent(resultsTab);
        renderResults();
    }

    private void renderResults() {
        var state = shownResults;
        if (state.page == null) {
            table.setModel(new javax.swing.table.DefaultTableModel());
            return;
        }
        if (state.filters.isEmpty() && state.ordering.isEmpty()) {
            showCurrentPage();
        } else {
            try {
                showFetchedView(state.fetched.view(state.filters, state.ordering));
            } catch (IllegalArgumentException incomparable) {
                clearTransforms();
                report(incomparable.getMessage() + " Fetched rows are retained.");
            }
        }
    }

    private void showCurrentPage() {
        var page = shownResults.page;
        displayResult(page.columns(), page.rows());
        report("Page " + page.page() + " · " + page.rows().size() + " rows"
                + (page.notice().isEmpty() ? "" : " · " + page.notice()));
    }

    private static final class QueryResults {
        private QueryPage page;
        private FetchedResults fetched;
        private List<FetchedResults.Filter> filters = List.of();
        private List<FetchedResults.Sort> ordering = List.of();

        void clear() {
            page = null;
            fetched = null;
            filters = List.of();
            ordering = List.of();
        }
    }

    private void displayResult(List<QueryPage.Column> columns, List<QueryPage.Row> rows) {
        table.setModel(new AbstractTableModel() {
            @Override public int getRowCount() { return rows.size(); }
            @Override public int getColumnCount() { return columns.size(); }
            @Override public String getColumnName(int column) { return columns.get(column).label(); }
            @Override public Object getValueAt(int row, int column) { return rows.get(row).cells().get(column).display(); }
        });
        for (int index = 0; index < table.getColumnCount(); index++) {
            table.getColumnModel().getColumn(index).setPreferredWidth(145);
        }
    }

    private void showResultOptions() {
        var menu = new JPopupMenu();
        var filter = new JMenuItem("Add filter…");
        filter.addActionListener(event -> addTransform(true));
        menu.add(filter);
        var sort = new JMenuItem("Add sort key…");
        sort.addActionListener(event -> addTransform(false));
        menu.add(sort);
        var clear = new JMenuItem("Clear filters and sort");
        clear.setEnabled(!shownResults.filters.isEmpty() || !shownResults.ordering.isEmpty());
        clear.addActionListener(event -> clearTransforms());
        menu.add(clear);
        menu.show(resultOptions, 0, resultOptions.getHeight());
    }

    private void addTransform(boolean filtering) {
        var target = shownResults;
        var results = target.fetched;
        if (results == null) return;
        var dialog = new ResultOptionsDialog(project, results.columns(), filtering);
        if (!dialog.showAndGet()) return;
        var requestedFilters = new ArrayList<>(target.filters);
        var requestedOrder = new ArrayList<>(target.ordering);
        if (filtering) requestedFilters.add(dialog.filter());
        else requestedOrder.add(dialog.sort());
        submit("Updating the local result view…", null, () -> results.view(requestedFilters, requestedOrder), view -> {
            target.filters = List.copyOf(requestedFilters);
            target.ordering = List.copyOf(requestedOrder);
            if (target == shownResults) showFetchedView(view);
        });
    }

    private void showFetchedView(FetchedResults.View view) {
        displayResult(view.columns(), view.rows());
        report("Local view · " + view.rows().size() + " visible of " + view.fetchedCount() + " fetched rows"
                + " · " + shownResults.page.page() + " pages");
    }

    private void clearTransforms() {
        shownResults.filters = List.of();
        shownResults.ordering = List.of();
        showCurrentPage();
    }

    private void exportResults() {
        var state = shownResults;
        if (state.fetched == null) return;
        FetchedResults.View view = state.fetched.view(state.filters, state.ordering);
        var dialog = new ResultExportDialog(project, view);
        if (!dialog.showAndGet()) return;
        var path = dialog.destination();
        var options = dialog.options();
        submit("Exporting fetched rows…", null, () -> {
            try {
                return ResultExport.write(path, view, options);
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                throw new DatabaseProblem("The output file already exists. Choose a new path.");
            } catch (java.nio.charset.CharacterCodingException encoding) {
                throw new DatabaseProblem("The selected encoding cannot represent every exported value.");
            } catch (java.io.IOException | UnsupportedOperationException unavailable) {
                throw new DatabaseProblem("Could not create the export. Choose a writable folder with hard-link support.");
            }
        }, count -> report("Exported " + count + " fetched rows."));
    }

    void report(String message) {
        status.setText(message == null ? "The database operation failed." : message);
        status.setToolTipText(status.getText());
        updateControls();
    }

    private void updateControls() {
        boolean cleanupPending = cleanupPending();
        connect.setEnabled(!busy && !connected && !cleanupPending);
        testConnection.setEnabled(!busy && !cleanupPending);
        retryCleanup.setVisible(cleanupPending);
        retryCleanup.setEnabled(cleanupPending && !busy);
        disconnect.setEnabled(!busy && connected);
        refresh.setEnabled(!busy && connected);
        commit.setVisible(writable);
        rollback.setVisible(writable);
        commit.setEnabled(!busy && connected && writable);
        rollback.setEnabled(!busy && connected && writable);
        run.setEnabled(!busy && connected);
        explain.setEnabled(run.isEnabled() && activeSource.dialect() != SqlText.Dialect.CLICKHOUSE);
        openConsole.setEnabled(!busy && connected);
        boolean canBrowse = !busy && connected && schema.selectedObject() != null;
        show.setEnabled(canBrowse);
        inspect.setEnabled(canBrowse);
        openTable.setEnabled(canBrowse);
        int row = dataGrid.getSelectedRow();
        boolean readyRow = !busy && canEditRow(row);
        var draft = drafts.get(row);
        editRow.setEnabled(readyRow && (draft == null || !draft.delete())
                && java.util.stream.IntStream.range(0, editableTable.definition().columns().size())
                        .anyMatch(index -> editableTable.columnType(index) != null));
        deleteRow.setEnabled(readyRow && (draft == null || !draft.delete()));
        undoRow.setEnabled(!busy && draft != null);
        applyRows.setEnabled(!busy && !drafts.isEmpty());
        reloadTable.setEnabled(!busy && connected && tableObject != null);
        nextTablePage.setEnabled(reloadTable.isEnabled() && tablePage != null && tablePage.hasNext());
        where.setEnabled(reloadTable.isEnabled());
        orderBy.setEnabled(reloadTable.isEnabled());
        if (row >= 0 && editableTable != null) {
            String reason = editableTable.rowProtection(row);
            editRow.setToolTipText(reason.isEmpty() ? null : reason);
            deleteRow.setToolTipText(editRow.getToolTipText());
        }
        boolean hasResults = !busy && connected && shownResults.fetched != null && !shownResults.fetched.columns().isEmpty();
        resultOptions.setEnabled(hasResults);
        export.setEnabled(hasResults);
        boolean canPage = connected && cursorOwner == shownResults && hasNext(shownResults.page);
        next.setEnabled(!busy && canPage);
        stop.setEnabled(busy ? active != null : canPage);
        stopTable.setEnabled(busy ? active != null : connected && hasNext(tablePage));
        for (var editor : tableEditors) editor.updateControls();
        onStateChanged.run();
    }

    static JButton button(String title, Runnable click) {
        var button = new JButton(title);
        button.addActionListener(event -> click.run());
        return button;
    }

    @Override public void dispose() {
        disposed = true;
        if (active != null) cancels.execute(active::stop);
        String sourceId = profile.id();
        commands.execute(() -> {
            try {
                releaseSession(sourceId);
            } catch (DatabaseProblem pending) {
                // The application still owns the retired session through DatabaseDriverCleanup.
            }
        });
        commands.shutdown();
        cancels.shutdown();
    }
}
