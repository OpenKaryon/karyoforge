package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.codeEditor
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

class NotebookFilesUiTest {
    @Test
    fun openingAndPagingSavedNotebookCellsPreservesJsonWithoutExecutingCode() {
        val project = VmIde.project("notebook files")
        val marker = project.resolve("opening-must-not-execute.result")
        val notebook = project.resolve("analysis.ipynb")
        val first = "from pathlib import Path\nPath('${marker.toString().replace('\\', '/')}').write_text('unexpected execution')"
        val original = notebookFixtureJson(25, first)
        notebook.writeText(original)
        VmIde.context("notebook-files", project).runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(notebook)
            ideFrame {
                x { byVisibleText("Cells 1–20 of 25") }.shouldBe("The notebook shows a bounded first page", present, 2.minutes)
                val saved = x { byAccessibleName("Saved notebook output") }
                saved.shouldBe("Saved cell output is visible without a kernel", present, 2.minutes)
                assertEquals("saved result only", saved.nativeDocumentText())
                button { byVisibleText("Next cells") }.click()
                x { byVisibleText("Cells 21–25 of 25") }.shouldBe("The next cells are visible", present, 1.minutes)
                button { byVisibleText("Previous cells") }.click()
                x { byVisibleText("Cells 1–20 of 25") }.shouldBe("The first page can be revisited", present, 1.minutes)
                x { byVisibleText("Text") }.click()
                codeEditor { assertTrue(text.contains("\"nbformat\""), text) }
            }
            assertEquals(original, notebook.readText())
            assertFalse(marker.exists(), "Opening, viewing outputs and paging must not execute saved code")
        }
        assertEquals(original, notebook.readText())
        assertFalse(marker.exists())
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun malformedNotebookCanBeRepairedThroughItsNativeJsonDocument() {
        val project = VmIde.project("notebook JSON repair")
        val notebook = project.resolve("repair.ipynb")
        notebook.writeText("{ invalid notebook")
        val repaired = notebookFixtureJson(1, "value = 42")
        VmIde.context("notebook-repair", project).runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(notebook)
            ideFrame {
                x { byAccessibleName("Notebook source error") }.shouldBe("Invalid source remains available for repair", present, 2.minutes)
                assertEquals("{ invalid notebook", notebook.readText())
                x { byVisibleText("Text") }.click()
                codeEditor {
                    click()
                    keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A) }
                    ui.pasteText(repaired)
                }
                invokeAction("SaveAll")
                x { byVisibleText("Notebook") }.click()
                x { byVisibleText("Cells 1–1 of 1") }.shouldBe("The notebook reloads the repaired shared document", present, 2.minutes)
            }
            awaitCondition("The native save preserves the repaired notebook") { notebook.readText() == repaired }
        }
        VmIde.assertNoConsentCrash()
    }


}
