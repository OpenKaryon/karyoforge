package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
import com.jetbrains.python.sdk.PythonSdkUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class InstallPythonProjectAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(PythonModuleContext.available(event.getProject()));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var module = PythonModuleContext.choose(event);
        if (module == null) return;
        var project = module.getProject();
        try {
            var environment = PythonDependencyOperations.selected(module);
            var roots = ModuleRootManager.getInstance(module).getContentRoots();
            var initial = roots.length == 0 ? null : roots[0].findChild("pyproject.toml");
            var selected = FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile()
                    .withTitle("Choose pyproject.toml"), project, initial);
            if (selected == null) return;
            Path file = Path.of(selected.getPath()).toAbsolutePath().normalize();
            var metadata = StandardPythonProject.read(project, file);
            var dialog = new InstallPythonProjectDialog(project, metadata, environment);
            if (!dialog.showAndGet()) return;
            boolean editable = dialog.editable();
            boolean isolation = dialog.buildIsolation();
            var extras = dialog.extras();
            FileDocumentManager.getInstance().saveAllDocuments();
            new Task.Backgroundable(project, "Install Python Project", true) {
                @Override public void run(@NotNull ProgressIndicator indicator) {
                    if (ReadAction.compute(() -> module.isDisposed() || PythonSdkUtil.findPythonSdk(module) != environment.sdk())) {
                        throw new IllegalStateException("The module interpreter changed. Start installation again with the intended environment.");
                    }
                    var current = StandardPythonProject.read(project, file);
                    try {
                        PythonDependencyOperations.run(project, module, environment, current.directory(),
                                current.arguments(editable, extras, isolation), "Install Python Project", indicator);
                    } catch (com.intellij.execution.ExecutionException failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                }

                @Override public void onThrowable(@NotNull Throwable failure) {
                    if (!project.isDisposed()) Messages.showErrorDialog(project,
                            failure.getMessage() + "\n\nInterpreter: " + environment.python(), "Python Project Installation");
                }

                @Override public void onCancel() {
                    if (!project.isDisposed()) Messages.showInfoMessage(project,
                            "Installation was cancelled. Installed packages may already have changed; review Run output before retrying.",
                            "Python Project Installation");
                }
            }.queue();
        } catch (IllegalArgumentException failure) {
            Messages.showErrorDialog(project, failure.getMessage(), "Python Project Installation");
        }
    }
}
