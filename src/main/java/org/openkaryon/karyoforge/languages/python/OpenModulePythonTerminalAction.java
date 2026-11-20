package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

public final class OpenModulePythonTerminalAction extends DumbAwareAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(PythonModuleContext.available(event.getProject()));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        var module = PythonModuleContext.choose(event);
        if (module == null) return;
        try {
            var directory = ModulePythonSessions.terminalDirectory(module);
            String activation = ModulePythonSessions.cmdActivation(module);
            var terminal = TerminalToolWindowManager.getInstance(module.getProject())
                    .createShellWidget(directory.toString(), "Python: " + module.getName(), true, true);
            if (activation != null) terminal.sendCommandToExecute(activation);
        } catch (IllegalArgumentException failure) {
            Messages.showErrorDialog(module.getProject(), failure.getMessage(), "Python Terminal");
        }
    }
}
