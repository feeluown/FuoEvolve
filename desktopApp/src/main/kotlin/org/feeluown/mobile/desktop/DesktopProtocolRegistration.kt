package org.feeluown.mobile.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Runtime registration is needed on Windows/Linux; macOS receives the URL scheme from Info.plist. */
internal fun registerDesktopFuoProtocolHandler() {
    val launcher = packagedLauncher() ?: return
    runCatching {
        when {
            isWindows() -> registerWindowsProtocol(launcher)
            isLinux() -> registerLinuxProtocol(launcher)
        }
    }.onFailure { throwable ->
        System.err.println("FuoEvolve: unable to register fuo:// protocol: ${throwable.message}")
    }
}

private fun packagedLauncher(): Path? {
    if (isLinux()) {
        // AppImage runs from a transient mount point, while APPIMAGE points to the stable image path.
        System.getenv("APPIMAGE")
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }
            ?.takeIf { Files.isRegularFile(it) }
            ?.let { return it }
    }
    val command = ProcessHandle.current().info().command().orElse(null) ?: return null
    val path = runCatching { Paths.get(command).toAbsolutePath().normalize() }.getOrNull() ?: return null
    val fileName = path.fileName?.toString().orEmpty().lowercase()
    val looksLikeJvm = fileName in setOf("java", "java.exe", "javaw.exe")
    return path.takeIf { Files.isRegularFile(it) && !looksLikeJvm }
}

private fun registerWindowsProtocol(launcher: Path) {
    val root = "HKCU\\Software\\Classes\\fuo"
    runCommand("reg", "add", root, "/ve", "/d", "URL:FuoEvolve Protocol", "/f")
    runCommand("reg", "add", root, "/v", "URL Protocol", "/d", "", "/f")
    val command = "\"${launcher}\" \"%1\""
    runCommand("reg", "add", "$root\\shell\\open\\command", "/ve", "/d", command, "/f")
}

private fun registerLinuxProtocol(launcher: Path) {
    val home = System.getProperty("user.home").orEmpty()
    if (home.isBlank()) return
    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
        ?: Paths.get(home, ".local", "share").toString()
    val applicationsDir = Paths.get(dataHome).resolve("applications")
    Files.createDirectories(applicationsDir)
    val desktopFileName = "fuoevolve-url.desktop"
    val desktopFile = applicationsDir.resolve(desktopFileName)
    val exec = desktopExecQuote(launcher.toString())
    Files.writeString(
        desktopFile,
        """
        [Desktop Entry]
        Type=Application
        Name=FuoEvolve
        NoDisplay=true
        Exec=$exec %u
        MimeType=x-scheme-handler/fuo;
        """.trimIndent() + "\n",
        StandardCharsets.UTF_8,
    )
    runCommand("xdg-mime", "default", desktopFileName, "x-scheme-handler/fuo")
    runCatching { runCommand("update-desktop-database", applicationsDir.toString()) }
}

private fun desktopExecQuote(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun runCommand(vararg command: String) {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    check(exitCode == 0) {
        "${command.firstOrNull().orEmpty()} exited with $exitCode${output.trim().takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
private fun isLinux(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
