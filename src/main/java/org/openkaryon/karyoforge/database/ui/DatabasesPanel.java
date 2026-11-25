package org.openkaryon.karyoforge.database.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import org.openkaryon.karyoforge.database.core.ClickHouseSource;
import org.openkaryon.karyoforge.database.core.DatabaseSource;
import org.openkaryon.karyoforge.database.core.DuckDbSource;
import org.openkaryon.karyoforge.database.core.MysqlSource;
import org.openkaryon.karyoforge.database.core.PostgresqlSource;
import org.openkaryon.karyoforge.database.core.SqliteSource;
import org.openkaryon.karyoforge.database.core.SqlText;

import javax.swing.*;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.openkaryon.karyoforge.database.ui.DatabaseSessionPanel.button;
import static org.openkaryon.karyoforge.database.ui.DatabaseSessionPanel.controls;
import static org.openkaryon.karyoforge.database.ui.DatabaseSessionPanel.literalListRenderer;

/** Selects among independent source workspaces in this project. */
final class DatabasesPanel extends JPanel implements Disposable {
    private static final String LAST_SOURCE = "org.openkaryon.karyoforge.database.lastSource";
    private final Project project;
    private final DatabaseEditorBridge editorBridge;
    private final DatabaseSources sources = ApplicationManager.getApplication().getService(DatabaseSources.class);
    private final ComboBox<DatabaseSources.Profile> profiles = new ComboBox<>();
    private final Map<String, DatabaseSessionPanel> workspaces = new LinkedHashMap<>();
    private final JPanel viewer = new JPanel(new BorderLayout());
    private final JBLabel empty = new JBLabel("Choose or add a data source.");
    private final JButton add = button("Add SQLite Source", () -> editSource(null, SqlText.Dialect.SQLITE));
    private final JButton addDuck = button("Add DuckDB Source", () -> editSource(null, SqlText.Dialect.DUCKDB));
    private final JButton addServer = button("Add Server Source", this::addServerSource);
    private final JButton edit = button("Edit Source", () -> {
        var profile = selectedSource();
        if (profile != null) editSource(profile, profile.source().dialect());
    });
    private final JButton remove = button("Remove Source", this::removeSource);
    private final JButton limits = button("Query Limits", this::editLimits);
    private final JPanel toolbar = controls(profiles, add, addDuck, addServer, edit, remove, limits);
    private DatabaseSessionPanel selected;
    private boolean refreshing;
    private boolean disposed;

    DatabasesPanel(Project project) {
        super(new BorderLayout(0, 4));
        this.project = project;
        editorBridge = project.getService(DatabaseEditorBridge.class);
        editorBridge.attach(this);
        profiles.getAccessibleContext().setAccessibleName("Database data sources");
        profiles.setRenderer(literalListRenderer());
        empty.getAccessibleContext().setAccessibleName("Database status");
        empty.putClientProperty("html.disable", true);
        add(toolbar, BorderLayout.NORTH);
        add(viewer, BorderLayout.CENTER);
        profiles.addActionListener(event -> {
            if (!refreshing) showSelectedSource();
        });
        refreshProfiles(PropertiesComponent.getInstance(project).getValue(LAST_SOURCE));
    }

    private DatabaseSources.Profile selectedSource() {
        return (DatabaseSources.Profile) profiles.getSelectedItem();
    }

    private void refreshProfiles(String preferred) {
        var options = sources.profiles();
        refreshing = true;
        try {
            profiles.setModel(new DefaultComboBoxModel<>(options.toArray(DatabaseSources.Profile[]::new)));
            for (var profile : options) {
                if (profile.id().equals(preferred)) {
                    profiles.setSelectedItem(profile);
                    break;
                }
            }
        } finally {
            refreshing = false;
        }
        showSelectedSource();
    }

    private void showSelectedSource() {
        if (selected != null) toolbar.remove(selected.actionButtons());
        viewer.removeAll();
        var profile = selectedSource();
        if (profile == null) {
            selected = null;
            viewer.add(empty, BorderLayout.SOUTH);
        } else {
            selected = workspaces.computeIfAbsent(profile.id(),
                    id -> new DatabaseSessionPanel(project, profile, editorBridge, this::updateControls));
            selected.updateProfile(profile);
            toolbar.add(selected.actionButtons());
            viewer.add(selected, BorderLayout.CENTER);
        }
        String id = profile == null ? null : profile.id();
        editorBridge.selectCatalog(id);
        PropertiesComponent.getInstance(project).setValue(LAST_SOURCE, id);
        revalidate();
        repaint();
        updateControls();
    }

    void runFromEditor(String sourceId, String text, int start, int end, boolean explain) {
        if (sourceId != null) {
            var target = sources.profiles().stream().filter(profile -> profile.id().equals(sourceId)).findFirst().orElse(null);
            if (target == null) {
                report("This console's data source was removed.");
                return;
            }
            if (selectedSource() == null || !sourceId.equals(selectedSource().id())) refreshProfiles(sourceId);
        }
        if (selected == null) report("Connect a data source before running SQL from the editor.");
        else selected.runFromEditor(text, start, end, explain);
    }

