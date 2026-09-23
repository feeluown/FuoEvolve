package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/** A calm editorial entry point; artwork in the content grid remains the visual focus. */
@Composable
internal fun RefinedHomeIntro(section: HomeSection) {
    val isRecommendation = section == HomeSection.Recommend
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FuoSurfaceColors.section(MaterialTheme.colorScheme),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = FuoVisualTokens.group,
        tonalElevation = FuoElevation.content,
    ) {
        Column(
            modifier = Modifier.padding(FuoSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            Text(
                text = if (isRecommendation) "为你精选" else "探索音乐",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (isRecommendation) "找到此刻喜欢的旋律" else "换个方向，听点新的",
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (isRecommendation) "从每日推荐开始，继续发现好音乐" else "发现不同音源里的歌单、专辑与新声音",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
