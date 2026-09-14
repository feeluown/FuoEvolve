import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.NativeImageMarch
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.WriteProperties
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

private val desktopAppIcon = rootProject.file(
    "androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
)
private val desktopWindowsIcon = layout.projectDirectory.file("packaging/icons/fuoevolve.ico")
private val desktopMacIcon = layout.projectDirectory.file("packaging/icons/fuoevolve.icns")

fun gitOutput(vararg args: String): String? = providers.exec {
    workingDir = rootProject.projectDir
    isIgnoreExitValue = true
    commandLine("git", *args)
}.standardOutput.asText.get().trim().takeIf(String::isNotBlank)

private val versionPattern = Regex("\\d+\\.\\d+\\.\\d+")
private val exactTaggedVersion = gitOutput(
    "describe",
    "--tags",
    "--exact-match",
    "--match",
    "[0-9]*",
    "HEAD",
)?.let { tag -> versionPattern.find(tag)?.value }
private val latestTaggedVersion = gitOutput("describe", "--tags", "--match", "[0-9]*", "--abbrev=0")
    ?.let { tag -> versionPattern.find(tag)?.value }

val desktopPackageVersion = providers.gradleProperty("fuoevolve.packageVersion")
    .orElse(providers.environmentVariable("FUOEVOLVE_PACKAGE_VERSION"))
    .orNull
    ?.takeIf(String::isNotBlank)
    ?: exactTaggedVersion
    ?: latestTaggedVersion
    ?: "0.1.0"
val desktopCommitSha = providers.environmentVariable("FUOEVOLVE_COMMIT_SHA")
    .orElse(providers.environmentVariable("GITHUB_SHA"))
    .orNull
    ?.takeIf(String::isNotBlank)
    ?: gitOutput("rev-parse", "HEAD")
    ?: "unknown"
val desktopVersionChannel = providers.environmentVariable("FUOEVOLVE_DESKTOP_CHANNEL")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: if (exactTaggedVersion != null) "stable" else "canary"
val desktopVersionLabel = providers.environmentVariable("FUOEVOLVE_DESKTOP_VERSION_LABEL")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: if (desktopVersionChannel == "stable") {
        desktopPackageVersion
    } else {
        "$desktopPackageVersion-canary+${desktopCommitSha.take(8)}"
    }

val generatedDesktopVersionResourceDir = layout.buildDirectory.dir("generated/resources/desktopVersion")
val generateDesktopVersionInfo by tasks.registering(WriteProperties::class) {
    group = "build"
    description = "Generate desktop version metadata embedded into the application."
    destinationFile.set(
        generatedDesktopVersionResourceDir.map { directory ->
            directory.file("fuoevolve-desktop-version.properties")
        },
    )
    property("versionLabel", desktopVersionLabel)
    property("packageVersion", desktopPackageVersion)
    property("channel", desktopVersionChannel)
    property("commitSha", desktopCommitSha)
}

sourceSets {
    named("main") {
        resources.srcDir(desktopAppIcon.parentFile)
        resources.srcDir(generatedDesktopVersionResourceDir)
    }
}
tasks.named("processResources").configure {
    dependsOn(generateDesktopVersionInfo)
}
// Nucleus scans main source-set resources before native-image packaging instead of consuming
// processResources, so wire the generated version resource into that task graph explicitly.
tasks.matching { it.name == "generateGraalvmProjectResourceMetadata" }.configureEach {
    dependsOn(generateDesktopVersionInfo)
}

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
val audioFingerprintExecutableName =
    if (isWindowsHost) "fuoevolve-audio-fingerprint.exe" else "fuoevolve-audio-fingerprint"
val audioFingerprintProjectDir = layout.projectDirectory.dir("native/audio-fingerprint")
val audioFingerprintExecutable =
    audioFingerprintProjectDir.file("target/release/$audioFingerprintExecutableName")
