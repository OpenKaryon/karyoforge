package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.pasteText
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

@Remote("javax.swing.text.Document")
interface NotebookInputDocument { fun getLength(): Int }

@Remote("javax.swing.JPasswordField")
interface NotebookPasswordState {
    fun echoCharIsSet(): Boolean
    fun isFocusOwner(): Boolean
    fun getDocument(): NotebookInputDocument
}

class NotebookInputOutputsUiTest {
    @Test
    fun liveInputAndMaskedGetpassDoNotEchoOrPersistResponsesAndRichMimeStaysPassive() {
        val project = VmIde.project("notebook input")
        val file = project.resolve("input.ipynb")
        val counter = project.resolve("execution-count.result")
        val original = notebookCells("code" to "from pathlib import Path\ncounter=Path(${pythonString(counter)})\ncounter.write_text(str(int(counter.read_text())+1 if counter.exists() else 1))\nprint('before-input',flush=True)\nname=input('Name: ')\nimport getpass\nsecret=getpass.getpass('Password: ')\nfrom IPython.display import display\ndisplay({'text/plain':'plain result','text/html':'<b>literal preview</b>','image/png':'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=='},raw=True)\nprint('lengths',len(name),len(secret))")
        file.writeText(original)
        VmIde.context("notebook-input", project).runIdeWithDriver(runTimeout = 25.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(file)
            chooseNotebookPython()
            assertFalse(counter.exists(), "Opening and choosing Python do not execute the cell")
            ideFrame {
                button { byAccessibleName("Run cell 1") }.click()
                x { and(byAccessibleName("Notebook input prompt"), contains(byVisibleText("Name:"))) }.shouldBe("The active input request is nonmodal", present, 3.minutes)
                assertEquals(original, file.readText(), "Live output and pending input do not rewrite the accepted document")
                val input = x { byAccessibleName("Notebook input value") }
                val inputState = driver.cast(input.component, NotebookPasswordState::class)
                assertFalse(inputState.echoCharIsSet())
                input.click()
                awaitCondition("The ordinary input owns keyboard focus", 1.minutes) { inputState.isFocusOwner() }
                ui.pasteText("alpha")
                awaitCondition("The ordinary response reaches the transient field", 1.minutes) { inputState.getDocument().getLength() == 5 }
                button { byVisibleText("Submit input") }.click()
                x { and(byAccessibleName("Notebook input prompt"), contains(byVisibleText("Password:"))) }.shouldBe("getpass creates a masked request", present, 2.minutes)
                val password = x { byAccessibleName("Notebook input value") }
                val passwordState = driver.cast(password.component, NotebookPasswordState::class)
                assertTrue(passwordState.echoCharIsSet())
                assertEquals(0, passwordState.getDocument().getLength(), "The preceding response is cleared")
                password.click()
                awaitCondition("The masked input owns keyboard focus", 1.minutes) { passwordState.isFocusOwner() }
                ui.pasteText("synthetic-ui-secret")
                awaitCondition("The masked response reaches the transient field", 1.minutes) { passwordState.getDocument().getLength() == 19 }
                button { byVisibleText("Submit input") }.click()
                x { contains(byVisibleText("lengths 5 19")) }.shouldBe("The kernel receives both responses without automatic echo", present, 2.minutes)
            }
            awaitCondition("The input cell finishes") { notebookStatus().contains("finished: ok") }
            invokeAction("SaveAll")
            awaitCondition("Rich output and the completed stream reach JSON") { file.readText().contains("\"text/html\"") && file.readText().contains("lengths 5 19") }
            assertFalse(file.readText().contains("synthetic-ui-secret"))
            assertFalse(file.readText().contains("alpha"))
            assertTrue(file.readText().contains("<b>literal preview</b>"))
            assertTrue(file.readText().contains("\"image/png\""))
            ideFrame { x { byAccessibleName("Saved notebook image") }.shouldBe("A local bounded PNG preview is displayed", present, 1.minutes) }
            ideFrame {
                awaitCondition("Static plain output is visible alongside retained rich MIME", 1.minutes) {
                    xx { byAccessibleName("Saved notebook output") }.list().any { it.nativeDocumentText() == "plain result" }
                }
                x { byVisibleText("Additional MIME data is retained in JSON; markup is not executed.") }
                    .shouldBe("Rich markup stays passive", present, 1.minutes)
            }
            val completed = file.readText()
            assertEquals("1", counter.readText())
            ideFrame {
                val source = x { byAccessibleName("Code cell 1 source") }
                source.click()
                source.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            }
            invokeAction("SaveAll")
            awaitCondition("Native Undo restores the original notebook without executing code") { file.readText() == original }
            ideFrame {
                val source = x { byAccessibleName("Code cell 1 source") }
                source.click()
                source.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_Z) }
            }
            invokeAction("SaveAll")
            awaitCondition("Native Redo restores the completed result without a new input request") { file.readText() == completed }
            assertEquals("1", counter.readText())
        }
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun cancellingInputStopsTheRemainingQueueAndClosingDoesNotExecuteItLater() {
        val project = VmIde.project("notebook cancel input")
        val file = project.resolve("cancel.ipynb")
        val marker = project.resolve("must-not-run.result")
        file.writeText(notebookCells("code" to "input('Waiting: ')",
            "code" to "from pathlib import Path\nPath(${pythonString(marker)}).write_text('unexpected')"))
        VmIde.context("notebook-cancel-input", project).runIdeWithDriver(runTimeout = 25.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(file)
            chooseNotebookPython()
            ideFrame {
                button { byVisibleText("Run All") }.click()
                x { and(byAccessibleName("Notebook input prompt"), contains(byVisibleText("Waiting:"))) }.shouldBe("The first queued cell requests input", present, 3.minutes)
                x { byAccessibleName("Notebook input value") }.click()
                ui.pasteText("discarded-ui-input")
                button { byVisibleText("Cancel input") }.click()
                button { byVisibleText("Run All") }.shouldBe("Cancellation stops the queued run", { isEnabled() }, 2.minutes)
            }
            invokeAction("SaveAll")
            assertFalse(marker.exists())
            assertFalse(file.readText().contains("discarded-ui-input"))
            invokeAction("CloseAllEditors")
            openOwnedFile(file)
            ideFrame { x { contains(byVisibleText("Dependencies not checked")) }.shouldBe("Reopening remains passive", present, 2.minutes) }
            assertFalse(marker.exists())
        }
        VmIde.assertNoConsentCrash()
    }
}
