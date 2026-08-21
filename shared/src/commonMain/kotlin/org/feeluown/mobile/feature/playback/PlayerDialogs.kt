package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AudioFormatInfoDialog(
    info: AudioFormatInfo?,
    decoderInfo: AudioDecoderInfo? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音频信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info?.format?.takeIf { it.isNotBlank() }?.let { ReplacementInfoLine("当前格式", it) }
                info?.codec?.takeIf { it.isNotBlank() }?.let { ReplacementInfoLine("编码", it) }
                formatAudioBitrate(info?.averageBitrate)?.let { ReplacementInfoLine("平均比特率", it) }
                formatAudioBitrate(info?.peakBitrate)?.let { ReplacementInfoLine("峰值比特率", it) }
                decoderInfo?.let { decoder ->
                    ReplacementInfoLine(
                        "解码方式",
                        if (decoder.type == AudioDecoderType.Software) "软件解码" else "硬件解码",
                    )
                    decoder.name.takeIf { it.isNotBlank() }?.let {
                        ReplacementInfoLine("解码器", it)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

fun AudioFormatInfo.hasDisplayableValue(): Boolean {
    return !format.isNullOrBlank() ||
        !codec.isNullOrBlank() ||
        formatAudioBitrate(averageBitrate) != null ||
        formatAudioBitrate(peakBitrate) != null
}

fun formatAudioBitrate(value: Long?): String? {
    if (value == null || value <= 0) return null
    return "${(value / 1_000.0).roundToInt()} kbps"
}

@Composable
fun InfoTag(text: String, onClick: (() -> Unit)? = null) {
    FuoMetadataChip(label = text, onClick = onClick)
}

@Composable
fun ReplacementInfoDialog(
    track: MusicTrack,
    onDismiss: () -> Unit,
    onOpenDetail: (() -> Unit)? = null,
    candidateState: ReplacementCandidateState = ReplacementCandidateState(),
    onRetry: (() -> Unit)? = null,
    onSelectCandidate: ((ReplacementCandidate) -> Unit)? = null,
) {
    val detailAction = onOpenDetail?.takeIf { track.replacementId?.isNotBlank() == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("替换音频") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplacementInfoLine("标题", track.replacementTitle ?: track.title)
                ReplacementInfoLine("歌手", track.replacementArtists ?: track.artists)
                replacementProviderLabel(track).takeIf { it.isNotBlank() }?.let {
                    ReplacementInfoLine("来源", it)
                }
                track.replacementStrategy?.let {
                    ReplacementInfoLine("策略", it)
                }
                track.replacementScore?.let {
                    ReplacementInfoLine("匹配度", formatSmartReplacementScore(it))
                }
                Text(
                    text = "候选音源",
                    style = MaterialTheme.typography.titleSmall,
                )
                when {
                    candidateState.isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    candidateState.errorMessage != null -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "候选查询失败：${candidateState.errorMessage}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            onRetry?.let { retry ->
                                TextButton(onClick = retry) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                    candidateState.candidates.isEmpty() -> {
                        Text(
                            text = "暂无符合条件的候选音源",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(candidateState.candidates) { _, candidate ->
                                val isSelected = candidate.track.id == track.replacementId
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !isSelected) {
                                                onSelectCandidate?.invoke(candidate)
                                            }
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CoverBox(
                                            track = candidate.track,
                                            modifier = Modifier.size(48.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = candidate.track.title.ifBlank { "未知歌曲" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = candidate.track.artists.ifBlank { "未知歌手" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                text = "${sourceLabel(candidate.track, null)} · 匹配度 ${formatSmartReplacementScore(candidate.score)}",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        if (isSelected) {
                                            Text(
                                                text = "已选",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
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
                onClick = {
                    onDismiss()
                    if (detailAction != null) detailAction()
                },
            ) {
                Text(if (detailAction != null) "歌曲详情" else "关闭")
            }
        },
        dismissButton = if (detailAction != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        } else {
            null
        },
    )
}

@Composable
fun ReplacementInfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
