package org.openkaryon.karyoforge.uitest

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class DistributionUiTest {
    @Test
    fun publicDistributionOpensAProjectAndShowsItsIdentity() {
        VmIde.context("distribution", VmIde.project("startup")).runIdeWithDriver(runTimeout = 15.minutes)
            .useDriverAndCloseIde {
                waitForIndicators(10.minutes)
                ideFrame { x { contains(byVisibleText("main.py")) }.shouldBe("The project is visible", present, 1.minutes) }
                invokeAction("About", now = false)
                ui.dialog({ byTitle("About KaryoForge") }) {
                    x { and(byClass("JEditorPane"), contains(byVisibleText("KaryoForge"))) }
                        .shouldBe("Public product identity", present, 1.minutes)
                    x { contains(byVisibleText("OpenKaryon")) }.shouldBe("Public vendor identity", present, 1.minutes)
                    pressButton("Close")
                }
            }
        VmIde.assertNoConsentCrash()
    }
}
