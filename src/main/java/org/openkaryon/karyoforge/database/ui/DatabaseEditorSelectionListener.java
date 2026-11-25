package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class DatabaseEditorSelectionListener implements FileEditorManagerListener {
    private final Project project;

    public DatabaseEditorSelectionListener(Project project) { this.project = project; }

    @Override public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        project.getService(DatabaseEditorBridge.class).editorSelected(event.getNewFile());
    }

    @Override public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        project.getService(DatabaseEditorBridge.class).editorClosed(file);
    }
}
