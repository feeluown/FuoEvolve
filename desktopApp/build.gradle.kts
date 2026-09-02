import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

fun gitOutput(vararg args: String): String? = runCatching {
    val output = providers.exec {
        workingDir = rootProject.projectDir
        commandLine("git", *args)
    }.standardOutput.asText.get().trim()
    output.takeIf { it.isNotBlank() }
}.getOrNull()

val desktopPackageVersion = gitOutput("describe", "--tags", "--match", "[0-9]*", "--abbrev=0")
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

val buildWindowsSmtcBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Rust Windows SMTC bridge used by the desktop runtime."
    onlyIf { isWindowsHost }
    workingDir(layout.projectDirectory.dir("native/windows-smtc"))
    commandLine("cargo", "build", "--release")
}

val buildMacNowPlayingBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Rust macOS Now Playing bridge used by the desktop runtime."
    onlyIf { isMacHost }
    workingDir(layout.projectDirectory.dir("native/macos-now-playing"))
    commandLine("cargo", "build", "--release")
}

val buildLinuxTrayBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Rust Linux StatusNotifier tray bridge used by the desktop runtime."
    onlyIf { isLinuxHost }
    workingDir(layout.projectDirectory.dir("native/linux-tray"))
    commandLine("cargo", "build", "--release")
}

if (isWindowsHost || isMacHost || isLinuxHost) {
    tasks.matching { it.name == "run" }.configureEach {
        if (isWindowsHost) dependsOn(buildWindowsSmtcBridge)
        if (isMacHost) dependsOn(buildMacNowPlayingBridge)
        if (isLinuxHost) dependsOn(buildLinuxTrayBridge)
    }
}

val packageLibMpvDirPath = providers.environmentVariable("FUOEVOLVE_PACKAGE_LIBMPV_DIR")
    .orElse(providers.gradleProperty("fuoevolve.packageLibmpvDir"))
val packageLibMpvDir = packageLibMpvDirPath.map(::file)
val packagedResourcesRoot = layout.buildDirectory.dir("desktop-package-resources")
val packagedNativeRoot = "$packageResourceOs/native"
val expectedLibMpvNames = when {
    isWindowsHost -> listOf("mpv-2.dll", "libmpv-2.dll", "mpv.dll")
    isMacHost -> listOf("libmpv.dylib")
    else -> listOf("libmpv.so.2", "libmpv.so")
}

val prepareDesktopPackageResources by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stage relocatable libmpv and platform bridges for native desktop packages."
    inputs.property("libmpvBundle", packageLibMpvDirPath.orElse("<unset>"))
    into(packagedResourcesRoot)

    from(packageLibMpvDir) {
        into("$packagedNativeRoot/mpv")
    }

    when {
        isWindowsHost -> {
            dependsOn(buildWindowsSmtcBridge)
            from(layout.projectDirectory.file("native/windows-smtc/target/release/fuoevolve_smtc_bridge.dll")) {
                into("$packagedNativeRoot/bridges")
            }
        }

        isMacHost -> {
            dependsOn(buildMacNowPlayingBridge)
            from(layout.projectDirectory.file("native/macos-now-playing/target/release/libfuoevolve_now_playing_bridge.dylib")) {
                into("$packagedNativeRoot/bridges")
            }
        }

        isLinuxHost -> {
            dependsOn(buildLinuxTrayBridge)
            from(layout.projectDirectory.file("native/linux-tray/target/release/libfuoevolve_linux_tray_bridge.so")) {
                into("$packagedNativeRoot/bridges")
            }
        }
    }

    doFirst {
        val source = packageLibMpvDir.orNull
            ?: throw GradleException(
                "Native desktop packaging requires FUOEVOLVE_PACKAGE_LIBMPV_DIR " +
                    "(or -Pfuoevolve.packageLibmpvDir) pointing to a relocatable libmpv runtime bundle.",
            )
        if (!source.isDirectory) {
            throw GradleException("libmpv bundle directory does not exist: ${source.absolutePath}")
        }
        if (expectedLibMpvNames.none { name -> source.resolve(name).isFile }) {
            throw GradleException(
                "libmpv bundle ${source.absolutePath} must contain one of " +
                    expectedLibMpvNames.joinToString(),
            )
        }
    }
}

val nativePackageTaskNames = setOf(
    "createDistributable",
    "createReleaseDistributable",
    "runDistributable",
    "runReleaseDistributable",
    "packageDistributionForCurrentOS",
    "packageReleaseDistributionForCurrentOS",
    "packageDmg",
    "packageReleaseDmg",
    "packageMsi",
    "packageReleaseMsi",
    "packageDeb",
    "packageReleaseDeb",
)
tasks.matching { it.name in nativePackageTaskNames }.configureEach {
    dependsOn(prepareDesktopPackageResources)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":playback:api"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.credential.secure.storage)
    implementation(libs.jaudiotagger)
    implementation(libs.dbus.java.core)
    runtimeOnly(libs.dbus.java.transport.native.unixsocket)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "org.feeluown.mobile.desktop.MainKt"
        val appDir = "\$APPDIR"
        jvmArgs += "-Djna.library.path=" + listOf(
            "$appDir/resources/native/mpv",
            "$appDir/resources/native/bridges",
        ).joinToString(File.pathSeparator)

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "FuoEvolve"
            packageVersion = desktopPackageVersion
            description = "A cross-platform multi-source music player based on FeelUOwn"
            vendor = "FeelUOwn"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(packagedResourcesRoot)

            windows {
                perUserInstall = true
                dirChooser = true
                menuGroup = "FuoEvolve"
                upgradeUuid = "2c663b22-3837-4f6b-a5d0-74cba65a6c31"
            }
            macOS {
                bundleID = "org.feeluown.mobile.desktop"
                dockName = "FuoEvolve"
                appCategory = "public.app-category.music"
            }
            linux {
                packageName = "fuoevolve"
                menuGroup = "AudioVideo"
                appCategory = "sound"
            }
        }
    }
}
