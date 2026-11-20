package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.ui.components.UiComponent

@Remote("com.intellij.terminal.JBTerminalPanel")
interface NativeTerminalPanel {
    fun getTerminalTextBuffer(): NativeTerminalBuffer
}

@Remote("com.jediterm.terminal.model.TerminalTextBuffer")
interface NativeTerminalBuffer {
    fun getScreenLines(): String
}

/** Observe the visible terminal's real screen instead of separate painted glyphs. */
fun UiComponent.nativeTerminalScreenText(): String =
    driver.cast(component, NativeTerminalPanel::class).getTerminalTextBuffer().getScreenLines()
