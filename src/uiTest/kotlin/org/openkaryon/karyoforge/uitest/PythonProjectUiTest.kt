package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.dialogs.newProjectDialog
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class PythonProjectUiTest {
    @Test
    fun nativePythonWizardCreatesAProjectAndFileOpenRestoresAnExistingProject() {
        val original = VmIde.project("existing project")
        val parent = VmIde.root.resolve("projects/wizard-parent").createDirectories()
        val created = parent.resolve("created-from-wizard")
        assertFalse(created.exists())
        VmIde.context("project-wizard", original).runIdeWithDriver(runTimeout = 40.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            val expected = createEnvironment(original)
            invokeAction("CloseProject")
            invokeAction("NewProject", now = false)
            newProjectDialog {
                chooseProjectType("Python")
                setProjectName(created.fileName.toString())
                val location = textField { and(byClass("ExtendableTextField"), byAccessibleName("Location:")) }
                location.text = parent.toString()
                assertEquals(parent.toString(), location.text, "The wizard must use the owned project parent before Create")
                checkBox { byVisibleText("Create Git repository") }.uncheck()
                createButton.click()
            }
            waitForPythonIndexing(25.minutes)
            assertTrue(created.exists(), "The native wizard creates the requested directory")
            awaitModuleInterpreter(created, created.resolve(".venv/Scripts/python.exe"))
            val python = Path.of(runProbe(created, "wizard"))
            assertTrue(python.startsWith(created), "The native project environment belongs to the new project: $python")
            invokeAction("CloseProject")
            invokeAction("OpenFile", now = false)
            selectFile(original)
            waitForPythonIndexing(20.minutes)
            assertInterpreter(original, expected, "opened_again")
            assertEditorReady()
        }
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun interpreterSetupForOneModuleDoesNotConfigureAnotherModule() {
        val project = VmIde.project("two modules")
        val first = project.resolve("first").createDirectories()
        val second = project.resolve("second").createDirectories()
        first.resolve("main.py").writeText("print('first module')\n")
        second.resolve("main.py").writeText("print('second module')\n")
        for ((name, directory) in listOf("first" to first, "second" to second)) {
            directory.resolve("$name.iml").writeText("""
                <module type="PYTHON_MODULE" version="4"><component name="NewModuleRootManager">
                <content url="file://${'$'}MODULE_DIR${'$'}"><sourceFolder url="file://${'$'}MODULE_DIR${'$'}" isTestSource="false"/></content>
                <orderEntry type="inheritedJdk"/><orderEntry type="sourceFolder" forTests="false"/>
                </component></module>
            """.trimIndent())
        }
        project.resolve(".idea").createDirectories().resolve("modules.xml").writeText("""
            <project version="4"><component name="ProjectModuleManager"><modules>
            <module fileurl="file://${'$'}PROJECT_DIR${'$'}/first/first.iml" filepath="${'$'}PROJECT_DIR${'$'}/first/first.iml"/>
            <module fileurl="file://${'$'}PROJECT_DIR${'$'}/second/second.iml" filepath="${'$'}PROJECT_DIR${'$'}/second/second.iml"/>
            </modules></component></project>
        """.trimIndent())
        VmIde.context("module-scope", project).runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(first.resolve("main.py"))
            val selected = createEnvironment(first)
            assertInterpreter(first, selected, "first_selected")
            openOwnedFile(second.resolve("main.py"))
            ideFrame {
                x { contains(byVisibleText("Choose a Python interpreter for module 'second'")) }
                    .shouldBe("The second module still requires its own interpreter", present, 2.minutes)
            }
            assertFalse(second.resolve(".venv").exists())
            assertTrue(Files.isRegularFile(first.resolve(".venv/pyvenv.cfg")))
        }
        VmIde.assertNoConsentCrash()
    }
}
