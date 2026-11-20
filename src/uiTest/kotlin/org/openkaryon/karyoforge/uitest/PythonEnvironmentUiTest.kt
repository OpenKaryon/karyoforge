package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.editor.completionList
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.pasteText
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class PythonEnvironmentUiTest {
    @Test
    fun createChooseCancelAndReopenAnEnvironmentWithoutOverwritingFiles() {
        val project = VmIde.project("environment with spaces")
        val context = VmIde.context("environments", project)
        var selected = project.resolve(".venv/Scripts/python.exe")
        context.runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            selected = createEnvironment(project)
            assertInterpreter(project, selected, "created")
            assertEditorReady()
            val sentinel = project.resolve(".venv/keep.txt")
            sentinel.writeText("owned environment content")
            invokeAction("org.openkaryon.karyoforge.python.createEnvironment", now = false)
            ui.dialog({ contains(byTitle("Create Python Environment for")) }) {
                textField { byAccessibleName("Base Python executable") }.text = VmIde.python.toString()
                textField { byAccessibleName("New environment directory") }.text = project.resolve(".venv").toString()
                val create = button { byVisibleText("Create") }
                if (create.isEnabled()) create.click()
                x { contains(byVisibleText("The environment directory already exists")) }
                    .shouldBe("Creation explicitly rejects the existing environment", present, 1.minutes)
                create.shouldBe("Invalid creation cannot proceed", { !isEnabled() }, 1.minutes)
                pressButton("Cancel")
            }
            assertEquals("owned environment content", sentinel.readText())
            invokeAction("org.openkaryon.karyoforge.python.createEnvironment", now = false)
            ui.dialog({ contains(byTitle("Create Python Environment for")) }) {
                textField { byAccessibleName("New environment directory") }.text = project.resolve("cancelled-env").toString()
                pressButton("Cancel")
            }
            assertFalse(project.resolve("cancelled-env").exists())
            assertInterpreter(project, selected, "after_cancel")
            createEnvironment(project, "env")
            chooseEnvironment(".venv")
            assertInterpreter(project, selected, "reselected")
        }
        context.runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(15.minutes)
            assertInterpreter(project, selected, "reopened")
            assertEditorReady()
        }
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun editorCompletionAndTypingUseTheConfiguredPythonProvider() {
        val project = VmIde.project("editor")
        project.resolve("editing.py").writeText("import pathlib\n\npathlib.Pa")
        VmIde.context("editor", project).runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            createEnvironment(project)
            openFile("editing.py")
            ideFrame {
                val editor = codeEditor()
                editor.click()
                editor.moveCaretToOffset(editor.text.length)
                invokeAction("CodeCompletion", now = false)
                completionList {
                    var reportedNames: List<String>? = null
                    shouldBe("Native Python completion offers Path", {
                        val names = pythonCompletionNames()
                        if (names != reportedNames) {
                            println("Native Python completion names: $names")
                            reportedNames = names
                        }
                        names.contains("Path")
                    }, 2.minutes)
                    clickItemAtIndex(pythonCompletionNames().indexOf("Path"))
                    keyboard { enter() }
                }
                assertTrue(editor.text.contains("pathlib.Path"), editor.text)
                // Source text is layout-independent; real Enter events exercise Python indentation.
                ui.pasteText("()")
                keyboard { enter() }
                ui.pasteText("def answer():")
                keyboard { enter() }
                ui.pasteText("return 42")
                assertTrue(editor.text.contains("def answer():\n    return 42"), editor.text)
            }
        }
        VmIde.assertNoConsentCrash()
    }
}
