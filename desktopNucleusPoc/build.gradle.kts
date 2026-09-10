import dev.nucleusframework.desktop.application.dsl.TargetFormat
import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("dev.nucleusframework") version "2.5.15"
}

kotlin {
    jvmToolchain(17)
}

fun gitOutput(vararg args: String): String? = runCatching {
    providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *args)
    }.standardOutput.asText.get().trim().takeIf(String::isNotBlank)
}.getOrNull()

val desktopPackageVersion = providers.gradleProperty("fuoevolve.packageVersion")
    .orElse(providers.environmentVariable("FUOEVOLVE_PACKAGE_VERSION"))
    .orNull
    ?.takeIf(String::isNotBlank)
    ?: gitOutput("describe", "--tags", "--match", "[0-9]*", "--abbrev=0")
        ?.let { tag -> Regex("\\d+\\.\\d+\\.\\d+").find(tag)?.value }
    ?: "0.1.0"

val hostOs = System.getProperty("os.name").orEmpty().lowercase()
val isWindowsHost = hostOs.contains("windows")
val isMacHost = hostOs.contains("mac") || hostOs.contains("darwin")
val isLinuxHost = hostOs.contains("linux")
val packageResourceOs = when {
    isWindowsHost -> "windows"
    isMacHost -> "macos"
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
    isMacHost -> "libfuoevolve_mpv_jni.dylib"
    else -> "libfuoevolve_mpv_jni.so"
}
val mpvJniSource = layout.projectDirectory.file("native/mpv-jni/fuoevolve_mpv_jni.c")
val mpvJniOutput = layout.buildDirectory.file("native/mpv-jni/$mpvJniLibraryName")
val mpvDevDirPath = providers.gradleProperty("fuoevolve.nucleus.libmpvDevDir")
    .orElse(providers.environmentVariable("FUOEVOLVE_NUCLEUS_LIBMPV_DEV_DIR"))
val mpvRuntimeDirPath = providers.gradleProperty("fuoevolve.nucleus.libmpvRuntimeDir")
    .orElse(providers.environmentVariable("FUOEVOLVE_NUCLEUS_LIBMPV_RUNTIME_DIR"))
val bundleLinuxRuntime = providers.gradleProperty("fuoevolve.nucleus.bundleLinuxRuntime")
    .map(String::toBoolean)
    .orElse(false)
