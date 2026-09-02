plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

val hostOs = System.getProperty("os.name").orEmpty().lowercase()
val isWindowsHost = hostOs.contains("windows")
val isMacHost = hostOs.contains("mac") || hostOs.contains("darwin")

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

if (isWindowsHost || isMacHost) {
    tasks.matching { it.name == "run" }.configureEach {
        if (isWindowsHost) dependsOn(buildWindowsSmtcBridge)
        if (isMacHost) dependsOn(buildMacNowPlayingBridge)
    }
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
    }
}
