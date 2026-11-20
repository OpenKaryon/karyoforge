package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.jbTerminalPanel
import com.intellij.driver.sdk.ui.components.python.pythonConsole
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.pasteText
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class PythonSessionsUiTest {
    @Test
    fun cmdSessionsUseTheCurrentModuleEnvironmentAndKeepExistingSessions() =
        exerciseShell("cmd", "cmd.exe")

    @Test
    fun powershellSessionsUseTheCurrentModuleEnvironmentAndKeepExistingSessions() =
        exerciseShell("powershell", "powershell.exe -NoLogo -NoExit -ExecutionPolicy Bypass")

    private fun exerciseShell(name: String, shell: String) {
        val project = VmIde.project("sessions-$name")
        VmIde.context("sessions-$name", project).runIdeWithDriver(runTimeout = 40.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            val first = createEnvironment(project)
            invokeAction("ShowSettings", now = false)
            ideFrame {
                settingsDialog {
                    settingsTree.clickPath("Tools", "Terminal", fullMatch = false)
                    comboBox { and(byClass("ComboBox"), byAccessibleName("Terminal engine:")) }.selectItemContains("Classic")
                    textField { and(byClass("JBTextField"), byAccessibleName("Shell path:")) }.text = shell
                    checkBox { byVisibleText("Activate virtualenv") }.check()
                    pressButton("OK")
                }
            }
            openNewModuleConsole()
            val originalConsole = consoleProbe(project, "original_console")
            assertEquals(first, Path.of(originalConsole[0]))
            val originalConsolePid = originalConsole[1].toLong()
            val originalStart = ProcessHandle.of(originalConsolePid).orElseThrow().info().startInstant().orElseThrow()
            invokeAction("org.openkaryon.karyoforge.python.openTerminal", now = false)
            assertTerminal(project, first, terminalProbe(project, "original_terminal"))

            val second = createEnvironment(project, "env")
            assertNotEquals(first, second)
            // Switching SDK must not reactivate an already open terminal.
            invokeAction("ActivateTerminalToolWindow")
            assertTerminal(project, first, terminalProbe(project, "preserved_terminal"))

            openNewModuleConsole()
            val newConsole = consoleProbe(project, "new_console")
            assertEquals(second, Path.of(newConsole[0]))
            assertNotEquals(originalConsolePid, newConsole[1].toLong())
            val oldProcess = ProcessHandle.of(originalConsolePid)
            assertTrue(oldProcess.isPresent && oldProcess.get().isAlive, "Changing SDK and opening a new console keeps the old console alive")
            assertEquals(originalStart, oldProcess.get().info().startInstant().orElseThrow(), "The original console process was not replaced")

            invokeAction("org.openkaryon.karyoforge.python.openTerminal", now = false)
            assertTerminal(project, second, terminalProbe(project, "new_terminal"))
            assertInterpreter(project, second, "sessions_environment")
            awaitPythonSdkUpdatesBeforeClose()
        }
        VmIde.assertNoConsentCrash()
    }

    private fun Driver.openNewModuleConsole() {
        val previous = selectedPythonConsoleIdentity()
        invokeAction("org.openkaryon.karyoforge.python.openConsole", now = false)
        awaitCondition("A new native module Python Console process starts", 5.minutes) {
            val current = selectedPythonConsoleIdentity()
            current != null && current != previous
        }
        ideFrame {
            val window = x("//div[@class='InternalDecoratorImpl'][.//div[@class='PythonConsoleView']]")
            window.shouldBe("The new native Python Console is visible", present, 5.minutes)
            awaitCondition("The native Python Console history and prompt editors are ready", 5.minutes) {
                window.xx { byClass("EditorComponentImpl") }.list().size >= 2
            }
        }
    }

    private fun Driver.consoleProbe(project: Path, name: String): List<String> {
        val marker = project.resolve("$name.result")
        assertFalse(marker.exists())
        ideFrame {
            pythonConsole {
                awaitCondition("The native Python console accepts input", 5.minutes) { content.nativePythonConsoleAcceptsInput() }
                prompt.text = "import os,sys; from pathlib import Path; Path(${pythonString(marker)}).write_text(sys.executable+'\\n'+str(os.getpid()), encoding='utf-8')"
                prompt.click()
                prompt.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_END); enter() }
            }
        }
        awaitCondition("The real console evaluates the probe") { marker.exists() && marker.readLines().size == 2 }
        return marker.readLines()
    }

    private fun Driver.terminalProbe(project: Path, name: String): List<String> {
        val marker = project.resolve("$name.result")
        val script = project.resolve("$name.py")
        assertFalse(marker.exists())
        script.writeText("import os,sys\nfrom pathlib import Path\n" +
            "Path(sys.argv[1]).write_text('\\n'.join([sys.executable, os.getcwd(), os.environ.get('VIRTUAL_ENV','')]), encoding='utf-8')\n")
        ideFrame {
            val terminal = jbTerminalPanel()
            awaitCondition("The native terminal has initialized in the module directory", 5.minutes) {
                terminal.nativeTerminalScreenText().replace("\r", "").replace("\n", "").contains(project.fileName.toString())
            }
            terminal.click()
            ui.pasteText("python -I \"$script\" \"$marker\"")
            terminal.keyboard { enter() }
        }
        awaitCondition("The actual terminal shell executes its activated Python", 5.minutes) {
            marker.exists() && marker.readLines().size == 3
        }
        return marker.readLines()
    }

    private fun assertTerminal(project: Path, python: Path, values: List<String>) {
        assertEquals(python, Path.of(values[0]), "Shell PATH resolves the selected environment's Python")
        assertEquals(project, Path.of(values[1]), "The shell starts in the module content root")
        assertEquals(python.parent.parent, Path.of(values[2]), "Native activation sets VIRTUAL_ENV")
    }
}
