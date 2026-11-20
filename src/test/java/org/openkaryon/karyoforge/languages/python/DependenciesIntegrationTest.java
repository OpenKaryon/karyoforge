package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.wm.ToolWindowEP;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DependenciesIntegrationTest extends BasePlatformTestCase {
    @Override protected TempDirTestFixture createTempDirTestFixture() { return new TempDirTestFixtureImpl(); }

    public void testRequirementsPathWithSpacesIsOneArgumentAndOpeningDoesNotExecute() throws Exception {
        Path root = Path.of(myFixture.getTempDirPath());
        Path file = Files.writeString(root.resolve("project requirements.txt"), "./local package.whl\n");
        var previous = ModuleRootManager.getInstance(getModule()).getSdk();
        int count = ProjectJdkTable.getInstance().getAllJdks().length;
        var arguments = PythonDependencyOperations.requirementsArguments(file);
        assertEquals(4, arguments.size());
        assertEquals(file.toString(), arguments.getLast());
        assertSame(previous, ModuleRootManager.getInstance(getModule()).getSdk());
        assertEquals(count, ProjectJdkTable.getInstance().getAllJdks().length);
    }

    public void testBaseInterpreterIsRejectedBeforeAnyPackageOperation() throws Exception {
        Path base = Files.writeString(Path.of(myFixture.getTempDirPath()).resolve("python.exe"), "validation only");
        var sdk = ProjectJdkTable.getInstance().createSdk("Protected base " + getName(), com.jetbrains.python.sdk.PythonSdkType.getInstance());
        var original = ModuleRootManager.getInstance(getModule()).getSdk();
        com.intellij.openapi.application.WriteAction.run(() -> {
            var data = sdk.getSdkModificator();
            data.setHomePath(base.toString());
            data.setVersionString("Python 3.14.6");
            data.commitChanges();
            ProjectJdkTable.getInstance().addJdk(sdk, getTestRootDisposable());
        });
        try {
            PythonInterpreters.assign(getModule(), sdk);
            PythonDependencyOperations.selected(getModule());
            fail("Dependency changes must reject base Python before execution");
        } catch (IllegalArgumentException expected) {
            assertSame(sdk, ModuleRootManager.getInstance(getModule()).getSdk());
            assertEquals("validation only", Files.readString(base));
        } finally {
            com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(getModule(), original);
        }
    }

    public void testMissingRequirementsFileFailsBeforeStartingAnOperation() {
        try {
            PythonDependencyOperations.requirementsArguments(Path.of(myFixture.getTempDirPath()).resolve("missing.txt"));
            fail("Missing files must fail before starting pip");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        assertNotNull(ActionManager.getInstance().getAction("org.openkaryon.karyoforge.python.installRequirements"));
        assertTrue(ToolWindowEP.EP_NAME.getExtensionList().stream().anyMatch(window -> "Python Packages".equals(window.id)));
    }
}
