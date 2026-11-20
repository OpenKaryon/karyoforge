package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.DumbService
import com.intellij.driver.sdk.Module
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.Sdk
import com.intellij.driver.sdk.getContentEntries
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.getProgressIndicators
import com.intellij.driver.sdk.getRunContentManager
import com.intellij.driver.sdk.singleProject
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Read native state only; module assignment and package changes still happen through the GUI. */
@Remote("com.intellij.openapi.roots.ModuleRootManager")
interface ModuleSdkState {
    fun getInstance(module: Module): ModuleSdkState
    fun getSdk(): Sdk?
}

@Remote("java.lang.System")
interface NativeIdentity {
    fun identityHashCode(value: Any): Int
}

@Remote("com.intellij.codeInsight.lookup.LookupManager")
interface PythonLookupManager {
    fun getInstance(project: Project): PythonLookupManager
    fun getActiveLookup(): PythonLookup?
}

@Remote("com.intellij.codeInsight.lookup.Lookup")
interface PythonLookup {
    fun getItems(): List<PythonLookupItem>
}

@Remote("com.intellij.codeInsight.lookup.LookupElement")
interface PythonLookupItem {
    fun getLookupString(): String
}

/** Highlighted renderer fragments are not stable completion names. */
fun Driver.pythonCompletionNames(): List<String> = utility<PythonLookupManager>()
    .getInstance(singleProject()).getActiveLookup()?.getItems()?.map { it.getLookupString() }.orEmpty()

/** Background repository refreshes can outlive a usable interpreter; wait for indexing itself. */
fun Driver.waitForPythonIndexing(timeout: Duration = 10.minutes) {
    var quietSince = 0L
    awaitCondition("The open Python project finishes indexing", timeout) {
        val project = getOpenProjects().singleOrNull()
        if (project == null || !project.isInitialized() || service<DumbService>(project).isDumb()) {
            quietSince = 0L
            false
        } else {
            if (quietSince == 0L) quietSince = System.nanoTime()
            System.nanoTime() - quietSince >= 1.seconds.inWholeNanoseconds
        }
    }
}

fun Driver.awaitModuleInterpreter(directory: Path, executable: Path) {
    val expectedRoot = directory.toAbsolutePath().normalize()
    val expectedPython = executable.toAbsolutePath().normalize()
    awaitCondition("The selected module uses $expectedPython", 10.minutes) {
        val project = getOpenProjects().singleOrNull() ?: return@awaitCondition false
        val module = getModules(project).singleOrNull { candidate ->
            getContentEntries(candidate).any { entry ->
                Path.of(entry.getFile().getPath()).toAbsolutePath().normalize() == expectedRoot
            }
        } ?: return@awaitCondition false
        val sdk = utility<ModuleSdkState>().getInstance(module).getSdk()
        val home = sdk?.getHomePath()
        home != null && Path.of(home).toAbsolutePath().normalize() == expectedPython
                && sdk.getVersionString()?.isNotBlank() == true
    }
    waitForPythonIndexing()
}

fun Driver.selectedOperationIdentity(): Int? = getRunContentManager(singleProject()).getSelectedContent()
    ?.getProcessHandler()?.let { utility<NativeIdentity>().identityHashCode(it) }

/** Require a new native process, so a previous successful install cannot satisfy this wait. */
fun Driver.awaitEnvironmentOperation(previous: Int?, title: String) {
    awaitCondition("The new $title process finishes", 15.minutes) {
        val content = getRunContentManager(singleProject()).getSelectedContent()
        val process = content?.getProcessHandler()
        content?.getDisplayName() == title && process != null
                && utility<NativeIdentity>().identityHashCode(process) != previous && process.isProcessTerminated()
    }
    awaitCondition("The native $title task finishes its package refresh", 15.minutes) {
        getProgressIndicators(singleProject()).none { it.getFirst()?.getTitle() == title && it.getSecond()?.isRunning() == true }
    }
    waitForPythonIndexing()
}

/** Let owned native SDK refreshes finish before disposing their fresh test project. */
fun Driver.awaitPythonSdkUpdatesBeforeClose() {
    awaitCondition("Native Python interpreter updates finish before project shutdown", 20.minutes) {
        getProgressIndicators(singleProject()).none {
            it.getFirst()?.getTitle() == "Updating Python interpreter" && it.getSecond()?.isRunning() == true
        }
    }
    waitForPythonIndexing()
}