val portableLinuxRuntime = layout.buildDirectory.dir("nucleus-portable-linux-runtime")

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

    doFirst {
        mpvJniOutput.get().asFile.parentFile.mkdirs()
        val javaHome = File(System.getProperty("java.home"))
        val includeRoot = javaHome.resolve("include")
        val platformInclude = includeRoot.resolve(
            when {
                isWindowsHost -> "win32"
                isMacHost -> "darwin"
                else -> "linux"
            },
        )
        check(includeRoot.isDirectory && platformInclude.isDirectory) {
            "JNI headers were not found below ${javaHome.absolutePath}"
        }

        when {
            isWindowsHost -> {
                val devDir = mpvDevDirPath.orNull?.let(::file)
                    ?: throw GradleException(
                        "Windows Nucleus JNI build requires FUOEVOLVE_NUCLEUS_LIBMPV_DEV_DIR",
                    )
                val header = devDir.resolve("include/mpv/client.h")
                val importLibrary = devDir.resolve("libmpv.dll.a")
                check(header.isFile && importLibrary.isFile) {
                    "Windows libmpv development bundle is incomplete: ${devDir.absolutePath}"
                }
                commandLine(
                    "clang",
                    "-shared",
                    "-O2",
                    "-Wall",
                    "-Wextra",
                    "-I${includeRoot.absolutePath}",
                    "-I${platformInclude.absolutePath}",
                    "-I${devDir.resolve("include").absolutePath}",
                    mpvJniSource.asFile.absolutePath,
                    "-L${devDir.absolutePath}",
                    "-lmpv",
                    "-o",
                    mpvJniOutput.get().asFile.absolutePath,
                )
            }

            isMacHost -> {
                val devDir = mpvDevDirPath.orNull?.let(::file)
                    ?: throw GradleException(
                        "macOS Nucleus JNI build requires FUOEVOLVE_NUCLEUS_LIBMPV_DEV_DIR",
                    )
                val runtimeDir = mpvRuntimeDirPath.orNull?.let(::file) ?: devDir.resolve("lib")
                val header = devDir.resolve("include/mpv/client.h")
                check(header.isFile) {
                    "macOS libmpv development headers are missing: ${header.absolutePath}"
                }
                commandLine(
                    "cc",
                    "-dynamiclib",
                    "-fPIC",
                    "-O2",
                    "-Wall",
                    "-Wextra",
                    "-I${includeRoot.absolutePath}",
                    "-I${platformInclude.absolutePath}",
                    "-I${devDir.resolve("include").absolutePath}",
                    mpvJniSource.asFile.absolutePath,
                    "-L${runtimeDir.absolutePath}",
                    "-lmpv",
                    "-Wl,-rpath,@loader_path",
                    "-o",
                    mpvJniOutput.get().asFile.absolutePath,
                )
            }

            isLinuxHost -> commandLine(
                "cc",
                "-shared",
                "-fPIC",
                "-O2",
                "-Wall",
                "-Wextra",
                "-I${includeRoot.absolutePath}",
                "-I${platformInclude.absolutePath}",
                mpvJniSource.asFile.absolutePath,
                "-Wl,-rpath,\$ORIGIN",
                "-o",
                mpvJniOutput.get().asFile.absolutePath,
                "-lmpv",
            )

            else -> throw GradleException("Unsupported Nucleus desktop host: $hostOs")
        }
    }
}

val prepareNucleusPortableLinuxRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Collect the portable libmpv/Libsecret/WebKitGTK closure used by the Nucleus AppImage."
    dependsOn(buildNucleusWebLoginHelper)
    onlyIf { isLinuxHost && bundleLinuxRuntime.get() }
    inputs.file(webLoginExecutable)
    outputs.dir(portableLinuxRuntime)
    doFirst {
        portableLinuxRuntime.get().asFile.deleteRecursively()
    }
    commandLine(
        "bash",
        layout.projectDirectory.file("packaging/linux/prepare-portable-runtime.sh").asFile.absolutePath,
        portableLinuxRuntime.get().asFile.absolutePath,
        webLoginExecutable.asFile.absolutePath,
    )
}

