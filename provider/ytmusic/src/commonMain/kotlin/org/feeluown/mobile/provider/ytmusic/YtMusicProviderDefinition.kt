package org.feeluown.mobile.provider.ytmusic

import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.provider.core.network.currentTimeMillis

internal object YtMusicProviderDefinition {
    const val ID = "ytmusic"
    const val NAME = "YouTube Music"
    const val YTM_ORIGIN = "https://music.youtube.com"
    const val API_BASE = "$YTM_ORIGIN/youtubei/v1"
    const val DATA_API_BASE = "https://www.googleapis.com/youtube/v3"
    const val YOUTUBE_API_BASE = "https://www.youtube.com/youtubei/v1"
    const val FALLBACK_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    const val ANDROID_VR_CLIENT_NAME = "28"
    const val ANDROID_VR_CLIENT_VERSION = "1.65.10"
    const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    const val ANDROID_CLIENT_NAME = "3"
    const val ANDROID_CLIENT_VERSION = "20.10.38"
    const val ANDROID_USER_AGENT =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"

    val info = ProviderInfo(
        providerId = ID,
        providerName = NAME,
        supportedLoginModes = setOf(
            org.feeluown.mobile.ProviderLoginMode.Headers,
            org.feeluown.mobile.ProviderLoginMode.OAuth,
        ),
    )

    val capabilities = ProviderCapabilities(
        providerId = ID,
        providerName = NAME,
        canAddSongToPlaylist = true,
    )

    val features = listOf(
        ProviderFeature("ytmusic_daily_songs", ID, NAME, "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, false),
        ProviderFeature("ytmusic_daily_playlists", ID, NAME, "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, false),
        ProviderFeature("ytmusic_toplists", ID, NAME, "排行榜", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
        ProviderFeature("ytmusic_user_playlists", ID, NAME, "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
    )

    fun dynamicClientVersion(nowMillis: Long = currentTimeMillis()): String {
        val (year, month, day) = utcYmd(nowMillis)
        return "1.${year.toString().padStart(4, '0')}${month.toString().padStart(2, '0')}${day.toString().padStart(2, '0')}.01.00"
    }

    fun sapisidFromCookie(cookie: String): String? {
        if (cookie.isBlank()) return null
        val parts = cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val values = parts.mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
        return values["__Secure-3PAPISID"]
            ?: values["SAPISID"]
            ?: values["__Secure-1PAPISID"]
    }

    fun sapisidHashAuthorization(
        sapisid: String,
        origin: String,
        nowMillis: Long = currentTimeMillis(),
    ): String {
        val timestamp = (nowMillis / 1_000).toString()
        val digest = sha1Hex("$timestamp $sapisid $origin")
        return "SAPISIDHASH ${timestamp}_$digest"
    }

    fun sha1Hex(value: String): String {
        val bytes = sha1(value.encodeToByteArray())
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val number = byte.toInt() and 0xff
            hex[index * 2] = digits[number ushr 4]
            hex[index * 2 + 1] = digits[number and 0x0f]
        }
        return hex.concatToString()
    }

    fun sha1(message: ByteArray): ByteArray {
        val h = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())
        val bitLength = message.size.toLong() * 8
        val withOne = message + byteArrayOf(0x80.toByte())
        val padding = ((56 - withOne.size % 64) + 64) % 64
        val padded = withOne + ByteArray(padding) + byteArrayOf(
            (bitLength ushr 56).toByte(),
            (bitLength ushr 48).toByte(),
            (bitLength ushr 40).toByte(),
            (bitLength ushr 32).toByte(),
            (bitLength ushr 24).toByte(),
            (bitLength ushr 16).toByte(),
            (bitLength ushr 8).toByte(),
            bitLength.toByte(),
        )
        var offset = 0
        while (offset < padded.size) {
            val w = IntArray(80)
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = ((padded[j].toInt() and 0xff) shl 24) or
                    ((padded[j + 1].toInt() and 0xff) shl 16) or
                    ((padded[j + 2].toInt() and 0xff) shl 8) or
                    (padded[j + 3].toInt() and 0xff)
            }
            for (i in 16 until 80) {
                w[i] = rotateLeft(w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16], 1)
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            for (i in 0 until 80) {
                val (f, k) = when {
                    i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                    i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val temp = rotateLeft(a, 5) + f + e + k + w[i]
                e = d
                d = c
                c = rotateLeft(b, 30)
                b = a
                a = temp
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            offset += 64
        }
        val out = ByteArray(20)
        for (i in 0 until 5) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    private fun utcYmd(epochMillis: Long): Triple<Int, Int, Int> {
        val days = floorDiv(epochMillis, 86_400_000L)
        val z = days + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = (z - era * 146_097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        val y = (yoe.toLong() + era * 400L).toInt()
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = mp + if (mp < 10) 3 else -9
        val year = y + if (month <= 2) 1 else 0
        return Triple(year, month, day)
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        var result = value / divisor
        if ((value xor divisor) < 0 && result * divisor != value) result -= 1
        return result
    }

    private fun rotateLeft(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))
}
