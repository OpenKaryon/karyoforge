package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Share raw JSON with the native Text editor without persisting file associations. */
public final class NotebookJsonType implements FileTypeOverrider {
    @Override public @Nullable FileType getOverriddenFileType(@NotNull VirtualFile file) {
        if (file.isDirectory() || !"ipynb".equalsIgnoreCase(file.getExtension())) return null;
        FileType json = FileTypeManager.getInstance().getFileTypeByExtension("json");
        return json == UnknownFileType.INSTANCE ? null : json;
    }
}
