package org.openkaryon.karyoforge.languages.python;

import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.ide.wizard.NewProjectWizardBaseStep;
import com.intellij.ide.wizard.RootNewProjectWizardStep;
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.python.community.plugin.java.facet.PythonModuleBuilder;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.jetbrains.python.newProject.NewPythonProjectStep;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdk.PythonSdkUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Native provider entry points, without replacing its wizard or starting an interpreter. */
public final class PythonProjectIntegrationTest extends HeavyPlatformTestCase {
    public void testNativePythonWizardIsRegisteredAndOwnsItsProjectStep() {
        var generators = LanguageGeneratorNewProjectWizard.EP_NAME.getExtensionList().stream()
                .filter(generator -> "Python".equals(generator.getName())).toList();
        assertEquals("The packaged Python provider must contribute one Python wizard", 1, generators.size());
        var context = new WizardContext(null, getTestRootDisposable());
        var base = new NewProjectWizardBaseStep(new RootNewProjectWizardStep(context));
        base.setName("python project");
        var step = generators.getFirst().createStep(base);
        assertInstanceOf(step, NewPythonProjectStep.class);
        assertEquals("python project", ((NewPythonProjectStep) step).getName());
        assertTrue(((NewPythonProjectStep) step).getCreatePythonModuleStructure());
        assertNotNull(ActionManager.getInstance().getAction("NewProject"));
        assertNotNull(ActionManager.getInstance().getAction("OpenFile"));
    }

    public void testNativeBuilderCreatesPythonModuleWithExplicitSdkAndContentRoot() throws Exception {
        Path root = createTempDirectory().toPath().resolve("new python project");
        Files.createDirectory(root);
        var sdk = ProjectJdkTable.getInstance().createSdk("Project workflow " + getName(), PythonSdkType.getInstance());
        WriteAction.run(() -> {
            var data = sdk.getSdkModificator();
            data.setHomePath(root.resolve("chosen-python").toString());
            data.setVersionString("Python 3.14.6");
            data.commitChanges();
            ProjectJdkTable.getInstance().addJdk(sdk, getTestRootDisposable());
        });
        var projectSdk = ProjectRootManager.getInstance(getProject()).getProjectSdk();
        var builder = builder(root, "created-python");
        builder.setModuleJdk(sdk);
        Module module = builder.commitModule(getProject(), null);
        assertNotNull(module);
        assertEquals("PYTHON_MODULE", com.intellij.openapi.module.ModuleType.get(module).getId());
        assertSame(sdk, PythonSdkUtil.findPythonSdk(module));
        assertSame(projectSdk, ProjectRootManager.getInstance(getProject()).getProjectSdk());
        assertEquals(root.toString().replace('\\', '/'), ModuleRootManager.getInstance(module).getContentRoots()[0].getPath());
    }

    public void testExistingPythonSourcesAndDependencyFilesSurviveNativeModuleSetup() throws Exception {
        Path root = createTempDirectory().toPath().resolve("existing python project");
        Files.createDirectory(root);
        var names = List.of("main.py", "requirements.txt", "pyproject.toml");
        var contents = List.of("print('existing source')\n", "example-package==1.0\n", "[tool.example]\nenabled = true\n");
        for (int index = 0; index < names.size(); index++) Files.writeString(root.resolve(names.get(index)), contents.get(index));
        Path environment = Files.createDirectory(root.resolve(".venv"));
        Files.writeString(environment.resolve("keep.txt"), "existing environment content");
        var previous = ModuleRootManager.getInstance(getModule()).getSdk();
        Module module = builder(root, "existing-python").commitModule(getProject(), null);
        assertNotNull(module);
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("main.py"));
        assertNotNull(file);
        assertTrue(PythonInterpreters.isPython(file));
        assertSame(module, ModuleUtilCore.findModuleForFile(file, getProject()));
        assertNull(PythonSdkUtil.findPythonSdk(module));
        assertSame(previous, ModuleRootManager.getInstance(getModule()).getSdk());
        for (int index = 0; index < names.size(); index++) assertEquals(contents.get(index), Files.readString(root.resolve(names.get(index))));
        assertEquals("existing environment content", Files.readString(environment.resolve("keep.txt")));
    }

    private static PythonModuleBuilder builder(Path root, String name) {
        var builder = new PythonModuleBuilder();
        builder.setName(name);
        builder.setContentEntryPath(root.toString());
        builder.setModuleFilePath(root.resolve(name + ".iml").toString());
        return builder;
    }
}
