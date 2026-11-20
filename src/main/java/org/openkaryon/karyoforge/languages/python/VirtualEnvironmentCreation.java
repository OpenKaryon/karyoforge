package org.openkaryon.karyoforge.languages.python;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.SystemInfo;
import com.jetbrains.python.sdk.PythonSdkUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Creates at the final path: activation scripts and pip launchers embed that path. */
public final class VirtualEnvironmentCreation {
    private VirtualEnvironmentCreation() {}

    static void validate(Path base, Path target) {
        if (!base.isAbsolute() || !Files.isRegularFile(base)) {
            throw new IllegalArgumentException("Choose an existing, absolute local Python executable.");
        }
        if (!target.isAbsolute() || target.getParent() == null || !Files.isDirectory(target.getParent())) {
            throw new IllegalArgumentException("Choose a new environment directory inside an existing parent directory.");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The environment directory already exists. Choose a new directory.");
        }
    }

    static Path create(Project project, Disposable lifetime, Path base, Path target, ProgressIndicator indicator)
            throws IOException, ExecutionException {
        validate(base, target);
        indicator.checkCanceled();
        Files.createDirectory(target);
        // Never recursively delete here: concurrent user changes may already exist in this directory.
        PythonEnvironmentProcess.run(project, lifetime, PythonEnvironmentProcess.command(base, target.getParent(),
                List.of("-I", "-m", "venv", target.toString())), "Create Python Environment", indicator);
        Path executable = target.resolve(SystemInfo.isWindows ? "Scripts/python.exe" : "bin/python");
        if (!Files.isRegularFile(target.resolve("pyvenv.cfg")) || !Files.isRegularFile(executable)) {
            throw new IOException("Python finished without creating a usable virtual environment.");
        }
        return executable;
    }

    /** A later user selection wins over completion of a background creation task. */
    static boolean assignIfUnchanged(Module module, Sdk previous, Sdk created) {
        return !module.isDisposed() && !module.getProject().isDisposed()
                && PythonSdkUtil.findPythonSdk(module) == previous && PythonInterpreters.assign(module, created);
    }
}
