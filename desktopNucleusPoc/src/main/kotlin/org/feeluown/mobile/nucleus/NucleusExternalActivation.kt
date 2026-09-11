package org.feeluown.mobile.nucleus

import dev.nucleusframework.core.runtime.SingleInstanceManager
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Nucleus single-instance transport with an application payload.
 *
 * Nucleus' default application wrapper forwards URI deep links. FuoEvolve also needs ordinary file
 * paths from OS file associations, so this keeps Nucleus' lock/watcher implementation while writing
 * the secondary process arguments into the restore-request payload.
 */
internal class NucleusExternalActivation private constructor(
    private val inputChannel: Channel<String>,
    private val focusChannel: Channel<Unit>,
) {
    val inputs: Flow<String> = inputChannel.receiveAsFlow()
    val focusRequests: Flow<Unit> = focusChannel.receiveAsFlow()

    fun emitInput(value: String) {
        value.takeIf(String::isNotBlank)?.let(inputChannel::trySend)
    }

    companion object {
        fun open(args: Array<String>): NucleusExternalActivation? {
            val inputChannel = Channel<String>(Channel.UNLIMITED)
            val focusChannel = Channel<Unit>(Channel.UNLIMITED)

            // Nucleus onDeepLink handles cold-start URI arguments itself. Seed only plain .fuo
            // paths here so file:// URIs are not delivered twice after composition starts.
            nucleusColdStartFileInputs(args).forEach(inputChannel::trySend)

            val isPrimary = SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = {
                    Files.writeString(this, encodeNucleusActivationArguments(args))
                },
                onRestoreRequest = {
                    val forwarded = runCatching {
                        decodeNucleusActivationArguments(Files.readString(this))
                    }.getOrDefault(emptyList())
                    nucleusForwardedExternalInputs(forwarded.toTypedArray()).forEach(inputChannel::trySend)
                    focusChannel.trySend(Unit)
                },
            )
            if (!isPrimary) {
                inputChannel.close()
                focusChannel.close()
                return null
            }
            return NucleusExternalActivation(inputChannel, focusChannel)
        }
    }
}

internal fun nucleusColdStartFileInputs(args: Array<String>): List<String> =
    args.asSequence()
        .map(::normalizeNucleusActivationArgument)
        .filter { input -> isFuoPlaylistArgument(input) && !isUriArgument(input) }
        .toList()

internal fun nucleusForwardedExternalInputs(args: Array<String>): List<String> =
    args.asSequence()
        .map(::normalizeNucleusActivationArgument)
        .filter(::isExternalInputArgument)
        .toList()

internal fun encodeNucleusActivationArguments(args: Array<String>): String =
    args.joinToString(ARGUMENT_SEPARATOR.toString())

internal fun decodeNucleusActivationArguments(value: String): List<String> =
    value.split(ARGUMENT_SEPARATOR).filter(String::isNotBlank)

private fun normalizeNucleusActivationArgument(value: String): String = value.trim().trim('"')

private fun isExternalInputArgument(value: String): Boolean =
    isFuoPlaylistArgument(value) || isUriArgument(value)

private fun isFuoPlaylistArgument(value: String): Boolean =
    value.substringBefore('?').substringBefore('#').endsWith(".fuo", ignoreCase = true)

private fun isUriArgument(value: String): Boolean {
    if (value.isBlank()) return false
    if (WINDOWS_ABSOLUTE_PATH.matches(value)) return false
    return runCatching { URI(value).scheme != null }.getOrDefault(false)
}

private const val ARGUMENT_SEPARATOR = '\u0000'
private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
