package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.jetbrains.python.console.PyConsoleOptions;
import com.jetbrains.python.sdk.PythonSdkType;
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SessionsIntegrationTest extends BasePlatformTestCase {
    @Override protected TempDirTestFixture createTempDirTestFixture() { return new TempDirTestFixtureImpl(); }

    public void testNewConsoleUsesModuleSdkWithoutChangingExistingRunnerOrSavedOptions() throws Exception {
        var original = ModuleRootManager.getInstance(getModule()).getSdk();
        var settings = PyConsoleOptions.getInstance(getProject()).getPythonConsoleSettings();
        String previousHome = settings.getSdkHome();
        boolean previousUseModule = settings.isUseModuleSdk();
        var first = sdk("first");
        var second = sdk("second");
        try {
            settings.setSdkHome(second.getHomePath());
            settings.setUseModuleSdk(false);
            PythonInterpreters.assign(getModule(), first);
            var existing = ModulePythonSessions.console(getModule());
            assertSame(first, existing.getSdk());
            assertNotNull(first.getSdkAdditionalData());
            try {
                ModulePythonSessions.terminalDirectory(getModule());
                fail("A module Python terminal must not silently use shell PATH for a base SDK");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("virtual environment"));
            }
            PythonInterpreters.assign(getModule(), second);
            var next = ModulePythonSessions.console(getModule());
            assertNotSame(existing, next);
            assertSame(second, next.getSdk());
            assertSame(first, existing.getSdk());
            assertEquals(second.getHomePath(), settings.getSdkHome());
            assertFalse(settings.isUseModuleSdk());
        } finally {
            settings.setSdkHome(previousHome);
            settings.setUseModuleSdk(previousUseModule);
            ModuleRootModificationUtil.setModuleSdk(getModule(), original);
        }
    }

    public void testNativeTerminalActivationAndExplicitSessionActionsAreRegistered() {
        assertTrue(LocalTerminalCustomizer.EP_NAME.getExtensionList().stream().anyMatch(customizer ->
                customizer.getClass().getName().equals("com.intellij.python.terminal.PyVirtualEnvTerminalCustomizer")));
        assertNotNull(ActionManager.getInstance().getAction("org.openkaryon.karyoforge.python.openConsole"));
        assertNotNull(ActionManager.getInstance().getAction("org.openkaryon.karyoforge.python.openTerminal"));
    }

    private Sdk sdk(String name) throws Exception {
        Path python = Path.of(myFixture.getTempDirPath()).resolve(name).resolve("python.exe");
        Files.createDirectories(python.getParent());
        Files.writeString(python, "metadata validation only; not an executable");
        var sdk = ProjectJdkTable.getInstance().createSdk(name + getName(), PythonSdkType.getInstance());
        WriteAction.run(() -> {
            var data = sdk.getSdkModificator();
            data.setHomePath(python.toString());
            data.setVersionString("Python 3.14.6");
            data.commitChanges();
            ProjectJdkTable.getInstance().addJdk(sdk, getTestRootDisposable());
        });
        return sdk;
    }
}
