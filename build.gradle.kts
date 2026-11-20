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
    val nativeDirectory = layout.buildDirectory.dir("native").get().asFile
    systemProperty("winp.folder.preferred", nativeDirectory.absolutePath)
    doFirst {
        nativeDirectory.mkdirs()
    }
}
