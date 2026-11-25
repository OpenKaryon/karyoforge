package org.openkaryon.karyoforge.database.sql;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.openkaryon.karyoforge.database.ui.DatabaseEditorBridge;

public final class RunDatabaseSqlAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        var editor = event.getData(CommonDataKeys.EDITOR);
        var file = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && accepts(file));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!accepts(file)) return;
        var caret = editor.getCaretModel().getPrimaryCaret();
        project.getService(DatabaseEditorBridge.class).run(file, editor.getDocument().getText(),
                caret.getSelectionStart(), caret.getSelectionEnd());
    }

    static boolean accepts(VirtualFile file) {
        return file != null && file.isWritable() && file.getFileType() == DatabaseSqlFileType.INSTANCE;
    }
}
