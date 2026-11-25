package org.openkaryon.karyoforge.database.sql;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import org.jetbrains.annotations.NotNull;
import org.openkaryon.karyoforge.database.ui.DatabaseEditorBridge;

public final class RunDatabaseSqlAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean sql = file != null && file.isWritable() && file.getFileType() == DatabaseSqlFileType.INSTANCE;
        event.getPresentation().setEnabledAndVisible(sql && event.getProject() != null && event.getData(CommonDataKeys.EDITOR) != null);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        var editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || file == null || editor == null || !file.isWritable()
                || file.getFileType() != DatabaseSqlFileType.INSTANCE) return;
        var caret = editor.getCaretModel().getPrimaryCaret();
        project.getService(DatabaseEditorBridge.class).run(file, editor.getDocument().getText(),
                caret.getSelectionStart(), caret.getSelectionEnd());
    }
}
