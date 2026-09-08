package org.feeluown.mobile.provider.ytmusic

internal enum class YtStreamingProtocol {
    Https,
    Hls,
    Dash,
}

internal data class YtPoTokenPolicy(
    val required: Boolean = false,
    val recommended: Boolean = false,
    val notRequiredWithPlayerToken: Boolean = false,
)

internal data class YtPlayerClientProfile(
    val key: String,
    val clientName: String,
    val clientId: String,
    val clientVersion: String,
    val userAgent: String,
    val host: String = "www.youtube.com",
    val requireJsPlayer: Boolean = true,
    val supportsCookies: Boolean = false,
    val requireAuth: Boolean = false,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
    val embedded: Boolean = false,
    val gvsPoTokenPolicy: Map<YtStreamingProtocol, YtPoTokenPolicy> = emptyMap(),
    val playerPoTokenRecommended: Boolean = false,
) {
    fun policy(protocol: YtStreamingProtocol): YtPoTokenPolicy =
        gvsPoTokenPolicy[protocol] ?: YtPoTokenPolicy()
}

/**
 * Player profiles tracked from yt-dlp's current YouTube extractor.
 *
 * Keep these definitions data-only so the fallback order can evolve independently
 * from request/format extraction. ANDROID_VR is intentionally absent: yt-dlp
 * removed 1.65.10 after all formats started returning HTTP 403 in August 2026.
 */
internal object YtMusicPlayerClients {
    private val webPoPolicies = mapOf(
        YtStreamingProtocol.Https to YtPoTokenPolicy(required = true, recommended = true),
        YtStreamingProtocol.Dash to YtPoTokenPolicy(required = true, recommended = true),
        YtStreamingProtocol.Hls to YtPoTokenPolicy(required = false, recommended = true),
    )

    val VisionOs = YtPlayerClientProfile(
        key = "visionos",
        clientName = "VISIONOS",
        clientId = "101",
        clientVersion = "1.02",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        requireJsPlayer = false,
        deviceMake = "Apple",
        deviceModel = "RealityDevice17,1",
        osName = "visionOS",
        osVersion = "26.5.23O471",
    )

    val Web = YtPlayerClientProfile(
        key = "web",
        clientName = "WEB",
        clientId = "1",
        clientVersion = "2.20260708.00.00",
        userAgent = YtMusicOAuth.USER_AGENT,
        supportsCookies = true,
        gvsPoTokenPolicy = webPoPolicies,
    )

    val WebEmbedded = YtPlayerClientProfile(
        key = "web_embedded",
        clientName = "WEB_EMBEDDED_PLAYER",
        clientId = "56",
        clientVersion = "2.20260708.00.00",
        userAgent = YtMusicOAuth.USER_AGENT,
        supportsCookies = true,
        embedded = true,
    )

    val WebMusic = YtPlayerClientProfile(
        key = "web_music",
        clientName = "WEB_REMIX",
        clientId = "67",
        clientVersion = "1.20260707.12.00",
        userAgent = YtMusicOAuth.USER_AGENT,
        host = "music.youtube.com",
        supportsCookies = true,
        gvsPoTokenPolicy = webPoPolicies,
    )

    val Tv = YtPlayerClientProfile(
        key = "tv",
        clientName = "TVHTML5",
        clientId = "7",
        clientVersion = "7.20260707.07.00",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
        supportsCookies = true,
    )

    val TvDowngraded = YtPlayerClientProfile(
        key = "tv_downgraded",
        clientName = "TVHTML5",
        clientId = "7",
        clientVersion = "5.20260707",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
        supportsCookies = true,
    )

    val Android = YtPlayerClientProfile(
        key = "android",
        clientName = "ANDROID",
        clientId = "3",
        clientVersion = "21.26.364",
        userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        requireJsPlayer = false,
        deviceMake = "Google",
        osName = "Android",
        osVersion = "11",
        androidSdkVersion = 30,
        gvsPoTokenPolicy = mapOf(
            YtStreamingProtocol.Https to YtPoTokenPolicy(
                required = true,
                recommended = true,
                notRequiredWithPlayerToken = true,
            ),
            YtStreamingProtocol.Dash to YtPoTokenPolicy(
                required = true,
                recommended = true,
                notRequiredWithPlayerToken = true,
            ),
            YtStreamingProtocol.Hls to YtPoTokenPolicy(
                required = false,
                recommended = true,
                notRequiredWithPlayerToken = true,
            ),
        ),
        playerPoTokenRecommended = true,
    )

    /** yt-dlp's authenticated defaults plus resilient no-POT fallbacks. */
    fun ordered(hasBrowserSession: Boolean): List<YtPlayerClientProfile> = if (hasBrowserSession) {
        listOf(WebEmbedded, TvDowngraded, Web, VisionOs, Tv, WebMusic, Android)
    } else {
        listOf(VisionOs, TvDowngraded, WebEmbedded, Tv, Web, WebMusic, Android)
    }
}
