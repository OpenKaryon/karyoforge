package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.getRunContentManager
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.fileChooser
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitForIndicators
import org.junit.jupiter.api.Assertions.*
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun awaitCondition(description: String, timeout: Duration = 5.minutes, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(250)
    }
    assertTrue(condition(), description)
}

fun pythonString(path: Path): String = "'" + path.toString().replace("\\", "\\\\").replace("'", "\\'") + "'"

fun Driver.createEnvironment(project: Path, name: String = ".venv"): Path {
    val target = project.resolve(name)
    invokeAction("org.openkaryon.karyoforge.python.createEnvironment", now = false)
    ui.dialog({ contains(byTitle("Create Python Environment for")) }) {
        textField { byAccessibleName("Base Python executable") }.text = VmIde.python.toString()
        textField { byAccessibleName("New environment directory") }.text = target.toString()
        pressButton("Create")
    }
    awaitCondition("CPython creates the chosen environment", 10.minutes) { target.resolve("pyvenv.cfg").exists() }
    waitForIndicators(20.minutes)
    assertTrue(target.resolve("Scripts/python.exe").exists())
    return target.resolve("Scripts/python.exe")
}

fun Driver.chooseEnvironment(name: String) {
    invokeAction("org.openkaryon.karyoforge.python.useProjectEnvironment", now = false)
    ui.dialog({ byTitle("Project Python Environment") }) {
        comboBox { byClass("ComboBox") }.selectItemContains(name)
        pressButton("Select")
    }
    waitForIndicators(20.minutes)
}

fun Driver.selectFile(path: Path) {
    ui.fileChooser({ byClass("MyDialog") }) {
        openPath(path)
        pressButton("OK")
    }
}

/** Runs through the editor shortcut and checks a fresh marker written by the launched interpreter. */
fun Driver.runProbe(project: Path, name: String, code: String = "import sys\nresult = sys.executable\n"): String {
    val script = project.resolve("probe_$name.py")
    val marker = project.resolve("$name.result")
    assertFalse(marker.exists(), "A new probe must not accept an earlier run's result")
    script.writeText(code + "\nfrom pathlib import Path\nPath(" + pythonString(marker) + ").write_text(str(result), encoding='utf-8')\n")
    invokeAction("Synchronize")
    openFile(script.toString(), waitForCodeAnalysis = false)
    assertFalse(marker.exists(), "Opening a Python file must not execute it")
    ideFrame {
        codeEditor().click()
        keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_F10) }
    }
    awaitCondition("The GUI run executes the selected script", 10.minutes) { marker.exists() && Files.size(marker) > 0 }
    val manager = getRunContentManager(singleProject())
    awaitCondition("The selected native run finishes") { manager.getSelectedContent()?.getProcessHandler()?.isProcessTerminated() == true }
    return marker.readText()
}

fun Driver.assertInterpreter(project: Path, expected: Path, name: String) {
    assertEquals(expected.toAbsolutePath().normalize(), Path.of(runProbe(project, name)).toAbsolutePath().normalize())
}

fun Driver.installRequirements(file: Path) {
    invokeAction("org.openkaryon.karyoforge.python.installRequirements", now = false)
    selectFile(file)
    ui.dialog({ byTitle("Install Python Requirements") }) {
        x { contains(byVisibleText(file.toString())) }.shouldBe("The requirements path is visible before installation", present, 1.minutes)
        pressButton("Install")
    }
    waitForIndicators(20.minutes)
}

fun Driver.assertEditorReady() {
    ideFrame {
        x { contains(byVisibleText("Choose a Python interpreter for module")) }
            .shouldBe("The assigned module has no missing-interpreter banner", { !present() }, 1.minutes)
    }
}
