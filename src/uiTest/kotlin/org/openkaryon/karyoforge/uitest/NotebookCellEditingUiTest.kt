package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.invokeAction
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
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class NotebookCellEditingUiTest {
    @Test
    fun nativeCellTypingSaveUndoAndRedoUseTheSharedNotebookDocument() {
        val project = VmIde.project("notebook editing")
        val file = project.resolve("editing.ipynb")
        val original = notebookFixtureJson(1, "baseline_token = 1")
        file.writeText(original)
        VmIde.context("notebook-editing", project).runIdeWithDriver(runTimeout = 25.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(file)
            ideFrame {
                val source = x { byAccessibleName("Code cell 1 source") }
                source.shouldBe("A native editable Code cell is visible", present, 2.minutes)
                source.click()
                source.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A) }
                ui.pasteText("edited_token = 42")
                invokeAction("SaveAll")
                awaitCondition("The cell edit reaches the actual notebook file") { file.readText().contains("edited_token = 42") }
                assertTrue(file.readText().contains("saved result only"))
                x { byAccessibleName("Code cell 1 source") }.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
                invokeAction("SaveAll")
                awaitCondition("Native Undo restores the exact original JSON") { file.readText() == original }
                val restored = x { byAccessibleName("Code cell 1 source") }
                restored.click()
                restored.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_Z) }
                invokeAction("SaveAll")
                awaitCondition("Native Redo restores the saved cell edit") { file.readText().contains("edited_token = 42") }
            }
        }
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun cellControlsPreserveDataAndRespectMetadataLocks() {
        val project = VmIde.project("notebook cell controls")
        val file = project.resolve("controls.ipynb")
        val original = notebookFixtureJson(1, "preserved_code = 1")
        file.writeText(original)
        val locked = project.resolve("locked.ipynb")
        val lockedSource = original.replace("\"metadata\":{},\"source\":", "\"metadata\":{\"editable\":false,\"deletable\":false},\"source\":")
        locked.writeText(lockedSource)
        VmIde.context("notebook-controls", project).runIdeWithDriver(runTimeout = 25.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(file)
            ideFrame {
                comboBox { byAccessibleName("Cell 1 type") }.selectItemContains("Markdown")
                x { contains(byVisibleText("Clear this cell's outputs before converting")) }
                    .shouldBe("Conversion preserves existing saved results", present, 2.minutes)
                assertEquals(original, file.readText())
                button { byAccessibleName("Clear cell 1 outputs") }.click()
                comboBox { byAccessibleName("Cell 1 type") }.selectItemContains("Markdown")
                x { byAccessibleName("Markdown cell 1 source") }.shouldBe("The cell changes to Markdown after explicit output clearing", present, 2.minutes)
                button { byVisibleText("Add Raw") }.click()
                val raw = x { byAccessibleName("Raw cell 2 source") }
                raw.shouldBe("A new Raw cell is editable", present, 2.minutes)
                raw.click()
                ui.pasteText("raw-note")
                button { byAccessibleName("Move cell 2 up") }.click()
                x { byAccessibleName("Raw cell 1 source") }.shouldBe("The new cell moves without losing its source", present, 2.minutes)
                button { byAccessibleName("Delete cell 1") }.click()
                invokeAction("SaveAll")
                awaitCondition("The explicit deletion is saved") { !file.readText().contains("raw-note") && file.readText() != original }
                val markdown = x { byAccessibleName("Markdown cell 1 source") }
                markdown.click()
                markdown.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
                x { byAccessibleName("Raw cell 1 source") }.shouldBe("Native Undo restores the deleted cell", present, 2.minutes)
                invokeAction("SaveAll")
                awaitCondition("The restored source reaches the file") { file.readText().contains("raw-note") }
            }
            openOwnedFile(locked)
            ideFrame {
                assertFalse(button { byAccessibleName("Delete cell 1") }.isEnabled())
                assertFalse(comboBox { byAccessibleName("Cell 1 type") }.isEnabled())
            }
            assertEquals(lockedSource, locked.readText())
        }
        VmIde.assertNoConsentCrash()
    }
}
