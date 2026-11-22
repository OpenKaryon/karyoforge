package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/** The platform owns JSON editor loading, restored state and preview disposal. Opening is passive. */
public final class NotebookEditorProvider extends TextEditorWithPreviewProvider implements DumbAware {
    public NotebookEditorProvider() { super(new CellProvider()); }

    @Override public @NotNull String getEditorTypeId() { return "org.openkaryon.karyoforge.notebook"; }

    @Override protected @NotNull FileEditor createSplitEditor(@NotNull TextEditor text, @NotNull FileEditor preview) {
        return new NotebookFileEditor(text, (NotebookEditor) preview);
    }

    private static final class CellProvider implements FileEditorProvider, DumbAware {
        @Override public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
            return !file.isDirectory() && "ipynb".equalsIgnoreCase(file.getExtension());
        }
        @Override public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
            return new NotebookEditor(project, file);
        }
        @Override public @NotNull String getEditorTypeId() { return "org.openkaryon.karyoforge.notebook.cells"; }
        @Override public @NotNull FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }
    }
}
