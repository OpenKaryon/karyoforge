package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.getRunContentManager
import com.intellij.driver.sdk.getContentEntries
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openEditor
import com.intellij.driver.sdk.VirtualFile
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
    println("Waiting: $description")
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
        if (condition()) {
            println("Ready: $description")
            return
        }
        Thread.sleep(250)
    }
    assertTrue(condition(), description)
}

/** Resolve an owned fixture against its actual module, including multi-module projects. */
fun Driver.openOwnedFile(file: Path) {
    val expected = file.toAbsolutePath().normalize()
    val roots = getModules(singleProject()).flatMap { getContentEntries(it).toList() }.map { it.getFile() }
    val root = roots.filter { expected.startsWith(Path.of(it.getPath()).toAbsolutePath().normalize()) }
        .maxByOrNull { Path.of(it.getPath()).nameCount }
    assertNotNull(root, "The fixture belongs to an open module: $expected")
    val relative = Path.of(root!!.getPath()).toAbsolutePath().normalize().relativize(expected)
        .joinToString("/") { it.toString() }
    invokeAction("Synchronize")
    var target: VirtualFile? = null
    awaitCondition("The module refresh discovers $relative", 2.minutes) {
        target = root.findFileByRelativePath(relative)
        target != null
    }
    openEditor(target!!)
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
    awaitCondition("CPython creates the chosen environment and executable", 10.minutes) {
        target.resolve("pyvenv.cfg").exists() && target.resolve("Scripts/python.exe").exists()
    }
    awaitModuleInterpreter(project, target.resolve("Scripts/python.exe"))
    return target.resolve("Scripts/python.exe")
}

fun Driver.chooseEnvironment(name: String) {
    invokeAction("org.openkaryon.karyoforge.python.useProjectEnvironment", now = false)
    ui.dialog({ byTitle("Project Python Environment") }) {
        comboBox { byClass("ComboBox") }.selectItemContains(name)
        pressButton("Select")
    }
    waitForPythonIndexing()
}

fun Driver.selectFile(path: Path) {
    ui.fileChooser({ byClass("MyDialog") }) {
        openPath(path)
    }
}

/** Runs through the editor shortcut and checks a fresh marker written by the launched interpreter. */
fun Driver.runProbe(project: Path, name: String, code: String = "import sys\nresult = sys.executable\n"): String {
    val script = project.resolve("probe_$name.py")
    val marker = project.resolve("$name.result")
    assertFalse(marker.exists(), "A new probe must not accept an earlier run's result")
    script.writeText(code + "\nfrom pathlib import Path\nPath(" + pythonString(marker) + ").write_text(str(result), encoding='utf-8')\n")
    openOwnedFile(script)
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
    val previous = selectedOperationIdentity()
    invokeAction("org.openkaryon.karyoforge.python.installRequirements", now = false)
    selectFile(file)
    ui.dialog({ byTitle("Install Python Requirements") }) {
        val message = x { and(byClass("JEditorPane"), contains(byVisibleText("Requirements:"))) }
        message.shouldBe("The requirements confirmation is visible before installation", present, 1.minutes)
        assertTrue(message.nativeDocumentText().contains(file.toString()),
            "The confirmation contains the exact selected requirements path")
        pressButton("Install")
    }
    awaitEnvironmentOperation(previous, "Install Python Requirements")
}

fun Driver.assertEditorReady() {
    ideFrame {
        x { contains(byVisibleText("Choose a Python interpreter for module")) }
            .shouldBe("The assigned module has no missing-interpreter banner", { !present() }, 1.minutes)
    }
}
