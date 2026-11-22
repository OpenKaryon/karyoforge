package org.openkaryon.karyoforge.notebook.runtime;

import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.python.sdk.PythonSdkUtil;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Resolves cached SDK data only. Opening a notebook never probes an executable. */
public record NotebookInterpreter(Path executable, String description, String problem) {
    public boolean available() { return executable != null && problem == null; }

    public static NotebookInterpreter resolve(Project project, VirtualFile file) {
        String selection = project.getService(NotebookRuntimePreferences.class).selection(file.getUrl());
        if (NotebookRuntimePreferences.NONE.equals(selection)) return unavailable("No interpreter selected.");
        if (selection != null) return local(selection, "Selected Python");
        var module = ModuleUtilCore.findModuleForFile(file, project);
        Sdk sdk = module == null ? ProjectRootManager.getInstance(project).getProjectSdk()
                : ModuleRootManager.getInstance(module).getSdk();
        if (sdk == null || !PythonSdkUtil.isPythonSdk(sdk)) return unavailable("The owning module has no Python SDK. Choose an executable.");
        if (PythonSdkUtil.isRemote(sdk)) return unavailable("Notebook execution requires a local Python interpreter.");
        return local(sdk.getHomePath(), "Module Python: " + sdk.getName());
    }

    public static NotebookInterpreter local(String home, String description) {
        try {
            if (home == null || home.isBlank()) return unavailable("The selected Python executable is missing.");
            Path path = Path.of(home);
            if (!path.isAbsolute() || !Files.isRegularFile(path)) return unavailable("The selected Python executable is missing: " + home);
            return new NotebookInterpreter(path.normalize(), description, null);
        } catch (InvalidPathException failure) { return unavailable("The selected Python path is invalid."); }
    }

    private static NotebookInterpreter unavailable(String problem) { return new NotebookInterpreter(null, "Unavailable", problem); }
}