val prepareNucleusAppResources by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stage native resources required by the Nucleus desktop runtime."
    dependsOn(buildNucleusWebLoginHelper, buildNucleusMpvJniBridge)
    if (isLinuxHost) dependsOn(prepareNucleusPortableLinuxRuntime)

    from(webLoginExecutable) {
        into("$stagedNativeResourceRoot/helpers")
        if (!isWindowsHost) {
            filePermissions { unix("755") }
        }
    }
    from(mpvJniOutput) {
        into("$stagedNativeResourceRoot/lib")
        if (!isWindowsHost) {
            filePermissions { unix("755") }
        }
    }

    mpvRuntimeDirPath.orNull?.let { configuredPath ->
        from(file(configuredPath)) {
            include("*.dll", "*.dylib", "*.so", "*.so.*")
            into("$stagedNativeResourceRoot/lib")
        }
    }

    if (isLinuxHost) {
        from(portableLinuxRuntime) {
            into(stagedNativeResourceRoot)
            onlyIf { bundleLinuxRuntime.get() }
        }
    }

    into(nucleusAppResources)

    doLast {
        val platformRoot = nucleusAppResources.get().asFile.resolve(stagedNativeResourceRoot)
        val stagedHelper = platformRoot.resolve("helpers/$webLoginExecutableName")
        val stagedMpvBridge = platformRoot.resolve("lib/$mpvJniLibraryName")
        if (!stagedHelper.isFile) {
            throw GradleException("Nucleus web login helper was not staged: ${stagedHelper.absolutePath}")
        }
        if (!isWindowsHost && !stagedHelper.canExecute()) {
            throw GradleException("Nucleus web login helper is not executable: ${stagedHelper.absolutePath}")
        }
        if (!stagedMpvBridge.isFile) {
            throw GradleException("Nucleus libmpv JNI bridge was not staged: ${stagedMpvBridge.absolutePath}")
        }
        if ((isWindowsHost || isMacHost || (isLinuxHost && bundleLinuxRuntime.get()))) {
            val runtimeNames = platformRoot.resolve("lib").listFiles().orEmpty().map(File::getName)
            val hasLibMpv = when {
                isWindowsHost -> runtimeNames.any { it.equals("libmpv-2.dll", true) || it.equals("mpv-2.dll", true) || it.equals("mpv.dll", true) }
                isMacHost -> "libmpv.dylib" in runtimeNames
                else -> runtimeNames.any { it.startsWith("libmpv.so") }
            }
            if (!hasLibMpv) {
                throw GradleException("Bundled Nucleus package is missing the libmpv runtime")
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

    testImplementation(kotlin("test"))
}

// Nucleus feeds native-image a repackaged uber JAR rather than the original dependency JARs.
// Upstream credential-secure-storage is signed, so its META-INF signature blocks no longer match
// after the merge. Strip only JAR-level signatures from this Nucleus-owned uber JAR.
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

val requestedTargetFormat = providers.gradleProperty("fuoevolve.nucleus.targetFormat")
    .orNull
    ?.trim()
    ?.lowercase()
val nucleusTargetFormats = when (requestedTargetFormat) {
    null, "all" -> arrayOf(
        TargetFormat.Msi,
        TargetFormat.Dmg,
        TargetFormat.AppImage,
        TargetFormat.Pacman,
    )
    "msi" -> arrayOf(TargetFormat.Msi)
    "dmg" -> arrayOf(TargetFormat.Dmg)
    "appimage" -> arrayOf(TargetFormat.AppImage)
    "pacman", "arch" -> arrayOf(TargetFormat.Pacman)
    else -> throw GradleException("Unsupported Nucleus target format: $requestedTargetFormat")
}

nucleus.application {
    mainClass = "org.feeluown.mobile.nucleus.NucleusMainKt"

    nativeDistributions {
        appName = "FuoEvolve"
        packageName = "FuoEvolve"
        packageVersion = desktopPackageVersion
        homepage = "https://feeluown.github.io/FuoEvolve/"
        targetFormats(*nucleusTargetFormats)
        appResourcesRootDir.set(nucleusAppResources)

        windows {
            packageName = "FuoEvolve"
        }
        macOS {
            packageName = "FuoEvolve"
            bundleID = "org.feeluown.FuoEvolve"
            appCategory = "public.app-category.music"
        }
        linux {
            packageName = "fuoevolve"
            shortcut = true
            appCategory = "AudioVideo"
            menuGroup = "AudioVideo"
            pacmanDepends = listOf(
                "gtk3",
                "libx11",
                "libxkbcommon",
                "libsecret",
                "mpv",
                "webkit2gtk-4.1",
            )
        }
    }

    graalvm {
        isEnabled.set(true)
        imageName.set("fuoevolve")
    }
}

// Compose/Nucleus consume appResources through prepareAppResources. Make the staging dependency
// explicit so Gradle validation and JVM/GraalVM pipelines see native resources deterministically.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareNucleusAppResources)
}
tasks.matching { task ->
    task.name == "run" ||
        task.name.startsWith("runGraalvm") ||
        task.name.startsWith("packageGraalvm") ||
        task.name.startsWith("createGraalvm")
}.configureEach {
    dependsOn(prepareNucleusAppResources)
}

tasks.register("printNucleusPackageVersion") {
    group = "distribution"
    doLast { println(desktopPackageVersion) }
}
