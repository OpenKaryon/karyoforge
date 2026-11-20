package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class PythonDependenciesUiTest {
    @Test
    fun requirementsInstallUpgradeAndNativePackageRemovalUseTheChosenEnvironment() {
        val project = VmIde.fixtureProject("dependencies")
        VmIde.context("dependencies", project).runIdeWithDriver(runTimeout = 40.minutes).useDriverAndCloseIde {
            waitForIndicators(10.minutes)
            val python = createEnvironment(project)
            installRequirements(project.resolve("requirements.txt"))
            assertEquals("9.0.2:1", runProbe(project, "installed",
                "import pytest,karyoforge_gui_fixture\nresult=pytest.__version__+':'+str(karyoforge_gui_fixture.VALUE)\n"))
            installRequirements(project.resolve("upgrade.txt"))
            assertEquals("2.0.0", runProbe(project, "upgraded",
                "import importlib.metadata\nresult=importlib.metadata.version('karyoforge-gui-fixture')\n"))
            invokeAction("ActivatePythonPackagesToolWindow")
            ideFrame {
                val installed = x { or(byVisibleText("karyoforge-gui-fixture"), byVisibleText("karyoforge_gui_fixture")) }
                installed.shouldBe("The native package window shows the installed fixture", present, 3.minutes)
                installed.click()
            }
            invokeAction("PyDeletePackage", now = false)
            waitForIndicators(10.minutes)
            assertEquals("True", runProbe(project, "removed",
                "import importlib.util\nresult=importlib.util.find_spec('karyoforge_gui_fixture') is None\n"))
            assertInterpreter(project, python, "after_package_operations")
        }
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun aPipErrorShowsItsOutputAndKeepsTheModuleInterpreter() {
        val project = VmIde.fixtureProject("dependency-error")
        VmIde.context("dependency-error", project).runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde {
            waitForIndicators(10.minutes)
            val python = createEnvironment(project)
            invokeAction("org.openkaryon.karyoforge.python.installRequirements", now = false)
            selectFile(project.resolve("bad-requirements.txt"))
            ui.dialog({ byTitle("Install Python Requirements") }) { pressButton("Install") }
            ui.dialog({ byTitle("Python Requirements") }) {
                x { contains(byVisibleText("exit code")) }.shouldBe("The real pip failure is visible", present, 5.minutes)
                x { contains(byVisibleText("missing-owned-wheel")) }.shouldBe("The dialog includes pip output", present, 1.minutes)
                pressButton("OK")
            }
            assertInterpreter(project, python, "after_failure")
        }
    }

    @Test
    fun stopCancelsAnOwnedPipBuildAndItsPythonChild() {
        val project = VmIde.fixtureProject("dependency-cancel")
        val slow = project.resolve("slow-project").createDirectories()
        slow.resolve("pyproject.toml").writeText("[build-system]\nrequires=[]\nbuild-backend='slow_backend'\nbackend-path=['.']\n")
        slow.resolve("slow_backend.py").writeText("""
            import os,time
            from pathlib import Path
            def build_wheel(wheel_directory, config_settings=None, metadata_directory=None):
                Path(__file__).with_suffix('.pid').write_text(str(os.getpid()))
                print('Waiting for explicit GUI cancellation', flush=True)
                time.sleep(180)
                raise RuntimeError('The GUI test did not stop this build')
        """.trimIndent() + "\n")
        val requirements = project.resolve("slow-requirements.txt")
        requirements.writeText("--no-index\n./slow-project\n")
        VmIde.context("dependency-cancel", project).runIdeWithDriver(runTimeout = 30.minutes).useDriverAndCloseIde {
            waitForIndicators(10.minutes)
            val python = createEnvironment(project)
            invokeAction("org.openkaryon.karyoforge.python.installRequirements", now = false)
            selectFile(requirements)
            ui.dialog({ byTitle("Install Python Requirements") }) { pressButton("Install") }
            val pid = slow.resolve("slow_backend.pid")
            awaitCondition("Pip starts the owned slow build", 5.minutes) { pid.exists() && Files.size(pid) > 0 }
            val child = pid.readText().trim().toLong()
            ideFrame {
                x { contains(byTooltip("Cancel this environment operation")) }.click()
            }
            ui.dialog({ byTitle("Python Requirements") }) {
                x { contains(byVisibleText("cancelled")) }.shouldBe("Cancellation is reported", present, 2.minutes)
                pressButton("OK")
            }
            awaitCondition("The owned build child terminates") { ProcessHandle.of(child).map { !it.isAlive }.orElse(true) }
            assertInterpreter(project, python, "after_cancellation")
        }
    }
}
