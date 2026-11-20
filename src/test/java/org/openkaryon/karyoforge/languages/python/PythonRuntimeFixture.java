package org.openkaryon.karyoforge.languages.python;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.CapturingProcessAdapter;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jetbrains.python.sdk.PythonSdkType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Physical, owned fixture for explicitly requested Python runtime acceptance. */
abstract class PythonRuntimeFixture extends BasePlatformTestCase {
    private static final LightProjectDescriptor PROJECT = new LightProjectDescriptor() {};
    protected VirtualFile sourceRoot;
    protected Sdk interpreter;

    @Override protected LightProjectDescriptor getProjectDescriptor() { return PROJECT; }
    @Override protected TempDirTestFixture createTempDirTestFixture() { return new TempDirTestFixtureImpl(); }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        String selected = System.getProperty("karyoforge.python.executable", "");
        assertTrue("Choose an existing runtime with PYTHON_EXECUTABLE", !selected.isBlank() && Files.isRegularFile(Path.of(selected)));
        sourceRoot = myFixture.getTempDirFixture().findOrCreateDir("runtime sources");
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), sourceRoot.getPath());
        ModuleRootModificationUtil.updateModel(getModule(), model -> model.addContentEntry(sourceRoot).addSourceFolder(sourceRoot, true));
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
        var probe = new GeneralCommandLine(selected, "-I", "-c",
                "import sys,sysconfig;print('Python '+sys.version.split()[0]);print(sysconfig.get_path('stdlib'));print(sysconfig.get_path('purelib'));print(sysconfig.get_path('platlib'))")
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE).withEnvironment(childEnvironment());
        var output = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                new CapturingProcessHandler(probe).runProcess(15_000)), 20_000);
        assertFalse(output.isTimeout());
        assertEquals(output.getStderr(), 0, output.getExitCode());
        var lines = output.getStdout().lines().filter(line -> !line.isBlank()).toList();
        assertTrue(lines.size() >= 3);
        interpreter = ProjectJdkTable.getInstance().createSdk("KaryoForge runtime " + getName(), PythonSdkType.getInstance());
        var roots = lines.subList(1, lines.size()).stream().distinct()
                .map(path -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)))
                .filter(java.util.Objects::nonNull).toList();
        assertFalse(roots.isEmpty());
        WriteAction.run(() -> {
            var sdk = interpreter.getSdkModificator();
            sdk.setHomePath(selected);
            sdk.setVersionString(lines.getFirst());
            roots.forEach(root -> sdk.addRoot(root, OrderRootType.CLASSES));
            sdk.commitChanges();
            ProjectJdkTable.getInstance().addJdk(interpreter, getTestRootDisposable());
        });
        PythonInterpreters.assign(getModule(), interpreter);
        IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            ModuleRootModificationUtil.setModuleSdk(getModule(), null);
            if (sourceRoot != null) {
                ModuleRootModificationUtil.updateModel(getModule(), model -> {
                    for (var entry : model.getContentEntries()) {
                        if (entry.getUrl().equals(sourceRoot.getUrl())) model.removeContentEntry(entry);
                    }
                });
            }
        } finally {
            super.tearDown();
        }
    }

    protected static Map<String, String> childEnvironment() {
        var environment = new LinkedHashMap<String, String>();
        for (String name : List.of("SystemRoot", "WINDIR", "TEMP", "TMP", "PATH", "PATHEXT")) {
            String value = System.getenv(name);
            if (value != null) environment.put(name, value);
        }
        environment.put("PYTHONNOUSERSITE", "1");
        environment.put("PYTEST_DISABLE_PLUGIN_AUTOLOAD", "1");
        return environment;
    }

    protected ProcessOutput executeNative(RunConfiguration configuration) throws Exception {
        var executor = DefaultRunExecutor.getRunExecutorInstance();
        var environment = ExecutionEnvironmentBuilder.create(executor, configuration).build();
        var runner = ProgramRunner.getRunner(executor.getId(), configuration);
        assertNotNull(runner);
        var process = new AtomicReference<ProcessHandler>();
        var descriptor = new AtomicReference<RunContentDescriptor>();
        var output = new CapturingProcessAdapter();
        environment.setCallback(descriptor::set);
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            private void capture(ExecutionEnvironment started, ProcessHandler handler) {
                if (started == environment && process.compareAndSet(null, handler)) handler.addProcessListener(output);
            }
            @Override public void processStarting(String id, ExecutionEnvironment started, ProcessHandler handler) { capture(started, handler); }
            @Override public void processStarted(String id, ExecutionEnvironment started, ProcessHandler handler) { capture(started, handler); }
        });
        try {
            runner.execute(environment);
            PlatformTestUtil.waitWithEventsDispatching("Native process starts", () -> process.get() != null, 30);
            PlatformTestUtil.waitWithEventsDispatching("Native console is created", () -> descriptor.get() != null, 30);
            assertSame(process.get(), descriptor.get().getProcessHandler());
            assertNotNull(descriptor.get().getExecutionConsole());
            PlatformTestUtil.waitWithEventsDispatching("Native process finishes", process.get()::isProcessTerminated, 45);
            assertEquals(output.getOutput().getStderr(), Integer.valueOf(0), process.get().getExitCode());
            return output.getOutput();
        } finally {
            if (process.get() != null && !process.get().isProcessTerminated()) {
                process.get().destroyProcess();
                PlatformTestUtil.waitWithEventsDispatching("Owned child stops", process.get()::isProcessTerminated, 20);
            }
            var contents = RunContentManager.getInstance(getProject());
            var owned = new java.util.LinkedHashSet<RunContentDescriptor>();
            if (descriptor.get() != null) owned.add(descriptor.get());
            for (var candidate : contents.getAllDescriptors()) {
                if (candidate.getProcessHandler() == process.get() && process.get() != null
                        || environment.getExecutionId() > 0 && candidate.getExecutionId() == environment.getExecutionId()) {
                    owned.add(candidate);
                }
            }
            for (var content : owned) {
                var console = content.getExecutionConsole();
                contents.removeRunContent(executor, content);
                Disposer.dispose(content);
                if (console != null) Disposer.dispose(console);
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        }
    }
}
