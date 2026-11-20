package org.openkaryon.karyoforge.languages.python;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.execution.ui.actions.CloseAction;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** An explicit environment operation with native output and an owned, cancellable process. */
public final class PythonEnvironmentProcess {
    private PythonEnvironmentProcess() {}

    static GeneralCommandLine command(Path python, Path directory, List<String> arguments) {
        var command = new GeneralCommandLine(python.toString()).withParameters(arguments)
                .withWorkDirectory(directory.toString()).withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE);
        for (String name : List.of("SystemRoot", "WINDIR", "TEMP", "TMP", "PATH", "PATHEXT")) {
            String value = System.getenv(name);
            if (value != null) command.withEnvironment(name, value);
        }
        command.withEnvironment("PYTHONNOUSERSITE", "1");
        command.withEnvironment("PYTHONUNBUFFERED", "1");
        return command;
    }

    static void run(Project project, Disposable lifetime, GeneralCommandLine command, String title, ProgressIndicator indicator)
            throws ExecutionException {
        indicator.checkCanceled();
        var process = new KillableColoredProcessHandler(command);
        process.setShouldDestroyProcessRecursively(true);
        try {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                if (project.isDisposed() || indicator.isCanceled()) return;
                var console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
                var panel = new JPanel(new BorderLayout());
                panel.add(console.getComponent(), BorderLayout.CENTER);
                var descriptor = new RunContentDescriptor(console, process, panel, title);
                descriptor.setAutoFocusContent(false);
                Disposer.register(lifetime, descriptor);
                Disposer.register(descriptor, console);
                Disposer.register(descriptor, indicator::cancel);
                var actions = new DefaultActionGroup();
                actions.add(new DumbAwareAction("Stop", "Cancel this environment operation", AllIcons.Actions.Suspend) {
                    @Override public void actionPerformed(@NotNull AnActionEvent event) { indicator.cancel(); }
                    @Override public void update(@NotNull AnActionEvent event) {
                        event.getPresentation().setEnabled(!process.isProcessTerminated());
                    }
                });
                actions.addAll(console.createConsoleActions());
                var executor = DefaultRunExecutor.getRunExecutorInstance();
                actions.add(new CloseAction(executor, descriptor, project));
                var toolbar = ActionManager.getInstance().createActionToolbar("KaryoForgePythonEnvironment", actions, false);
                toolbar.setTargetComponent(panel);
                panel.add(toolbar.getComponent(), BorderLayout.WEST);
                console.attachToProcess(process);
                RunContentManager.getInstance(project).showRunContent(executor, descriptor);
            });
            if (!process.isStartNotified()) process.startNotify();
            while (!process.waitFor(100)) {
                if (project.isDisposed()) indicator.cancel();
                indicator.checkCanceled();
            }
            indicator.checkCanceled();
            if (!Integer.valueOf(0).equals(process.getExitCode())) {
                throw new ExecutionException(title + " failed (exit code " + process.getExitCode()
                        + "). Review the output in Run.");
            }
        } finally {
            if (!process.isStartNotified()) process.startNotify();
            if (!process.isProcessTerminated()) {
                process.destroyProcess();
                if (!process.waitFor(2_000)) {
                    process.killProcess();
                    process.waitFor(5_000);
                }
            }
        }
    }
}
