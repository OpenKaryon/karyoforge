package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.sdk.PythonSdkUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Local module ownership and explicit SDK assignment; never discovers or launches a runtime. */
public final class PythonInterpreters {
    public enum Status { MISSING, UNREADY, READY }

    private PythonInterpreters() {}

    public static boolean isPython(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory()
                && FileTypeRegistry.getInstance().isFileOfType(file, PythonFileType.INSTANCE);
    }

    public static @Nullable Module owner(Project project, @Nullable VirtualFile file) {
        if (project.isDisposed() || !isPython(file)) {
            return null;
        }
        Module module = ModuleUtilCore.findModuleForFile(file, project);
        return module == null || module.isDisposed() ? null : module;
    }

    public static Status status(Module module) {
        Sdk sdk = PythonSdkUtil.findPythonSdk(module);
        if (sdk == null) {
            return Status.MISSING;
        }
        String home = sdk.getHomePath();
        String version = sdk.getVersionString();
        if (home == null || home.isBlank() || version == null || version.isBlank()) {
            return Status.UNREADY;
        }
        try {
            return Files.isRegularFile(Path.of(home)) ? Status.READY : Status.UNREADY;
        } catch (InvalidPathException ignored) {
            return Status.UNREADY;
        }
    }

    /** A null selection is cancellation; only the selected module's SDK changes. */
    public static boolean assign(Module module, @Nullable Sdk selection) {
        if (selection == null || module.isDisposed() || module.getProject().isDisposed()) {
            return false;
        }
        if (!PythonSdkUtil.isPythonSdk(selection)) {
            throw new IllegalArgumentException("Choose a Python SDK for a Python module");
        }
        return WriteAction.compute(() -> {
            if (module.isDisposed()) {
                return false;
            }
            ModuleRootModificationUtil.setModuleSdk(module, selection);
            return true;
        });
    }
}
