package org.openkaryon.karyoforge.data.preview;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DelimitedPreviewProvider implements FileEditorProvider, DumbAware {
    public static final String ID = "org.openkaryon.karyoforge.data.delimited";

    @Override public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return supports(file);
    }

    @Override public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        boolean csv = "csv".equalsIgnoreCase(file.getExtension());
        return new PreviewEditor(project, file, csv ? "CSV" : "TSV", new DelimitedPreviewReader(csv));
    }

    @Override public @NotNull String getEditorTypeId() { return ID; }
    @Override public @NotNull FileEditorPolicy getPolicy() { return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR; }

    private static boolean supports(VirtualFile file) {
        if (file.isDirectory() || !file.isInLocalFileSystem()) return false;
        String extension = file.getExtension();
        return "csv".equalsIgnoreCase(extension) || "tsv".equalsIgnoreCase(extension) || "tab".equalsIgnoreCase(extension);
    }

    public static final class TextType implements FileTypeOverrider {
        @Override public @Nullable FileType getOverriddenFileType(@NotNull VirtualFile file) {
            if (!supports(file)) return null;
            var associated = FileTypeManager.getInstance().getFileTypeByFileName(file.getName());
            return associated == UnknownFileType.INSTANCE ? PlainTextFileType.INSTANCE : null;
        }
    }
}
