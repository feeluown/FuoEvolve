import org.gradle.api.tasks.Sync

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("dev.nucleusframework") version "2.5.15"
}

kotlin {
    jvmToolchain(17)
}

val hostOs = System.getProperty("os.name").orEmpty().lowercase()
val isWindowsHost = hostOs.contains("windows")
val webLoginExecutableName = if (isWindowsHost) "fuoevolve-web-login.exe" else "fuoevolve-web-login"
val webLoginProjectDir = rootProject.layout.projectDirectory.dir("desktopApp/native/web-login")
val webLoginExecutable = webLoginProjectDir.file("target/release/$webLoginExecutableName")
val nucleusAppResources = layout.buildDirectory.dir("nucleus-app-resources")

val buildNucleusWebLoginHelper by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the shared system-WebView login helper for the Nucleus desktop runtime."
    workingDir(webLoginProjectDir)
    commandLine("cargo", "build", "--release")
}

val prepareNucleusAppResources by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stage native resources required by the Nucleus desktop runtime."
    dependsOn(buildNucleusWebLoginHelper)
    from(webLoginExecutable) {
        into("native/helpers")
        if (!isWindowsHost) {
            filePermissions {
                unix("755")
            }
        }
    }
    into(nucleusAppResources)

    doLast {
        val stagedHelper = nucleusAppResources.get().asFile
            .resolve("native/helpers/$webLoginExecutableName")
        if (!stagedHelper.isFile) {
            throw GradleException("Nucleus web login helper was not staged: ${stagedHelper.absolutePath}")
        }
        if (!isWindowsHost && !stagedHelper.canExecute()) {
            throw GradleException("Nucleus web login helper is not executable: ${stagedHelper.absolutePath}")
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":desktopRuntime"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    implementation("dev.nucleusframework:nucleus.nucleus-application:2.5.15")
    implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.5.15")
    implementation("dev.nucleusframework:nucleus.graalvm-runtime:2.5.15")
}

nucleus.application {
    mainClass = "org.feeluown.mobile.nucleus.NucleusMainKt"

    nativeDistributions {
        appResourcesRootDir.set(nucleusAppResources)
    }

    graalvm {
        isEnabled.set(true)
        imageName.set("fuoevolve-nucleus-poc")
    }
}

// Compose/Nucleus consume appResources through prepareAppResources. Make the staging dependency
// explicit so Gradle 9 validation and both JVM/GraalVM pipelines see the helper deterministically.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareNucleusAppResources)
}
tasks.matching { it.name == "run" }.configureEach {
    dependsOn(buildNucleusWebLoginHelper)
}
