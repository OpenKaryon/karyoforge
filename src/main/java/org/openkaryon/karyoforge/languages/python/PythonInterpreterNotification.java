package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.function.Function;

public final class PythonInterpreterNotification implements EditorNotificationProvider, DumbAware {
    @Override
    public @Nullable Function<? super @NotNull FileEditor, ? extends @Nullable JComponent> collectNotificationData(
            @NotNull Project project, @NotNull VirtualFile file) {
        var module = PythonInterpreters.owner(project, file);
        if (module == null) {
            return null;
        }
        var status = PythonInterpreters.status(module);
        if (status == PythonInterpreters.Status.READY) {
            return null;
        }
        boolean localEnvironment = status == PythonInterpreters.Status.MISSING
                && !ExistingPythonEnvironments.discover(module).isEmpty();
        return editor -> {
            if (module.isDisposed() || project.isDisposed()) {
                return null;
            }
            var panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning);
            panel.setText(status == PythonInterpreters.Status.MISSING
                    ? "Choose a Python interpreter for module '" + module.getName() + "' to run and debug Python."
                    : "The Python interpreter for module '" + module.getName() + "' is not ready. Review its configuration.");
            if (localEnvironment) {
                panel.createActionLabel("Use project environment...", () -> UseProjectPythonEnvironmentAction.choose(module));
            }
            panel.createActionLabel("Choose interpreter...", () -> ChoosePythonInterpreterAction.choose(module));
            return panel;
        };
    }
}
