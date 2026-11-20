package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.jetbrains.python.console.PydevConsoleRunner;
import com.jetbrains.python.console.PythonConsoleRunnerFactory;
import com.jetbrains.python.sdk.PythonSdkUtil;

import java.nio.file.Path;

/** New sessions take a module SDK snapshot; existing sessions keep their original runtime. */
public final class ModulePythonSessions {
    private ModulePythonSessions() {}

    static Sdk interpreter(Module module) {
        if (module.isDisposed() || module.getProject().isDisposed()) throw new IllegalArgumentException("The module is no longer open.");
        Sdk sdk = PythonSdkUtil.findPythonSdk(module);
        if (sdk == null || PythonSdkUtil.isRemote(sdk) || PythonInterpreters.status(module) != PythonInterpreters.Status.READY) {
            throw new IllegalArgumentException("Choose a ready local Python interpreter for this module first.");
        }
        if (sdk.getHomeDirectory() == null) {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(sdk.getHomePath()));
        }
        if (sdk.getSdkAdditionalData() == null) {
            com.intellij.openapi.application.WriteAction.run(() -> {
                var data = sdk.getSdkModificator();
                data.setSdkAdditionalData(com.jetbrains.python.sdk.PythonSdkType.getInstance()
                        .loadAdditionalData(sdk, new org.jdom.Element("additional")));
                data.commitChanges();
            });
        }
        return sdk;
    }

    static PydevConsoleRunner console(Module module) {
        var sdk = interpreter(module);
        var runner = PythonConsoleRunnerFactory.getInstance().createConsoleRunner(module.getProject(), module);
        // Override this new runner only, preserving the user's saved native console options.
        runner.setSdk(sdk);
        return runner;
    }

    static Path terminalDirectory(Module module) {
        if (!PythonSdkUtil.isVirtualEnv(interpreter(module))) {
            throw new IllegalArgumentException("Choose a local virtual environment for a module Python terminal. A base interpreter can be used in Python Console.");
        }
        for (var root : ModuleRootManager.getInstance(module).getContentRoots()) {
            Path directory = root.getFileSystem().getNioPath(root);
            if (directory != null && java.nio.file.Files.isDirectory(directory)) return directory;
        }
        throw new IllegalArgumentException("A Python terminal requires a local content directory for the selected module.");
    }

    static String cmdActivation(Module module) {
        if (!com.intellij.openapi.util.SystemInfo.isWindows) return null;
        var sdk = interpreter(module);
        String shell = org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider.getInstance(module.getProject()).getShellPath();
        // A supported-shell activation plan lets the public provider enforce its own opt-out.
        // It computes script metadata only; no PowerShell process is launched here.
        var plan = new java.util.HashMap<String, String>();
        new com.intellij.python.terminal.PyVirtualEnvTerminalCustomizer().customizeCommandAndEnvironment(
                module.getProject(), terminalDirectory(module).toString(), new String[]{"powershell.exe"}, plan);
        boolean enabled = plan.containsKey(com.intellij.python.terminal.PyVirtualEnvTerminalCustomizer.JEDITERM_SOURCE);
        return cmdActivationCommand(Path.of(sdk.getHomePath()), shell, enabled);
    }

    /** Queue the standard activation script only for plain cmd, preserving custom startup commands. */
    static String cmdActivationCommand(Path executable, String shell, boolean enabled) {
        if (!enabled || shell == null) return null;
        var command = com.intellij.util.execution.ParametersListUtil.parse(shell);
        if (command.isEmpty()) return null;
        String name = Path.of(command.get(0)).getFileName().toString();
        if (!name.equalsIgnoreCase("cmd.exe") && !name.equalsIgnoreCase("cmd")) return null;
        for (int index = 1; index < command.size(); index++) {
            if (!java.util.Set.of("/d", "/q", "/a", "/u").contains(command.get(index).toLowerCase(java.util.Locale.ROOT))) return null;
        }
        Path activation = executable.getParent().resolve("activate.bat");
        if (!java.nio.file.Files.isRegularFile(activation)) {
            throw new IllegalArgumentException("The selected environment has no cmd activation script.");
        }
        String root = executable.getParent().getParent().toString();
        // PythonCore can cache an empty environment after its helper times out.
        // The new terminal's queue runs before user commands and leaves an active environment unchanged.
        return "if /I not \"%VIRTUAL_ENV%\"==\"" + root + "\" call \"" + activation + "\"";
    }
}
