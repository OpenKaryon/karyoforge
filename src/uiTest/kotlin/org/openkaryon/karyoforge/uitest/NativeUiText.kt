package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.ui.components.UiComponent

@Remote("javax.swing.text.JTextComponent")
interface NativeTextComponent {
    fun getDocument(): NativeTextDocument
}

@Remote("javax.swing.text.Document")
interface NativeTextDocument {
    fun getLength(): Int
    fun getText(offset: Int, length: Int): String
}

/** Read the displayed widget's document without turning visual line wraps into path separators. */
fun UiComponent.nativeDocumentText(): String {
    val document = driver.cast(component, NativeTextComponent::class).getDocument()
    return document.getText(0, document.getLength())
}
