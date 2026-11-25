package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import org.openkaryon.karyoforge.database.core.DatabaseProblem;
import org.openkaryon.karyoforge.database.core.DatabaseSource;
import org.openkaryon.karyoforge.database.core.PostgresqlSource;
import org.openkaryon.karyoforge.database.core.SqliteSource;
import org.openkaryon.karyoforge.database.core.JdbcSession;
import org.openkaryon.karyoforge.database.core.QueryPage;
import org.openkaryon.karyoforge.database.core.QueryRequest;
import org.openkaryon.karyoforge.database.core.SqlResult;
import org.openkaryon.karyoforge.database.core.SqlText;
import org.openkaryon.karyoforge.database.core.SqlValue;
import org.openkaryon.karyoforge.database.core.TableDefinition;
import org.openkaryon.karyoforge.database.core.TableSnapshot;
import org.openkaryon.karyoforge.database.results.FetchedResults;
import org.openkaryon.karyoforge.database.results.ResultExport;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native project view. The JDBC command lane and EDT never share a vendor object. */
final class DatabasesPanel extends JPanel implements Disposable {
    private final Project project;
    private final DatabaseSources sources = ApplicationManager.getApplication().getService(DatabaseSources.class);
    private final ComboBox<DatabaseSources.Profile> profiles = new ComboBox<>();
    private final JBList<JdbcSession.SchemaObject> schema = new JBList<>();
    private final JBTextArea sql = new JBTextArea(5, 48);
    private final JBTable table = new JBTable();
    private final JBTable structure = new JBTable();
    private final JBTable dataGrid = new JBTable();
    private final JPanel tableDataTab = new JPanel(new BorderLayout());
    private final JBTextArea definition = new JBTextArea();
    private final JTabbedPane details = new JTabbedPane();
    private final JScrollPane resultsTab = new JBScrollPane(table);
    private final JScrollPane structureTab = new JBScrollPane(structure);
    private final JBLabel status = new JBLabel("Choose or add a data source.");
    private final JButton add = button("Add SQLite Source", () -> editSource(null, false));
    private final JButton addPostgresql = button("Add PostgreSQL Source", () -> editSource(null, true));
    private final JButton edit = button("Edit Source", () -> {
        var profile = selectedSource();
        if (profile != null) editSource(profile, profile.source() instanceof PostgresqlSource);
    });
    private final JButton remove = button("Remove Source", this::removeSource);
    private final JButton connect = button("Connect", this::connect);
    private final JButton disconnect = button("Disconnect", this::disconnect);
    private final JButton show = button("Show Rows", this::showRows);
    private final JButton inspect = button("Inspect", this::inspect);
    private final JButton openTable = button("Open Table", this::openTable);
    private final JButton editRow = button("Edit Row", this::editRow);
    private final JButton deleteRow = button("Delete Row", this::deleteRow);
    private final JButton undoRow = button("Discard Row Draft", this::undoRow);
    private final JButton applyRows = button("Review & Apply", this::applyRows);
    private final JButton refresh = button("Refresh Schema", this::refreshSchema);
    private final JButton commit = button("Commit", () -> finishTransaction(true));
    private final JButton rollback = button("Rollback", () -> finishTransaction(false));
    private final JButton run = button("Run SQL", this::runSql);
    private final JButton next = button("Next Result Page", this::next);
    private final JButton stop = button("Stop", this::stop);
    private final JButton resultOptions = button("Result Options", this::showResultOptions);
    private final JButton export = button("Export Results", this::exportResults);
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
    private QueryPage page;
    private FetchedResults fetched;
    private List<FetchedResults.Filter> filters = List.of();
    private List<FetchedResults.Sort> ordering = List.of();
    private TableSnapshot editableTable;
    private final TreeMap<Integer, RowDraft> drafts = new TreeMap<>();

