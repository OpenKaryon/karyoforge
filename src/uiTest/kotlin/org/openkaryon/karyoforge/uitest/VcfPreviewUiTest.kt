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

class VcfPreviewUiTest {
    @Test
    fun vcfShowsMetadataAndLiteralSampleFieldsAcrossPages() {
        val project = VmIde.project("VCF preview")
        val file = project.resolve("variants.vcf")
        val metadata = "##fileformat=VCFv4.3\n##source=synthetic-fixture\n"
        val header = "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tfirst\tsecond\n"
        val record = "chr1\t00017\t.\tA\tC\t.\tPASS\tDP=0008\tGT:DP\t0|1:0005\t./.:.\n"
        val source = metadata + header + record.repeat(201)
        file.writeText(source)
        VmIde.context("vcf-preview", project).runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(file)
            ideFrame {
                x { byVisibleText("VCF records 1–200") }.shouldBe("VCF loads a record page", present, 2.minutes)
                val details = x { byAccessibleName("Preview record details") }
                val first = details.nativeDocumentText()
                assertTrue(first.contains("POS: 00017") && first.contains("INFO: DP=0008"))
                assertTrue(first.contains("FORMAT: GT:DP\nfirst: 0|1:0005\nsecond: ./.:."))
                button { byVisibleText("Next page") }.click()
                x { contains(byVisibleText("VCF records 201–201")) }.shouldBe("VCF seeks to its final record", present, 1.minutes)
                assertTrue(details.nativeDocumentText().startsWith("Record 201"))
                button { byVisibleText("Metadata / headers") }.click()
                assertTrue(x { byAccessibleName("Preview metadata") }.nativeDocumentText().contains("##source=synthetic-fixture"))
            }
            assertEquals(source, file.readText())
        }
        VmIde.assertNoConsentCrash()
    }
}
