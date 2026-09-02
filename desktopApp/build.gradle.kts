import java.io.File
import java.util.zip.ZipFile
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

fun verifyServiceProviderInReleaseImage(
    appRoot: File,
    serviceClassName: String,
    providerClassName: String,
) {
    val jars = appRoot.walkTopDown()
        .filter { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
        .toList()
    if (jars.isEmpty()) {
        throw GradleException("No application JARs found in release image: ${appRoot.absolutePath}")
    }

    val serviceEntryName = "META-INF/services/$serviceClassName"
    val providerEntryName = providerClassName.replace('.', '/') + ".class"
    var serviceDeclaresProvider = false
    var providerClassPresent = false

    jars.forEach { jar ->
        ZipFile(jar).use { zip ->
            if (zip.getEntry(providerEntryName) != null) {
                providerClassPresent = true
            }
            zip.getEntry(serviceEntryName)?.let { entry ->
                val declaresProvider = zip.getInputStream(entry).bufferedReader().use { reader ->
                    reader.lineSequence()
                        .map { line -> line.substringBefore('#').trim() }
                        .any { line -> line == providerClassName }
                }
                serviceDeclaresProvider = serviceDeclaresProvider || declaresProvider
            }
        }
    }

    if (!serviceDeclaresProvider) {
        throw GradleException(
            "Release image is missing ServiceLoader declaration for $providerClassName in $serviceEntryName",
        )
    }
    if (!providerClassPresent) {
        throw GradleException(
            "Release shrink removed ServiceLoader provider class $providerClassName",
        )
    }
}

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

val defaultPackageProfile = if (isLinuxHost) "system" else "bundled"
val desktopPackageProfile = providers.environmentVariable("FUOEVOLVE_PACKAGE_PROFILE")
    .orElse(providers.gradleProperty("fuoevolve.packageProfile"))
    .orElse(defaultPackageProfile)
    .map { value -> value.lowercase() }
    .get()
if (desktopPackageProfile !in setOf("bundled", "system")) {
    throw GradleException(
        "Unsupported desktop package profile '$desktopPackageProfile'. Use bundled or system.",
    )
}
if (!isLinuxHost && desktopPackageProfile != "bundled") {
    throw GradleException(
        "Windows and macOS packages must use the bundled native dependency profile.",
    )
}
val bundlesLibMpv = desktopPackageProfile == "bundled"

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

val desktopWebLoginExecutableName = if (isWindowsHost) "fuoevolve-web-login.exe" else "fuoevolve-web-login"
val desktopWebLoginExecutable = layout.projectDirectory.file(
    "native/web-login/target/release/$desktopWebLoginExecutableName",
)
val buildDesktopWebLoginHelper by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the isolated system-WebView login helper used by the desktop runtime."
    workingDir(layout.projectDirectory.dir("native/web-login"))
    commandLine("cargo", "build", "--release")
}

if (isWindowsHost || isMacHost || isLinuxHost) {
    tasks.matching { it.name == "run" }.configureEach {
        dependsOn(buildDesktopWebLoginHelper)
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
    description = "Stage desktop native bridges, web login helper and optional bundled libmpv runtime."
    inputs.property("packageProfile", desktopPackageProfile)
    if (bundlesLibMpv) {
        inputs.property("libmpvBundle", packageLibMpvDirPath.orElse("<unset>"))
        from(packageLibMpvDir) {
            into("$packagedNativeRoot/mpv")
        }
    }
    dependsOn(buildDesktopWebLoginHelper)
    from(desktopWebLoginExecutable) {
        into("$packagedNativeRoot/helpers")
    }
    into(packagedResourcesRoot)

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
        if (!desktopWebLoginExecutable.asFile.isFile) {
            throw GradleException("Desktop web login helper was not built: ${desktopWebLoginExecutable.asFile}")
        }
        if (!bundlesLibMpv) return@doFirst
        val source = packageLibMpvDir.orNull
            ?: throw GradleException(
                "Bundled desktop packaging requires FUOEVOLVE_PACKAGE_LIBMPV_DIR " +
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
    "packagePkg",
    "packageReleasePkg",
    "packageMsi",
    "packageReleaseMsi",
    "packageExe",
    "packageReleaseExe",
)
tasks.matching { it.name in nativePackageTaskNames }.configureEach {
    dependsOn(prepareDesktopPackageResources)
}
// Compose's app resources task consumes appResourcesRootDir. Make that relationship explicit
// so Gradle 9 task validation sees the staged native resources as a declared dependency.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareDesktopPackageResources)
}

// ProGuard cannot infer java.util.ServiceLoader entry points from META-INF/services resources.
// Verify the final minified image, not just the unshrunk compile classpath, so packaging fails if
// a provider declaration survives while its implementation class was removed.
tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        verifyServiceProviderInReleaseImage(
            appRoot = layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile,
            serviceClassName = "io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider",
            providerClassName = "io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider",
        )
    }
}

tasks.register("printDesktopPackageVersion") {
    group = "distribution"
    doLast { println(desktopPackageVersion) }
}

tasks.register("printDesktopPackageProfile") {
    group = "distribution"
    doLast { println(desktopPackageProfile) }
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
        jvmArgs += "-Dfuoevolve.appdir=$appDir"
        jvmArgs += "-Djna.library.path=" + listOf(
            "$appDir/resources/native/mpv",
            "$appDir/resources/native/bridges",
        ).joinToString(File.pathSeparator)

        buildTypes.release.proguard {
            // Phase 1 size optimization: remove unused JVM/Compose dependency code while keeping
            // obfuscation disabled so stack traces and native/reflection boundaries stay readable.
            optimize.set(true)
            obfuscate.set(false)
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            // These dependencies are reached reflectively by desktop-only libraries and are not
            // always discovered by jdeps: JNA/credential storage uses sun.misc.Unsafe, while
            // dbus-java uses com.sun.security.auth.module.UnixSystem for the MPRIS Unix identity.
            modules("jdk.unsupported", "jdk.security.auth")

            when {
                isWindowsHost -> targetFormats(TargetFormat.Msi, TargetFormat.Exe)
                isMacHost -> targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
                // Linux distro packages are produced from createReleaseDistributable by dedicated
                // scripts so dependency metadata can use each distribution's package manager.
                isLinuxHost -> Unit
            }
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
