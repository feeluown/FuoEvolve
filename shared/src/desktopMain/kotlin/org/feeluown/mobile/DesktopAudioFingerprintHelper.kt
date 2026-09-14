package org.feeluown.mobile

import java.io.File
import java.io.Reader

internal fun resolveDesktopAudioFingerprintHelper(): File? {
    // Keep the legacy executable/resource name for package compatibility. The binary is now a
    // headless Rust/Wasmi fingerprint runtime; it no longer contains a WebView or login implementation.
    val executableName = desktopAudioFingerprintExecutableName()
    val appDir = System.getProperty("fuoevolve.appdir")
        ?.takeIf { it.isNotBlank() && !it.contains("\$APPDIR") }
        ?.let(::File)
    val composeResourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })

    val directCandidates = buildList {
        if (composeResourcesDir != null) {
            add(File(composeResourcesDir, "native/helpers/$executableName"))
        }
        if (appDir != null) {
            add(File(appDir, "resources/native/helpers/$executableName"))
        }
        add(File(userDir, "desktopApp/native/web-login/target/release/$executableName"))
        add(File(userDir, "native/web-login/target/release/$executableName"))
    }
    directCandidates.firstOrNull(::isUsableDesktopExecutable)?.let { return it }

    return sequenceOf(composeResourcesDir, appDir)
        .filterNotNull()
        .filter(File::isDirectory)
        .flatMap { root ->
            root.walkTopDown()
                .maxDepth(6)
                .filter { candidate ->
                    candidate.name == executableName && isUsableDesktopExecutable(candidate)
                }
        }
        .firstOrNull()
}

internal fun readDesktopAudioFingerprintDiagnosticTail(reader: Reader): String {
    val tail = StringBuilder()
    val buffer = CharArray(DIAGNOSTIC_BUFFER_CHARS)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        tail.append(buffer, 0, count)
        if (tail.length > MAX_DIAGNOSTIC_CHARS) {
            tail.delete(0, tail.length - MAX_DIAGNOSTIC_CHARS)
        }
    }
    return tail.toString()
}

private fun desktopAudioFingerprintExecutableName(): String =
    if (isDesktopWindows()) "fuoevolve-web-login.exe" else "fuoevolve-web-login"

private fun isUsableDesktopExecutable(candidate: File): Boolean =
    candidate.isFile && (isDesktopWindows() || candidate.canExecute())

internal fun isDesktopWindows(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

private const val MAX_DIAGNOSTIC_CHARS = 8 * 1024
private const val DIAGNOSTIC_BUFFER_CHARS = 1024
