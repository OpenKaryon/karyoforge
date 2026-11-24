package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class FastaPreviewUiTest {
    @Test
    fun longFastaRecordShowsFullLengthAndPagesWithoutChangingSource() {
        val project = VmIde.project("FASTA preview")
        val path = project.resolve("sequences.FA")
        val source = ">long synthetic\n" + "ACGT".repeat(600_000) + "\nAAAA\n>short\nTT\n"
        path.writeText(source)
        VmIde.context("fasta-preview", project).runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(path)
            ideFrame {
                x { byVisibleText("FASTA records 1–1") }.shouldBe("FASTA opens a bounded page", present, 2.minutes)
                val details = x { byAccessibleName("Preview record details") }
                assertTrue(details.nativeDocumentText().contains("Length: 2400004\nSequence: " + "ACGT".repeat(128) + "…"))
                button { byVisibleText("Next page") }.click()
                x { contains(byVisibleText("FASTA records 2–2")) }.shouldBe("Next seeks to the following record", present, 1.minutes)
                assertTrue(details.nativeDocumentText().contains("Identifier: short"))
            }
            assertEquals(source, path.readText())
        }
        VmIde.assertNoConsentCrash()
    }
}
