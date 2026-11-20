package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.jetbrains.python.sdk.PythonSdkType;

public final class PythonModuleIntegrationTest extends HeavyPlatformTestCase {
    public void testModuleAssignmentPreservesProjectAndOtherModuleSdk() throws Exception {
        var java = sdk("Java context", JavaSdk.getInstance(), System.getProperty("java.home"), "java version \"21\"");
        WriteAction.run(() -> ProjectRootManager.getInstance(getProject()).setProjectSdk(java));
        var directory = createTempDirectory().toPath();
        var other = createModule("other");
        ModuleRootModificationUtil.setModuleSdk(other, java);
        var python = sdk("Python selection", PythonSdkType.getInstance(), directory.resolve("python").toString(), "Python 3.14.6");
        assertTrue(PythonInterpreters.assign(getModule(), python));
        assertSame(python, ModuleRootManager.getInstance(getModule()).getSdk());
        assertSame(java, ModuleRootManager.getInstance(other).getSdk());
        assertSame(java, ProjectRootManager.getInstance(getProject()).getProjectSdk());
        assertFalse(PythonInterpreters.assign(getModule(), null));
        assertSame(python, ModuleRootManager.getInstance(getModule()).getSdk());
        try {
            PythonInterpreters.assign(getModule(), java);
            fail("A non-Python SDK must be rejected");
        } catch (IllegalArgumentException expected) {
            assertSame(python, ModuleRootManager.getInstance(getModule()).getSdk());
        }
    }

    private Sdk sdk(String name, com.intellij.openapi.projectRoots.SdkType type, String home, String version) {
        var sdk = ProjectJdkTable.getInstance().createSdk(name + getName(), type);
        WriteAction.run(() -> {
            var editor = sdk.getSdkModificator();
            editor.setHomePath(home);
            editor.setVersionString(version);
            editor.commitChanges();
            ProjectJdkTable.getInstance().addJdk(sdk, getTestRootDisposable());
        });
        return sdk;
    }
}
