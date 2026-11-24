package org.openkaryon.karyoforge.biology.preview;

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
import org.openkaryon.karyoforge.data.preview.PreviewEditor;
import org.openkaryon.karyoforge.data.preview.PreviewReader;

import java.util.Locale;
import java.util.function.Supplier;

public final class BiologyPreviewProvider implements FileEditorProvider, DumbAware {
    public static final String ID = "org.openkaryon.karyoforge.biology.preview";

    @Override public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return format(file) != null;
    }

    @Override public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        var format = format(file);
        if (format == null) throw new IllegalArgumentException("Unsupported biological preview file");
        return new PreviewEditor(project, file, format.name(), format.reader.get());
    }

    @Override public @NotNull String getEditorTypeId() { return ID; }
    @Override public @NotNull FileEditorPolicy getPolicy() { return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR; }

    private static Format format(VirtualFile file) {
        if (file.isDirectory() || !file.isInLocalFileSystem() || file.getExtension() == null) return null;
        return switch (file.getExtension().toLowerCase(Locale.ROOT)) {
            case "fa", "fasta", "fna", "faa", "fas" -> Format.FASTA;
            case "fq", "fastq" -> Format.FASTQ;
            default -> null;
        };
    }

    private enum Format {
        FASTA(FastaPreviewReader::new),
        FASTQ(FastqPreviewReader::new);

        private final Supplier<PreviewReader> reader;

        Format(Supplier<PreviewReader> reader) { this.reader = reader; }
    }

    public static final class TextType implements FileTypeOverrider {
        @Override public @Nullable FileType getOverriddenFileType(@NotNull VirtualFile file) {
            if (format(file) == null) return null;
            var associated = FileTypeManager.getInstance().getFileTypeByFileName(file.getName());
            return associated == UnknownFileType.INSTANCE ? PlainTextFileType.INSTANCE : null;
        }
    }
}