    private void updateControls() {
        boolean ready = selected != null && !selected.busy();
        edit.setEnabled(ready);
        limits.setEnabled(ready);
        remove.setEnabled(ready && !selected.connected() && !selected.cleanupPending());
    }

    private void report(String status) {
        if (selected == null) empty.setText(status);
        else selected.report(status);
    }

    private void addServerSource() {
        var menu = new JPopupMenu();
        var postgres = new JMenuItem("PostgreSQL");
        postgres.addActionListener(event -> editSource(null, SqlText.Dialect.POSTGRESQL));
        menu.add(postgres);
        var mysql = new JMenuItem("MySQL");
        mysql.addActionListener(event -> editSource(null, SqlText.Dialect.MYSQL));
        menu.add(mysql);
        var maria = new JMenuItem("MariaDB");
        maria.addActionListener(event -> editSource(null, SqlText.Dialect.MYSQL, MysqlSource.Variant.MARIADB));
        menu.add(maria);
        var click = new JMenuItem("ClickHouse");
        click.addActionListener(event -> editSource(null, SqlText.Dialect.CLICKHOUSE));
        menu.add(click);
        menu.show(addServer, 0, addServer.getHeight());
    }

    private void editSource(DatabaseSources.Profile profile, SqlText.Dialect dialect) {
        editSource(profile, dialect, MysqlSource.Variant.MYSQL);
    }

    private void editSource(DatabaseSources.Profile profile, SqlText.Dialect dialect, MysqlSource.Variant mysqlVariant) {
        var existing = profile == null ? null : profile.source();
        DatabaseSource source = switch (dialect) {
            case SQLITE -> {
                var dialog = new SqliteSourceDialog(project, (SqliteSource) existing);
                yield dialog.showAndGet() ? dialog.source() : null;
            }
            case DUCKDB -> {
                var dialog = new DuckDbSourceDialog(project, (DuckDbSource) existing);
                yield dialog.showAndGet() ? dialog.source() : null;
            }
            case POSTGRESQL -> {
                var dialog = new PostgresqlSourceDialog(project, (PostgresqlSource) existing);
                yield dialog.showAndGet() ? dialog.source() : null;
            }
            case CLICKHOUSE -> {
                var dialog = new ClickHouseSourceDialog(project, (ClickHouseSource) existing);
                yield dialog.showAndGet() ? dialog.source() : null;
            }
            case MYSQL -> {
                var dialog = new MysqlSourceDialog(project, (MysqlSource) existing, mysqlVariant);
                yield dialog.showAndGet() ? dialog.source() : null;
            }
        };
        if (source == null) return;
        String id = sources.save(profile == null ? null : profile.id(), source);
        refreshProfiles(id);
        if (profile != null && profile.source().usesPassword()
                && !DatabaseCredentials.sameDestination(profile, new DatabaseSources.Profile(id, source))) {
            forgetPassword(profile);
        }
        report("Data source saved.");
    }

    private void editLimits() {
        var profile = selectedSource();
        if (profile == null) return;
        var dialog = new DatabaseResultLimitsDialog(project, profile);
        if (!dialog.showAndGet()) return;
        boolean reconnect = selected != null && selected.connected();
        sources.saveLimits(profile.id(), dialog.limits(), dialog.timeouts());
        refreshProfiles(profile.id());
        report(reconnect ? "Query limits saved. Reconnect this source to apply them." : "Query limits saved.");
    }

    private void removeSource() {
        var profile = selectedSource();
        if (profile == null || selected == null || selected.busy() || selected.connected() || selected.cleanupPending()) return;
        int result = Messages.showYesNoDialog(project, "Remove the saved data source from the IDE?",
                "Remove Data Source", Messages.getQuestionIcon());
        if (result != Messages.YES) return;
        if (profile.source().usesPassword()) forgetPassword(profile);
        var workspace = workspaces.remove(profile.id());
        if (workspace != null) workspace.dispose();
        editorBridge.forgetCatalog(profile.id());
        sources.remove(profile.id());
        refreshProfiles(null);
        report("Data source removed.");
    }

    private void forgetPassword(DatabaseSources.Profile profile) {
        Runnable cleanup = () -> {
            try {
                DatabaseCredentials.forget(profile);
            } catch (RuntimeException unavailable) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed && !project.isDisposed()) {
                        Messages.showWarningDialog(project, "PasswordSafe could not clear the previous database credential.",
                                "KaryoForge Databases");
                    }
                });
            }
        };
        var workspace = workspaces.get(profile.id());
        if (workspace == null) ApplicationManager.getApplication().executeOnPooledThread(cleanup);
        else workspace.queueCredentialCleanup(cleanup);
    }

    @Override public void dispose() {
        disposed = true;
        editorBridge.detach(this);
        for (var workspace : workspaces.values()) workspace.dispose();
        workspaces.clear();
    }
}
