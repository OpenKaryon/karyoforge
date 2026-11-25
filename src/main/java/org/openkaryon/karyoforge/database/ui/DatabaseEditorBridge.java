package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.testFramework.LightVirtualFile;
import org.openkaryon.karyoforge.database.core.JdbcSession.SchemaObject;
import org.openkaryon.karyoforge.database.core.TableDefinition;
import org.openkaryon.karyoforge.database.sql.DatabaseSqlFileType;
import org.openkaryon.karyoforge.database.sql.LoadedDatabaseCatalog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Routes editor execution through the same project command lane as the tool window. */
@Service(Service.Level.PROJECT)
public final class DatabaseEditorBridge {
    private final Project project;
    private final ConcurrentHashMap<String, LoadedDatabaseCatalog> catalogs = new ConcurrentHashMap<>();
    private volatile String selectedCatalog;
    private static final Key<String> CONSOLE_SOURCE = Key.create("org.openkaryon.karyoforge.database.consoleSource");
    private DatabasesPanel panel;

    public DatabaseEditorBridge(Project project) { this.project = project; }

    void attach(DatabasesPanel owner) { panel = owner; }

    void detach(DatabasesPanel owner) {
        if (panel == owner) {
            panel = null;
            selectedCatalog = null;
            catalogs.clear();
        }
    }

    void selectCatalog(String sourceId) { selectedCatalog = sourceId; }

    void publishCatalog(String sourceId, List<SchemaObject> objects) {
        catalogs.put(sourceId, new LoadedDatabaseCatalog(objects, Map.of()));
    }

    void publishDefinition(String sourceId, TableDefinition definition) {
        catalogs.computeIfPresent(sourceId, (id, catalog) -> catalog.withDefinition(definition));
    }

    void forgetCatalog(String sourceId) { catalogs.remove(sourceId); }

    public LoadedDatabaseCatalog catalogFor(VirtualFile file) {
        String bound = file.getUserData(CONSOLE_SOURCE);
        String source = bound == null ? selectedCatalog : bound;
        return source == null ? LoadedDatabaseCatalog.EMPTY : catalogs.getOrDefault(source, LoadedDatabaseCatalog.EMPTY);
    }

    VirtualFile console(String sourceId, String sourceName, String text) {
        var editor = new LightVirtualFile(sourceName + " Console.sql", DatabaseSqlFileType.INSTANCE, text);
        editor.putUserData(CONSOLE_SOURCE, sourceId);
        return editor;
    }

    public void run(VirtualFile editor, String text, int start, int end) {
        var window = ToolWindowManager.getInstance(project).getToolWindow(DatabasesToolWindowFactory.ID);
        if (window == null) return;
        window.show(() -> {
            if (panel != null) panel.runFromEditor(editor.getUserData(CONSOLE_SOURCE), text, start, end);
        });
    }
}
