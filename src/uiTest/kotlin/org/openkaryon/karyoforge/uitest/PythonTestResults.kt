package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.getRunContentManager
import com.intellij.driver.sdk.singleProject
import org.junit.jupiter.api.Assertions.assertNotNull
import kotlin.time.Duration.Companion.minutes

@Remote("com.intellij.execution.ui.RunContentDescriptor")
interface NativePythonTestDescriptor {
    fun getExecutionConsole(): NativePythonTestConsole
}

@Remote("com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView")
interface NativePythonTestConsole {
    fun getResultsViewer(): NativePythonTestResults
}

@Remote("com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm")
interface NativePythonTestResults {
    fun getTestsRootNode(): NativePythonTestNode
    fun isShowing(): Boolean
}

@Remote("com.intellij.execution.testframework.sm.runner.SMTestProxy")
interface NativePythonTestNode {
    fun isPassed(): Boolean
    fun isSuite(): Boolean
    fun getAllTests(): List<NativePythonTestNode>
}

/** Observe the same native model rendered by the visible Run panel. */
fun Driver.assertNativePythonTestsPassed(expected: Int) {
    val descriptor = getRunContentManager(singleProject()).getSelectedContent()
    assertNotNull(descriptor, "The GUI context run exposes its native result descriptor")
    val results = cast(descriptor!!, NativePythonTestDescriptor::class).getExecutionConsole().getResultsViewer()
    awaitCondition("The visible native Python runner reports exactly $expected passed tests", 2.minutes) {
        val root = results.getTestsRootNode()
        val tests = root.getAllTests().filterNot { it.isSuite() }
        results.isShowing() && root.isPassed() && tests.size == expected && tests.all { it.isPassed() }
    }
}
