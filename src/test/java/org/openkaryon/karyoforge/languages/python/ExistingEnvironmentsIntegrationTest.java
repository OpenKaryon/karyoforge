package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdk.PythonSdkUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ExistingEnvironmentsIntegrationTest extends BasePlatformTestCase {
    private Sdk originalSdk;

    @Override protected TempDirTestFixture createTempDirTestFixture() { return new TempDirTestFixtureImpl(); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        originalSdk = ModuleRootManager.getInstance(getModule()).getSdk();
        ModuleRootModificationUtil.setModuleSdk(getModule(), null);
    }

    @Override protected void tearDown() throws Exception {
        try {
            ModuleRootModificationUtil.setModuleSdk(getModule(), originalSdk);
        } finally {
            super.tearDown();
        }
    }

    public void testDiscoveryIsBoundedDeduplicatedAndDoesNotAssignOrLaunch() throws Exception {
        Path root = Path.of(myFixture.getTempDirPath());
        Path executable = environment(root.resolve(".venv"));
        environment(root.resolve("nested/venv"));
        int registered = PythonSdkUtil.getAllSdks().size();
        var found = ExistingPythonEnvironments.discover(List.of(root, root.resolve(".")), SystemInfo.isWindows);
        assertEquals(1, found.size());
        assertEquals(executable, found.getFirst().executable());
        assertEquals(".venv", found.getFirst().name());
        assertNull(ModuleRootManager.getInstance(getModule()).getSdk());
        assertEquals(registered, PythonSdkUtil.getAllSdks().size());
        // The fixture executable is plain text: discovery cannot have executed it successfully.
        assertEquals("not an executable", Files.readString(executable));
    }

    public void testCandidatesRequireBothVenvMetadataAndTheExecutable() throws Exception {
        Path root = Path.of(myFixture.getTempDirPath());
        Files.writeString(root.resolve(".env"), "PROJECT_VARIABLE=value\n");
        Path executable = environment(root.resolve(".venv"));
        Files.delete(root.resolve(".venv/pyvenv.cfg"));
        Files.createDirectories(root.resolve("venv"));
        Files.writeString(root.resolve("venv/pyvenv.cfg"), "home = unused\n");
        assertTrue(ExistingPythonEnvironments.discover(List.of(root), SystemInfo.isWindows).isEmpty());
        Files.writeString(root.resolve(".venv/pyvenv.cfg"), "home = unused\n");
        assertEquals(executable, ExistingPythonEnvironments.discover(List.of(root), SystemInfo.isWindows).getFirst().executable());
    }

    public void testExistingSdkIsReusedWithoutTouchingProjectSdkOrDuplicatingEntries() throws Exception {
        Path executable = environment(Path.of(myFixture.getTempDirPath()).resolve(".venv"));
        Sdk sdk = registered(executable, "Existing environment");
        Sdk projectSdk = ProjectRootManager.getInstance(getProject()).getProjectSdk();
        int count = PythonSdkUtil.getAllSdks().size();
        assertSame(sdk, ExistingPythonEnvironments.prepare(executable));
        Path equivalent = executable.getParent().resolve("..").resolve(executable.getParent().getFileName()).resolve(executable.getFileName());
        assertSame(sdk, ExistingPythonEnvironments.prepare(equivalent));
        assertTrue(PythonInterpreters.assign(getModule(), sdk));
        assertSame(sdk, ModuleRootManager.getInstance(getModule()).getSdk());
        assertSame(projectSdk, ProjectRootManager.getInstance(getProject()).getProjectSdk());
        assertEquals(count, PythonSdkUtil.getAllSdks().size());
    }

    public void testDistinctEnvironmentInvocationPathsKeepDistinctSdks() throws Exception {
        Path root = Path.of(myFixture.getTempDirPath());
        Path first = environment(root.resolve(".venv"));
        Path second = environment(root.resolve("env"));
        Sdk one = registered(first, "First environment");
        Sdk two = registered(second, "Second environment");
        assertSame(one, ExistingPythonEnvironments.registered(first));
        assertSame(two, ExistingPythonEnvironments.registered(second));
    }

    public void testUnavailableExecutableDoesNotReplaceAnAssignedSdk() throws Exception {
        Path root = Path.of(myFixture.getTempDirPath());
        Sdk original = registered(environment(root.resolve("env")), "Preserved environment");
        PythonInterpreters.assign(getModule(), original);
        int count = PythonSdkUtil.getAllSdks().size();
        try {
            ExistingPythonEnvironments.prepare(root.resolve("missing-python"));
            fail("A missing executable must fail before SDK setup");
        } catch (IllegalArgumentException expected) {
            assertSame(original, ModuleRootManager.getInstance(getModule()).getSdk());
            assertEquals(count, PythonSdkUtil.getAllSdks().size());
        }
    }

    public void testExplicitProjectEnvironmentActionIsRegistered() {
        assertNotNull(ActionManager.getInstance().getAction("org.openkaryon.karyoforge.python.useProjectEnvironment"));
        assertTrue(PythonModuleContext.available(getProject()));
        assertFalse(PythonModuleContext.available(null));
    }

    private Path environment(Path directory) throws Exception {
        Path executable = directory.resolve(SystemInfo.isWindows ? "Scripts/python.exe" : "bin/python");
        Files.createDirectories(executable.getParent());
        Files.writeString(directory.resolve("pyvenv.cfg"), "home = unused\n");
        Files.writeString(executable, "not an executable");
        return executable.toAbsolutePath().normalize();
    }

    private Sdk registered(Path executable, String name) {
        Sdk sdk = ProjectJdkTable.getInstance().createSdk(name + getName(), PythonSdkType.getInstance());
        WriteAction.run(() -> {
            var modificator = sdk.getSdkModificator();
            modificator.setHomePath(executable.toString());
            modificator.setVersionString("Python 3.14.6");
            modificator.commitChanges();
            ProjectJdkTable.getInstance().addJdk(sdk, getTestRootDisposable());
        });
        return sdk;
    }
}
