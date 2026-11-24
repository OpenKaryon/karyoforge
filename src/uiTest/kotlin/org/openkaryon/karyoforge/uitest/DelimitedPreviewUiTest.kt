package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.ui.components.common.codeEditor
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

class DelimitedPreviewUiTest {
    @Test
    fun savedCsvAndTsvOpenWithBoundedPagingAndNativeText() {
        val project = VmIde.project("delimited preview")
        val csv = project.resolve("table.csv")
        val tsv = project.resolve("table.tsv")
        val csvSource = "name,count\r\n" + (1..205).joinToString("") { "\"item,$it\",001\r\n" }
        val tsvSource = "key\tvalue\nsample\t\"literal\"\n"
        csv.writeText(csvSource)
        tsv.writeText(tsvSource)
        VmIde.context("delimited-preview", project).runIdeWithDriver(runTimeout = 20.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            openOwnedFile(csv)
            ideFrame {
                x { byVisibleText("CSV records 1–200") }.shouldBe("CSV loads the first page", present, 2.minutes)
                val details = x { byAccessibleName("Preview record details") }
                assertTrue(details.nativeDocumentText().contains("name: item,1\ncount: 001"))
                button { byVisibleText("Next page") }.click()
                x { contains(byVisibleText("CSV records 201–205")) }.shouldBe("Next shows only the remaining records", present, 1.minutes)
                assertTrue(details.nativeDocumentText().contains("name: item,201"))
                button { byVisibleText("Previous page") }.click()
                x { byVisibleText("CSV records 1–200") }.shouldBe("Previous restores the first page", present, 1.minutes)
                x { byVisibleText("Text") }.click()
                codeEditor { assertTrue(text.contains("\"item,1\",001")) }
            }
            openOwnedFile(tsv)
            ideFrame {
                // The platform can remember Text for the next file after the CSV check.
                x { byVisibleText("Preview") }.click()
                x { contains(byVisibleText("TSV records 1–1")) }.shouldBe("TSV has its own preview", present, 2.minutes)
                assertTrue(x { byAccessibleName("Preview record details") }.nativeDocumentText().contains("value: \"literal\""))
            }
            assertEquals(csvSource, csv.readText())
            assertEquals(tsvSource, tsv.readText())
        }
        VmIde.assertNoConsentCrash()
    }
}
