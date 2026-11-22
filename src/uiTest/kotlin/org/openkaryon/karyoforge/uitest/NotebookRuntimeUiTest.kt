package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.pasteText
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

@Remote("javax.swing.JComboBox")
interface NotebookComboState { fun getSelectedItem(): String? }

@Remote("javax.swing.JLabel")
interface NotebookLabelState { fun getText(): String? }

internal fun Driver.chooseNotebookPython(python: Path = VmIde.notebookPython) {
    ideFrame { button { byVisibleText("Choose executable...") }.click() }
    selectFile(python)
    awaitCondition("The notebook shows the explicitly selected Python") { notebookUsesPython(python) }
}

internal fun Driver.notebookPythonChoice(): String {
    var result = ""
    ideFrame {
        val choice = comboBox { byAccessibleName("Notebook Python interpreter") }
        result = driver.cast(choice.component, NotebookComboState::class).getSelectedItem().orEmpty()
    }
    return result
}

internal fun Driver.notebookUsesPython(python: Path = VmIde.notebookPython): Boolean {
    val choice = notebookPythonChoice()
    val selected = when {
        choice.startsWith("Selected: ") -> choice.removePrefix("Selected: ")
        choice.startsWith("SDK: ") -> choice.substringAfterLast(" · ")
        else -> return false
    }
    // VirtualFile uses forward slashes even when the selected executable is on Windows.
    return Path.of(selected).toAbsolutePath().normalize() == python.toAbsolutePath().normalize()
}

internal fun Driver.notebookStatus(): String {
    var result = ""
    ideFrame { result = driver.cast(x { byAccessibleName("Notebook edit status") }.component, NotebookLabelState::class).getText().orEmpty() }
    return result
}

internal fun Driver.typeNotebookSource(name: String, source: String) {
    ideFrame {
        val editor = x { byAccessibleName(name) }
        editor.click()
        editor.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A) }
        ui.pasteText(source)
    }
}

class NotebookRuntimeUiTest {
    @Test
    fun dependencyChecksAreExplicitAndWorkspaceChoicesStaySeparateFromModuleSdks() {
        val project = VmIde.project("notebook Python selection")
        val first = project.resolve("first.ipynb")
        val second = project.resolve("second.ipynb")
        val original = notebookCells("code" to "raise RuntimeError('opening-must-not-run')")
        first.writeText(original)
        second.writeText(original)
        val context = VmIde.context("notebook-python-selection", project)
        context.runIdeWithDriver(runTimeout = 25.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(project.resolve("main.py"))
            ideFrame { codeEditor().click() }
            val modulePython = createEnvironment(project)
            openOwnedFile(first)
            assertEquals("Follow module SDK", notebookPythonChoice())
            ideFrame {
                x { contains(byVisibleText("Dependencies not checked")) }.shouldBe("Opening is passive", present, 2.minutes)
                button { byVisibleText("Check dependencies") }.click()
                x { contains(byVisibleText("Missing jupyter_client ipykernel")) }.shouldBe("The isolated module environment is missing notebook packages", present, 2.minutes)
            }
            assertEquals(original, first.readText())
            chooseNotebookPython()
            ideFrame {
                button { byVisibleText("Check dependencies") }.click()
                x { contains(byVisibleText("Dependencies ready:")) }.shouldBe("The pinned notebook runtime is ready", present, 2.minutes)
            }
            assertInterpreter(project, modulePython, "module_unchanged_by_notebook")
            openOwnedFile(second)
            assertEquals("Follow module SDK", notebookPythonChoice())
            openOwnedFile(first)
            assertTrue(notebookUsesPython())
            assertEquals(original, first.readText())
            assertEquals(original, second.readText())
        }
        context.runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(first)
            assertTrue(notebookUsesPython())
            ideFrame { x { contains(byVisibleText("Dependencies not checked")) }.shouldBe("Reopening restores the choice without probing", present, 2.minutes) }
            assertEquals(original, first.readText())
        }
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun runAllStopRestartAndShutdownKeepExecutionExplicitAndTheQueueOrdered() {
        val project = VmIde.project("notebook kernel controls")
        val file = project.resolve("controls.ipynb")
        val running = project.resolve("loop.ready")
        file.writeText(notebookCells(
            "code" to "value = 40 + 2\nprint('namespace-ready')",
            "markdown" to "# This is not executed",
            "code" to "import time\nfrom pathlib import Path\nPath(${pythonString(running)}).write_text('running')\nprint('loop-started',flush=True)\nwhile True: time.sleep(0.02)",
            "code" to "print('queued-cell-must-not-run')"))
        VmIde.context("notebook-kernel-controls", project).runIdeWithDriver(runTimeout = 25.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(file)
            chooseNotebookPython()
            ideFrame {
                button { byVisibleText("Run All") }.click()
            }
            awaitCondition("The ordered second Code cell executes in the owned project") { running.toFile().isFile && notebookStatus().contains("Running the selected cell") }
            ideFrame {
                button { byVisibleText("Stop") }.click()
                button { byVisibleText("Run All") }.shouldBe("Interrupt completes before another run", { isEnabled() }, 2.minutes)
            }
            invokeAction("SaveAll")
            awaitCondition("Stop saves the ordered prefix and skips the queued final cell") {
                val text = file.readText()
                text.contains("KeyboardInterrupt") && !text.contains("\"text\": \"queued-cell-must-not-run\\n\"")
            }
            assertTrue(file.readText().contains("namespace-ready"))
            ideFrame { button { byAccessibleName("Run cell 1") }.click() }
            awaitCondition("A responsive kernel retains its execution counter") { notebookStatus().contains("finished: ok") }
            invokeAction("SaveAll")
            awaitCondition("The next explicit run has count three") { Regex("\"execution_count\"\\s*:\\s*3").containsMatchIn(file.readText()) }
            ideFrame { button { byVisibleText("Restart kernel") }.click() }
            awaitCondition("Restart creates a fresh ready kernel without running cells") { notebookStatus().contains("kernel restarted") }
            ideFrame { button { byVisibleText("Run All") }.shouldBe("The restarted kernel is ready", { isEnabled() }, 2.minutes) }
            typeNotebookSource("Code cell 1 source", "print(value)")
            ideFrame { button { byAccessibleName("Run cell 1") }.click() }
            awaitCondition("The restarted namespace does not retain the previous variable") { notebookStatus().contains("finished: error") }
            invokeAction("SaveAll")
            awaitCondition("Fresh execution persists NameError and a reset counter") { file.readText().contains("NameError") }
            assertTrue(Regex("\"execution_count\"\\s*:\\s*1").containsMatchIn(file.readText()))
            ideFrame { button { byVisibleText("Shutdown kernel") }.click() }
            awaitCondition("Shutdown is explicit and leaves a visible idle status") { notebookStatus().contains("kernel shut down") }
        }
        VmIde.assertNoConsentCrash()
    }
}
