package org.feeluown.mobile.provider.netease

import org.feeluown.mobile.ProviderFeatureFilterOption
import org.feeluown.mobile.ProviderFeatureFilterSpec

internal const val NETEASE_PLAYLIST_SQUARE = "netease_playlist_square"
internal const val NETEASE_ARTIST_SQUARE = "netease_artist_square"
internal const val NETEASE_MV_SQUARE = "netease_mv_square"
internal const val NETEASE_STYLES = "netease_styles"

internal data class NeteaseFeatureRequest(
    val baseId: String,
    val params: Map<String, String>,
)

internal data class NeteaseStyleTag(
    val id: String,
    val name: String,
)

internal fun parseNeteaseFeatureRequest(featureId: String): NeteaseFeatureRequest {
    val parts = featureId.substringBefore("^filters^").split('|')
    return NeteaseFeatureRequest(
        baseId = parts.firstOrNull().orEmpty(),
        params = parts.drop(1).mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            part.substring(0, separator) to part.substring(separator + 1)
        }.toMap(),
    )
}

internal fun neteaseFeatureId(baseId: String, vararg params: Pair<String, String>): String =
    buildString {
        append(baseId)
        params.forEach { (key, value) ->
            append('|')
            append(key)
            append('=')
            append(value)
        }
    }

internal fun playlistSquareFilters(cat: String, order: String): List<ProviderFeatureFilterSpec> = listOf(
    ProviderFeatureFilterSpec(
        key = "cat",
        title = "分类",
        options = NETEASE_PLAYLIST_CATEGORIES.map { category ->
            ProviderFeatureFilterOption(
                label = category,
                featureId = neteaseFeatureId(NETEASE_PLAYLIST_SQUARE, "cat" to category, "order" to order),
                selected = category == cat,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "order",
        title = "排序",
        options = listOf("hot" to "热门", "new" to "最新").map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = neteaseFeatureId(NETEASE_PLAYLIST_SQUARE, "cat" to cat, "order" to value),
                selected = value == order,
            )
        },
    ),
)

internal fun artistSquareFilters(
    area: String,
    type: String,
    initial: String,
): List<ProviderFeatureFilterSpec> = listOf(
    ProviderFeatureFilterSpec(
        key = "area",
        title = "地区",
        options = listOf(
            "-1" to "全部",
            "7" to "华语",
            "96" to "欧美",
            "8" to "日本",
            "16" to "韩国",
            "0" to "其他",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = neteaseFeatureId(
                    NETEASE_ARTIST_SQUARE,
                    "area" to value,
                    "type" to type,
                    "initial" to initial,
                ),
                selected = value == area,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "type",
        title = "类型",
        options = listOf(
            "-1" to "全部",
            "1" to "男歌手",
            "2" to "女歌手",
            "3" to "乐队",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = neteaseFeatureId(
                    NETEASE_ARTIST_SQUARE,
                    "area" to area,
                    "type" to value,
                    "initial" to initial,
                ),
                selected = value == type,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "initial",
        title = "首字母",
        options = buildList {
            add("-1" to "热门")
            ('A'..'Z').forEach { letter -> add(letter.code.toString() to letter.toString()) }
            add("0" to "#")
        }.map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = neteaseFeatureId(
                    NETEASE_ARTIST_SQUARE,
                    "area" to area,
                    "type" to type,
                    "initial" to value,
                ),
                selected = value == initial,
            )
        },
    ),
)

internal fun mvSquareFilters(
    area: String,
    type: String,
    order: String,
): List<ProviderFeatureFilterSpec> = listOf(
    ProviderFeatureFilterSpec(
        key = "area",
        title = "地区",
        options = listOf("全部", "内地", "港台", "欧美", "日本", "韩国").map { value ->
            ProviderFeatureFilterOption(
                label = value,
                featureId = neteaseFeatureId(NETEASE_MV_SQUARE, "area" to value, "type" to type, "order" to order),
                selected = value == area,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "type",
        title = "类型",
        options = listOf("全部", "官方版", "原生", "现场版", "网易出品").map { value ->
            ProviderFeatureFilterOption(
                label = value,
                featureId = neteaseFeatureId(NETEASE_MV_SQUARE, "area" to area, "type" to value, "order" to order),
                selected = value == type,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "order",
        title = "排序",
        options = listOf("上升最快", "最热", "最新").map { value ->
            ProviderFeatureFilterOption(
                label = value,
                featureId = neteaseFeatureId(NETEASE_MV_SQUARE, "area" to area, "type" to type, "order" to value),
                selected = value == order,
            )
        },
    ),
)

internal fun styleFilters(
    styles: List<NeteaseStyleTag>,
    tagId: String,
    kind: String,
): List<ProviderFeatureFilterSpec> = listOf(
    ProviderFeatureFilterSpec(
        key = "tagId",
        title = "曲风",
        options = styles.map { style ->
            ProviderFeatureFilterOption(
                label = style.name,
                featureId = neteaseFeatureId(NETEASE_STYLES, "tagId" to style.id, "kind" to kind),
                selected = style.id == tagId,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "kind",
        title = "内容",
        options = listOf(
            "songs" to "歌曲",
            "playlists" to "歌单",
            "albums" to "专辑",
            "artists" to "歌手",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = neteaseFeatureId(NETEASE_STYLES, "tagId" to tagId, "kind" to value),
                selected = value == kind,
            )
        },
    ),
)

private val NETEASE_PLAYLIST_CATEGORIES = listOf(
    "全部",
    "华语", "欧美", "日语", "韩语", "粤语", "小语种",
    "流行", "摇滚", "民谣", "电子", "舞曲", "说唱", "轻音乐", "爵士", "乡村", "R&B/Soul",
    "古典", "民族", "英伦", "金属", "朋克", "蓝调", "雷鬼", "世界音乐", "拉丁", "另类/独立",
    "New Age", "古风", "后摇", "Bossa Nova",
    "清晨", "夜晚", "学习", "工作", "午休", "下午茶", "地铁", "驾车", "运动", "旅行", "散步", "酒吧",
    "怀旧", "清新", "浪漫", "性感", "伤感", "治愈", "放松", "孤独", "感动", "兴奋", "快乐", "安静", "思念",
    "影视原声", "ACG", "儿童", "校园", "游戏", "70后", "80后", "90后", "00后", "网络歌曲", "KTV", "经典",
    "翻唱", "吉他", "钢琴", "器乐", "榜单",
)
