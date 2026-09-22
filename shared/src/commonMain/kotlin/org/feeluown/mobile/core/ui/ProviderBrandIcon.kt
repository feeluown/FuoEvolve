package org.feeluown.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Offline, consistently masked provider marks. The colored tile is part of this component,
 * rather than of the original logo, so each source uses the same rounded-square silhouette.
 * Source artwork and trademark notices: docs/provider-brand-icons.md.
 */
@Composable
internal fun ProviderBrandIcon(
    providerId: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val background = when (providerId) {
        "netease" -> Color(0xFFE60026)
        "qqmusic" -> Color(0xFFFFD42A)
        "bilibili" -> Color(0xFFFB7299)
        "ytmusic" -> Color(0xFFFF0033)
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val mark = remember(providerId) { providerBrandVector(providerId) }
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(percent = 22)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (mark != null) {
            Image(
                imageVector = mark,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(size * if (providerId == "qqmusic") 0.08f else 0.20f),
            )
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(size * 0.52f))
        }
    }
}

private fun providerBrandVector(providerId: String): ImageVector? {
    val path = when (providerId) {
        "netease" -> NETEASE_MARK
        "qqmusic" -> QQ_MUSIC_MARK
        "bilibili" -> BILIBILI_MARK
        "ytmusic" -> YOUTUBE_MUSIC_MARK
        else -> return null
    }
    val qq = providerId == "qqmusic"
    return ImageVector.Builder(
        name = "Provider mark: $providerId",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = if (qq) 470f else 24f,
        viewportHeight = if (qq) 492.3f else 24f,
    ).apply {
        if (qq) addGroup(translationX = 147f, translationY = 173.3f)
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(if (qq) Color(0xFF0DAF52) else Color.White),
        )
        if (qq) clearGroup()
    }.build()
}

