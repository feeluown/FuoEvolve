package org.feeluown.mobile

import java.io.File
import java.io.Reader

internal fun resolveDesktopWebViewHelper(): File? {
    val executableName = desktopWebViewHelperExecutableName()
    val appDir = System.getProperty("fuoevolve.appdir")
        ?.takeIf { it.isNotBlank() && !it.contains("\$APPDIR") }
        ?.let(::File)
    val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })

    val directCandidates = buildList {
        if (appDir != null) {
            add(File(appDir, "resources/native/helpers/$executableName"))
        }
        add(File(userDir, "desktopApp/native/web-login/target/release/$executableName"))
        add(File(userDir, "native/web-login/target/release/$executableName"))
    }
    directCandidates.firstOrNull(::isUsableDesktopWebViewHelper)?.let { return it }

    return appDir
        ?.takeIf { it.isDirectory }
        ?.walkTopDown()
        ?.maxDepth(6)
        ?.firstOrNull { candidate ->
            candidate.name == executableName && isUsableDesktopWebViewHelper(candidate)
        }
}

internal fun readDesktopWebViewHelperDiagnosticTail(reader: Reader): String {
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

private fun desktopWebViewHelperExecutableName(): String =
    if (isDesktopWindows()) "fuoevolve-web-login.exe" else "fuoevolve-web-login"

private fun isUsableDesktopWebViewHelper(candidate: File): Boolean =
    candidate.isFile && (isDesktopWindows() || candidate.canExecute())

internal fun isDesktopWindows(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

private const val MAX_DIAGNOSTIC_CHARS = 8 * 1024
private const val DIAGNOSTIC_BUFFER_CHARS = 1024