    DatabasesPanel(Project project) {
        super(new BorderLayout(0, 4));
        this.project = project;
        profiles.getAccessibleContext().setAccessibleName("Database data sources");
        schema.getAccessibleContext().setAccessibleName("Database schema objects");
        schema.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sql.getAccessibleContext().setAccessibleName("SQL statement");
        table.getAccessibleContext().setAccessibleName("Database query results");
        structure.getAccessibleContext().setAccessibleName("Database object columns");
        definition.getAccessibleContext().setAccessibleName("Database object definition");
        definition.setEditable(false);
        structure.setDefaultRenderer(Object.class, literalRenderer());
        dataGrid.getAccessibleContext().setAccessibleName("SQLite table data");
        dataGrid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dataGrid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        dataGrid.setDefaultRenderer(Object.class, literalRenderer());
        dataGrid.getTableHeader().setDefaultRenderer(literalRenderer());
        status.getAccessibleContext().setAccessibleName("Database status");
        status.putClientProperty("html.disable", true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, literalRenderer());
        table.getTableHeader().setDefaultRenderer(literalRenderer());
        schema.setCellRenderer(literalListRenderer());
        profiles.setRenderer(literalListRenderer());

        var sourceControls = controls(profiles, add, addPostgresql, edit, remove, connect, disconnect);
        var objectPanel = new JPanel(new BorderLayout());
        objectPanel.add(new JBScrollPane(schema), BorderLayout.CENTER);
        objectPanel.add(controls(show, openTable, inspect, refresh), BorderLayout.SOUTH);
        var queryPanel = new JPanel(new BorderLayout());
        queryPanel.add(new JBScrollPane(sql), BorderLayout.CENTER);
        queryPanel.add(controls(run, next, stop, resultOptions, export, commit, rollback), BorderLayout.SOUTH);
        details.addTab("Results", resultsTab);
        details.addTab("Structure", structureTab);
        details.addTab("DDL", new JBScrollPane(definition));
        details.setEnabledAt(1, false);
        details.setEnabledAt(2, false);
        tableDataTab.add(new JBScrollPane(dataGrid), BorderLayout.CENTER);
        tableDataTab.add(controls(editRow, deleteRow, undoRow, applyRows), BorderLayout.SOUTH);
        details.addTab("Table Data", tableDataTab);
        details.setEnabledAt(3, false);
        var queryAndResult = new JSplitPane(JSplitPane.VERTICAL_SPLIT, queryPanel, details);
        queryAndResult.setResizeWeight(0.25);
        var explorer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, objectPanel, queryAndResult);
        explorer.setResizeWeight(0.25);
        add(sourceControls, BorderLayout.NORTH);
        add(explorer, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        profiles.addActionListener(event -> updateControls());
        schema.addListSelectionListener(event -> updateControls());
        dataGrid.getSelectionModel().addListSelectionListener(event -> updateControls());
        sql.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "runSql");
        sql.getActionMap().put("runSql", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (run.isEnabled()) runSql();
            }
        });
        refreshProfiles(null);
        updateControls();
    }

    private static DefaultListCellRenderer literalListRenderer() {
        var renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", true);
        return renderer;
    }

    private static DefaultTableCellRenderer literalRenderer() {
        var renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", true);
        return renderer;
    }

    private static JPanel controls(JComponent... components) {
        var bar = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 2));
        for (var component : components) bar.add(component);
        return bar;
    }

    private DatabaseSources.Profile selectedSource() {
        return (DatabaseSources.Profile) profiles.getSelectedItem();
    }

    private void refreshProfiles(String preferred) {
        var options = sources.profiles();
        profiles.setModel(new DefaultComboBoxModel<>(options.toArray(DatabaseSources.Profile[]::new)));
        for (var profile : options) {
            if (profile.id().equals(preferred)) {
                profiles.setSelectedItem(profile);
                break;
            }
        }
    }

    private void editSource(DatabaseSources.Profile profile, boolean postgresql) {
        DatabaseSource source;
        if (postgresql) {
            var dialog = new PostgresqlSourceDialog(project, profile == null ? null : (PostgresqlSource) profile.source());
            if (!dialog.showAndGet()) return;
            source = dialog.source();
        } else {
            var dialog = new SqliteSourceDialog(project, profile == null ? null : (SqliteSource) profile.source());
            if (!dialog.showAndGet()) return;
            source = dialog.source();
        }
        String id = sources.save(profile == null ? null : profile.id(), source);
        refreshProfiles(id);
        report("Data source saved.");
    }

    private void removeSource() {
        var profile = selectedSource();
        if (profile == null) return;
        int result = Messages.showYesNoDialog(project, "Remove the saved data source from the IDE?",
                "Remove Data Source", Messages.getQuestionIcon());
        if (result != Messages.YES) return;
        sources.remove(profile.id());
        refreshProfiles(null);
        report("Data source removed.");
    }

    private void connect() {
        var profile = selectedSource();
        if (profile == null) return;
        char[] password = new char[0];
        if (profile.source() instanceof PostgresqlSource) {
            var dialog = new ConnectionPasswordDialog(project);
            if (!dialog.showAndGet()) return;
            password = dialog.takePassword();
        }
        final char[] entered = password;
        if (disposed || busy) {
            Arrays.fill(entered, '\0');
            return;
        }
        submit("Connecting to " + profile.source().engine() + "…", null, () -> {
            try {
                if (session != null) session.close();
                session = new JdbcSession(profile.source());
                session.connect(entered);
                return session.schema();
            } catch (DatabaseProblem unavailable) {
                if (session != null) session.close();
                session = null;
                throw unavailable;
            } finally {
                Arrays.fill(entered, '\0');
            }
        }, objects -> {
            connected = true;
            activeSource = profile.source();
            writable = profile.source().writable();
            showSchema(objects);
            report(writable ? "Connected in read-write mode. Use Commit or Rollback to finish changes."
                    : "Connected in read-only mode. " + objects.size() + " schema objects.");
        });
    }

    private void disconnect() {
        if (!discardTableDraft()) return;
        submit("Disconnecting…", null, () -> {
            if (session != null) {
                session.close();
                session = null;
            }
            return Boolean.TRUE;
        }, ignored -> {
            connected = false;
            writable = false;
            activeSource = null;
            showSchema(List.of());
            clearResult();
            details.setSelectedComponent(resultsTab);
            details.setEnabledAt(1, false);
            details.setEnabledAt(2, false);
            definition.setText("");
            structure.setModel(new javax.swing.table.DefaultTableModel());
            report("Disconnected.");
        });
    }

    private void showRows() {
        var object = schema.getSelectedValue();
        if (object != null) {
            sql.setText(object.selectSql());
            runSql();
        }
    }

    private void runSql() {
        if (!connected || busy) return;
        SqlText.Command command;
        try {
            var dialect = activeSource.dialect();
            command = SqlText.compile(SqlText.activeStatement(sql.getText(), sql.getSelectionStart(), sql.getSelectionEnd(), dialect), dialect);
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
        submit("Running SQL…", request, () -> session.execute(command, bindings, request), this::showResult);
    }

    private void openTable() {
        var object = schema.getSelectedValue();
        if (object == null || !discardTableDraft()) return;
        var request = new QueryRequest();
        submit("Opening table data…", request, () -> session.openTable(object, request), this::showTable);
    }

    private void showTable(TableSnapshot snapshot) {
        clearResult();
        showDefinition(snapshot.definition());
        editableTable = snapshot;
        var result = snapshot.data();
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
        report(snapshot.definition().object().name() + " · " + result.rows().size() + " rows"
                + (result.notice().isEmpty() ? "" : " · " + result.notice())
                + (snapshot.protection().isEmpty() ? "" : " · " + snapshot.protection()));
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

    private boolean discardTableDraft() {
        if (!drafts.isEmpty() && Messages.showYesNoDialog(project, "Discard unapplied table drafts?",
                "Discard Table Drafts", Messages.getQuestionIcon()) != Messages.YES) return false;
        resetTableData();
        return true;
    }

    private void resetTableData() {
        editableTable = null;
        drafts.clear();
        if (details.getSelectedComponent() == tableDataTab) details.setSelectedComponent(resultsTab);
        details.setEnabledAt(3, false);
        dataGrid.setModel(new javax.swing.table.DefaultTableModel());
    }

    private record RowDraft(boolean delete, Map<Integer, TableRowDialog.DraftValue> values) {}

    private void inspect() {
        var object = schema.getSelectedValue();
        if (object == null) return;
        submit("Reading object definition…", null, () -> session.describe(object), this::showDefinition);
    }

    private void showDefinition(TableDefinition object) {
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
        definition.setText(object.ddl());
        definition.setCaretPosition(0);
        details.setEnabledAt(1, true);
        details.setEnabledAt(2, true);
        details.setSelectedComponent(structureTab);
        report("Definition of " + object.object().name() + (object.clipped() ? " · DDL preview clipped." : "."));
    }

    private void refreshSchema() {
        submit("Refreshing the schema…", null, () -> session.schema(), objects -> {
            showSchema(objects);
            report("Schema refreshed. " + objects.size() + " objects.");
        });
    }

    private void showSchema(List<JdbcSession.SchemaObject> objects) {
        var selected = schema.getSelectedValue();
        schema.setListData(objects.toArray(JdbcSession.SchemaObject[]::new));
        if (selected != null) schema.setSelectedValue(selected, false);
    }

    private void finishTransaction(boolean commitChanges) {
        if (!discardTableDraft()) return;
        submit(commitChanges ? "Committing…" : "Rolling back…", null, () -> {
            session.finishTransaction(commitChanges);
            return Boolean.TRUE;
        }, ignored -> {
            clearResult();
            report(commitChanges ? "Transaction committed." : "Transaction rolled back.");
        });
    }

    private void clearResult() {
        page = null;
        fetched = null;
        filters = List.of();
        ordering = List.of();
        table.setModel(new javax.swing.table.DefaultTableModel());
    }

    private void showResult(SqlResult result) {
        if (result instanceof QueryPage query) {
            showPage(query);
        } else if (result instanceof SqlResult.UpdateCount count) {
            clearResult();
            details.setSelectedComponent(resultsTab);
            report(count.rows() < 0 ? "Statement completed. Changes are pending."
                    : count.rows() + " affected rows. Changes are pending.");
        }
    }

    private void next() {
        if (page == null || !page.hasNext()) return;
        var request = new QueryRequest();
        submit("Reading the next result page…", request, () -> session.next(request), this::showPage);
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
        if (cursorMayHaveClosed && page != null && page.hasNext()) {
            page = new QueryPage(page.columns(), page.rows(), page.page(), page.totalRows(), false, page.notice());
        }
        report(message);
    }

    private void stop() {
        QueryRequest request = active;
        if (request == null) return;
        stop.setEnabled(false);
        status.setText("Stopping the current database request…");
        cancels.execute(request::stop);
    }

    private void showPage(QueryPage result) {
        page = result;
        if (result.page() == 1) {
            fetched = FetchedResults.first(result);
            filters = List.of();
            ordering = List.of();
        } else {
            fetched = fetched.append(result);
        }
        details.setSelectedComponent(resultsTab);
        if (filters.isEmpty() && ordering.isEmpty()) {
            showCurrentPage();
        } else {
            try {
                showFetchedView(fetched.view(filters, ordering));
            } catch (IllegalArgumentException incomparable) {
                clearTransforms();
                report(incomparable.getMessage() + " Fetched rows are retained.");
            }
        }
    }

    private void showCurrentPage() {
        displayResult(page.columns(), page.rows());
        report("Page " + page.page() + " · " + page.rows().size() + " rows"
                + (page.notice().isEmpty() ? "" : " · " + page.notice()));
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
        clear.setEnabled(!filters.isEmpty() || !ordering.isEmpty());
        clear.addActionListener(event -> clearTransforms());
        menu.add(clear);
        menu.show(resultOptions, 0, resultOptions.getHeight());
    }

    private void addTransform(boolean filtering) {
        var results = fetched;
        if (results == null) return;
        var dialog = new ResultOptionsDialog(project, results.columns(), filtering);
        if (!dialog.showAndGet()) return;
        var requestedFilters = new ArrayList<>(filters);
        var requestedOrder = new ArrayList<>(ordering);
        if (filtering) requestedFilters.add(dialog.filter());
        else requestedOrder.add(dialog.sort());
        submit("Updating the local result view…", null, () -> results.view(requestedFilters, requestedOrder), view -> {
            filters = List.copyOf(requestedFilters);
            ordering = List.copyOf(requestedOrder);
            showFetchedView(view);
        });
    }

    private void showFetchedView(FetchedResults.View view) {
        displayResult(view.columns(), view.rows());
        report("Local view · " + view.rows().size() + " visible of " + view.fetchedCount() + " fetched rows"
                + " · " + page.page() + " pages");
    }

    private void clearTransforms() {
        filters = List.of();
        ordering = List.of();
        showCurrentPage();
    }

    private void exportResults() {
        if (fetched == null) return;
        FetchedResults.View view = fetched.view(filters, ordering);
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

    private void report(String message) {
        status.setText(message == null ? "The database operation failed." : message);
        status.setToolTipText(status.getText());
        updateControls();
    }

    private void updateControls() {
        boolean canManage = !busy && !connected;
        boolean selected = selectedSource() != null;
        profiles.setEnabled(canManage);
        add.setEnabled(canManage);
        addPostgresql.setEnabled(canManage);
        edit.setEnabled(canManage && selected);
        remove.setEnabled(canManage && selected);
        connect.setEnabled(canManage && selected);
        disconnect.setEnabled(!busy && connected);
        refresh.setEnabled(!busy && connected);
        commit.setVisible(writable);
        rollback.setVisible(writable);
        commit.setEnabled(!busy && connected && writable);
        rollback.setEnabled(!busy && connected && writable);
        run.setEnabled(!busy && connected);
        boolean canBrowse = !busy && connected && schema.getSelectedValue() != null;
        show.setEnabled(canBrowse);
        inspect.setEnabled(canBrowse);
        openTable.setEnabled(canBrowse && activeSource instanceof SqliteSource);
        int row = dataGrid.getSelectedRow();
        boolean readyRow = !busy && canEditRow(row);
        var draft = drafts.get(row);
        editRow.setEnabled(readyRow && (draft == null || !draft.delete())
                && java.util.stream.IntStream.range(0, editableTable.definition().columns().size())
                        .anyMatch(index -> editableTable.columnType(index) != null));
        deleteRow.setEnabled(readyRow && (draft == null || !draft.delete()));
        undoRow.setEnabled(!busy && draft != null);
        applyRows.setEnabled(!busy && !drafts.isEmpty());
        if (row >= 0 && editableTable != null) {
            String reason = editableTable.rowProtection(row);
            editRow.setToolTipText(reason.isEmpty() ? null : reason);
            deleteRow.setToolTipText(editRow.getToolTipText());
        }
        boolean hasResults = !busy && connected && fetched != null && !fetched.columns().isEmpty();
        resultOptions.setEnabled(hasResults);
        export.setEnabled(hasResults);
        next.setEnabled(!busy && connected && page != null && page.hasNext());
        stop.setEnabled(busy && active != null);
    }

    private static JButton button(String title, Runnable click) {
        var button = new JButton(title);
        button.addActionListener(event -> click.run());
        return button;
    }

    @Override public void dispose() {
        disposed = true;
        if (active != null) cancels.execute(active::stop);
        commands.execute(() -> {
            if (session != null) session.close();
        });
        commands.shutdown();
        cancels.shutdown();
    }
}
