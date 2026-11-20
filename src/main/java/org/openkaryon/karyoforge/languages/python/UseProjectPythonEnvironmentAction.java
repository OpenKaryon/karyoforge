package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;


public final class UseProjectPythonEnvironmentAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(PythonModuleContext.available(event.getProject()));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Module module = PythonModuleContext.choose(event);
        if (module != null) choose(module);
    }

    public static void choose(Module module) {
        if (module.isDisposed() || module.getProject().isDisposed()) return;
        var project = module.getProject();
        var candidates = ExistingPythonEnvironments.discover(module);
        if (candidates.isEmpty()) {
            int answer = Messages.showYesNoDialog(project,
                    "No local virtual environment was found for module '" + module.getName()
                            + "'. Choose another Python interpreter?", "Python Environment", "Choose Interpreter...", "Cancel", null);
            if (answer == Messages.YES) ChoosePythonInterpreterAction.choose(module);
            return;
        }
        String[] labels = candidates.stream().map(ExistingPythonEnvironments.Candidate::label).toArray(String[]::new);
        int index = PythonChoiceDialog.choose(project, "Project Python Environment",
                "Choose the environment for module '" + module.getName() + "'.", labels);
        if (index < 0 || module.isDisposed() || project.isDisposed()) return;
        try {
            var sdk = ExistingPythonEnvironments.prepare(candidates.get(index).executable());
            if (sdk == null) {
                Messages.showErrorDialog(project, "Python SDK setup did not complete. The module interpreter was not changed.", "Python Environment");
            } else if (PythonInterpreters.assign(module, sdk)) {
                EditorNotifications.getInstance(project).updateAllNotifications();
            }
        } catch (ProcessCanceledException ignored) {
            // Cancelling native SDK setup must not assign an interpreter.
        } catch (IllegalArgumentException | IllegalStateException failure) {
            Messages.showErrorDialog(project, failure.getMessage(), "Python Environment");
        }
    }
}