val audioFingerprintWasm = rootProject.file("shared/src/commonMain/resources/audio_recognition/afp.wasm")
val desktopAppImageLauncher = layout.projectDirectory.file("packaging/linux/AppRun")
val audioCaptureLibraryName = when {
    isWindowsHost -> "fuoevolve_audio_capture.dll"
    isMacHost -> "libfuoevolve_audio_capture.dylib"
    else -> "libfuoevolve_audio_capture.so"
}
val audioCaptureProjectDir = layout.projectDirectory.dir("native/audio-capture")
val audioCaptureLibrary = audioCaptureProjectDir.file("target/release/$audioCaptureLibraryName")
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

val buildNucleusAudioFingerprintHelper by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the headless audio fingerprint helper used by the Nucleus desktop runtime."
    workingDir(audioFingerprintProjectDir)
    inputs.files(
        audioFingerprintProjectDir.file("Cargo.toml"),
        audioFingerprintProjectDir.dir("src"),
        audioFingerprintWasm,
    )
    outputs.file(audioFingerprintExecutable)
    commandLine("cargo", "build", "--release")
}

val buildNucleusAudioCaptureLibrary by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the system-output audio capture library used by the Nucleus desktop runtime."
    workingDir(audioCaptureProjectDir)
    inputs.files(
        audioCaptureProjectDir.file("Cargo.toml"),
        audioCaptureProjectDir.file("Cargo.lock"),
        audioCaptureProjectDir.dir("src"),
    )
    outputs.file(audioCaptureLibrary)
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
                    "-fuse-ld=lld",
                    "-I${includeRoot.absolutePath}",
                    "-I${platformInclude.absolutePath}",
                    "-I${devDir.resolve("include").absolutePath}",
                    mpvJniSource.asFile.absolutePath,
                    importLibrary.absolutePath,
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
    description = "Collect the portable Nucleus libmpv/Libsecret/WebKitGTK/audio closure used by the AppImage."
    dependsOn(buildNucleusAudioFingerprintHelper, buildNucleusAudioCaptureLibrary)
    onlyIf { isLinuxHost && bundleLinuxRuntime.get() }
    inputs.files(
        audioFingerprintExecutable,
        audioCaptureLibrary,
        layout.projectDirectory.file("packaging/linux/prepare-portable-runtime.sh"),
    )
    outputs.dir(portableLinuxRuntime)
    doFirst {
        portableLinuxRuntime.get().asFile.deleteRecursively()
    }
    commandLine(
        "bash",
        layout.projectDirectory.file("packaging/linux/prepare-portable-runtime.sh").asFile.absolutePath,
        portableLinuxRuntime.get().asFile.absolutePath,
        audioFingerprintExecutable.asFile.absolutePath,
        audioCaptureLibrary.asFile.absolutePath,
    )
}

