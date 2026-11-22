package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;
import org.openkaryon.karyoforge.notebook.model.CellType;
import org.openkaryon.karyoforge.notebook.model.NotebookDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class NewNotebookAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) return;
        var chooser = FileChooserFactory.getInstance().createSaveFileDialog(
                new FileSaverDescriptor("New Jupyter Notebook", "Create a notebook in an existing directory", "ipynb"), project);
        Path initial = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        var chosen = chooser.save(initial, "Untitled.ipynb");
        if (chosen == null) return;
        Path target = chosen.getFile().toPath();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".ipynb")) {
            target = target.resolveSibling(target.getFileName() + ".ipynb");
        }
        try {
            create(target);
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target);
            if (file == null) throw new IOException("The created notebook is not visible to the IDE yet: " + target);
            FileEditorManager.getInstance(project).openFile(file, true);
        } catch (IOException failure) {
            Messages.showErrorDialog(project, failure.getMessage(), "New Jupyter Notebook");
        }
    }

    static void create(Path target) throws IOException {
        NotebookDocument document = NotebookDocument.create();
        document.insertCell(0, CellType.CODE, "");
        // Exclusive creation also protects against a file appearing after the chooser closes.
        Files.writeString(target, document.toJson(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
