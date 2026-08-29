package org.feeluown.mobile.provider.qqmusic

import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.string

internal data class QqAudioQuality(
    val code: String,
    val prefix: String,
    val extension: String,
    val label: String,
) {
    fun filename(mediaId: String): String = "$prefix$mediaId.$extension"
}

internal fun JsonObject.hasPositive(key: String): Boolean =
    string(key).toLongOrNull()?.let { it > 0 } == true

internal fun qqSign(data: String): String {
    val randomPart = buildString {
        repeat(Random.nextInt(10, 17)) {
            append(QQ_SIGN_ALPHABET[Random.nextInt(QQ_SIGN_ALPHABET.length)])
        }
    }
    return "zza$randomPart${md5Hex("CJBPACrRuNy7$data")}"
}

private const val QQ_SIGN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
