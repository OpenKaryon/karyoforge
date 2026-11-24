package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class FastqPreviewUiTest {
    @Test
    fun wrappedQualityStaysLiteralAndIncorrectLengthShowsAnError() {
        val project = VmIde.project("FASTQ preview")
        val valid = project.resolve("wrapped.fastq")
        val invalid = project.resolve("bad-length.fq")
        val source = "@first synthetic\nAC\nGT\n+\n@!\n+?\n@second\nTG\n+\n##\n"
        val malformed = "@broken\nACGT\n+\n!!!\n"
        valid.writeText(source)
        invalid.writeText(malformed)
        VmIde.context("fastq-preview", project).runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(valid)
            ideFrame {
                x { contains(byVisibleText("FASTQ records 1–2")) }.shouldBe("FASTQ shows both records", present, 2.minutes)
                assertTrue(x { byAccessibleName("Preview record details") }.nativeDocumentText().contains("Quality: @!+?"))
            }
            openOwnedFile(invalid)
            ideFrame {
                x { contains(byVisibleText("FASTQ quality is shorter than its sequence")) }
                    .shouldBe("Malformed FASTQ explains its length mismatch", present, 2.minutes)
            }
            assertEquals(source, valid.readText())
            assertEquals(malformed, invalid.readText())
        }
        VmIde.assertNoConsentCrash()
    }
}
