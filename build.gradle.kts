import groovy.json.JsonOutput
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "org.openkaryon.karyoforge"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2")
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        plugin("PythonCore", providers.gradleProperty("pythonPluginVersion").get())
        testFramework(TestFrameworkType.Platform, providers.gradleProperty("platformBuild").get())
        testBundledModule("intellij.spellchecker")
        bundledPlugin("org.toml.lang")
        bundledPlugin("org.jetbrains.plugins.terminal")
        testBundledPlugin("com.intellij.java")
        javaCompiler(providers.gradleProperty("platformBuild").get())
        pluginVerifier("1.410")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.processResources {
    from(files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
}

intellijPlatform {
    autoReload = false
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformBuild")
            untilBuild = providers.gradleProperty("platformBuild")
        }
    }
    pluginVerification {
        ides {
            current()
        }
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
    }
}

tasks.named<VerifyPluginTask>("verifyPlugin") {
    dependsOn("stageVerifierPlugins")
    systemProperty("plugin.verifier.home.dir", layout.buildDirectory.dir("pluginVerifierHome").get().asFile.absolutePath)
    offline.set(true)
}

tasks.register<Copy>("stageVerifierPlugins") {
    from(configurations.named("intellijPlatformPlugin"))
    into(layout.buildDirectory.dir("pluginVerifierHome/loaded-plugins"))
}

tasks.test {
    dependsOn("brandingJar")
    systemProperty("idea.vendor.name", "OpenKaryon")
    systemProperty("intellij.platform.load.app.info.from.resources", "true")
    systemProperty("idea.disable.collect.statistics", "true")
    systemProperty("idea.suppress.statistics.report", "true")
    systemProperty("idea.headless.enable.statistics", "false")
    val consentProfile = layout.buildDirectory.dir("test-profile").get().asFile
    environment("APPDATA", consentProfile.resolve("roaming").absolutePath)
    environment("LOCALAPPDATA", consentProfile.resolve("local").absolutePath)
    doFirst {
        classpath = files(tasks.named<Jar>("brandingJar").flatMap { it.archiveFile }) + classpath
    }
    if (!providers.gradleProperty("pythonRuntimeTests").orElse("false").get().toBoolean()) {
        exclude("**/PythonRuntimeIntegrationTest.class")
    }
    systemProperty("karyoforge.python.executable", providers.gradleProperty("pythonExecutable").orElse("").get())
    // Keep tests headless and independent from the owner's normal IDE profile.
    systemProperty("java.awt.headless", "true")
    val nativeDirectory = layout.buildDirectory.dir("native-test").get().asFile
    systemProperty("winp.folder.preferred", nativeDirectory.absolutePath)
    doFirst {
        nativeDirectory.mkdirs()
    }
    maxHeapSize = if (providers.gradleProperty("pythonRuntimeTests").orElse("false").get().toBoolean()) "2g" else "1g"
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("idea.disable.collect.statistics", "true")
    systemProperty("idea.suppress.statistics.report", "true")
    systemProperty("idea.headless.enable.statistics", "false")
    val nativeDirectory = layout.buildDirectory.dir("native").get().asFile
    systemProperty("winp.folder.preferred", nativeDirectory.absolutePath)
    doFirst {
        nativeDirectory.mkdirs()
    }
}