val prepareNucleusAppResources by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stage native resources required by the Nucleus desktop runtime."
    dependsOn(buildNucleusAudioFingerprintHelper, buildNucleusAudioCaptureLibrary, buildNucleusMpvJniBridge)
    if (isLinuxHost) dependsOn(prepareNucleusPortableLinuxRuntime)

    from(audioFingerprintExecutable) {
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

    if (!(isLinuxHost && bundleLinuxRuntime.get())) {
        from(audioCaptureLibrary) {
            into("$stagedNativeResourceRoot/audio")
            if (!isWindowsHost) {
                filePermissions { unix("755") }
            }
        }
    }

    mpvRuntimeDirPath.orNull?.let { configuredPath ->
        from(file(configuredPath)) {
            include("*.dll", "*.dylib", "*.so", "*.so.*")
            into("$stagedNativeResourceRoot/lib")
        }
    }

    if (isLinuxHost && bundleLinuxRuntime.get()) {
        from(portableLinuxRuntime) {
            into(stagedNativeResourceRoot)
        }
        from(desktopAppImageLauncher) {
            into(packageResourceOs)
            filePermissions { unix("755") }
        }
    }

    into(nucleusAppResources)

    doLast {
        val platformRoot = nucleusAppResources.get().asFile.resolve(stagedNativeResourceRoot)
        val stagedHelper = platformRoot.resolve("helpers/$audioFingerprintExecutableName")
        val stagedMpvBridge = platformRoot.resolve("lib/$mpvJniLibraryName")
        val stagedAudioCapture = platformRoot.resolve("audio/$audioCaptureLibraryName")
        if (!stagedHelper.isFile) {
            throw GradleException("Nucleus audio fingerprint helper was not staged: ${stagedHelper.absolutePath}")
        }
        if (!isWindowsHost && !stagedHelper.canExecute()) {
            throw GradleException("Nucleus audio fingerprint helper is not executable: ${stagedHelper.absolutePath}")
        }
        if (isLinuxHost && bundleLinuxRuntime.get()) {
            val stagedAppImageLauncher = nucleusAppResources.get().asFile.resolve("$packageResourceOs/AppRun")
            if (!stagedAppImageLauncher.isFile || !stagedAppImageLauncher.canExecute()) {
                throw GradleException("Nucleus AppImage launcher was not staged: ${stagedAppImageLauncher.absolutePath}")
            }
        }
        if (!stagedMpvBridge.isFile) {
            throw GradleException("Nucleus libmpv JNI bridge was not staged: ${stagedMpvBridge.absolutePath}")
        }
        if (!stagedAudioCapture.isFile) {
            throw GradleException("Nucleus system audio capture library was not staged: ${stagedAudioCapture.absolutePath}")
        }
        if (isWindowsHost || isMacHost || (isLinuxHost && bundleLinuxRuntime.get())) {
            val runtimeNames = platformRoot.resolve("lib").listFiles().orEmpty().map(File::getName)
            val hasLibMpv = when {
                isWindowsHost -> runtimeNames.any {
                    it.equals("libmpv-2.dll", true) ||
                        it.equals("mpv-2.dll", true) ||
                        it.equals("mpv.dll", true)
                }
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
    implementation(libs.compose.material3.expressive)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)

    implementation("dev.nucleusframework:nucleus.nucleus-application:2.5.15")
    implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.5.15")
    implementation("dev.nucleusframework:nucleus.graalvm-runtime:2.5.15")
    implementation("dev.nucleusframework:nucleus.media-control:2.5.15")
    implementation("dev.nucleusframework:nucleus.notification-common:2.5.15")
    implementation("dev.nucleusframework:nucleus.launcher-windows:2.5.15")
    implementation("dev.nucleusframework:composenativetray:2.1.6")

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
            targetNativeResourcePrefixes()?.let { targetPrefixes ->
                val removedCount = filterTargetNativeResourcesInUberJar(uberJar, targetPrefixes)
                if (removedCount > 0) {
                    logger.lifecycle(
                        "Filtered $removedCount non-target native resources from ${uberJar.name}",
                    )
                }
            }
        }
    }

val requestedTargetFormat = providers.gradleProperty("fuoevolve.nucleus.targetFormat")
    .orElse(providers.environmentVariable("FUOEVOLVE_NUCLEUS_TARGET_FORMAT"))
    .orNull
    ?.trim()
    ?.lowercase()
val nucleusTargetFormats = when (requestedTargetFormat) {
    null, "all" -> arrayOf(
        TargetFormat.Nsis,
        TargetFormat.Dmg,
        TargetFormat.Deb,
        TargetFormat.Rpm,
        TargetFormat.AppImage,
        TargetFormat.Pacman,
    )
    "nsis" -> arrayOf(TargetFormat.Nsis)
    "dmg" -> arrayOf(TargetFormat.Dmg)
    "deb" -> arrayOf(TargetFormat.Deb)
    "rpm" -> arrayOf(TargetFormat.Rpm)
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
        compressionLevel = CompressionLevel.Ultra
        targetFormats(*nucleusTargetFormats)
        appResourcesRootDir.set(nucleusAppResources)
        protocol("FuoEvolve", "fuo")
        fileAssociation(
            mimeType = "application/x-fuo",
            extension = "fuo",
            description = "FeelUOwn Playlist",
        )

        windows {
            packageName = "FuoEvolve"
            iconFile.set(desktopWindowsIcon)
        }
        macOS {
            packageName = "FuoEvolve"
            bundleID = "org.feeluown.mobile.desktop"
            appCategory = "public.app-category.music"
            iconFile.set(desktopMacIcon)
        }
        linux {
            packageName = "fuoevolve"
            shortcut = true
            appCategory = "AudioVideo"
            menuGroup = "AudioVideo"
            iconFile.set(desktopAppIcon)
            debMaintainer = "FuoEvolve Maintainers <6873988+BruceZhang1993@users.noreply.github.com>"
            debDepends = listOf(
                "libgtk-3-0t64",
                "libx11-6",
                "libxkbcommon0",
                "libsecret-1-0",
                "libmpv2",
                "libwebkit2gtk-4.1-0",
                "libasound2t64",
                "libpipewire-0.3-0t64",
                "libpulse0",
            )
            rpmLicenseType = "GPL-3.0-or-later"
            rpmRequires = listOf(
                "gtk3",
                "libX11",
                "libxkbcommon",
                "libsecret",
                "mpv-libs",
                "webkit2gtk4.1",
                "alsa-lib",
                "pipewire-libs",
                "pulseaudio-libs",
            )
            pacmanDepends = listOf(
                "gtk3",
                "libx11",
                "libxkbcommon",
                "libsecret",
                "mpv",
                "webkit2gtk-4.1",
                "alsa-lib",
                "pipewire",
                "libpulse",
            )
        }
    }

    graalvm {
        isEnabled.set(true)
        imageName.set("fuoevolve")
        march.set(NativeImageMarch.COMPATIBILITY)
        // Keep native-image builds bounded on developer machines with other desktop apps running.
        buildArgs.addAll("-J-Xmx6g", "--parallelism=2")
    }
}

