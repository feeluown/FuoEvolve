package org.feeluown.mobile.provider.qqmusic

import org.feeluown.mobile.ProviderFeatureFilterCodec
import org.feeluown.mobile.ProviderFeatureFilterOption
import org.feeluown.mobile.ProviderFeatureFilterSpec

internal const val QQMUSIC_TOPLISTS = "qqmusic_toplists"
internal const val QQMUSIC_PLAYLIST_SQUARE = "qqmusic_playlist_square"
internal const val QQMUSIC_ARTIST_SQUARE = "qqmusic_artist_square"
internal const val QQMUSIC_NEW_ALBUMS = "qqmusic_new_albums"
internal const val QQMUSIC_MV_SQUARE = "qqmusic_mv_square"

internal data class QQMusicFeatureRequest(
    val baseId: String,
    val params: Map<String, String>,
)

internal data class QQMusicPlaylistCategory(
    val id: String,
    val name: String,
)

internal fun parseQQMusicFeatureRequest(featureId: String): QQMusicFeatureRequest {
    val parts = ProviderFeatureFilterCodec.requestId(featureId).split('|')
    return QQMusicFeatureRequest(
        baseId = parts.firstOrNull().orEmpty(),
        params = parts.drop(1).mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            part.substring(0, separator) to part.substring(separator + 1)
        }.toMap(),
    )
}

internal fun qqMusicFeatureId(baseId: String, vararg params: Pair<String, String>): String =
    buildString {
        append(baseId)
        params.forEach { (key, value) ->
            append('|')
            append(key)
            append('=')
            append(value)
        }
    }

internal fun qqMusicPlaylistSquareFilters(
    categories: List<QQMusicPlaylistCategory>,
    categoryId: String,
    sortId: String,
): List<ProviderFeatureFilterSpec> {
    val normalizedCategories = buildList {
        add(QQMusicPlaylistCategory(QQ_DEFAULT_PLAYLIST_CATEGORY_ID, "全部"))
        addAll(categories.filter { it.id != QQ_DEFAULT_PLAYLIST_CATEGORY_ID })
    }.distinctBy { it.id }
    return listOf(
        ProviderFeatureFilterSpec(
            key = "categoryId",
            title = "分类",
            options = normalizedCategories.map { category ->
                ProviderFeatureFilterOption(
                    label = category.name,
                    featureId = qqMusicFeatureId(
                        QQMUSIC_PLAYLIST_SQUARE,
                        "categoryId" to category.id,
                        "sortId" to sortId,
                    ),
                    selected = category.id == categoryId,
                )
            },
        ),
        ProviderFeatureFilterSpec(
            key = "sortId",
            title = "排序",
            options = listOf(
                "5" to "热门",
                "2" to "最新",
            ).map { (value, label) ->
                ProviderFeatureFilterOption(
                    label = label,
                    featureId = qqMusicFeatureId(
                        QQMUSIC_PLAYLIST_SQUARE,
                        "categoryId" to categoryId,
                        "sortId" to value,
                    ),
                    selected = value == sortId,
                )
            },
        ),
    )
}

internal fun qqMusicArtistSquareFilters(
    area: String,
    sex: String,
    genre: String,
    index: String,
): List<ProviderFeatureFilterSpec> = listOf(
    ProviderFeatureFilterSpec(
        key = "area",
        title = "地区",
        options = listOf(
            "-100" to "全部",
            "200" to "内地",
            "2" to "港台",
            "5" to "欧美",
            "4" to "日本",
            "3" to "韩国",
            "6" to "其他",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = qqMusicFeatureId(
                    QQMUSIC_ARTIST_SQUARE,
                    "area" to value,
                    "sex" to sex,
                    "genre" to genre,
                    "index" to index,
                ),
                selected = value == area,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "sex",
        title = "类型",
        options = listOf(
            "-100" to "全部",
            "0" to "男歌手",
            "1" to "女歌手",
            "2" to "组合",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = qqMusicFeatureId(
                    QQMUSIC_ARTIST_SQUARE,
                    "area" to area,
                    "sex" to value,
                    "genre" to genre,
                    "index" to index,
                ),
                selected = value == sex,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "genre",
        title = "流派",
        options = listOf(
            "-100" to "全部",
            "1" to "流行",
            "6" to "嘻哈",
            "2" to "摇滚",
            "4" to "电子",
            "3" to "民谣",
            "8" to "R&B",
            "10" to "民歌",
            "9" to "轻音乐",
            "5" to "爵士",
            "14" to "古典",
            "25" to "乡村",
            "20" to "蓝调",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = qqMusicFeatureId(
                    QQMUSIC_ARTIST_SQUARE,
                    "area" to area,
                    "sex" to sex,
                    "genre" to value,
                    "index" to index,
                ),
                selected = value == genre,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "index",
        title = "首字母",
        options = buildList {
            add("-100" to "热门")
            ('A'..'Z').forEachIndexed { position, letter ->
                add((position + 1).toString() to letter.toString())
            }
            add("27" to "#")
        }.map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = qqMusicFeatureId(
                    QQMUSIC_ARTIST_SQUARE,
                    "area" to area,
                    "sex" to sex,
                    "genre" to genre,
                    "index" to value,
                ),
                selected = value == index,
            )
        },
    ),
)

internal fun qqMusicMvSquareFilters(
    area: String,
    version: String,
    order: String = QQ_DEFAULT_MV_ORDER,
): List<ProviderFeatureFilterSpec> = listOf(
    ProviderFeatureFilterSpec(
        key = "area",
        title = "地区",
        options = listOf(
            "15" to "全部",
            "16" to "内地",
            "17" to "港台",
            "18" to "欧美",
            "19" to "韩国",
            "20" to "日本",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = qqMusicFeatureId(
                    QQMUSIC_MV_SQUARE,
                    "area" to value,
                    "version" to version,
                    "order" to order,
                ),
                selected = value == area,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "version",
        title = "类型",
        options = listOf(
            "7" to "全部",
            "8" to "MV",
            "9" to "现场",
            "10" to "翻唱",
            "11" to "舞蹈",
            "12" to "影视",
            "13" to "综艺",
            "14" to "儿歌",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = qqMusicFeatureId(
                    QQMUSIC_MV_SQUARE,
                    "area" to area,
                    "version" to value,
                    "order" to order,
                ),
                selected = value == version,
            )
        },
    ),
    ProviderFeatureFilterSpec(
        key = "order",
        title = "排序",
        options = listOf(
            "1" to "热门",
            "0" to "最新",
        ).map { (value, label) ->
            ProviderFeatureFilterOption(
                label = label,
                featureId = qqMusicFeatureId(
                    QQMUSIC_MV_SQUARE,
                    "area" to area,
                    "version" to version,
                    "order" to value,
                ),
                selected = value == order,
            )
        },
    ),
)

internal const val QQ_DEFAULT_PLAYLIST_CATEGORY_ID = "10000000"
internal const val QQ_DEFAULT_PLAYLIST_SORT_ID = "5"
internal const val QQ_DEFAULT_ARTIST_FILTER = "-100"
internal const val QQ_DEFAULT_MV_AREA = "15"
internal const val QQ_DEFAULT_MV_VERSION = "7"
internal const val QQ_DEFAULT_MV_ORDER = "1"