val brandingOutput = layout.buildDirectory.dir("branding/resources")
val developmentIde = tasks.named<RunIdeTask>("runIde")
val sdkDirectory = providers.provider { developmentIde.get().platformPath.toFile() }
val prepareBranding = tasks.register<Exec>("prepareBranding") {
    inputs.dir("installer/branding")
    inputs.file("tools/branding.py")
    inputs.file(sdkDirectory.map { it.resolve("product-info.json") })
    inputs.file(sdkDirectory.map { it.resolve("lib/app.jar") })
    inputs.property("platformBuild", providers.gradleProperty("platformBuild"))
    outputs.dir(brandingOutput)
    doFirst {
        commandLine(providers.gradleProperty("pythonCommand").orElse(providers.environmentVariable("PYTHON")).orElse("python").get(), "tools/branding.py",
            "--sdk", sdkDirectory.get().absolutePath, "--assets", "installer/branding",
            "--output", brandingOutput.get().asFile.absolutePath,
            "--build", providers.gradleProperty("platformBuild").get())
    }
}
val brandingJar = tasks.register<Jar>("brandingJar") {
    dependsOn(prepareBranding)
    archiveBaseName.set("karyoforge-branding")
    destinationDirectory.set(layout.buildDirectory.dir("branding"))
    from(brandingOutput) {
        include("idea/IdeaApplicationInfo.xml", "branding-manifest.json", "consents-OpenKaryon.json", "karyoforge.svg",
            "karyoforge_16.svg", "karyoforge.ico", "karyoforge_logo.png", "karyoforge_logo@2x.png")
    }
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

developmentIde {
    dependsOn(brandingJar)
    systemProperty("intellij.platform.load.app.info.from.resources", "true")
    systemProperty("idea.reset.classpath.from.manifest", "false")
    doFirst {
        classpath = files(brandingJar.flatMap { it.archiveFile }) + classpath
    }
}

tasks.register<JavaExec>("verifyBranding") {
    dependsOn(brandingJar, tasks.testClasses)
    mainClass.set("org.openkaryon.karyoforge.branding.BrandingProbe")
    classpath = files(brandingJar.flatMap { it.archiveFile }) + sourceSets.test.get().runtimeClasspath +
        files(sdkDirectory.map { fileTree(it.resolve("lib")) { include("*.jar") } })
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    systemProperty("java.awt.headless", "true")
    systemProperty("idea.platform.prefix", "Idea")
    systemProperty("intellij.platform.load.app.info.from.resources", "true")
    doFirst {
        systemProperty("idea.home.path", sdkDirectory.get().absolutePath)
    }
    val consentProfile = layout.buildDirectory.dir("branding/probe-profile").get().asFile
    environment("APPDATA", consentProfile.resolve("roaming").absolutePath)
    environment("LOCALAPPDATA", consentProfile.resolve("local").absolutePath)
    args(providers.gradleProperty("platformBuild").get())
}

val windowsInputs = layout.buildDirectory.file("windows/inputs.json")
val windowsInputsTask = tasks.register("prepareWindowsInputs") {
    dependsOn(tasks.named("buildPlugin"), tasks.named("verifyPluginStructure"), brandingJar)
    outputs.file(windowsInputs)
    // Record resolved inputs on every invocation; paths stay in ignored build output.
    outputs.upToDateWhen { false }
    doLast {
        val cache = gradle.gradleUserHomeDir.resolve("caches/modules-2/files-2.1")
        val platformVersion = providers.gradleProperty("platformVersion").get()
        val pythonVersion = providers.gradleProperty("pythonPluginVersion").get()
        val data = mapOf(
            "schemaVersion" to 1,
            "pluginVersion" to project.version.toString(),
            "platformVersion" to platformVersion,
            "platformBuild" to providers.gradleProperty("platformBuild").get(),
            "pythonPluginVersion" to pythonVersion,
            "pluginArchive" to tasks.named<Zip>("buildPlugin").get().archiveFile.get().asFile.absolutePath,
            "brandingArchive" to brandingJar.get().archiveFile.get().asFile.absolutePath,
            "platformArchives" to fileTree(cache.resolve("idea/ideaIC/$platformVersion")) { include("**/*.zip") }.files.map { it.absolutePath },
            "pythonArchives" to fileTree(cache.resolve("com.jetbrains.plugins/PythonCore/$pythonVersion")) { include("**/*.zip") }.files.map { it.absolutePath },
        )
        windowsInputs.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)) + "\n")
        }
    }
}
val stageWindows = tasks.register<Exec>("stageWindows") {
    dependsOn(windowsInputsTask)
    inputs.file(windowsInputs)
    inputs.file("tools/windows_distribution.py")
    inputs.file("installer/windows-artifacts.json")
    inputs.file(tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile })
    inputs.file(brandingJar.flatMap { it.archiveFile })
    outputs.dir(layout.buildDirectory.dir("windows/app"))
    doFirst {
        commandLine(providers.gradleProperty("pythonCommand").orElse("python").get(),
            "tools/windows_distribution.py", "stage", "--inputs", windowsInputs.get().asFile.absolutePath)
    }
}
for ((taskName, mode) in listOf("packageWindows" to "installer", "zipWindows" to "zip", "verifyWindows" to "verify")) {
    tasks.register<Exec>(taskName) {
        dependsOn(stageWindows)
        doFirst {
            commandLine(providers.gradleProperty("pythonCommand").orElse("python").get(),
                "tools/windows_distribution.py", mode, "--inputs", windowsInputs.get().asFile.absolutePath)
        }
    }
}