private val nativeResourceRoots = listOf(
    "com/sun/jna/",
    "composetray/native/",
    "jni/",
    "nucleus/native/",
    "org/sqlite/native/",
)

private val linuxX64SkikoLibrary = "libskiko-linux-x64.so"

private fun isLinuxX64NativeTarget(): Boolean {
    val osName = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    return osName.contains("linux") &&
        (architecture == "amd64" || architecture == "x86_64" || architecture == "x64")
}

private fun targetNativeResourcePrefixes(): List<String>? {
    val osName = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    val isX64 = architecture == "amd64" || architecture == "x86_64" || architecture == "x64"
    val isArm64 = architecture == "aarch64" || architecture == "arm64"

    return when {
        osName.contains("linux") && isX64 -> listOf(
            "com/sun/jna/linux-x86-64/",
            "composetray/native/linux-x64/",
            "jni/linux_x64/",
            "nucleus/native/linux-x64/",
            "org/sqlite/native/Linux/x86_64/",
        )
        osName.contains("linux") && isArm64 -> listOf(
            "com/sun/jna/linux-aarch64/",
            "composetray/native/linux-aarch64/",
            "jni/linux_aarch64/",
            "nucleus/native/linux-aarch64/",
            "org/sqlite/native/Linux/aarch64/",
        )
        osName.contains("mac") && isX64 -> listOf(
            "com/sun/jna/darwin-x86-64/",
            "composetray/native/darwin-x64/",
            "jni/macos_x64/",
            "nucleus/native/darwin-x64/",
            "org/sqlite/native/Mac/x86_64/",
        )
        osName.contains("mac") && isArm64 -> listOf(
            "com/sun/jna/darwin-aarch64/",
            "composetray/native/darwin-aarch64/",
            "jni/macos_aarch64/",
            "nucleus/native/darwin-aarch64/",
            "org/sqlite/native/Mac/aarch64/",
        )
        osName.contains("windows") && isX64 -> listOf(
            "com/sun/jna/win32-x86-64/",
            "composetray/native/win32-x64/",
            "jni/windows_x64/",
            "nucleus/native/win32-x64/",
            "org/sqlite/native/Windows/x86_64/",
        )
        else -> null
    }
}

