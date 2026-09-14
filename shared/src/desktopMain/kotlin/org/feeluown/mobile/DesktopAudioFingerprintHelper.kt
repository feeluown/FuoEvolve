package org.feeluown.mobile

import java.io.File
import java.io.Reader

internal data class DesktopAudioFingerprintRuntimeFiles(
    val executable: File,
    val wasm: File,
)

internal fun resolveDesktopAudioFingerprintRuntime(): DesktopAudioFingerprintRuntimeFiles? {
    val executableName = desktopAudioFingerprintExecutableName()
    val appDir = System.getProperty("fuoevolve.appdir")
        ?.takeIf { it.isNotBlank() && !it.contains("\$APPDIR") }
        ?.let(::File)
    val composeResourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })

    val packagedRoots = sequenceOf(composeResourcesDir, appDir?.resolve("resources"))
        .filterNotNull()
        .filter(File::isDirectory)
    packagedRoots.forEach { root ->
        val runtime = DesktopAudioFingerprintRuntimeFiles(
            executable = root.resolve("native/fingerprint/$executableName"),
            wasm = root.resolve("native/fingerprint/afp.wasm"),
        )
        if (runtime.isUsable()) return runtime
    }

    val localExecutableCandidates = listOf(
        userDir.resolve("desktopApp/native/audio-fingerprint/build/$executableName"),
        userDir.resolve("native/audio-fingerprint/build/$executableName"),
    )
    val localWasmCandidates = listOf(
        userDir.resolve("shared/src/commonMain/resources/audio_recognition/afp.wasm"),
        userDir.resolve("../shared/src/commonMain/resources/audio_recognition/afp.wasm"),
    )
    localExecutableCandidates.forEach { executable ->
        localWasmCandidates.forEach { wasm ->
            val runtime = DesktopAudioFingerprintRuntimeFiles(executable, wasm)
            if (runtime.isUsable()) return runtime
        }
    }

    return sequenceOf(composeResourcesDir, appDir)
        .filterNotNull()
        .filter(File::isDirectory)
        .flatMap { root ->
            root.walkTopDown()
                .maxDepth(7)
                .filter { candidate ->
                    candidate.name == executableName && isUsableDesktopExecutable(candidate)
                }
                .mapNotNull { executable ->
                    val wasm = executable.parentFile?.resolve("afp.wasm") ?: return@mapNotNull null
                    DesktopAudioFingerprintRuntimeFiles(executable, wasm).takeIf { it.isUsable() }
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

private fun DesktopAudioFingerprintRuntimeFiles.isUsable(): Boolean =
    isUsableDesktopExecutable(executable) && wasm.isFile

private fun desktopAudioFingerprintExecutableName(): String =
    if (isDesktopWindows()) "fuoevolve-audio-fingerprint.exe" else "fuoevolve-audio-fingerprint"

private fun isUsableDesktopExecutable(candidate: File): Boolean =
    candidate.isFile && (isDesktopWindows() || candidate.canExecute())

internal fun isDesktopWindows(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

private const val MAX_DIAGNOSTIC_CHARS = 8 * 1024
private const val DIAGNOSTIC_BUFFER_CHARS = 1024
