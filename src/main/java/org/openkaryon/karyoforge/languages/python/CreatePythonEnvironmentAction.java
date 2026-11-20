package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.EditorNotifications;
import com.jetbrains.python.sdk.PythonSdkUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class CreatePythonEnvironmentAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(PythonModuleContext.available(event.getProject()));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Module module = PythonModuleContext.choose(event);
        if (module != null) choose(module);
    }

    static void choose(Module module) {
        if (module.isDisposed() || module.getProject().isDisposed()) return;
        var dialog = new CreatePythonEnvironmentDialog(module);
        if (!dialog.showAndGet()) return;
        var project = module.getProject();
        var previous = PythonSdkUtil.findPythonSdk(module);
        Path base = dialog.base();
        Path target = dialog.target();
        new Task.Backgroundable(project, "Create Python Environment", true) {
            private Path executable;

            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    executable = VirtualEnvironmentCreation.create(project, module, base, target, indicator);
                } catch (java.io.IOException | com.intellij.execution.ExecutionException failure) {
                    throw new IllegalStateException(failure.getMessage(), failure);
                }
            }

            @Override public void onSuccess() {
                if (module.isDisposed() || project.isDisposed()) return;
                try {
                    var sdk = ExistingPythonEnvironments.prepare(executable);
                    if (sdk == null || !VirtualEnvironmentCreation.assignIfUnchanged(module, previous, sdk)) {
                        report("The environment was created but not assigned. Use Project Python Environment or Choose Python Interpreter to select it.");
                    } else {
                        EditorNotifications.getInstance(project).updateAllNotifications();
                    }
                } catch (ProcessCanceledException ignored) {
                    report("SDK setup was cancelled. The created environment was kept; the module interpreter was not changed.");
                } catch (IllegalArgumentException | IllegalStateException failure) {
                    report(failure.getMessage());
                }
            }

            @Override public void onCancel() {
                report("Creation was cancelled. The module interpreter was not changed.");
            }

            @Override public void onThrowable(@NotNull Throwable failure) { report(failure.getMessage()); }

            private void report(String message) {
                if (!project.isDisposed()) Messages.showInfoMessage(project, message + "\n\nEnvironment directory: "
                        + target + "\nAny partially created directory is kept for inspection; it is never overwritten or automatically deleted.",
                        "Python Environment");
            }
        }.queue();
    }
}
