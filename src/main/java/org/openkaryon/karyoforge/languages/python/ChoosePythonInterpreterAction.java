package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.EditorNotifications;
import com.jetbrains.python.configuration.PythonInterpreterConfigurable;
import com.jetbrains.python.sdk.PythonSdkUtil;
import org.jetbrains.annotations.NotNull;

/** Opens PythonCore's SDK chooser for the module owning the active Python file. */
public final class ChoosePythonInterpreterAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(PythonModuleContext.available(event.getProject()));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Module module = PythonModuleContext.choose(event);
        if (module != null) choose(module);
    }

    public static void choose(Module module) {
        if (module.isDisposed() || module.getProject().isDisposed()) {
            return;
        }
        var project = module.getProject();
        var selected = PythonInterpreterConfigurable.openInDialog(project, module, PythonSdkUtil.findPythonSdk(module));
        PythonInterpreters.assign(module, selected);
        EditorNotifications.getInstance(project).updateAllNotifications();
    }
}
