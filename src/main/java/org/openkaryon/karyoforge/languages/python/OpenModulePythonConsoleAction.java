package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public final class OpenModulePythonConsoleAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(PythonModuleContext.available(event.getProject()));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var module = PythonModuleContext.choose(event);
        if (module == null) return;
        try {
            var project = module.getProject();
            var runner = ModulePythonSessions.console(module);
            runner.addConsoleListener(console -> ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || module.isDisposed()) return;
                // Native startup attaches content without revealing a hidden tool window.
                var window = ToolWindowManager.getInstance(project).getToolWindow("Python Console");
                if (window != null) window.activate(null, true);
            }));
            runner.run(true);
        } catch (IllegalArgumentException failure) {
            Messages.showErrorDialog(module.getProject(), failure.getMessage(), "Python Console");
        }
    }
}
