package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.SystemInfo;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdk.PythonSdkUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Bounded, process-free discovery followed by explicitly requested native SDK setup. */
public final class ExistingPythonEnvironments {
    public record Candidate(String name, Path executable) {
        public String label() { return name + " - " + executable; }
    }

    private ExistingPythonEnvironments() {}

    public static List<Candidate> discover(Module module) {
        if (module.isDisposed()) return List.of();
        var roots = new LinkedHashSet<Path>();
        for (var root : ModuleRootManager.getInstance(module).getContentRoots()) {
            Path path = root.getFileSystem().getNioPath(root);
            if (path != null) roots.add(path.toAbsolutePath().normalize());
        }
        String base = module.getProject().getBasePath();
        if (base != null) roots.add(Path.of(base).toAbsolutePath().normalize());
        return discover(new ArrayList<>(roots), SystemInfo.isWindows);
    }

    static List<Candidate> discover(List<Path> roots, boolean windows) {
        var found = new LinkedHashMap<String, Candidate>();
        for (Path root : roots) {
            for (String name : List.of(".venv", "venv", "env", ".env")) {
                ProgressManager.checkCanceled();
                Path directory = root.resolve(name);
                if (!Files.isRegularFile(directory.resolve("pyvenv.cfg"))) continue;
                Path executable = directory.resolve(windows ? "Scripts/python.exe" : "bin/python");
                if (!Files.isRegularFile(executable)) continue;
                executable = executable.toAbsolutePath().normalize();
                found.putIfAbsent(identity(executable), new Candidate(name, executable));
            }
        }
        return List.copyOf(found.values());
    }

    public static @Nullable Sdk registered(Path executable) {
        String requested = identity(executable);
        for (Sdk sdk : PythonSdkUtil.getAllSdks()) {
            if (PythonSdkUtil.isRemote(sdk) || sdk.getHomePath() == null) continue;
            try {
                if (requested.equals(identity(Path.of(sdk.getHomePath())))) return sdk;
            } catch (InvalidPathException ignored) {
                // An unrelated stale SDK entry must not prevent selecting a working environment.
            }
        }
        return null;
    }

    /** Call on the EDT after a user choice; native setup owns probing, roots and progress. */
    public static @Nullable Sdk prepare(Path executable) {
        Path path = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("The selected Python executable no longer exists.");
        Sdk existing = registered(path);
        if (existing != null) {
            if (existing.getVersionString() == null || existing.getVersionString().isBlank()) {
                PythonSdkType.getInstance().setupSdkPaths(existing);
            }
            if (!ready(existing)) throw new IllegalStateException("The existing Python SDK could not be initialized.");
            return existing;
        }
        Sdk created = SdkConfigurationUtil.createAndAddSDK(path.toString(), PythonSdkType.getInstance());
        if (created == null) return null;
        if (!ready(created)) {
            WriteAction.run(() -> ProjectJdkTable.getInstance().removeJdk(created));
            throw new IllegalStateException("Python SDK setup did not finish. The module interpreter was not changed.");
        }
        return created;
    }

    private static boolean ready(Sdk sdk) {
        return sdk.getVersionString() != null && !sdk.getVersionString().isBlank();
    }

    private static String identity(Path path) {
        // Keep the invocation path: resolving a venv symlink can collapse distinct environments.
        String value = path.toAbsolutePath().normalize().toString();
        return SystemInfo.isWindows ? value.toLowerCase(Locale.ROOT) : value;
    }
}
