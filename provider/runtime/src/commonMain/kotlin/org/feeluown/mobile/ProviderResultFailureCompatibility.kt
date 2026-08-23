package org.feeluown.mobile

/**
 * Temporary migration factories for concrete providers that still return display strings.
 * The strings are classified at the provider runtime boundary so physical provider modules
 * do not need to depend back on :shared. P5 provider migrations should progressively emit
 * ProviderFailure directly and this compatibility surface can then be removed.
 */
@Suppress("FunctionName", "UNUSED_PARAMETER")
fun ProviderSearchResults(
    tracks: List<MusicTrack> = emptyList(),
    playlists: List<ProviderPlaylist> = emptyList(),
    artists: List<MediaRef> = emptyList(),
    albums: List<MediaRef> = emptyList(),
    videos: List<ProviderVideo> = emptyList(),
    errorMessage: String?,
    compatibility: Unit = Unit,
): ProviderSearchResults = ProviderSearchResults(
    tracks = tracks,
    playlists = playlists,
    artists = artists,
    albums = albums,
    videos = videos,
    failure = errorMessage.toProviderResultFailure(),
)

@Suppress("FunctionName", "UNUSED_PARAMETER")
fun ProviderContentSection(
    feature: ProviderFeature,
    tracks: List<MusicTrack> = emptyList(),
    playlists: List<ProviderPlaylist> = emptyList(),
    mediaItems: List<MediaRef> = emptyList(),
    videos: List<ProviderVideo> = emptyList(),
    isLoginRequired: Boolean = false,
    errorMessage: String?,
    nextOffset: Int = 0,
    hasMore: Boolean = false,
    compatibility: Unit = Unit,
): ProviderContentSection = ProviderContentSection(
    feature = feature,
    tracks = tracks,
    playlists = playlists,
    mediaItems = mediaItems,
    videos = videos,
    isLoginRequired = isLoginRequired,
    failure = errorMessage.toProviderResultFailure(feature.providerId),
    nextOffset = nextOffset,
    hasMore = hasMore,
)

private fun String?.toProviderResultFailure(providerId: String? = null): ProviderFailure? {
    val message = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    val normalized = message.lowercase()
    val kind = when {
        normalized.containsAny(
            "login required",
            "not logged in",
            "token expired",
            "cookie expired",
            "登录状态已失效",
            "登录失效",
            "登录过期",
            "未登录",
            "重新登录",
        ) -> ProviderFailureKind.LoginExpired

        normalized.containsAny(
            "region restricted",
            "geo restricted",
            "not available in your country",
            "地区限制",
            "当前地区",
            "海外限制",
        ) -> ProviderFailureKind.RegionRestricted

        normalized.containsAny(
            "copyright",
            "版权限制",
            "版权或资源限制",
            "无版权",
        ) -> ProviderFailureKind.CopyrightUnavailable

        normalized.containsAny(
            "network",
            "timeout",
            "timed out",
            "网络",
            "超时",
        ) -> ProviderFailureKind.Network

        normalized.containsAny(
            "payload missing",
            "missing data",
            "response format",
            "schema changed",
            "接口响应",
            "响应格式",
            "字段缺失",
        ) -> ProviderFailureKind.UpstreamContractChanged

        normalized.containsAny(
            "account id is unavailable",
            "profile is unavailable",
            "用户信息",
            "账号资料",
            "账号信息",
        ) -> ProviderFailureKind.AccountUnavailable

        normalized.containsAny(
            "not supported",
            "unsupported",
            "暂不支持",
            "不支持",
        ) -> ProviderFailureKind.UnsupportedOperation

        normalized.containsAny(
            "not found",
            "unavailable",
            "暂无",
            "未找到",
            "暂不可用",
        ) -> ProviderFailureKind.ContentUnavailable

        else -> ProviderFailureKind.Unknown
    }
    return ProviderFailure(kind = kind, providerId = providerId)
}

private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
