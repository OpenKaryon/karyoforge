package org.openkaryon.karyoforge.uitest

import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IDEStartConfig
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.models.VMOptions
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.util.system.OS
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** VM-only acceptance of the staged public distribution, with fresh project/profile ownership. */
object VmIde {
    private fun property(name: String): String = System.getProperty("karyoforge.ui.$name")
        ?.takeIf { it.isNotBlank() } ?: error("Missing karyoforge.ui.$name; use the VM runner")

    val root: Path get() = Path.of(property("root")).toAbsolutePath().normalize()
    val python: Path get() = Path.of(property("python"))
    val notebookPython: Path get() = Path.of(property("notebookPython"))
    val fixtures: Path get() = Path.of(property("fixtures"))

    fun project(name: String): Path {
        require(System.getProperty("karyoforge.ui.vmVerified") == "true") { "GUI acceptance requires the separate-VM preflight" }
        require(root.resolve(".karyoforge-ui-run.json").toFile().isFile) { "Missing owned VM run marker" }
        val directory = root.resolve("projects").resolve("$name-${System.nanoTime()}").createDirectories()
        directory.resolve("main.py").writeText("import sys\nprint(sys.executable)\n")
        return directory
    }

    fun fixtureProject(name: String): Path {
        val destination = project(name)
        require(Files.isRegularFile(fixtures.resolve("manifest.json"))) { "Build the pinned offline GUI fixtures first" }
        Files.walk(fixtures).use { files ->
            files.forEach { source ->
                val target = destination.resolve(fixtures.relativize(source).toString())
                if (Files.isDirectory(source)) target.createDirectories()
                else Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return destination
    }

    fun context(name: String, project: Path): IDETestContext {
        require(project.toAbsolutePath().normalize().startsWith(root.resolve("projects")))
        val home = Path.of(property("ide"))
        require(Files.isRegularFile(home.resolve("bin/karyoforge.exe"))) { "Expected the public KaryoForge launcher" }
        val product = IdeProductProvider.IC.copy(getInstaller = { PublicDistribution(home) })
        val context = Starter.newContext(name, TestCase(product, LocalProjectInfo(project)))
        val bridge = Path.of(property("testPlugins")).resolve("performanceTesting")
        require(Files.isRegularFile(bridge.resolve("lib/performanceTesting.jar"))) { "Missing pinned test-only Driver bridge" }
        context.pluginConfigurator.installPluginFromDir(bridge)
        context.paths.configDir.resolve("options").createDirectories().resolve("advancedSettings.xml").writeText("""
            <application><component name="AdvancedSettings"><option name="settings"><map>
            <entry key="ide.ui.native.file.chooser" value="false"/>
            </map></option></component></application>
        """.trimIndent())
        val memoryMb = System.getProperty("karyoforge.ui.ideMemoryMb", "1024").toInt()
        require(memoryMb in 768..4096) { "Invalid IDE heap limit" }
        return context.setMemorySize(memoryMb).disableReportingStatisticsToProduction(true)
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
            "public-distribution" to PublicInstalledIde(home)
    }

    /** Starter's stock Windows factory hardcodes a 64.exe suffix and IDEA environment names. */
    private class PublicInstalledIde(private val home: Path) : InstalledIde {
        private val identity = home.resolve("build.txt").readText().trim()
        override val build = identity.substringAfter('-')
        override val productCode = identity.substringBefore('-')
        override val os = OS.Windows
        override val isFromSources = false
        override val installationPath: Path = home.toAbsolutePath()
        override val patchedVMOptionsFile: Path = root.resolve("launch").createDirectories().resolve("${UUID.randomUUID()}.vmoptions")
        override val vmOptions: VMOptions by lazy {
            VMOptions(this, Files.readAllLines(home.resolve("bin/karyoforge.exe.vmoptions"))
                .map(String::trim).filter { it.startsWith('-') }, emptyMap())
        }

        override suspend fun resolveAndDownloadTheSameJDK(): Path = home.resolve("jbr").also {
            require(Files.isRegularFile(it.resolve("bin/java.exe"))) { "Missing bundled Java runtime" }
        }

        override fun startConfig(vmOptions: VMOptions, logsDir: Path): IDEStartConfig {
            vmOptions.writeIntelliJVmOptionFile(patchedVMOptionsFile)
            return object : IDEStartConfig {
                override val workDir: Path = home
                override val commandLine = listOf(home.resolve("bin/karyoforge.exe").toString())
                override val environmentVariables = System.getenv().filterKeys {
                    !it.endsWith("VM_OPTIONS") && it !in setOf("JAVA_HOME", "IDEA_PROPERTIES", "KARYOFORGE_PROPERTIES")
                } + vmOptions.environmentVariables + mapOf(
                    "KARYOFORGE_VM_OPTIONS" to patchedVMOptionsFile.toString(),
                    "KARYOFORGE_JDK" to home.resolve("jbr").toString())
            }
        }

        override fun toString() = "KaryoForge $identity at $home"
    }
}
