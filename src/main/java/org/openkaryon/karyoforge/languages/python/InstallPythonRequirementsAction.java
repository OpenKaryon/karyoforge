package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
import com.jetbrains.python.sdk.PythonSdkUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class InstallPythonRequirementsAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(PythonModuleContext.available(event.getProject()));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Module module = PythonModuleContext.choose(event);
        if (module == null) return;
        var project = module.getProject();
        try {
            var environment = PythonDependencyOperations.selected(module);
            var roots = ModuleRootManager.getInstance(module).getContentRoots();
            var initial = roots.length == 0 ? null : roots[0].findChild("requirements.txt");
            var file = FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile()
                    .withTitle("Choose Python Requirements File"), project, initial);
            if (file == null) return;
            Path requirements = Path.of(file.getPath()).toAbsolutePath().normalize();
            var arguments = PythonDependencyOperations.requirementsArguments(requirements);
            int answer = Messages.showYesNoDialog(project,
                    "Install dependencies for module '" + module.getName() + "'?\n\nRequirements: " + requirements
                            + "\nInterpreter: " + environment.python()
                            + "\n\nPip may download packages and run their build code. Installed packages may change before an error or cancellation.",
                    "Install Python Requirements", "Install", "Cancel", null);
            if (answer != Messages.YES) return;
            FileDocumentManager.getInstance().saveAllDocuments();
            new Task.Backgroundable(project, "Install Python Requirements", true) {
                @Override public void run(@NotNull ProgressIndicator indicator) {
                    if (com.intellij.openapi.application.ReadAction.compute(() -> module.isDisposed()
                            || PythonSdkUtil.findPythonSdk(module) != environment.sdk())) {
                        throw new IllegalStateException("The module interpreter changed. Start installation again with the intended environment.");
                    }
                    try {
                        PythonDependencyOperations.run(project, module, environment, requirements.getParent(), arguments,
                                "Install Python Requirements", indicator);
                    } catch (com.intellij.execution.ExecutionException failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                }

                @Override public void onThrowable(@NotNull Throwable failure) {
                    if (!project.isDisposed()) Messages.showErrorDialog(project,
                            failure.getMessage() + "\n\nInterpreter: " + environment.python(), "Python Requirements");
                }

                @Override public void onCancel() {
                    if (!project.isDisposed()) Messages.showInfoMessage(project,
                            "Installation was cancelled. Review Run output and installed packages before retrying.\n\nInterpreter: "
                                    + environment.python(), "Python Requirements");
                }
            }.queue();
        } catch (IllegalArgumentException failure) {
            Messages.showErrorDialog(project, failure.getMessage(), "Python Requirements");
        }
    }
}
