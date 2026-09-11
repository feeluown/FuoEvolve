package org.feeluown.mobile.nucleus

import dev.nucleusframework.core.runtime.SingleInstanceManager
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Nucleus single-instance transport with an application payload.
 *
 * Nucleus' default application wrapper forwards URI deep links. FuoEvolve also needs ordinary file
 * paths from OS file associations, so this keeps Nucleus' lock/watcher implementation while writing
 * the secondary process arguments into the restore-request payload.
 */
internal class NucleusExternalActivation private constructor(
    val inputs: MutableSharedFlow<String>,
    val focusRequests: MutableSharedFlow<Unit>,
) {
    companion object {
        fun open(args: Array<String>): NucleusExternalActivation? {
            val inputs = MutableSharedFlow<String>(
                replay = EXTERNAL_INPUT_REPLAY,
                extraBufferCapacity = EXTERNAL_INPUT_REPLAY,
            )
            val focusRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

            // Nucleus onDeepLink handles cold-start URI arguments itself. Seed only plain .fuo
            // paths here so file:// URIs are not delivered twice after composition starts.
            args.asSequence()
                .map(::normalizeArgument)
                .filter { input -> isFuoPlaylistArgument(input) && !isUriArgument(input) }
                .forEach { input -> inputs.tryEmit(input) }

            val isPrimary = SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = {
                    Files.writeString(this, encodeArguments(args))
                },
                onRestoreRequest = {
                    val forwarded = runCatching { decodeArguments(Files.readString(this)) }
                        .getOrDefault(emptyList())
                    forwarded
                        .map(::normalizeArgument)
                        .filter(::isExternalInputArgument)
                        .forEach { input -> inputs.tryEmit(input) }
                    focusRequests.tryEmit(Unit)
                },
            )
            return if (isPrimary) NucleusExternalActivation(inputs, focusRequests) else null
        }
    }
}

private fun encodeArguments(args: Array<String>): String = args.joinToString(ARGUMENT_SEPARATOR.toString())

private fun decodeArguments(value: String): List<String> =
    value.split(ARGUMENT_SEPARATOR).filter(String::isNotBlank)

private fun normalizeArgument(value: String): String = value.trim().trim('"')

private fun isExternalInputArgument(value: String): Boolean =
    isFuoPlaylistArgument(value) || isUriArgument(value)

private fun isFuoPlaylistArgument(value: String): Boolean =
    value.substringBefore('?').substringBefore('#').endsWith(".fuo", ignoreCase = true)

private fun isUriArgument(value: String): Boolean {
    if (value.isBlank()) return false
    if (WINDOWS_ABSOLUTE_PATH.matches(value)) return false
    return runCatching { URI(value).scheme != null }.getOrDefault(false)
}

private const val EXTERNAL_INPUT_REPLAY = 16
private const val ARGUMENT_SEPARATOR = '\u0000'
private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
