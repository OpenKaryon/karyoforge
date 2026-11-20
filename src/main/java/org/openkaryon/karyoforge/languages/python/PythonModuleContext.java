package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/** Explicit Python actions may select a module even before it contains a Python file. */
public final class PythonModuleContext {
    private PythonModuleContext() {}

    public static boolean available(@Nullable Project project) {
        return project != null && !project.isDisposed() && ModuleManager.getInstance(project).getModules().length > 0;
    }

    public static @Nullable Module choose(AnActionEvent event) {
        Project project = event.getProject();
        if (!available(project)) return null;
        Module owner = PythonInterpreters.owner(project, event.getData(CommonDataKeys.VIRTUAL_FILE));
        if (owner != null) return owner;
        Module selected = event.getData(LangDataKeys.MODULE);
        if (selected != null && !selected.isDisposed() && selected.getProject() == project) return selected;
        Module[] modules = ModuleManager.getInstance(project).getModules();
        if (modules.length == 1) return modules[0];
        Arrays.sort(modules, java.util.Comparator.comparing(Module::getName));
        String[] labels = Arrays.stream(modules).map(Module::getName).toArray(String[]::new);
        int index = PythonChoiceDialog.choose(project, "Python Module", "Choose the module to configure.", labels);
        return index >= 0 && index < modules.length && !modules[index].isDisposed() ? modules[index] : null;
    }
}