@Suppress("UNCHECKED_CAST")
private fun filterTargetNativeResourceText(
    metadataText: String,
    targetPrefixes: List<String>,
): Pair<String, Int> {
    val metadata = JsonSlurper().parseText(metadataText) as? MutableMap<String, Any?>
        ?: return metadataText to 0
    val resources = metadata["resources"] as? MutableList<MutableMap<String, Any?>>
        ?: return metadataText to 0
    val originalCount = resources.size
    resources.removeAll { resource ->
        val glob = (resource["glob"] ?: resource["pattern"]) as? String
            ?: return@removeAll false
        glob == "nucleus/**" ||
            (isLinuxX64NativeTarget() && glob == linuxX64SkikoLibrary) ||
            (nativeResourceRoots.any(glob::startsWith) && targetPrefixes.none(glob::startsWith))
    }
    val removedCount = originalCount - resources.size
    if (removedCount == 0) return metadataText to 0
    return JsonOutput.prettyPrint(JsonOutput.toJson(metadata)) + "\n" to removedCount
}

private fun filterTargetNativeResources(metadataFile: File, targetPrefixes: List<String>): Int {
    if (!metadataFile.isFile) return 0

    val originalText = metadataFile.readText()
    val (filteredText, removedCount) = filterTargetNativeResourceText(originalText, targetPrefixes)
    if (removedCount > 0) metadataFile.writeText(filteredText)
    return removedCount
}

