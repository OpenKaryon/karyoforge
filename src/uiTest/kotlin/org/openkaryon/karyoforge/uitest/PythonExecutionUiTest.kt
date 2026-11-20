package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.getRunContentManager
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.debugToolWindow
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/** Context runs must execute exactly the selected Python tests, not just open a run window. */
class PythonExecutionUiTest {
    @Test
    fun unittestRunsMethodClassFileFolderAndLastConfiguration() = exerciseFramework("Unittests")

    @Test
    fun pytestRunsMethodClassFileFolderAndLastConfiguration() = exerciseFramework("pytest")

    private fun exerciseFramework(framework: String) {
        val project = VmIde.fixtureProject("run-${framework.lowercase()}")
        val directory = project.resolve("tests").createDirectories()
        directory.resolve("__init__.py").writeText("")
        val marker = project.resolve("test-runs.txt")
        val fixture = directory.resolve("test_context.py")
        val declaration = if (framework == "Unittests") "import unittest\nclass ContextTest(unittest.TestCase):" else "class TestContext:"
        fixture.writeText("from pathlib import Path\n" + declaration + "\n" +
            "    def test_alpha(self):\n        with Path(${pythonString(marker)}).open('a') as result:\n            result.write('a')\n        assert 6 * 7 == 42\n" +
            "    def test_beta(self):\n        with Path(${pythonString(marker)}).open('a') as result:\n            result.write('b')\n        assert 'KaryoForge'.startswith('Karyo')\n")
        VmIde.context("run-${framework.lowercase()}", project).runIdeWithDriver(runTimeout = 40.minutes).useDriverAndCloseIde {
            waitForIndicators(10.minutes)
            val python = createEnvironment(project)
            if (framework == "pytest") installRequirements(project.resolve("requirements.txt"))
            invokeAction("ShowSettings", now = false)
            ideFrame {
                settingsDialog {
                    settingsTree.clickPath("Tools", "Python Integrated Tools", fullMatch = false)
                    comboBox { byAccessibleName("Default test runner:") }.selectItemContains(framework)
                    pressButton("OK")
                }
            }
            openFile(fixture.toString(), waitForCodeAnalysis = false)
            runAt("result.write('a')")
            expectTests(marker, "a", 1)
            runAt(if (framework == "Unittests") "class ContextTest" else "class TestContext")
            expectTests(marker, "aab", 2)
            runAt("from pathlib")
            expectTests(marker, "aaabb", 2)
            ideFrame {
                projectView { projectViewTree.clickPath(project.fileName.toString(), "tests", fullMatch = false) }
                keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_F10) }
            }
            expectTests(marker, "aaaabbb", 2)
            ideFrame { keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_F10) } }
            expectTests(marker, "aaaaabbbb", 2)
            assertInterpreter(project, python, "test_runner_environment")
        }
        VmIde.assertNoConsentCrash()
    }

    private fun Driver.runAt(text: String) {
        ideFrame {
            val editor = codeEditor()
            editor.click()
            val offset = editor.text.indexOf(text)
            assertTrue(offset >= 0, "The selected test context exists in the editor")
            editor.moveCaretToOffset(offset)
            editor.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_F10) }
        }
    }

    private fun Driver.expectTests(marker: Path, expected: String, count: Int) {
        awaitCondition("Only the selected context appends the expected test results", 10.minutes) {
            marker.exists() && marker.readText().length >= expected.length
        }
        val manager = getRunContentManager(singleProject())
        awaitCondition("The native Python test process terminates") {
            manager.getSelectedContent()?.getProcessHandler()?.isProcessTerminated() == true
        }
        assertEquals(expected, marker.readText().toCharArray().sorted().joinToString(""))
        ideFrame {
            val summary = if (count == 1) "1 test passed" else "$count tests passed"
            x { contains(byVisibleText(summary)) }.shouldBe("The native runner reports passing tests", present, 1.minutes)
        }
    }

    @Test
    fun debuggerStopsAtABreakpointEvaluatesAndResumesTheSelectedInterpreter() {
        val project = VmIde.project("debugger")
        val marker = project.resolve("debugger.result")
        val script = project.resolve("debug_fixture.py")
        script.writeText("value = 6\nvalue *= 7\nprint(value)\nfrom pathlib import Path\nPath(${pythonString(marker)}).write_text(str(value))\n")
        VmIde.context("debugger", project).runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde {
            waitForIndicators(10.minutes)
            val python = createEnvironment(project)
            openFile(script.toString(), waitForCodeAnalysis = false)
            ideFrame {
                val editor = codeEditor()
                editor.click()
                editor.moveCaretToOffset(editor.text.indexOf("print(value)"))
                editor.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_F8) }
                editor.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_F9) }
                debugToolWindow {
                    threadsAndVariablesTab.click()
                    x { contains(byVisibleText("value")) }.shouldBe("The suspended frame exposes the local variable", present, 5.minutes)
                    x { contains(byVisibleText("42")) }.shouldBe("The suspended frame has the computed value", present, 1.minutes)
                }
            }
            assertFalse(marker.exists(), "A suspended debug process has not reached the result write")
            invokeAction("EvaluateExpression", now = false)
            ui.dialog({ byTitle("Evaluate Expression") }) {
                editor().text = "value"
                pressButton("Evaluate")
                x { contains(byVisibleText("42")) }.shouldBe("Evaluate reads the suspended Python frame", present, 1.minutes)
                closeDialog()
            }
            ideFrame { debugToolWindow { resumeButton.click() } }
            awaitCondition("Resume completes the actual debug script") { marker.exists() && marker.readText() == "42" }
            assertInterpreter(project, python, "debugger_environment")
        }
        VmIde.assertNoConsentCrash()
    }
}
