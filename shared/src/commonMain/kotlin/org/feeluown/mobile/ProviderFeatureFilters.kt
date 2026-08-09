package org.feeluown.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ProviderFeatureFilters(
    controller: FuoPlayerController,
    feature: ProviderFeature,
    content: ProviderContentSection?,
    modifier: Modifier = Modifier,
) {
    val filters = content?.let { ProviderFeatureFilterCodec.filters(it.feature.id) }.orEmpty()
    if (filters.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { filter ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = filter.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filter.options.forEach { option ->
                        FilterChip(
                            selected = option.selected,
                            onClick = {
                                if (!option.selected && option.featureId.isNotBlank()) {
                                    controller.openFeature(feature.copy(id = option.featureId))
                                }
                            },
                            label = {
                                Text(
                                    text = option.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
