package org.openkaryon.karyoforge.uitest

import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeDistributionFactory
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.kodein.di.direct
import org.kodein.di.instance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** VM-only acceptance of the staged public distribution, with fresh project/profile ownership. */
object VmIde {
    private fun property(name: String): String = System.getProperty("karyoforge.ui.$name")
        ?.takeIf { it.isNotBlank() } ?: error("Missing karyoforge.ui.$name; use the VM runner")

    val root: Path get() = Path.of(property("root")).toAbsolutePath().normalize()
    val python: Path get() = Path.of(property("python"))
    val fixtures: Path get() = Path.of(property("fixtures"))

    fun project(name: String): Path {
        require(System.getProperty("karyoforge.ui.vmVerified") == "true") { "GUI acceptance requires the separate-VM preflight" }
        require(root.resolve(".karyoforge-ui-run.json").toFile().isFile) { "Missing owned VM run marker" }
        val directory = root.resolve("projects").resolve("$name-${System.nanoTime()}").createDirectories()
        directory.resolve("main.py").writeText("import sys\nprint(sys.executable)\n")
        return directory
    }

    fun context(name: String, project: Path): IDETestContext {
        require(project.toAbsolutePath().normalize().startsWith(root.resolve("projects")))
        val home = Path.of(property("ide"))
        require(Files.isRegularFile(home.resolve("bin/karyoforge.exe"))) { "Expected the public KaryoForge launcher" }
        val product = IdeProductProvider.IC.copy(getInstaller = { PublicDistribution(home) })
        val context = Starter.newContext(name, TestCase(product, LocalProjectInfo(project)))
        context.paths.configDir.resolve("options").createDirectories().resolve("advancedSettings.xml").writeText("""
            <application><component name="AdvancedSettings"><option name="settings"><map>
            <entry key="ide.ui.native.file.chooser" value="false"/>
            </map></option></component></application>
        """.trimIndent())
        return context.setMemorySize(1536).disableReportingStatisticsToProduction(true)
            .disableFusSendingOnIdeClose().disableAutoImport(true)
    }

    fun assertNoConsentCrash() {
        Files.walk(Path.of("out/ide-tests/tests")).use { files ->
            files.filter { it.fileName.toString() == "idea.log" }.forEach { file ->
                check(!file.readText().contains("consentMap")) { "Consent failure in $file" }
            }
        }
    }

    private class PublicDistribution(private val home: Path) : IdeInstaller {
        override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> =
            "public-distribution" to di.direct.instance<IdeDistributionFactory>().installIDE(home.toFile(), "karyoforge.exe")
    }
}
