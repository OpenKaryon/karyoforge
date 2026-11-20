package org.openkaryon.karyoforge.languages.python;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.python.community.plugin.java.facet.PythonModuleBuilder;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdk.PythonSdkUtil;

import java.nio.file.Files;

public final class ProjectPersistenceIntegrationTest extends HeavyPlatformTestCase {
    public void testModuleSdkAndSourcesSurviveNativeProjectSaveAndReload() throws Exception {
        var directory = createTempDirectory().toPath().resolve("saved Python project");
        Files.createDirectory(directory);
        var source = Files.writeString(directory.resolve("main.py"), "print('preserved source')\n");
        var python = Files.writeString(directory.resolve("python.exe"), "metadata fixture only");
        var sdk = ProjectJdkTable.getInstance().createSdk("Saved interpreter " + getName(), PythonSdkType.getInstance());
        WriteAction.run(() -> {
            var data = sdk.getSdkModificator();
            data.setHomePath(python.toString());
            data.setVersionString("Python 3.14.6");
            data.commitChanges();
            ProjectJdkTable.getInstance().addJdk(sdk, getTestRootDisposable());
        });
        var manager = ProjectManagerEx.getInstanceEx();
        Project created = manager.newProject(directory, OpenProjectTask.build().asNewProject());
        assertNotNull(created);
        try {
            var builder = new PythonModuleBuilder();
            builder.setName("saved-python");
            builder.setContentEntryPath(directory.toString());
            builder.setModuleFilePath(directory.resolve("saved-python.iml").toString());
            var module = builder.commitModule(created, null);
            assertNotNull(module);
            assertTrue(PythonInterpreters.assign(module, sdk));
            PlatformTestUtil.saveProject(created, true);
            assertTrue(Files.isRegularFile(directory.resolve(".idea/modules.xml")));
            assertTrue(Files.readString(directory.resolve("saved-python.iml")).contains(sdk.getName()));
        } finally {
            WriteAction.run(() -> Disposer.dispose(created));
        }
        Project reopened = com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                () -> manager.loadProject(directory), new com.intellij.openapi.progress.EmptyProgressIndicator());
        assertNotNull(reopened);
        try {
            var module = ModuleManager.getInstance(reopened).findModuleByName("saved-python");
            assertNotNull(module);
            assertSame(sdk, PythonSdkUtil.findPythonSdk(module));
            assertEquals(python.toString().replace('\\', '/'), sdk.getHomePath());
            assertEquals("print('preserved source')\n", Files.readString(source));
            assertNull(ProjectRootManager.getInstance(reopened).getProjectSdk());
        } finally {
            WriteAction.run(() -> Disposer.dispose(reopened));
        }
    }
}
