package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class PythonProjectInstallUiTest {
    @Test
    fun editableAndOrdinaryInstallWithSrcAndExtrasFollowTheDialogChoices() {
        val project = VmIde.fixtureProject("project-install with spaces")
        val source = project.resolve("src/karyoforge_project_fixture.py")
        VmIde.context("project-install", project).runIdeWithDriver(runTimeout = 40.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            val python = createEnvironment(project)
            installProject(project, editable = true)
            source.writeText("VALUE = 'changed editable source'\n")
            assertEquals("changed editable source", runProbe(project, "editable", "import karyoforge_project_fixture\nresult=karyoforge_project_fixture.VALUE\n"))
            installProject(project, editable = false)
            source.writeText("VALUE = 'changed after ordinary install'\n")
            assertEquals("changed editable source", runProbe(project, "ordinary", "import karyoforge_project_fixture\nresult=karyoforge_project_fixture.VALUE\n"))
            assertInterpreter(project, python, "project_environment")
        }
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun toolOnlyTomlIsRejectedWithoutStartingAnInstall() {
        val project = VmIde.fixtureProject("tool-only")
        val metadata = project.resolve("pyproject.toml")
        metadata.writeText("[tool.example]\nenabled=true\n")
        VmIde.context("tool-only", project).runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            val python = createEnvironment(project)
            invokeAction("org.openkaryon.karyoforge.python.installProject", now = false)
            selectFile(metadata)
            ui.dialog({ byTitle("Python Project Installation") }) {
                x { contains(byVisibleText("no standard Python project")) }.shouldBe("Tool-only TOML is explicitly rejected", present, 1.minutes)
                pressButton("OK")
            }
            assertEquals("[tool.example]\nenabled=true\n", metadata.readText())
            assertEquals("True", runProbe(project, "not_installed", "import importlib.util\nresult=importlib.util.find_spec('karyoforge_project_fixture') is None\n"))
            assertInterpreter(project, python, "preserved_environment")
        }
    }

    private fun Driver.installProject(project: Path, editable: Boolean) {
        val previous = selectedOperationIdentity()
        invokeAction("org.openkaryon.karyoforge.python.installProject", now = false)
        selectFile(project.resolve("pyproject.toml"))
        ui.dialog({ byTitle("Install Python Project") }) {
            x { byVisibleText(project.toString()) }.shouldBe("The selected project path is visible", present, 1.minutes)
            val editableChoice = checkBox { contains(byVisibleText("Editable install")) }
            if (editable) editableChoice.check() else editableChoice.uncheck()
            checkBox { contains(byVisibleText("Isolate build dependencies")) }.check()
            checkBox { byVisibleText("validation") }.check()
            assertEquals(editable, editableChoice.isSelected())
            pressButton("Install")
        }
        awaitEnvironmentOperation(previous, "Install Python Project")
    }
}
