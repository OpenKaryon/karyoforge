package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.openkaryon.karyoforge.notebook.model.NotebookDocument;
import org.openkaryon.karyoforge.notebook.model.NotebookFormatException;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Validate an isolated candidate before writing the shared native document. */
final class NotebookEdits {
    private NotebookEdits() {}

    static NotebookDocument commit(Project project, VirtualFile file, Document backing,
                                   NotebookDocument baseline, long expectedStamp, String title,
                                   Consumer<NotebookDocument> mutation) throws NotebookFormatException {
        if (!file.isWritable() || !backing.isWritable()) throw new IllegalArgumentException("The notebook is read-only.");
        if (backing.getModificationStamp() != expectedStamp) {
            throw new IllegalStateException("Notebook JSON changed. Review the refreshed cells before editing again.");
        }
        NotebookDocument candidate = baseline.copy();
        mutation.accept(candidate);
        String json = candidate.toJson();
        String previous = backing.getText();
        if (json.equals(previous)) return baseline;
        if (json.getBytes(StandardCharsets.UTF_8).length > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("The edit exceeds the notebook view's 32 MiB file limit.");
        }
        int prefix = 0;
        while (prefix < previous.length() && prefix < json.length() && previous.charAt(prefix) == json.charAt(prefix)) prefix++;
        int oldEnd = previous.length(), newEnd = json.length();
        while (oldEnd > prefix && newEnd > prefix && previous.charAt(oldEnd - 1) == json.charAt(newEnd - 1)) {
            oldEnd--;
            newEnd--;
        }
        int start = prefix, end = oldEnd;
        String replacement = json.substring(prefix, newEnd);
        WriteCommandAction.runWriteCommandAction(project, title, null, () -> {
            if (backing.getModificationStamp() != expectedStamp) {
                throw new IllegalStateException("Notebook JSON changed before the edit could be applied.");
            }
            backing.replaceString(start, end, replacement);
        });
        return json.contentEquals(backing.getImmutableCharSequence()) ? candidate : NotebookDocument.parse(backing.getText());
    }
}
