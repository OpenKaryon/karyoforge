package org.openkaryon.karyoforge.database.sql;

import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/** Installs Run on the SQL editor component, ahead of the platform editor's Split Line shortcut. */
public final class DatabaseSqlEditorShortcuts implements EditorFactoryListener {
    private static final Key<RunDatabaseSqlAction> ACTION = Key.create("org.openkaryon.karyoforge.database.runShortcut");
    private static final CustomShortcutSet RUN = new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK));

    @Override public void editorCreated(@NotNull EditorFactoryEvent event) {
        var editor = event.getEditor();
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!RunDatabaseSqlAction.accepts(file)) return;
        var action = new RunDatabaseSqlAction();
        action.registerCustomShortcutSet(RUN, editor.getContentComponent());
        editor.putUserData(ACTION, action);
    }

    @Override public void editorReleased(@NotNull EditorFactoryEvent event) {
        var editor = event.getEditor();
        var action = editor.getUserData(ACTION);
        if (action != null) {
            action.unregisterCustomShortcutSet(editor.getContentComponent());
            editor.putUserData(ACTION, null);
        }
    }
}
