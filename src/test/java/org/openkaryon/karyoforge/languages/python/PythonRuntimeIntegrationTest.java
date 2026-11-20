package org.openkaryon.karyoforge.languages.python;

import com.intellij.execution.PsiLocation;
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
