package org.feeluown.mobile

data class ProviderFeatureFilterOption(
    val label: String,
    val featureId: String,
    val selected: Boolean = false,
)

data class ProviderFeatureFilterSpec(
    val key: String,
    val title: String,
    val options: List<ProviderFeatureFilterOption>,
)

/**
 * Feature filters are transient presentation metadata returned with a content section.
 * Keeping them on the section's feature id avoids adding provider-specific state to the
 * app controller while preserving the existing ProviderMusicRepository contract.
 *
 * Filter target ids never include this metadata; selecting a chip therefore opens a normal
 * provider feature id and existing pagination/navigation continues to work unchanged.
 */
object ProviderFeatureFilterCodec {
    private const val METADATA_SEPARATOR = "^filters^"
    private const val GROUP_SEPARATOR = '~'
    private const val FIELD_SEPARATOR = '|'
    private const val OPTION_SEPARATOR = '>'

    fun attach(feature: ProviderFeature, filters: List<ProviderFeatureFilterSpec>): ProviderFeature {
        if (filters.isEmpty()) return feature
        val encoded = filters.joinToString(GROUP_SEPARATOR.toString()) { filter ->
            buildList {
                add(escape(filter.key))
                add(escape(filter.title))
                filter.options.forEach { option ->
                    add(
                        listOf(
                            if (option.selected) "1" else "0",
                            escape(option.label),
                            escape(option.featureId),
                        ).joinToString(OPTION_SEPARATOR.toString())
                    )
                }
            }.joinToString(FIELD_SEPARATOR.toString())
        }
        return feature.copy(id = requestId(feature.id) + METADATA_SEPARATOR + encoded)
    }

    fun requestId(featureId: String): String = featureId.substringBefore(METADATA_SEPARATOR)

    fun filters(featureId: String): List<ProviderFeatureFilterSpec> {
        val payload = featureId.substringAfter(METADATA_SEPARATOR, "")
        if (payload.isBlank()) return emptyList()
        return payload.split(GROUP_SEPARATOR).mapNotNull { group ->
            val fields = group.split(FIELD_SEPARATOR)
            if (fields.size < 3) return@mapNotNull null
            val options = fields.drop(2).mapNotNull { rawOption ->
                val option = rawOption.split(OPTION_SEPARATOR, limit = 3)
                if (option.size != 3) return@mapNotNull null
                ProviderFeatureFilterOption(
                    label = unescape(option[1]),
                    featureId = unescape(option[2]),
                    selected = option[0] == "1",
                )
            }
            if (options.isEmpty()) return@mapNotNull null
            ProviderFeatureFilterSpec(
                key = unescape(fields[0]),
                title = unescape(fields[1]),
                options = options,
            )
        }
    }

    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace("^", "%5E")
        .replace("~", "%7E")
        .replace("|", "%7C")
        .replace(">", "%3E")

    private fun unescape(value: String): String = value
        .replace("%3E", ">")
        .replace("%7C", "|")
        .replace("%7E", "~")
        .replace("%5E", "^")
        .replace("%25", "%")
}
