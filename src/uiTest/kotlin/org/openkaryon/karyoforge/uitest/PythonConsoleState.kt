package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.RunContentDescriptorRef
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent

/** Observe native selection; opening consoles and evaluating probes remain GUI actions. */
@Remote("com.jetbrains.python.console.PythonConsoleToolWindow", plugin = "PythonCore")
interface NativePythonConsoleWindow {
    fun getInstance(project: Project): NativePythonConsoleWindow
    fun isInitialized(): Boolean
    fun getSelectedContentDescriptor(): RunContentDescriptorRef?
}

fun Driver.selectedPythonConsoleIdentity(): Int? {
    val window = utility<NativePythonConsoleWindow>().getInstance(singleProject())
    if (!window.isInitialized()) return null
    val process = window.getSelectedContentDescriptor()?.getProcessHandler() ?: return null
    if (process.isProcessTerminated()) return null
    return utility<NativeIdentity>().identityHashCode(process)
}

@Remote("com.jetbrains.python.console.PythonConsoleView", plugin = "PythonCore")
interface NativePythonConsoleView {
    fun isInitialized(): Boolean
    fun getExecuteActionHandler(): NativePythonConsoleExecutor?
}

@Remote("com.jetbrains.python.console.PythonConsoleExecuteActionHandler", plugin = "PythonCore")
interface NativePythonConsoleExecutor {
    fun isEnabled(): Boolean
    fun canExecuteNow(): Boolean
}

/** Panel existence precedes the native console's RPC connection and execution readiness. */
fun UiComponent.nativePythonConsoleAcceptsInput(): Boolean {
    val view = driver.cast(component, NativePythonConsoleView::class)
    if (!view.isInitialized()) return false
    val executor = view.getExecuteActionHandler() ?: return false
    return executor.isEnabled() && executor.canExecuteNow()
}
