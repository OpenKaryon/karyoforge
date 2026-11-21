package org.openkaryon.karyoforge.languages.python;

import com.intellij.execution.PsiLocation;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.jetbrains.python.sdk.PythonSdkUpdater;
import com.jetbrains.python.sdk.PythonSdkUtil;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.RunConfigurationProducer;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.CapturingProcessAdapter;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.jetbrains.python.debugger.PyDebugRunner;
import com.jetbrains.python.debugger.PyLineBreakpointType;
import com.jetbrains.python.psi.PyFunction;
import com.jetbrains.python.run.PythonConfigurationType;
import com.jetbrains.python.run.PythonRunConfiguration;
import com.jetbrains.python.testing.PyAbstractTestConfiguration;
import com.jetbrains.python.testing.PyAbstractTestFactory;
import com.jetbrains.python.testing.PyTestConfiguration;
import com.jetbrains.python.testing.PyTestLineMarkerContributor;
import com.jetbrains.python.testing.PyTestsConfigurationProducer;
import com.jetbrains.python.testing.PyUnitTestConfiguration;
import com.jetbrains.python.testing.PythonTestConfigurationType;
import com.jetbrains.python.testing.TestRunnerService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PythonRuntimeIntegrationTest extends PythonRuntimeFixture {
    public void testNativeScriptAndModuleExecutionWithSelectedInterpreter() throws Exception {
        myFixture.addFileToProject("runtime sources/calculation.py", "def answer():\n    return 42\n");
        var script = myFixture.addFileToProject("runtime sources/application.py",
                "import os\nfrom calculation import answer\nprint('FORGE_RUN', answer(), os.environ['FORGE_FIXTURE'])\n");
        for (boolean moduleMode : new boolean[]{false, true}) {
            var configuration = scriptConfiguration();
            configuration.setModuleMode(moduleMode);
            configuration.setScriptName(moduleMode ? "application" : script.getVirtualFile().getPath());
            var environment = childEnvironment();
            environment.put("FORGE_FIXTURE", "explicit-value");
            configuration.setEnvs(environment);
            assertTrue(executeNative(configuration).getStdout().contains("FORGE_RUN 42 explicit-value"));
        }
    }

    public void testNativeUnittestGutterRunsOnlyChosenMethod() throws Exception {
        selectedTest(PythonTestConfigurationType.getInstance().getUnitTestFactory(), PyUnitTestConfiguration.class, """
                from pathlib import Path
                import os
                import unittest

                class CalculationTests(unittest.TestCase):
                    def test_selected(self):
                        Path(__file__).with_suffix('.pid').write_text(str(os.getpid()))
                        self.assertEqual(42, 6 * 7)

                    def test_neighbour(self):
                        Path(__file__).with_suffix('.neighbour').write_text('must not run')
                        self.fail('The unselected test was executed')
                """);
    }

    public void testNativePytestGutterRunsOnlyChosenFunction() throws Exception {
        selectedTest(PythonTestConfigurationType.getInstance().getPyTestFactory(), PyTestConfiguration.class, """
                from pathlib import Path
                import os

                def test_selected():
                    Path(__file__).with_suffix('.pid').write_text(str(os.getpid()))
                    assert 6 * 7 == 42

                def test_neighbour():
                    Path(__file__).with_suffix('.neighbour').write_text('must not run')
                    assert False, 'The unselected test was executed'
                """);
    }

    private void selectedTest(PyAbstractTestFactory<?> factory, Class<?> expected, String source) throws Exception {
        var service = TestRunnerService.getInstance(getModule());
        var previous = service.getSelectedFactory();
        service.setSelectedFactory(factory);
        try {
            var file = myFixture.addFileToProject("runtime sources/test_context.py", source);
            var function = PsiTreeUtil.findChildrenOfType(file, PyFunction.class).stream()
                    .filter(value -> "test_selected".equals(value.getName())).findFirst().orElseThrow();
            var identifier = function.getNameIdentifier();
            assertNotNull(identifier);
            var contributor = RunLineMarkerContributor.EXTENSION.allForLanguage(identifier.getLanguage()).stream()
                    .filter(PyTestLineMarkerContributor.class::isInstance).findFirst().orElseThrow();
            var marker = contributor.getInfo(identifier);
            assertNotNull(marker);
            assertTrue(Arrays.stream(marker.actions).anyMatch(action -> action instanceof ExecutorAction executor
                    && DefaultRunExecutor.EXECUTOR_ID.equals(executor.getExecutor().getId())));
            var producer = RunConfigurationProducer.getProducers(getProject()).stream()
                    .filter(PyTestsConfigurationProducer.class::isInstance).findFirst().orElseThrow();
            var context = ConfigurationContext.createEmptyContextForLocation(new PsiLocation<>(getProject(), getModule(), identifier));
            var produced = producer.createConfigurationFromContext(context);
            assertNotNull(produced);
            var configuration = (PyAbstractTestConfiguration) produced.getConfiguration();
            assertInstanceOf(configuration, expected);
            assertTrue(configuration.getTarget().getTarget().contains("test_selected"));
            configuration.setUseModuleSdk(true);
            configuration.setPassParentEnvs(false);
            configuration.setEnvs(childEnvironment());
            configuration.setBeforeRunTasks(List.of());
            var output = executeNative(configuration).getStdout();
            assertEquals(output, 1, output.lines().filter(line -> line.contains("##teamcity[testStarted")).count());
            assertEquals(output, 1, output.lines().filter(line -> line.contains("##teamcity[testFinished")).count());
            assertFalse(output, output.contains("##teamcity[testFailed"));
            assertTrue(output, output.contains("test_selected"));
            Path folder = Path.of(sourceRoot.getPath());
            assertFalse(Files.exists(folder.resolve("test_context.neighbour")));
            long child = Long.parseLong(Files.readString(folder.resolve("test_context.pid")).trim());
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            service.setSelectedFactory(previous);
        }
    }

    public void testNativeDebuggerPausesEvaluatesAndResumes() throws Exception {
        var file = myFixture.addFileToProject("runtime sources/debug_values.py", "value = 6\nvalue *= 7\nprint(value)\n");
        var configuration = scriptConfiguration();
        configuration.setScriptName(file.getVirtualFile().getPath());
        var manager = XDebuggerManager.getInstance(getProject()).getBreakpointManager();
        var type = XDebuggerUtil.getInstance().findBreakpointType(PyLineBreakpointType.class);
        assertNotNull(type);
        var breakpoint = WriteAction.compute(() -> manager.addLineBreakpoint(type, file.getVirtualFile().getUrl(), 2, null));
        var executor = DefaultDebugExecutor.getDebugExecutorInstance();
        var environment = ExecutionEnvironmentBuilder.create(executor, configuration).build();
        var runner = (PyDebugRunner) ProgramRunner.getRunner(executor.getId(), configuration);
        assertNotNull(runner);
        var paused = new AtomicBoolean();
        var captured = new CapturingProcessAdapter();
        try {
            runner.executeWithListener(environment, new XDebugSessionListener() {
                @Override public void sessionPaused() { paused.set(true); }
            });
            PlatformTestUtil.waitWithEventsDispatching("Python breakpoint is reached", paused::get, 45);
            var debugger = runner.getProcess();
            assertNotNull(debugger);
            assertEquals(2, debugger.getSession().getCurrentPosition().getLine());
            debugger.getProcessHandler().addProcessListener(captured);
            var evaluated = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                    debugger.evaluate("value", false, false)), 15_000);
            assertEquals("42", evaluated.getValue());
            debugger.getSession().resume();
            PlatformTestUtil.waitWithEventsDispatching("Debugged child exits", debugger.getProcessHandler()::isProcessTerminated, 30);
            assertEquals(Integer.valueOf(0), debugger.getProcessHandler().getExitCode());
            assertTrue(captured.getOutput().getStdout(), captured.getOutput().getStdout().contains("42"));
        } finally {
            var debugger = runner.getProcess();
            if (debugger != null) {
                if (!debugger.getProcessHandler().isProcessTerminated()) {
                    debugger.getSession().stop();
                    PlatformTestUtil.waitWithEventsDispatching("Owned debugger child stops", debugger.getProcessHandler()::isProcessTerminated, 20);
                }
                var console = debugger.getSession().getConsoleView();
                if (console != null) Disposer.dispose(console);
            }
            WriteAction.run(() -> manager.removeBreakpoint(breakpoint));
            for (var descriptor : RunContentManager.getInstance(getProject()).getAllDescriptors()) {
                if (debugger != null && descriptor.getProcessHandler() == debugger.getProcessHandler()
                        || environment.getExecutionId() > 0 && descriptor.getExecutionId() == environment.getExecutionId()) {
                    RunContentManager.getInstance(getProject()).removeRunContent(executor, descriptor);
                    Disposer.dispose(descriptor);
                }
            }
            runner.resetProcess();
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        }
    }

    public void testNativeSdkSetupAndReuseForAnExistingProjectEnvironment() throws Exception {
        Path environmentRoot = Path.of(sourceRoot.getPath()).resolve(".venv");
        var creation = new GeneralCommandLine(interpreter.getHomePath(), "-m", "venv", "--without-pip", environmentRoot.toString())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE).withEnvironment(childEnvironment());
        var created = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                new CapturingProcessHandler(creation).runProcess(30_000)), 35_000);
        assertFalse(created.isTimeout());
        assertEquals(created.getStderr(), 0, created.getExitCode());
        Path executable = environmentRoot.resolve(com.intellij.openapi.util.SystemInfo.isWindows ? "Scripts/python.exe" : "bin/python");
        assertTrue(Files.isRegularFile(executable));
        PythonSdkUpdater.setEnabledInTests(true);
        try {
            var sdk = ExistingPythonEnvironments.prepare(executable);
            assertNotNull(sdk);
            Disposer.register(getTestRootDisposable(), () -> WriteAction.run(() ->
                    com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().removeJdk(sdk)));
            assertTrue(sdk.getVersionString(), sdk.getVersionString().startsWith("Python 3."));
            int count = PythonSdkUtil.getAllSdks().size();
            assertSame(sdk, ExistingPythonEnvironments.prepare(executable));
            assertEquals(count, PythonSdkUtil.getAllSdks().size());
            assertTrue(PythonInterpreters.assign(getModule(), sdk));
            var file = myFixture.addFileToProject("runtime sources/environment_identity.py", "import sys\nprint('FORGE_ENV=' + sys.executable)\n");
            var configuration = scriptConfiguration();
            configuration.setSdk(sdk);
            configuration.setScriptName(file.getVirtualFile().getPath());
            String output = executeNative(configuration).getStdout();
            String selected = output.lines().filter(line -> line.startsWith("FORGE_ENV=")).findFirst().orElseThrow();
            assertEquals(executable, Path.of(selected.substring("FORGE_ENV=".length())));
        } finally {
            PythonSdkUpdater.setEnabledInTests(false);
        }
    }

    public void testCreateRealEnvironmentWithBundledPipAndNativeSdk() throws Exception {
        var lifetime = Disposer.newDisposable();
        Path target = Path.of(sourceRoot.getPath()).resolve("created environment");
        var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
        try {
            Path executable = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                    VirtualEnvironmentCreation.create(getProject(), lifetime, Path.of(interpreter.getHomePath()), target, indicator)), 60_000);
            var pip = PythonEnvironmentProcess.command(executable, target, List.of("-I", "-m", "pip", "--version"));
            var output = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                    new CapturingProcessHandler(pip).runProcess(15_000)), 20_000);
            assertEquals(output.getStderr(), 0, output.getExitCode());
            assertTrue(output.getStdout(), output.getStdout().contains(target.toString()));
            PythonSdkUpdater.setEnabledInTests(true);
            try {
                var sdk = ExistingPythonEnvironments.prepare(executable);
                assertNotNull(sdk);
                Disposer.register(getTestRootDisposable(), () -> WriteAction.run(() ->
                        com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().removeJdk(sdk)));
                assertFalse(VirtualEnvironmentCreation.assignIfUnchanged(getModule(), null, sdk));
                assertSame(interpreter, PythonSdkUtil.findPythonSdk(getModule()));
                assertTrue(VirtualEnvironmentCreation.assignIfUnchanged(getModule(), interpreter, sdk));
                assertSame(sdk, PythonSdkUtil.findPythonSdk(getModule()));
            } finally {
                PythonSdkUpdater.setEnabledInTests(false);
            }
        } finally {
            Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    public void testCancellingAnOwnedEnvironmentProcessTerminatesItsChild() throws Exception {
        var lifetime = Disposer.newDisposable();
        Path root = Path.of(sourceRoot.getPath());
        Path pid = root.resolve("owned-process.pid");
        var command = PythonEnvironmentProcess.command(Path.of(interpreter.getHomePath()), root, List.of("-I", "-c",
                "import os,time,pathlib;pathlib.Path('owned-process.pid').write_text(str(os.getpid()));time.sleep(120)"));
        var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
        var future = AppExecutorUtil.getAppExecutorService().submit(() -> {
            try {
                PythonEnvironmentProcess.run(getProject(), lifetime, command, "Cancellation acceptance", indicator);
                fail("Cancelled operations must not succeed");
            } catch (com.intellij.openapi.progress.ProcessCanceledException expected) {
                return true;
            }
            return false;
        });
        try {
            PlatformTestUtil.waitWithEventsDispatching("Owned Python child starts", () -> Files.exists(pid), 20);
            long child = Long.parseLong(Files.readString(pid));
            indicator.cancel();
            assertTrue(PlatformTestUtil.waitForFuture(future, 15_000));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            assertSame(interpreter, PythonSdkUtil.findPythonSdk(getModule()));
        } finally {
            indicator.cancel();
            PlatformTestUtil.waitWithEventsDispatching("Owned operation stops", future::isDone, 20);
            Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    public void testCompletedOperationConsoleDisposalDoesNotCancelPostProcessing() throws Exception {
        var lifetime = Disposer.newDisposable();
        var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
        String title = "Completed console lifecycle acceptance";
        indicator.start();
        try {
            var command = PythonEnvironmentProcess.command(Path.of(interpreter.getHomePath()),
                    Path.of(sourceRoot.getPath()), List.of("-I", "-c", "print('completed operation', flush=True)"));
            PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() -> {
                PythonEnvironmentProcess.run(getProject(), lifetime, command, title, indicator);
                return null;
            }), 20_000);
            assertFalse(indicator.isCanceled());

            // Run content can be reused while the task still refreshes installed packages.
            Disposer.dispose(lifetime);
            assertFalse("Closing completed output must not cancel post-processing", indicator.isCanceled());
            indicator.checkCanceled();
            assertTrue("The surrounding task still owns its progress lifetime", indicator.isRunning());
            assertSame(interpreter, PythonSdkUtil.findPythonSdk(getModule()));
        } finally {
            indicator.stop();
            if (!Disposer.isDisposed(lifetime)) Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    public void testFailedOperationConsoleDisposalKeepsItsFailureWithoutCancellation() throws Exception {
        var lifetime = Disposer.newDisposable();
        var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
        String title = "Failed console lifecycle acceptance";
        indicator.start();
        try {
            var command = PythonEnvironmentProcess.command(Path.of(interpreter.getHomePath()),
                    Path.of(sourceRoot.getPath()), List.of("-I", "-c",
                            "import sys;print('owned failure output', flush=True);sys.exit(7)"));
            var failure = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() -> {
                try {
                    PythonEnvironmentProcess.run(getProject(), lifetime, command, title, indicator);
                    return null;
                } catch (com.intellij.execution.ExecutionException expected) {
                    return expected;
                }
            }), 20_000);
            assertNotNull("The actual process failure must be reported", failure);
            assertTrue(failure.getMessage(), failure.getMessage().contains("exit code 7"));
            assertTrue(failure.getMessage(), failure.getMessage().contains("owned failure output"));
            Disposer.dispose(lifetime);
            assertFalse("Disposing failed output must not convert failure into cancellation", indicator.isCanceled());
            assertSame(interpreter, PythonSdkUtil.findPythonSdk(getModule()));
        } finally {
            indicator.stop();
            if (!Disposer.isDisposed(lifetime)) Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    public void testActiveOperationConsoleDisposalCancelsItsOwnedProcess() throws Exception {
        var lifetime = Disposer.newDisposable();
        var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
        Path root = Path.of(sourceRoot.getPath());
        Path pid = root.resolve("console-disposal.pid");
        String title = "Active console lifecycle acceptance";
        var command = PythonEnvironmentProcess.command(Path.of(interpreter.getHomePath()), root, List.of("-I", "-c",
                "import os,time,pathlib;pathlib.Path('console-disposal.pid').write_text(str(os.getpid()));time.sleep(120)"));
        indicator.start();
        var future = AppExecutorUtil.getAppExecutorService().submit(() -> {
            try {
                PythonEnvironmentProcess.run(getProject(), lifetime, command, title, indicator);
                fail("Disposing active output must cancel its operation");
            } catch (com.intellij.openapi.progress.ProcessCanceledException expected) {
                return true;
            }
            return false;
        });
        try {
            PlatformTestUtil.waitWithEventsDispatching("Owned process starts", () -> pid.toFile().length() > 0, 20);
            long child = Long.parseLong(Files.readString(pid));
            assertTrue(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            Disposer.dispose(lifetime);
            assertTrue(PlatformTestUtil.waitForFuture(future, 15_000));
            assertTrue(indicator.isCanceled());
            assertFalse("The owned process exits when its active console is disposed",
                    ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            assertSame(interpreter, PythonSdkUtil.findPythonSdk(getModule()));
        } finally {
            indicator.cancel();
            PlatformTestUtil.waitWithEventsDispatching("Owned operation stops", future::isDone, 20);
            indicator.stop();
            if (!Disposer.isDisposed(lifetime)) Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    private void disposeEnvironmentProcesses() {
        var manager = RunContentManager.getInstance(getProject());
        for (var descriptor : manager.getAllDescriptors()) {
            var handler = descriptor.getProcessHandler();
            if (handler != null && handler.isProcessTerminated()) {
                manager.removeRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
                if (!Disposer.isDisposed(descriptor)) Disposer.dispose(descriptor);
            }
        }
    }

    public void testRequirementsInstallAndRemovalRefreshNativePackageState() throws Exception {
        Path root = Path.of(sourceRoot.getPath());
        var lifetime = Disposer.newDisposable();
        try {
            Path target = root.resolve("dependency environment");
            var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
            Path executable = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                    VirtualEnvironmentCreation.create(getProject(), lifetime, Path.of(interpreter.getHomePath()), target, indicator)), 60_000);
            PythonSdkUpdater.setEnabledInTests(true);
            com.intellij.openapi.projectRoots.Sdk sdk;
            try {
                sdk = ExistingPythonEnvironments.prepare(executable);
                assertNotNull(sdk);
                Disposer.register(getTestRootDisposable(), () -> WriteAction.run(() ->
                        com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().removeJdk(sdk)));
            } finally {
                PythonSdkUpdater.setEnabledInTests(false);
            }
            PythonInterpreters.assign(getModule(), sdk);
            var environment = PythonDependencyOperations.selected(getModule());
            Path wheel = root.resolve("karyoforge_fixture-1.0.0-py3-none-any.whl");
            var entries = new java.util.LinkedHashMap<String, String>();
            entries.put("karyoforge_fixture.py", "VALUE = 42\n");
            entries.put("karyoforge_fixture-1.0.0.dist-info/METADATA", "Metadata-Version: 2.1\nName: karyoforge-fixture\nVersion: 1.0.0\n");
            entries.put("karyoforge_fixture-1.0.0.dist-info/WHEEL", "Wheel-Version: 1.0\nGenerator: KaryoForge acceptance\nRoot-Is-Purelib: true\nTag: py3-none-any\n");
            entries.put("karyoforge_fixture-1.0.0.dist-info/RECORD", "");
            entries.put("karyoforge_fixture-1.0.0.dist-info/RECORD",
                    entries.keySet().stream().map(name -> name + ",,\n").collect(java.util.stream.Collectors.joining()));
            try (var archive = new java.util.zip.ZipOutputStream(Files.newOutputStream(wheel))) {
                for (var entry : entries.entrySet()) {
                    archive.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                    archive.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    archive.closeEntry();
                }
            }
            Path requirements = Files.writeString(root.resolve("project requirements.txt"), "--no-index\n./" + wheel.getFileName() + "\n");
            PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() -> {
                PythonDependencyOperations.run(getProject(), lifetime, environment, root,
                        PythonDependencyOperations.requirementsArguments(requirements), "Requirements acceptance", indicator);
                return true;
            }), 45_000);
            var manager = environment.packages();
            assertTrue(manager.getPackages().toString(), manager.getPackages().stream().anyMatch(value -> "karyoforge-fixture".equals(value.getName().replace('_', '-'))));
            var imported = PythonEnvironmentProcess.command(executable, root, List.of("-I", "-c", "import karyoforge_fixture;print(karyoforge_fixture.VALUE)"));
            var output = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() -> new CapturingProcessHandler(imported).runProcess(15_000)), 20_000);
            assertEquals(output.getStderr(), 0, output.getExitCode());
            assertEquals("42", output.getStdout().trim());
            PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() -> {
                PythonDependencyOperations.run(getProject(), lifetime, environment, root,
                        List.of("uninstall", "--yes", "karyoforge-fixture"), "Package removal acceptance", indicator);
                return true;
            }), 45_000);
            assertFalse(manager.getPackages().stream().anyMatch(value -> "karyoforge-fixture".equals(value.getName().replace('_', '-'))));
            var removed = PythonEnvironmentProcess.command(executable, root, List.of("-I", "-c",
                    "import importlib.util;assert importlib.util.find_spec('karyoforge_fixture') is None"));
            var removalOutput = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                    new CapturingProcessHandler(removed).runProcess(15_000)), 20_000);
            assertEquals(removalOutput.getStderr(), 0, removalOutput.getExitCode());
            Files.writeString(requirements, "--no-index\n./missing-fixture.whl\n");
            assertTrue(PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() -> {
                try {
                    PythonDependencyOperations.run(getProject(), lifetime, environment, root,
                            PythonDependencyOperations.requirementsArguments(requirements), "Failed requirements acceptance", indicator);
                    return false;
                } catch (com.intellij.execution.ExecutionException expected) {
                    return expected.getMessage().contains("exit code");
                }
            }), 30_000));
            assertSame(sdk, PythonSdkUtil.findPythonSdk(getModule()));
        } finally {
            Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    public void testStandardProjectEditableAndRegularInstallWithSrcLayoutAndExtra() throws Exception {
        Path root = Path.of(sourceRoot.getPath());
        var lifetime = Disposer.newDisposable();
        try {
            Path target = root.resolve("project install environment");
            var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
            Path executable = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                    VirtualEnvironmentCreation.create(getProject(), lifetime, Path.of(interpreter.getHomePath()), target, indicator)), 60_000);
            PythonSdkUpdater.setEnabledInTests(true);
            com.intellij.openapi.projectRoots.Sdk sdk;
            try {
                sdk = ExistingPythonEnvironments.prepare(executable);
                assertNotNull(sdk);
                Disposer.register(getTestRootDisposable(), () -> WriteAction.run(() ->
                        com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().removeJdk(sdk)));
            } finally {
                PythonSdkUpdater.setEnabledInTests(false);
            }
            PythonInterpreters.assign(getModule(), sdk);
            var environment = PythonDependencyOperations.selected(getModule());
            Path project = Files.createDirectory(root.resolve("standard src project"));
            Path source = Files.createDirectories(project.resolve("src")).resolve("karyoforge_project_fixture.py");
            Files.writeString(source, "VALUE = 'original source'\n");
            Path backend = Files.createDirectory(project.resolve("build_backend"));
            try (var resource = getClass().getResourceAsStream("/python/local_build_backend.py")) {
                assertNotNull(resource);
                Files.copy(resource, backend.resolve("local_backend.py"));
            }
            Path file = Files.writeString(project.resolve("pyproject.toml"), """
                    [build-system]
                    requires = []
                    build-backend = "local_backend"
                    backend-path = ["build_backend"]
                    [project]
                    name = "karyoforge-project-fixture"
                    version = "1.0.0"
                    [project.optional-dependencies]
                    validation = ["pip"]
                    """);
            var metadata = StandardPythonProject.read(getProject(), file);
            assertEquals(List.of("validation"), metadata.extras());
            for (boolean editable : new boolean[]{true, false}) {
                var arguments = new java.util.ArrayList<>(metadata.arguments(editable, List.of("validation"), true));
                arguments.add("--no-index");
                PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() -> {
                    PythonDependencyOperations.run(getProject(), lifetime, environment, project, arguments,
                            "Standard project acceptance", indicator);
                    return true;
                }), 60_000);
                Files.writeString(source, editable ? "VALUE = 'changed editable source'\n" : "VALUE = 'changed after regular install'\n");
                var command = PythonEnvironmentProcess.command(executable, root, List.of("-I", "-c",
                        "import karyoforge_project_fixture as package;print(package.VALUE)"));
                var output = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                        new CapturingProcessHandler(command).runProcess(15_000)), 20_000);
                assertEquals(output.getStderr(), 0, output.getExitCode());
                assertEquals("changed editable source", output.getStdout().trim());
            }
            assertSame(sdk, PythonSdkUtil.findPythonSdk(getModule()));
        } finally {
            Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    public void testNativeConsoleAndTerminalUseModuleEnvironmentAndPreserveExistingSessions() throws Exception {
        Path root = Path.of(sourceRoot.getPath());
        var lifetime = Disposer.newDisposable();
        var consoles = new java.util.ArrayList<com.jetbrains.python.console.PydevConsoleRunner>();
        var settings = com.jetbrains.python.console.PyConsoleOptions.getInstance(getProject()).getPythonConsoleSettings();
        String previousHome = settings.getSdkHome();
        boolean previousUseModule = settings.isUseModuleSdk();
        boolean previousParent = settings.isPassParentEnvs();
        String previousWorkingDirectory = settings.getWorkingDirectory();
        var previousEnvs = new java.util.LinkedHashMap<>(settings.getEnvs());
        var terminalSettings = com.intellij.python.terminal.PyVirtualEnvTerminalSettings.Companion.getInstance(getProject());
        boolean previousActivation = terminalSettings.getVirtualEnvActivate();
        var terminalOptions = org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider.getInstance(getProject());
        String previousShell = terminalOptions.getShellPath();
        try {
            var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
            Path target = root.resolve("session environment");
            Path executable = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                    VirtualEnvironmentCreation.create(getProject(), lifetime, Path.of(interpreter.getHomePath()), target, indicator)), 60_000);
            PythonSdkUpdater.setEnabledInTests(true);
            com.intellij.openapi.projectRoots.Sdk sdk;
            try {
                sdk = ExistingPythonEnvironments.prepare(executable);
                assertNotNull(sdk);
                Disposer.register(getTestRootDisposable(), () -> WriteAction.run(() ->
                        com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().removeJdk(sdk)));
            } finally {
                PythonSdkUpdater.setEnabledInTests(false);
            }
            PythonInterpreters.assign(getModule(), sdk);
            terminalOptions.setShellPath("cmd.exe");
            terminalSettings.setVirtualEnvActivate(false);
            assertNull("The public provider's activation opt-out is preserved", ModulePythonSessions.cmdActivation(getModule()));
            terminalSettings.setVirtualEnvActivate(true);
            assertNotNull("The public provider enables the new cmd tab's activation", ModulePythonSessions.cmdActivation(getModule()));
            String directory = ModulePythonSessions.terminalDirectory(getModule()).toString();
            assertTrue("The new SDK must be a native virtual environment", PythonSdkUtil.isVirtualEnv(sdk));
            assertNotNull("The SDK executable must be visible to the native VFS", sdk.getHomeDirectory());
            assertSame(sdk, com.intellij.openapi.application.ReadAction.compute(() ->
                    com.jetbrains.python.sdk.PySdkUtil.findSdkForDirectory(getProject(), Path.of(directory), false)));
            var firstTerminal = customizeNativeTerminal(directory);
            assertNotNull("Native terminal activation must expose VIRTUAL_ENV", firstTerminal.get("VIRTUAL_ENV"));
            assertEquals(target, Path.of(firstTerminal.get("VIRTUAL_ENV")));
            verifyTerminalPython(directory, firstTerminal, executable);
            settings.setSdkHome(interpreter.getHomePath());
            settings.setUseModuleSdk(false);
            settings.setPassParentEnvs(false);
            settings.setEnvs(childEnvironment());
            settings.setWorkingDirectory(root.toString());
            var first = ModulePythonSessions.console(getModule());
            consoles.add(first);
            startConsole(first);
            assertConsolePython(first, root.resolve("first-console.txt"), executable);
            PythonInterpreters.assign(getModule(), interpreter);
            assertEquals(Path.of(directory), ModulePythonSessions.terminalDirectory(getModule()));
            var nextTerminal = customizeNativeTerminal(directory);
            assertEquals(Path.of(interpreter.getHomePath()).getParent().getParent(), Path.of(nextTerminal.get("VIRTUAL_ENV")));
            assertEquals(target, Path.of(firstTerminal.get("VIRTUAL_ENV")));
            verifyTerminalPython(directory, nextTerminal, Path.of(interpreter.getHomePath()));
            var second = ModulePythonSessions.console(getModule());
            consoles.add(second);
            startConsole(second);
            assertConsolePython(second, root.resolve("second-console.txt"), Path.of(interpreter.getHomePath()));
            assertConsolePython(first, root.resolve("preserved-console.txt"), executable);
            assertSame(sdk, first.getSdk());
            assertEquals(interpreter.getHomePath(), settings.getSdkHome());
            assertFalse(settings.isUseModuleSdk());
        } finally {
            settings.setSdkHome(previousHome);
            settings.setUseModuleSdk(previousUseModule);
            settings.setPassParentEnvs(previousParent);
            settings.setWorkingDirectory(previousWorkingDirectory);
            settings.setEnvs(previousEnvs);
            terminalSettings.setVirtualEnvActivate(previousActivation);
            terminalOptions.setShellPath(previousShell);
            for (var console : consoles) {
                var handler = console.getProcessHandler();
                if (handler != null && !handler.isProcessTerminated()) {
                    handler.destroyProcess();
                    PlatformTestUtil.waitWithEventsDispatching("Owned Python console stops", handler::isProcessTerminated, 20);
                }
                if (console.getConsoleView() != null) Disposer.dispose(console.getConsoleView());
            }
            for (var descriptor : com.jetbrains.python.console.PythonConsoleToolWindow.getInstance(getProject()).getConsoleContentDescriptors()) {
                Disposer.dispose(descriptor);
            }
            Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    public void testCmdActivationUsesTheSelectedEnvironmentWithoutCachedProviderVariables() throws Exception {
        Path root = Path.of(sourceRoot.getPath());
        var lifetime = Disposer.newDisposable();
        try {
            Path target = root.resolve("cmd & activation");
            Path executable = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                    VirtualEnvironmentCreation.create(getProject(), lifetime, Path.of(interpreter.getHomePath()), target,
                            new com.intellij.openapi.progress.EmptyProgressIndicator())), 60_000);
            String activation = ModulePythonSessions.cmdActivationCommand(executable, "cmd.exe /D", true);
            assertNotNull(activation);
            Files.writeString(root.resolve("cmd_activation_probe.py"), """
                    import os, sys
                    from pathlib import Path
                    print(sys.executable)
                    print(os.environ.get('VIRTUAL_ENV', ''))
                    print(os.environ.get('PATH', '').lower().split(os.pathsep).count(str(Path(sys.executable).parent).lower()))
                    """);
            Files.writeString(root.resolve("cmd_activation_probe.cmd"), "@echo off\r\n" + activation
                    + "\r\npython -I \"%~dp0cmd_activation_probe.py\"\r\n");
            for (String prior : List.of("", root.resolve("other environment").toString(), target.toString())) {
                var environment = childEnvironment();
                environment.put("VIRTUAL_ENV", prior);
                if (prior.equals(target.toString())) {
                    environment.put("PATH", executable.getParent() + ";" + environment.getOrDefault("PATH", ""));
                }
                var command = new GeneralCommandLine(System.getenv("SystemRoot") + "\\System32\\cmd.exe", "/d", "/c",
                        "cmd_activation_probe.cmd").withWorkDirectory(root.toFile())
                        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE).withEnvironment(environment);
                var output = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                        new CapturingProcessHandler(command).runProcess(20_000)), 25_000);
                assertEquals(output.getStderr(), 0, output.getExitCode());
                String[] lines = output.getStdout().trim().split("\\R");
                assertEquals(output.getStdout(), 3, lines.length);
                assertEquals(executable, Path.of(lines[0]));
                assertEquals(target, Path.of(lines[1]));
                assertEquals("The selected Scripts directory appears once in PATH", "1", lines[2]);
            }
        } finally {
            Disposer.dispose(lifetime);
            disposeEnvironmentProcesses();
        }
    }

    public void testCmdActivationPreservesOptOutOtherShellsAndCustomStartupCommands() {
        Path executable = Path.of(interpreter.getHomePath());
        assertNull(ModulePythonSessions.cmdActivationCommand(executable, "cmd.exe", false));
        assertNull(ModulePythonSessions.cmdActivationCommand(executable, "powershell.exe -NoLogo", true));
        assertNull(ModulePythonSessions.cmdActivationCommand(executable, "cmd.exe /K custom-startup.cmd", true));
    }

    private java.util.Map<String, String> customizeNativeTerminal(String directory) throws Exception {
        return PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() -> {
            var environment = childEnvironment();
            new com.intellij.python.terminal.PyVirtualEnvTerminalCustomizer().customizeCommandAndEnvironment(getProject(), directory,
                    new String[]{System.getenv("SystemRoot") + "\\System32\\cmd.exe"}, environment);
            return environment;
        }), 30_000);
    }

    private void verifyTerminalPython(String directory, java.util.Map<String, String> environment, Path expected) throws Exception {
        Path root = Path.of(directory);
        Files.writeString(root.resolve("terminal_probe.py"), "import sys\nprint(sys.executable)\n");
        Files.writeString(root.resolve("terminal_acceptance.cmd"), "@echo off\r\npython -I \"%~dp0terminal_probe.py\"\r\n");
        var command = new GeneralCommandLine(System.getenv("SystemRoot") + "\\System32\\cmd.exe", "/d", "/c", "terminal_acceptance.cmd")
                .withWorkDirectory(directory).withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE).withEnvironment(environment);
        var output = PlatformTestUtil.waitForFuture(AppExecutorUtil.getAppExecutorService().submit(() ->
                new CapturingProcessHandler(command).runProcess(15_000)), 20_000);
        assertEquals(output.getStderr(), 0, output.getExitCode());
        assertEquals(expected, Path.of(output.getStdout().trim()));
    }

    private void startConsole(com.jetbrains.python.console.PydevConsoleRunner console) {
        var ready = new AtomicBoolean();
        console.addConsoleListener(view -> ready.set(true));
        console.run(true);
        PlatformTestUtil.waitWithEventsDispatching("Native Python console connects", ready::get, 45);
        assertNotNull(console.getProcessHandler());
        assertFalse(console.getProcessHandler().isProcessTerminated());
    }

    private void assertConsolePython(com.jetbrains.python.console.PydevConsoleRunner console, Path result, Path expected) throws Exception {
        String literal = "'" + result.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
        console.getConsoleExecuteActionHandler().processLine("import sys;from pathlib import Path;Path(" + literal + ").write_text(sys.executable)");
        PlatformTestUtil.waitWithEventsDispatching("Native Python console executes input", () -> {
            try { return Files.exists(result) && Files.size(result) > 0; } catch (java.io.IOException ignored) { return false; }
        }, 20);
        assertEquals(expected, Path.of(Files.readString(result)));
    }

    private PythonRunConfiguration scriptConfiguration() {
        var configuration = (PythonRunConfiguration) PythonConfigurationType.getInstance().getFactory().createTemplateConfiguration(getProject());
        configuration.setUseModuleSdk(false);
        configuration.setSdk(interpreter);
        configuration.setWorkingDirectory(sourceRoot.getPath());
        configuration.setPassParentEnvs(false);
        configuration.setEnvs(childEnvironment());
        configuration.setBeforeRunTasks(List.of());
        return configuration;
    }
}