// Brand vector paths are bundled instead of fetched at runtime: icons work on every platform offline.
private const val NETEASE_MARK = "M13.046 9.388a3.919 3.919 0 0 0-.66.19c-.809.312-1.447.991-1.666 1.775a2.269 2.269 0 0 0-.074.81c.048.546.333 1.05.764 1.35a1.483 1.483 0 0 0 2.01-.286c.406-.531.355-1.183.24-1.636-.098-.387-.22-.816-.345-1.249a64.76 64.76 0 0 1-.269-.954zm-.82 10.07c-3.984 0-7.224-3.24-7.224-7.223 0-.98.226-3.02 1.884-4.822A7.188 7.188 0 0 1 9.502 5.6a.792.792 0 1 1 .587 1.472 5.619 5.619 0 0 0-2.795 2.462 5.538 5.538 0 0 0-.707 2.7 5.645 5.645 0 0 0 5.638 5.638c1.844 0 3.627-.953 4.542-2.428 1.042-1.68.772-3.931-.627-5.238a3.299 3.299 0 0 0-1.437-.777c.172.589.334 1.18.494 1.772.284 1.12.1 2.181-.519 2.989-.39.51-.956.888-1.592 1.064a3.038 3.038 0 0 1-2.58-.44 3.45 3.45 0 0 1-1.44-2.514c-.04-.467.002-.93.128-1.376.35-1.256 1.356-2.339 2.622-2.826a5.5 5.5 0 0 1 .823-.246l-.134-.505c-.37-1.371.25-2.579 1.547-3.007.329-.109.68-.145 1.025-.105.792.09 1.476.592 1.709 1.023.258.507-.096 1.153-.706 1.153a.788.788 0 0 1-.54-.213c-.088-.08-.163-.174-.259-.247a.825.825 0 0 0-.632-.166.807.807 0 0 0-.634.551c-.056.191-.031.406.02.595.07.256.159.597.217.82 1.11.098 2.162.54 2.97 1.296 1.974 1.844 2.35 4.886.892 7.233-1.197 1.93-3.509 3.177-5.889 3.177zM0 12c0 6.627 5.373 12 12 12s12-5.373 12-12S18.627 0 12 0 0 5.373 0 12Z"
private const val BILIBILI_MARK = "M17.813 4.653h.854c1.51.054 2.769.578 3.773 1.574 1.004.995 1.524 2.249 1.56 3.76v7.36c-.036 1.51-.556 2.769-1.56 3.773s-2.262 1.524-3.773 1.56H5.333c-1.51-.036-2.769-.556-3.773-1.56S.036 18.858 0 17.347v-7.36c.036-1.511.556-2.765 1.56-3.76 1.004-.996 2.262-1.52 3.773-1.574h.774l-1.174-1.12a1.234 1.234 0 0 1-.373-.906c0-.356.124-.658.373-.907l.027-.027c.267-.249.573-.373.92-.373.347 0 .653.124.92.373L9.653 4.44c.071.071.134.142.187.213h4.267a.836.836 0 0 1 .16-.213l2.853-2.747c.267-.249.573-.373.92-.373.347 0 .662.151.929.4.267.249.391.551.391.907 0 .355-.124.657-.373.906zM5.333 7.24c-.746.018-1.373.276-1.88.773-.506.498-.769 1.13-.786 1.894v7.52c.017.764.28 1.395.786 1.893.507.498 1.134.756 1.88.773h13.334c.746-.017 1.373-.275 1.88-.773.506-.498.769-1.129.786-1.893v-7.52c-.017-.765-.28-1.396-.786-1.894-.507-.497-1.134-.755-1.88-.773zM8 11.107c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c0-.373.129-.689.386-.947.258-.257.574-.386.947-.386zm8 0c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c.017-.391.15-.711.4-.96.249-.249.56-.373.933-.373Z"
private const val YOUTUBE_MUSIC_MARK = "M12 0C5.376 0 0 5.376 0 12s5.376 12 12 12 12-5.376 12-12S18.624 0 12 0zm0 19.104c-3.924 0-7.104-3.18-7.104-7.104S8.076 4.896 12 4.896s7.104 3.18 7.104 7.104-3.18 7.104-7.104 7.104zm0-13.332c-3.432 0-6.228 2.796-6.228 6.228S8.568 18.228 12 18.228s6.228-2.796 6.228-6.228S15.432 5.772 12 5.772zM9.684 15.54V8.46L15.816 12l-6.132 3.54z"
private const val QQ_MUSIC_MARK = "M123.8 104c-5.9-8.3-11.5-16.1-17.1-23.8C85.2 50.4 63.6 20.6 42-9.1 28.3-28 14.7-46.9.8-65.6-3.4-71.2-3.9-77.1-2-83.5c3.9-13.3 13.2-22.5 24.2-30.1 20.1-13.9 42.8-21 66.6-25.2 20.7-3.6 41.2-8 59.3-19.4 4.9-3.1 9-7.3 13.5-11 1.2-1 2.3-2.1 4.6-4.1 1.5 7.3 3 13.4 4 19.5 3 18 1.9 35.5-6.2 52.1-11 22.4-29 36.4-52.6 43.6C97-53.5 82.1-52.4 67-52.5c-1.1 0-2.2.3-4.1.5 3.7 6.5 7 12.6 10.6 18.5 14.5 23.7 29 47.4 43.4 71.2l47.4 78.6c4.1 6.8 8.4 13.6 12.4 20.5 9.3 16 16.1 32.7 14.8 51.9-1.3 18.8-8.1 35.2-19.8 49.6-19.1 23.5-43.9 37.1-73.5 42.4-27.9 4.9-54.5 1.8-79.2-12.3-27.8-15.8-44.5-44.5-41.4-78.7 2.7-22.5 13.9-41.1 30.4-56.4 17.6-16.2 38.5-26.1 61.8-30.6 17.2-3.4 34.4-3.2 51.4 1.4.5.1 1.1-.1 2.6-.1z"