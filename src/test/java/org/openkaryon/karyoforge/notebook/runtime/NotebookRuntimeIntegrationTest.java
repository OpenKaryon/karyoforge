package org.openkaryon.karyoforge.notebook.runtime;

import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.components.State;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.*;
import java.awt.Container;
import java.nio.file.Files;

public final class NotebookRuntimeIntegrationTest extends BasePlatformTestCase {
    public void testWorkspaceSelectionsAreIndependentAndDefensivelyPersisted() {
        var preferences = getProject().getService(NotebookRuntimePreferences.class);
        assertEquals(StoragePathMacros.WORKSPACE_FILE,
                NotebookRuntimePreferences.class.getAnnotation(State.class).storages()[0].value());
        assertNull(preferences.selection("file:///first.ipynb"));
        preferences.select("file:///first.ipynb", "C:/selected/python.exe");
        preferences.select("file:///second.ipynb", NotebookRuntimePreferences.NONE);
        var snapshot = preferences.getState();
        snapshot.paths.clear();
        assertEquals("C:/selected/python.exe", preferences.selection("file:///first.ipynb"));
        var restored = new NotebookRuntimePreferences();
        restored.loadState(preferences.getState());
        preferences.select("file:///first.ipynb", null);
        assertEquals("C:/selected/python.exe", restored.selection("file:///first.ipynb"));
        assertEquals(NotebookRuntimePreferences.NONE, restored.selection("file:///second.ipynb"));
    }

    public void testOpeningAndSelectingDoNotRunAnExecutableOrModifyNotebook() throws Exception {
        var file = myFixture.addFileToProject("passive.ipynb", "{\"nbformat\":4}").getVirtualFile();
        var fake = Files.createTempFile("notebook-unexecuted-", ".exe");
        Files.writeString(fake, "This is deliberately not an executable.");
        var preferences = getProject().getService(NotebookRuntimePreferences.class);
        preferences.select(file.getUrl(), fake.toString());
        var panel = new NotebookRuntimePanel(getProject(), file);
        try {
            assertEquals(fake, panel.interpreter().executable());
            assertTrue(labels(panel).contains("Dependencies not checked"));
            assertEquals("{\"nbformat\":4}", new String(file.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            combo(panel).setSelectedItem("No interpreter");
            assertFalse(panel.interpreter().available());
            assertEquals(NotebookRuntimePreferences.NONE, preferences.selection(file.getUrl()));
            combo(panel).setSelectedItem("Follow module SDK");
            assertNull(preferences.selection(file.getUrl()));
            assertFalse(panel.interpreter().available());
        } finally { Disposer.dispose(panel); Files.delete(fake); }
    }

    public void testMissingExplicitChoiceDoesNotFallBackAndReportsAreTyped() throws Exception {
        var file = myFixture.addFileToProject("missing.ipynb", "{}").getVirtualFile();
        getProject().getService(NotebookRuntimePreferences.class).select(file.getUrl(), "Z:/missing/python.exe");
        assertFalse(NotebookInterpreter.resolve(getProject(), file).available());
        assertTrue(NotebookInterpreter.resolve(getProject(), file).problem().contains("missing"));
        assertFalse(NotebookInterpreter.local("relative/python.exe", "Relative").available());
        var missing = NotebookDependencyCheck.inspect("{\"python\":\"selected\",\"version\":\"3.14\",\"packages\":{\"jupyter_client\":null,\"ipykernel\":\"7\"}}");
        assertFalse(missing.ready());
        assertTrue(missing.summary().contains("Missing jupyter_client"));
        assertFalse(missing.summary().contains("Missing ipykernel"));
        try { NotebookDependencyCheck.inspect("not json"); fail(); } catch (java.io.IOException expected) { /* explicit failure */ }
    }

    public void testModuleDefaultUsesItsOwnSdkWithoutChangingModuleOrProject() throws Exception {
        var file = myFixture.addFileToProject("module.ipynb", "{}").getVirtualFile();
        var executable = Files.createTempFile("notebook-cached-sdk-", ".exe");
        var roots = com.intellij.openapi.roots.ModuleRootManager.getInstance(getModule());
        var original = roots.getSdk();
        boolean inherited = roots.isSdkInherited();
        var projectSdk = com.intellij.openapi.roots.ProjectRootManager.getInstance(getProject()).getProjectSdk();
        var table = com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance();
        var sdk = table.createSdk("Notebook cached SDK " + getName(), com.jetbrains.python.sdk.PythonSdkType.getInstance());
        com.intellij.openapi.application.WriteAction.run(() -> {
            var modificator = sdk.getSdkModificator();
            modificator.setHomePath(executable.toString());
            modificator.setVersionString("Python 3.14.6");
            modificator.commitChanges();
            table.addJdk(sdk, getTestRootDisposable());
        });
        try {
            com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(getModule(), sdk);
            assertEquals(executable, NotebookInterpreter.resolve(getProject(), file).executable());
            getProject().getService(NotebookRuntimePreferences.class).select(file.getUrl(), "Z:/not-there/python.exe");
            assertFalse(NotebookInterpreter.resolve(getProject(), file).available());
            assertSame(sdk, roots.getSdk());
            assertSame(projectSdk, com.intellij.openapi.roots.ProjectRootManager.getInstance(getProject()).getProjectSdk());
        } finally {
            if (inherited) com.intellij.openapi.roots.ModuleRootModificationUtil.setSdkInherited(getModule());
            else com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(getModule(), original);
            Files.delete(executable);
        }
    }

    private static String labels(Container root) {
        var result = new StringBuilder();
        for (var child : root.getComponents()) {
            if (child instanceof JLabel label) result.append(label.getText());
            if (child instanceof Container container) result.append(labels(container));
        }
        return result.toString();
    }

    private static JComboBox<?> combo(Container root) {
        for (var child : root.getComponents()) {
            if (child instanceof JComboBox<?> combo) return combo;
            if (child instanceof Container container) { var found = combo(container); if (found != null) return found; }
        }
        return null;
    }
}
