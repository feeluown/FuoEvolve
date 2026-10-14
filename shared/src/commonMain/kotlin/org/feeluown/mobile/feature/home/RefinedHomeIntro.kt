package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/** Lightweight editorial context; content shelves remain the visual focus of Home. */
@Composable
internal fun RefinedHomeIntro(section: HomeSection) {
    val isRecommendation = section == HomeSection.Recommend
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FuoSpacing.lg, bottom = FuoSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
    ) {
        Text(
            text = if (isRecommendation) "推荐" else "探索",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (isRecommendation) "此刻想听什么？" else "换个方向，发现新声音",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (isRecommendation) {
                "从播放记录和已启用音源中整理适合现在播放的内容"
            } else {
                "从榜单、歌单、新歌、新碟和歌手中主动探索"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