private fun filterTargetNativeResourcesInUberJar(uberJar: File, targetPrefixes: List<String>): Int {
    if (!uberJar.isFile) return 0

    val temporaryJar = File.createTempFile("${uberJar.name}.filtered-", ".jar", uberJar.parentFile)
    var changed = false
    var removedCount = 0
    val composableTrayPrefix = targetPrefixes.first { it.startsWith("composetray/native/") }

    try {
        JarFile(uberJar).use { sourceJar ->
            JarOutputStream(temporaryJar.outputStream().buffered()).use { targetJar ->
                val entries = sourceJar.entries()
                while (entries.hasMoreElements()) {
                    val sourceEntry = entries.nextElement()
                    val targetEntry = JarEntry(sourceEntry.name)
                    if (sourceEntry.time >= 0) targetEntry.time = sourceEntry.time
                    targetJar.putNextEntry(targetEntry)

                    when {
                        sourceEntry.isDirectory -> Unit
                        sourceEntry.name.endsWith("reachability-metadata.json") ||
                            sourceEntry.name.endsWith("resource-config.json") -> {
                            val originalText = sourceJar.getInputStream(sourceEntry).bufferedReader().use { it.readText() }
                            val (filteredText, removed) = filterTargetNativeResourceText(
                                originalText,
                                targetPrefixes,
                            )
                            targetJar.write(filteredText.toByteArray())
                            removedCount += removed
                            changed = changed || removed > 0
                        }
                        sourceEntry.name.endsWith("native-image.properties") -> {
                            val originalText = sourceJar.getInputStream(sourceEntry).bufferedReader().use { it.readText() }
                            val filteredText = originalText.replace(
                                "-H:IncludeResources=composetray/native/.*",
                                "-H:IncludeResources=${composableTrayPrefix}.*",
                            )
                            targetJar.write(filteredText.toByteArray())
                            changed = changed || filteredText != originalText
                        }
                        else -> sourceJar.getInputStream(sourceEntry).use { it.copyTo(targetJar) }
                    }
                    targetJar.closeEntry()
                }
            }
        }

        if (changed) {
            Files.move(
                temporaryJar.toPath(),
                uberJar.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } else {
            Files.deleteIfExists(temporaryJar.toPath())
        }
    } catch (exception: Exception) {
        Files.deleteIfExists(temporaryJar.toPath())
        throw exception
    }
    return removedCount
}

private fun filterTargetNativeResourcesInDirectory(
    metadataRoot: File,
    targetPrefixes: List<String>,
): Int {
    if (!metadataRoot.isDirectory) return 0

    var removedCount = 0
    metadataRoot.walkTopDown()
        .filter { file ->
            file.isFile &&
                (file.name == "reachability-metadata.json" || file.name == "resource-config.json")
        }
        .forEach { metadataFile ->
            removedCount += filterTargetNativeResources(metadataFile, targetPrefixes)
        }
    return removedCount
}

tasks.configureEach {
    val metadataKind = when (name) {
        "analyzeGraalvmStaticMetadata" -> "staticAnalysis"
        "filterGraalvmLibraryMetadata" -> "libraryMetadata"
        "generateGraalvmProjectResourceMetadata" -> "projectResources"
        else -> null
    }
    if (metadataKind != null) {
        doLast {
            val targetPrefixes = targetNativeResourcePrefixes()
            if (targetPrefixes == null) {
                logger.lifecycle(
                    "Native resource filtering skipped for unsupported target: " +
                        "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
                )
            } else {
                val metadataRoot = layout.buildDirectory
                    .dir("compose/tmp/main/graalvm")
                    .get()
                    .asFile
                val metadataFile = metadataRoot.resolve(
                    "$metadataKind/reachability-metadata.json",
                )
                val removedCount = filterTargetNativeResources(metadataFile, targetPrefixes)
                if (removedCount > 0) {
                    logger.lifecycle(
                        "Filtered $removedCount non-target native resources from " +
                            metadataFile.absolutePath,
                    )
                }
            }
        }
    }
    if (name == "resolveGraalvmReachabilityMetadata") {
        doLast {
            val targetPrefixes = targetNativeResourcePrefixes()
            if (targetPrefixes == null) {
                logger.lifecycle(
                    "Native resource filtering skipped for unsupported target: " +
                        "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
                )
            } else {
                val metadataRoot = layout.buildDirectory
                    .dir("compose/tmp/main/graalvm/metadataRepository")
                    .get()
                    .asFile
                val removedCount = filterTargetNativeResourcesInDirectory(metadataRoot, targetPrefixes)
                if (removedCount > 0) {
                    logger.lifecycle(
                        "Filtered $removedCount non-target native resources from " +
                            metadataRoot.absolutePath,
                    )
                }
            }
        }
    }
}

// Nucleus 2.5.15 does not apply macOS.infoPlist.extraKeysRawXml to its GraalVM bundle.
// Patch the plist immediately after Nucleus copies it into Contents; the bundle codesign task
// depends on this Copy task, so the final signature covers the patched permission metadata.
if (isMacHost) {
    tasks.withType<Copy>()
        .matching { task -> task.name.contains("graalvmInfoPlist", ignoreCase = true) }
        .configureEach {
            doLast {
                val plist = destinationDir.resolve("Info.plist")
                check(plist.isFile) { "Nucleus GraalVM Info.plist was not copied: ${plist.absolutePath}" }
                listOf(
                    "NSMicrophoneUsageDescription" to "FuoEvolve 使用麦克风进行听歌识曲。",
                    "NSAudioCaptureUsageDescription" to "FuoEvolve 使用系统音频进行听歌识曲。",
                ).forEach { (key, value) ->
                    val setCommand = "Set :$key $value"
                    val setResult = providers.exec {
                        isIgnoreExitValue = true
                        commandLine("/usr/libexec/PlistBuddy", "-c", setCommand, plist.absolutePath)
                    }.result.get()
                    if (setResult.exitValue != 0) {
                        providers.exec {
                            commandLine(
                                "/usr/libexec/PlistBuddy",
                                "-c",
                                "Add :$key string $value",
                                plist.absolutePath,
                            )
                        }.result.get().assertNormalExitValue()
                    }
                }
            }
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

tasks.register("printDesktopPackageVersion") {
    group = "distribution"
    doLast { println(desktopPackageVersion) }
}