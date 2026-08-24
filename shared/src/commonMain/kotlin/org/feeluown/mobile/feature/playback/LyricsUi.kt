package org.feeluown.mobile

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LyricsPanel(state: PlaybackState, fontSize: LyricFontSize, modifier: Modifier) {
    val lyricsPort = LocalPlaybackLyricsPort.current
    val associationState by lyricsPort.associationState.collectAsStateWithLifecycle()
    val lines = remember(state.lyrics) { parseLyrics(state.lyrics) }
    val listState = rememberLazyListState()
    val renderPositionMs = rememberKaraokePositionMs(
        positionMs = state.positionMs,
        isPlaying = state.status == PlayerStatus.Playing,
    )
    val currentIndex by remember(lines, renderPositionMs) {
        androidx.compose.runtime.derivedStateOf {
            currentLyricIndex(lines, renderPositionMs.value)
        }
    }
    val activeStyle = when (fontSize) {
        LyricFontSize.Small -> MaterialTheme.typography.titleMedium
        LyricFontSize.Medium -> MaterialTheme.typography.titleLarge
        LyricFontSize.Large -> MaterialTheme.typography.headlineSmall
    }
    val inactiveStyle = when (fontSize) {
        LyricFontSize.Small -> MaterialTheme.typography.bodyMedium
        LyricFontSize.Medium -> MaterialTheme.typography.bodyLarge
        LyricFontSize.Large -> MaterialTheme.typography.titleMedium
    }
    val translationStyle = when (fontSize) {
        LyricFontSize.Small -> MaterialTheme.typography.bodySmall
        LyricFontSize.Medium -> MaterialTheme.typography.bodyMedium
        LyricFontSize.Large -> MaterialTheme.typography.bodyLarge
    }
    val romanizationStyle = when (fontSize) {
        LyricFontSize.Small -> MaterialTheme.typography.labelMedium
        LyricFontSize.Medium -> MaterialTheme.typography.bodySmall
        LyricFontSize.Large -> MaterialTheme.typography.bodyMedium
    }
    val linePadding = when (fontSize) {
        LyricFontSize.Small -> 6.dp
        LyricFontSize.Medium -> 7.dp
        LyricFontSize.Large -> 8.dp
    }
    val currentTrack = state.currentTrack
    val associationMatchesTrack = associationState.trackId == currentTrack?.id

    LaunchedEffect(currentIndex, lines.size) {
        if (currentIndex < 0) return@LaunchedEffect
        val targetListIndex = currentIndex + 1
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == targetListIndex }) {
            listState.scrollToItem(targetListIndex)
            withFrameNanos { }
        }
        val layoutInfo = listState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetListIndex }
            ?: return@LaunchedEffect
        val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        val itemCenter = itemInfo.offset + itemInfo.size / 2f
        val scrollDelta = itemCenter - viewportCenter
        if (kotlin.math.abs(scrollDelta) > 1f) {
            listState.animateScrollBy(scrollDelta)
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        if (lines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(FuoSpacing.lg),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "暂无歌词",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (
                    currentTrack != null &&
                    associationMatchesTrack &&
                    associationState.isLyricsUnavailable
                ) {
                    TextButton(onClick = { lyricsPort.openAssociationSearch(currentTrack) }) {
                        Text("搜索并关联歌词")
                    }
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val edgeSpacerHeight = maxHeight / 2
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = FuoSpacing.lg),
                ) {
                    item(key = "lyrics-top-spacer") {
                        Spacer(modifier = Modifier.height(edgeSpacerHeight))
                    }
                    itemsIndexed(lines) { index, line ->
                        val active = index == currentIndex
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = linePadding),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            line.romanization?.takeIf { it.isNotBlank() }?.let { romanization ->
                                if (active && !line.romanizationWords.isNullOrEmpty()) {
                                    KaraokeLyricText(
                                        words = line.romanizationWords,
                                        positionMs = renderPositionMs,
                                        style = romanizationStyle,
                                        activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                                        inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    Text(
                                        text = romanization,
                                        style = romanizationStyle,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
                                        },
                                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            if (active && !line.words.isNullOrEmpty()) {
                                KaraokeLyricText(
                                    words = line.words,
                                    positionMs = renderPositionMs,
                                    style = activeStyle,
                                    activeColor = MaterialTheme.colorScheme.primary,
                                    inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    text = line.text,
                                    style = if (active) activeStyle else inactiveStyle,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f)
                                    },
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                                Text(
                                    text = translation,
                                    style = translationStyle,
                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (active) 0.52f else 0.38f,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    item(key = "lyrics-bottom-spacer") {
                        Spacer(modifier = Modifier.height(edgeSpacerHeight))
                    }
                }
                if (
                    currentTrack != null &&
                    associationMatchesTrack &&
                    associationState.isManualAssociation
                ) {
                    TextButton(
                        onClick = { lyricsPort.openAssociationSearch(currentTrack) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(FuoSpacing.sm),
                    ) {
                        Text("更换歌词")
                    }
                }
            }
        }
    }

    if (associationMatchesTrack && associationState.isSearchOpen) {
        LyricsAssociationDialog(
            state = associationState,
            onQueryChange = lyricsPort::updateAssociationQuery,
            onSearch = lyricsPort::searchAssociation,
            onSelect = lyricsPort::selectAssociation,
            onDismiss = lyricsPort::closeAssociationSearch,
        )
    }
}

@Composable
private fun LyricsAssociationDialog(
    state: LyricsAssociationUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (MusicTrack) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关联歌词") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.associatedTrackTitle?.takeIf { state.isManualAssociation }?.let { title ->
                    Text(
                        text = "当前关联：$title",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = { Text("搜索歌曲") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    state.isSearching -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    state.message != null -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.results.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(state.results, key = { _, track -> track.id }) { _, track ->
                            TextButton(
                                onClick = { onSelect(track) },
                                enabled = state.selectingTrackId == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val detail = listOf(
                                        track.artists.takeIf { it.isNotBlank() },
                                        (track.providerName ?: track.source).takeIf { it.isNotBlank() },
                                    ).filterNotNull().joinToString(" · ")
                                    if (detail.isNotBlank()) {
                                        Text(
                                            text = detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (state.selectingTrackId == track.id) {
                                        Text(
                                            text = "正在读取歌词…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSearch,
                enabled = !state.isSearching && state.selectingTrackId == null,
            ) {
                Text("搜索")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun rememberKaraokePositionMs(
    positionMs: Long,
    isPlaying: Boolean,
): androidx.compose.runtime.State<Long> {
    val renderPositionMs = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying) {
        renderPositionMs.longValue = positionMs
        if (!isPlaying) return@LaunchedEffect
        val anchorPosition = positionMs
        val anchorFrame = withFrameNanos { it }
        while (true) {
            withFrameNanos { frame ->
                val elapsedMs = (frame - anchorFrame) / 1_000_000L
                renderPositionMs.longValue = anchorPosition + elapsedMs
            }
        }
    }
    return renderPositionMs
}

@Composable
private fun KaraokeLyricText(
    words: List<LyricWord>,
    positionMs: androidx.compose.runtime.State<Long>,
    style: TextStyle,
    activeColor: Color,
    inactiveColor: Color,
    fontWeight: FontWeight = FontWeight.SemiBold,
    modifier: Modifier = Modifier,
) {
    val text = remember(words) { words.joinToString("") { it.text } }
    val textMeasurer = rememberTextMeasurer()
    val fontSize = style.fontSize
    val wordWidths = remember(words, fontSize, fontWeight, textMeasurer) {
        val measureStyle = style.copy(fontWeight = fontWeight)
        words.map { word ->
            textMeasurer.measure(
                text = word.text,
                style = measureStyle,
                constraints = Constraints(),
            ).size.width.toFloat()
        }
    }
    val progress = karaokeFillProgress(words, positionMs.value, wordWidths)
    val textStyle = style.copy(fontWeight = fontWeight)

    BoxWithConstraints(modifier = modifier) {
        val layoutResult = remember(text, textStyle, constraints.maxWidth, textMeasurer) {
            textMeasurer.measure(
                text = text,
                style = textStyle,
                constraints = Constraints(maxWidth = constraints.maxWidth),
            )
        }
        val totalVisualWidth = remember(layoutResult) {
            (0 until layoutResult.lineCount).sumOf { lineIndex ->
                (layoutResult.getLineRight(lineIndex) - layoutResult.getLineLeft(lineIndex))
                    .coerceAtLeast(0f)
                    .toDouble()
            }.toFloat()
        }

        Text(
            text = text,
            style = textStyle,
            color = inactiveColor,
            softWrap = true,
        )
        Text(
            text = text,
            style = textStyle,
            color = activeColor,
            softWrap = true,
            modifier = Modifier.drawWithContent {
                var remainingWidth = totalVisualWidth * progress.coerceIn(0f, 1f)
                if (remainingWidth <= 0f || !remainingWidth.isFinite()) return@drawWithContent

                for (lineIndex in 0 until layoutResult.lineCount) {
                    val lineLeft = layoutResult.getLineLeft(lineIndex)
                    val lineRight = layoutResult.getLineRight(lineIndex)
                    val lineWidth = (lineRight - lineLeft).coerceAtLeast(0f)
                    if (lineWidth <= 0f) continue

                    val lineFillWidth = minOf(remainingWidth, lineWidth)
                    if (lineFillWidth > 0f) {
                        clipRect(
                            left = lineLeft,
                            top = layoutResult.getLineTop(lineIndex),
                            right = lineLeft + lineFillWidth,
                            bottom = layoutResult.getLineBottom(lineIndex),
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    remainingWidth -= lineWidth
                    if (remainingWidth <= 0f) break
                }
            },
        )
    }
}
