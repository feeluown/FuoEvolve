import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.zip.ZipFile

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
val isLinuxHost = hostOs.contains("linux")
val packageResourceOs = when {
    isWindowsHost -> "windows"
    hostOs.contains("mac") || hostOs.contains("darwin") -> "macos"
    isLinuxHost -> "linux"
    else -> "common"
}
val webLoginExecutableName = if (isWindowsHost) "fuoevolve-web-login.exe" else "fuoevolve-web-login"
val webLoginProjectDir = rootProject.layout.projectDirectory.dir("desktopApp/native/web-login")
val webLoginExecutable = webLoginProjectDir.file("target/release/$webLoginExecutableName")
val nucleusAppResources = layout.buildDirectory.dir("nucleus-app-resources")
val stagedNativeResourceRoot = "$packageResourceOs/native"

val mpvJniLibraryName = when {
    isWindowsHost -> "fuoevolve_mpv_jni.dll"
    hostOs.contains("mac") || hostOs.contains("darwin") -> "libfuoevolve_mpv_jni.dylib"
    else -> "libfuoevolve_mpv_jni.so"
}
val mpvJniSource = layout.projectDirectory.file("native/mpv-jni/fuoevolve_mpv_jni.c")
val mpvJniOutput = layout.buildDirectory.file("native/mpv-jni/$mpvJniLibraryName")

private val jarSignatureExtensions = setOf("SF", "RSA", "DSA", "EC")

private fun isJarSignatureEntry(name: String): Boolean {
    val normalized = name.replace('\\', '/')
    if (!normalized.startsWith("META-INF/", ignoreCase = true)) return false
    val fileName = normalized.substringAfter("META-INF/")
    if (fileName.isBlank() || '/' in fileName) return false
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
    return extension.uppercase() in jarSignatureExtensions
}

val buildNucleusWebLoginHelper by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the shared system-WebView login helper for the Nucleus desktop runtime."
    workingDir(webLoginProjectDir)
    commandLine("cargo", "build", "--release")
}

val buildNucleusMpvJniBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the thin JNI bridge used by the Nucleus libmpv playback backend."
    inputs.file(mpvJniSource)
    outputs.file(mpvJniOutput)
    onlyIf {
        // Linux is the first validated Native Image playback target. macOS/Windows keep the
        // existing JVM playback path until their JNI bridge packaging is validated separately.
        isLinuxHost
    }
    doFirst {
        mpvJniOutput.get().asFile.parentFile.mkdirs()
    }
    val javaHome = File(System.getProperty("java.home"))
    commandLine(
        "cc",
        "-shared",
        "-fPIC",
        "-O2",
        "-Wall",
        "-Wextra",
        "-I${javaHome.resolve("include").absolutePath}",
        "-I${javaHome.resolve("include/linux").absolutePath}",
        mpvJniSource.asFile.absolutePath,
        "-o",
        mpvJniOutput.get().asFile.absolutePath,
        "-lmpv",
    )
}

val prepareNucleusAppResources by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stage native resources required by the Nucleus desktop runtime."
    dependsOn(buildNucleusWebLoginHelper)
    if (isLinuxHost) {
        dependsOn(buildNucleusMpvJniBridge)
    }
    from(webLoginExecutable) {
        into("$stagedNativeResourceRoot/helpers")
        if (!isWindowsHost) {
            filePermissions {
                unix("755")
            }
        }
    }
    if (isLinuxHost) {
        from(mpvJniOutput) {
            into("$stagedNativeResourceRoot/lib")
            filePermissions {
                unix("755")
            }
        }
    }
    into(nucleusAppResources)

    doLast {
        val stagedHelper = nucleusAppResources.get().asFile
            .resolve("$stagedNativeResourceRoot/helpers/$webLoginExecutableName")
        if (!stagedHelper.isFile) {
            throw GradleException("Nucleus web login helper was not staged: ${stagedHelper.absolutePath}")
        }
        if (!isWindowsHost && !stagedHelper.canExecute()) {
            throw GradleException("Nucleus web login helper is not executable: ${stagedHelper.absolutePath}")
        }
        if (isLinuxHost) {
            val stagedMpvBridge = nucleusAppResources.get().asFile
                .resolve("$stagedNativeResourceRoot/lib/$mpvJniLibraryName")
            if (!stagedMpvBridge.isFile) {
                throw GradleException("Nucleus libmpv JNI bridge was not staged: ${stagedMpvBridge.absolutePath}")
            }
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

// Nucleus feeds native-image a repackaged uber JAR rather than the original dependency JARs.
// Upstream credential-secure-storage is signed, so its META-INF signature blocks no longer match
// after the merge. Strip only JAR-level signatures from this Nucleus-owned uber JAR; the existing
// JVM desktop packaging path and platform distribution signing remain completely untouched.
tasks.withType<Jar>()
    .matching { task -> task.name.contains("UberJar", ignoreCase = true) }
    .configureEach {
        exclude { element -> isJarSignatureEntry(element.path) }

        doLast {
            val uberJar = archiveFile.get().asFile
            val staleSignatures = ZipFile(uberJar).use { zip ->
                buildList {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (isJarSignatureEntry(entry.name)) add(entry.name)
                    }
                }
            }
            if (staleSignatures.isNotEmpty()) {
                throw GradleException(
                    "Nucleus uber JAR still contains invalid dependency signatures: " +
                        staleSignatures.joinToString(),
                )
            }
        }
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
// explicit so Gradle 9 validation and both JVM/GraalVM pipelines see native resources deterministically.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareNucleusAppResources)
}
tasks.matching { it.name == "run" }.configureEach {
    dependsOn(buildNucleusWebLoginHelper)
    if (isLinuxHost) {
        dependsOn(buildNucleusMpvJniBridge)
    }
}
