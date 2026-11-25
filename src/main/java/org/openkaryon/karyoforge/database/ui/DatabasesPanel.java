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
import org.openkaryon.karyoforge.database.core.JdbcSession;
import org.openkaryon.karyoforge.database.core.QueryPage;
import org.openkaryon.karyoforge.database.core.QueryRequest;
import org.openkaryon.karyoforge.database.core.SqlText;
import org.openkaryon.karyoforge.database.core.SqlValue;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
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
    private final JBLabel status = new JBLabel("Choose or add a SQLite data source.");
    private final JButton add = button("Add SQLite Source", () -> editSource(null));
    private final JButton edit = button("Edit Source", () -> editSource(selectedSource()));
    private final JButton remove = button("Remove Source", this::removeSource);
    private final JButton connect = button("Connect", this::connect);
    private final JButton disconnect = button("Disconnect", this::disconnect);
    private final JButton show = button("Show Rows", this::showRows);
    private final JButton run = button("Run SQL", this::runSql);
    private final JButton next = button("Next Result Page", this::next);
    private final JButton stop = button("Stop", this::stop);
    private final java.util.concurrent.ExecutorService commands =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("KaryoForge database command").factory());
    private final java.util.concurrent.ExecutorService cancels =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("KaryoForge database Stop").factory());
    private JdbcSession session; // Accessed only by the command executor.
    private boolean connected;
    private boolean busy;
    private boolean disposed;
    private QueryRequest active;
    private QueryPage page;

    DatabasesPanel(Project project) {
        super(new BorderLayout(0, 4));
        this.project = project;
        profiles.getAccessibleContext().setAccessibleName("Database data sources");
        schema.getAccessibleContext().setAccessibleName("Database schema objects");
        schema.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sql.getAccessibleContext().setAccessibleName("SQL statement");
        table.getAccessibleContext().setAccessibleName("Database query results");
        status.getAccessibleContext().setAccessibleName("Database status");
        status.putClientProperty("html.disable", true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setDefaultRenderer(Object.class, literalRenderer());
        table.getTableHeader().setDefaultRenderer(literalRenderer());
        schema.setCellRenderer(literalListRenderer());
        profiles.setRenderer(literalListRenderer());

        var sourceControls = controls(profiles, add, edit, remove, connect, disconnect);
        var objectPanel = new JPanel(new BorderLayout());
        objectPanel.add(new JBScrollPane(schema), BorderLayout.CENTER);
        objectPanel.add(controls(show), BorderLayout.SOUTH);
        var queryPanel = new JPanel(new BorderLayout());
        queryPanel.add(new JBScrollPane(sql), BorderLayout.CENTER);
        queryPanel.add(controls(run, next, stop), BorderLayout.SOUTH);
        var queryAndResult = new JSplitPane(JSplitPane.VERTICAL_SPLIT, queryPanel, new JBScrollPane(table));
        queryAndResult.setResizeWeight(0.25);
        var explorer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, objectPanel, queryAndResult);
        explorer.setResizeWeight(0.25);
        add(sourceControls, BorderLayout.NORTH);
        add(explorer, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        profiles.addActionListener(event -> updateControls());
        schema.addListSelectionListener(event -> updateControls());
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

    private void editSource(DatabaseSources.Profile profile) {
        var dialog = new SqliteSourceDialog(project, profile == null ? null : profile.source());
        if (dialog.showAndGet()) {
            String id = sources.save(profile == null ? null : profile.id(), dialog.source());
            refreshProfiles(id);
            report("Data source saved.");
        }
    }

    private void removeSource() {
        var profile = selectedSource();
        if (profile == null) return;
        int result = Messages.showYesNoDialog(project, "Remove the saved data source? The database file will be kept.",
                "Remove Data Source", Messages.getQuestionIcon());
        if (result != Messages.YES) return;
        sources.remove(profile.id());
        refreshProfiles(null);
        report("Data source removed.");
    }

    private void connect() {
        var profile = selectedSource();
        if (profile == null) return;
        submit("Connecting to SQLite…", null, () -> {
            if (session != null) session.close();
            session = new JdbcSession(profile.source());
            try {
                session.connect();
                return session.schema();
            } catch (DatabaseProblem unavailable) {
                session.close();
                session = null;
                throw unavailable;
            }
        }, objects -> {
            connected = true;
            schema.setListData(objects.toArray(JdbcSession.SchemaObject[]::new));
            report("Connected in read-only mode. " + objects.size() + " schema objects.");
        });
    }

    private void disconnect() {
        submit("Disconnecting…", null, () -> {
            if (session != null) {
                session.close();
                session = null;
            }
            return Boolean.TRUE;
        }, ignored -> {
            connected = false;
            schema.setListData(new JdbcSession.SchemaObject[0]);
            page = null;
            table.setModel(new javax.swing.table.DefaultTableModel());
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
            command = SqlText.compile(SqlText.activeStatement(sql.getText(), sql.getSelectionStart(), sql.getSelectionEnd()));
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
        var request = new QueryRequest();
        var bindings = values;
        submit("Running SQL…", request, () -> session.execute(command, bindings, request), this::showPage);
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
                onUi(() -> showFailure(failure.getMessage()));
            } catch (ProcessCanceledException failure) {
                onUi(() -> showFailure("Database request stopped."));
            } catch (RuntimeException | LinkageError failure) {
                onUi(() -> showFailure("The database operation failed."));
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

    private void showFailure(String message) {
        // A failed or stopped read may have closed its retained cursor. Keep its detached page.
        if (page != null && page.hasNext()) {
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
        table.setModel(new AbstractTableModel() {
            @Override public int getRowCount() { return result.rows().size(); }
            @Override public int getColumnCount() { return result.columns().size(); }
            @Override public String getColumnName(int column) { return result.columns().get(column).label(); }
            @Override public Object getValueAt(int row, int column) { return result.rows().get(row).cells().get(column).display(); }
        });
        for (int index = 0; index < table.getColumnCount(); index++) {
            table.getColumnModel().getColumn(index).setPreferredWidth(145);
        }
        report("Page " + result.page() + " · " + result.rows().size() + " rows"
                + (result.notice().isEmpty() ? "" : " · " + result.notice()));
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
        edit.setEnabled(canManage && selected);
        remove.setEnabled(canManage && selected);
        connect.setEnabled(canManage && selected);
        disconnect.setEnabled(!busy && connected);
        run.setEnabled(!busy && connected);
        show.setEnabled(!busy && connected && schema.getSelectedValue() != null);
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
