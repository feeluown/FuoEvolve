package org.feeluown.mobile

import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

data class ProviderVideoStat(
    val label: String,
    val value: Long,
)

data class ProviderVideoMetadata(
    val description: String = "",
    val publishedAt: String? = null,
    val stats: List<ProviderVideoStat> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
)

internal object ProviderVideoMetadataRepository {
    private val http = ProviderHttpClient()
    private val cache = mutableMapOf<String, ProviderVideoMetadata>()

    suspend fun load(video: ProviderVideo): ProviderVideoMetadata? {
        cache[video.id]?.let { return it }
        val metadata = runCatching {
            when (video.providerId) {
                "bilibili" -> loadBilibili(video)
                "netease" -> loadNetease(video)
                else -> null
            }
        }.getOrNull() ?: return null
        cache[video.id] = metadata
        return metadata
    }

    private suspend fun loadBilibili(video: ProviderVideo): ProviderVideoMetadata? {
        val (_, rawIdentifier) = splitResourceId(video.id, "video")
        val bvid = rawIdentifier
            .removePrefix("paged_")
            .substringBefore("__")
            .takeIf { it.isNotBlank() }
            ?: return null
        val root = http.getText(
            providerId = "bilibili",
            url = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid",
            headers = mapOf(
                "Referer" to "https://www.bilibili.com/",
                "User-Agent" to "Mozilla/5.0",
            ),
            cacheKey = "video-metadata:bilibili:$bvid",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        if (root.int("code") != 0) return null
        val data = root.obj("data") ?: return null
        val stats = data.obj("stat")
        val dimension = data.obj("dimension")
        val rawWidth = dimension?.int("width")?.takeIf { it > 0 }
        val rawHeight = dimension?.int("height")?.takeIf { it > 0 }
        val rotate = dimension?.int("rotate") ?: 0
        val width = if (rotate % 180 == 0) rawWidth else rawHeight
        val height = if (rotate % 180 == 0) rawHeight else rawWidth
        return ProviderVideoMetadata(
            description = data.string("desc").trim(),
            publishedAt = data.long("pubdate")?.let(::formatEpochDate),
            stats = buildList {
                stats?.long("view")?.let { add(ProviderVideoStat("播放", it)) }
                stats?.long("like")?.let { add(ProviderVideoStat("点赞", it)) }
                stats?.long("coin")?.let { add(ProviderVideoStat("投币", it)) }
                stats?.long("favorite")?.let { add(ProviderVideoStat("收藏", it)) }
                stats?.long("reply")?.let { add(ProviderVideoStat("评论", it)) }
                stats?.long("danmaku")?.let { add(ProviderVideoStat("弹幕", it)) }
                stats?.long("share")?.let { add(ProviderVideoStat("分享", it)) }
            },
            width = width,
            height = height,
        )
    }

    private suspend fun loadNetease(video: ProviderVideo): ProviderVideoMetadata? {
        val (_, identifier) = splitResourceId(video.id, "video")
        if (identifier.isBlank()) return null
        val root = http.getText(
            providerId = "netease",
            url = "https://music.163.com/api/mv/detail?id=$identifier",
            headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0",
            ),
            cacheKey = "video-metadata:netease:$identifier",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data") ?: return null
        return ProviderVideoMetadata(
            description = data.string("desc")
                .ifBlank { data.string("briefDesc") }
                .trim(),
            publishedAt = data.stringOrNull("publishTime"),
            stats = buildList {
                data.long("playCount")?.let { add(ProviderVideoStat("播放", it)) }
                data.long("subCount")?.let { add(ProviderVideoStat("收藏", it)) }
                data.long("commentCount")?.let { add(ProviderVideoStat("评论", it)) }
                data.long("shareCount")?.let { add(ProviderVideoStat("分享", it)) }
            },
        )
    }
}

internal fun parseBilibiliVideoMetadata(raw: String): ProviderVideoMetadata? {
    val root = runCatching { providerJson.parseToJsonElement(raw).asObject() }.getOrNull() ?: return null
    if (root.int("code") != 0) return null
    val data = root.obj("data") ?: return null
    val stats = data.obj("stat")
    val dimension = data.obj("dimension")
    val rawWidth = dimension?.int("width")?.takeIf { it > 0 }
    val rawHeight = dimension?.int("height")?.takeIf { it > 0 }
    val rotate = dimension?.int("rotate") ?: 0
    return ProviderVideoMetadata(
        description = data.string("desc").trim(),
        publishedAt = data.long("pubdate")?.let(::formatEpochDate),
        stats = buildList {
            stats?.long("view")?.let { add(ProviderVideoStat("播放", it)) }
            stats?.long("like")?.let { add(ProviderVideoStat("点赞", it)) }
            stats?.long("coin")?.let { add(ProviderVideoStat("投币", it)) }
            stats?.long("favorite")?.let { add(ProviderVideoStat("收藏", it)) }
            stats?.long("reply")?.let { add(ProviderVideoStat("评论", it)) }
            stats?.long("danmaku")?.let { add(ProviderVideoStat("弹幕", it)) }
            stats?.long("share")?.let { add(ProviderVideoStat("分享", it)) }
        },
        width = if (rotate % 180 == 0) rawWidth else rawHeight,
        height = if (rotate % 180 == 0) rawHeight else rawWidth,
    )
}

internal fun parseNeteaseVideoMetadata(raw: String): ProviderVideoMetadata? {
    val root = runCatching { providerJson.parseToJsonElement(raw).asObject() }.getOrNull() ?: return null
    val data = root.obj("data") ?: return null
    return ProviderVideoMetadata(
        description = data.string("desc").ifBlank { data.string("briefDesc") }.trim(),
        publishedAt = data.stringOrNull("publishTime"),
        stats = buildList {
            data.long("playCount")?.let { add(ProviderVideoStat("播放", it)) }
            data.long("subCount")?.let { add(ProviderVideoStat("收藏", it)) }
            data.long("commentCount")?.let { add(ProviderVideoStat("评论", it)) }
            data.long("shareCount")?.let { add(ProviderVideoStat("分享", it)) }
        },
    )
}

private fun formatEpochDate(epochSeconds: Long): String {
    val days = epochSeconds / 86_400L
    val z = days + 719_468L
    val era = if (z >= 0) z / 146_097L else (z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    var year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    year += if (month <= 2L) 1L else 0L
    return "${year}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
