package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.testFramework.LightVirtualFile;
import org.openkaryon.karyoforge.database.sql.DatabaseSqlFileType;

/** Routes editor execution through the same project command lane as the tool window. */
@Service(Service.Level.PROJECT)
public final class DatabaseEditorBridge {
    private final Project project;
    private static final Key<String> CONSOLE_SOURCE = Key.create("org.openkaryon.karyoforge.database.consoleSource");
    private DatabasesPanel panel;

    public DatabaseEditorBridge(Project project) { this.project = project; }

    void attach(DatabasesPanel owner) { panel = owner; }

    void detach(DatabasesPanel owner) {
        if (panel == owner) panel = null;
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
