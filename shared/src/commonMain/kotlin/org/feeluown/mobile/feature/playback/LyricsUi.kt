package org.feeluown.mobile

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

@Composable
fun LyricsPanel(state: PlaybackState, fontSize: LyricFontSize, modifier: Modifier) {
    val lines = remember(state.lyrics) { parseLyrics(state.lyrics) }
    val displayLines = lines.takeIf { it.isNotEmpty() } ?: listOf(LyricLine(0, "暂无歌词"))
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
                itemsIndexed(displayLines) { index, line ->
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
        }
    }
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
