package org.openkaryon.karyoforge.languages.python;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.SystemInfo;
import com.jetbrains.python.packaging.PyPackageManager;
import com.jetbrains.python.sdk.PythonSdkUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Pip adapters for explicitly chosen local virtual environments, never global Python. */
public final class PythonDependencyOperations {
    record Environment(Sdk sdk, Path python, PyPackageManager packages) {}
    private static final Set<String> BUSY = ConcurrentHashMap.newKeySet();

    private PythonDependencyOperations() {}

    static Environment selected(Module module) {
        Sdk sdk = PythonSdkUtil.findPythonSdk(module);
        if (sdk == null || PythonSdkUtil.isRemote(sdk) || sdk.getHomePath() == null
                || PythonInterpreters.status(module) != PythonInterpreters.Status.READY) {
            throw new IllegalArgumentException("Choose a ready local Python virtual environment for this module first.");
        }
        Path python = Path.of(sdk.getHomePath()).toAbsolutePath().normalize();
        Path root = python.getParent() == null ? null : python.getParent().getParent();
        if (root == null || !Files.isRegularFile(root.resolve("pyvenv.cfg"))) {
            throw new IllegalArgumentException("Dependency changes require a virtual environment. The base Python installation will not be modified.");
        }
        // This explicit selection runs on the EDT; establish the provider's stable SDK identity
        // before its background manager cache or any asynchronous refresh can be created.
        return new Environment(sdk, python, PyPackageManager.getInstance(sdk));
    }

    static List<String> requirementsArguments(Path requirements) {
        if (!requirements.isAbsolute() || !Files.isRegularFile(requirements)) {
            throw new IllegalArgumentException("Choose an existing absolute requirements file.");
        }
        return List.of("install", "--no-user", "-r", requirements.toString());
    }

    static void run(Project project, Disposable lifetime, Environment environment, Path directory,
                    List<String> arguments, String title, ProgressIndicator indicator) throws ExecutionException {
        String identity = environment.python().toString();
        if (SystemInfo.isWindows) identity = identity.toLowerCase(Locale.ROOT);
        if (!BUSY.add(identity)) throw new ExecutionException("Another KaryoForge dependency operation is using this environment.");
        try {
            var parameters = new ArrayList<>(List.of("-I", "-m", "pip", "--isolated", "--require-virtualenv",
                    "--disable-pip-version-check", "--no-input"));
            parameters.addAll(arguments);
            var command = PythonEnvironmentProcess.command(environment.python(), directory, parameters)
                    .withEnvironment("PIP_CONFIG_FILE", SystemInfo.isWindows ? "nul" : "/dev/null");
            PythonEnvironmentProcess.run(project, lifetime, command, title, indicator);
            indicator.checkCanceled();
            // Let the native provider refresh its own installed-package cache after an explicit mutation.
            // The pinned provider exposes refresh through its public compatibility bridge.
            // Its newer suspend refresh methods are internal and must not be linked here.
            environment.packages().refreshAndGetPackages(true);
        } finally {
            BUSY.remove(identity);
        }
    }
}
