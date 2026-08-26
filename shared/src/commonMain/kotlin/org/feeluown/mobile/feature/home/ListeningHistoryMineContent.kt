package org.feeluown.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.feeluown.mobile.provider.core.network.currentTimeMillis

private const val LISTENING_DAY_MS = 86_400_000L
private const val LISTENING_RECENT_LIMIT = 500
private const val LISTENING_TOP_LIMIT = 50

private enum class ListeningHistoryTab(val label: String) {
    Recent("最近"),
    Frequent("常听"),
    Statistics("统计"),
}

private enum class ListeningRangePreset(val label: String) {
    SevenDays("7 天"),
    ThirtyDays("30 天"),
    NinetyDays("90 天"),
    ThisYear("今年"),
    All("全部"),
}

private data class ListeningRecentType(
    val type: ListeningResourceType?,
    val label: String,
)

private val listeningRecentTypes = listOf(
    ListeningRecentType(null, "全部"),
    ListeningRecentType(ListeningResourceType.Track, "歌曲"),
    ListeningRecentType(ListeningResourceType.Artist, "歌手"),
    ListeningRecentType(ListeningResourceType.Album, "专辑"),
    ListeningRecentType(ListeningResourceType.Playlist, "歌单"),
    ListeningRecentType(ListeningResourceType.Feature, "功能"),
)

@Composable
internal fun ListeningHistoryMineContent(
    repository: ListeningHistoryRepository,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(ListeningHistoryTab.Recent) }
    var rangePreset by remember { mutableStateOf(ListeningRangePreset.ThirtyDays) }
    var recentType by remember { mutableStateOf(listeningRecentTypes.first()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recentEvents by remember { mutableStateOf<List<ListeningHistoryEvent>>(emptyList()) }
    var recentResources by remember { mutableStateOf<List<ListeningResourceStat>>(emptyList()) }
    var topResources by remember { mutableStateOf<Map<ListeningResourceType, List<ListeningResourceStat>>>(emptyMap()) }
    var insights by remember { mutableStateOf(ListeningInsights()) }

    LaunchedEffect(repository, rangePreset, recentType, refreshToken) {
        isLoading = true
        errorMessage = null
        val now = currentTimeMillis()
        val range = rangePreset.toTimeRange(now)
        runCatching {
            val events = repository.recentEvents(
                range = ListeningTimeRange.All,
                limit = LISTENING_RECENT_LIMIT,
                resourceType = recentType.type.takeIf { it == ListeningResourceType.Track },
            )
            val resources = recentType.type
                ?.takeUnless { it == ListeningResourceType.Track }
                ?.let { type ->
                    repository.recentResources(
                        range = ListeningTimeRange.All,
                        limit = LISTENING_RECENT_LIMIT,
                        resourceType = type,
                    )
                }
                .orEmpty()
            val resourceTypes = listOf(
                ListeningResourceType.Track,
                ListeningResourceType.Artist,
                ListeningResourceType.Album,
                ListeningResourceType.Playlist,
                ListeningResourceType.Feature,
                ListeningResourceType.LocalDirectory,
            )
            val tops = resourceTypes.associateWith { type ->
                repository.topResources(type, range, LISTENING_TOP_LIMIT)
            }
            val summary = repository.insights(range)
            DashboardLoad(events, resources, tops, summary)
        }.onSuccess { loaded ->
            recentEvents = loaded.events
            recentResources = loaded.resources
            topResources = loaded.topResources
            insights = loaded.insights
        }.onFailure { throwable ->
            errorMessage = throwable.message ?: "读取听歌记录失败"
        }
        isLoading = false
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ListeningHistoryTab.entries.forEach { candidate ->
                FilterChip(
                    selected = tab == candidate,
                    onClick = { tab = candidate },
                    label = { Text(candidate.label) },
                )
            }
            TextButton(onClick = { refreshToken++ }) { Text("刷新") }
        }

        if (tab != ListeningHistoryTab.Recent) {
            ListeningRangeChips(rangePreset) { rangePreset = it }
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listeningRecentTypes.forEach { candidate ->
                    FilterChip(
                        selected = recentType == candidate,
                        onClick = { recentType = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }
        }

        if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        errorMessage?.let { ProviderContentMessage(it) }

        when (tab) {
            ListeningHistoryTab.Recent -> ListeningRecentContent(
                recentType = recentType,
                events = recentEvents,
                resources = recentResources,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ListeningHistoryTab.Frequent -> ListeningFrequentContent(
                rangePreset = rangePreset,
                resources = topResources,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ListeningHistoryTab.Statistics -> ListeningStatisticsContent(
                rangePreset = rangePreset,
                insights = insights,
                resources = topResources,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

private data class DashboardLoad(
    val events: List<ListeningHistoryEvent>,
    val resources: List<ListeningResourceStat>,
    val topResources: Map<ListeningResourceType, List<ListeningResourceStat>>,
    val insights: ListeningInsights,
)

@Composable
private fun ListeningRangeChips(
    selected: ListeningRangePreset,
    onSelect: (ListeningRangePreset) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ListeningRangePreset.entries.forEach { candidate ->
            FilterChip(
                selected = selected == candidate,
                onClick = { onSelect(candidate) },
                label = { Text(candidate.label) },
            )
        }
    }
}

@Composable
private fun ListeningRecentContent(
    recentType: ListeningRecentType,
    events: List<ListeningHistoryEvent>,
    resources: List<ListeningResourceStat>,
    modifier: Modifier,
) {
    if (recentType.type == null || recentType.type == ListeningResourceType.Track) {
        val grouped = events.groupBy { event -> formatListeningUtcDay(event.startedAtMillis) }
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            grouped.forEach { (day, dayEvents) ->
                item("day:$day") {
                    Text(day, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(dayEvents, key = { it.sessionKey }) { event ->
                    ListeningEventRow(event)
                    HorizontalDivider()
                }
            }
            if (events.isEmpty()) item("empty") { ProviderContentMessage("暂无听歌记录") }
        }
    } else {
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(resources, key = { it.resource.resourceKey }) { stat ->
                ListeningResourceRow(stat, showRanking = false)
                HorizontalDivider()
            }
            if (resources.isEmpty()) item("empty") { ProviderContentMessage("暂无${recentType.label}记录") }
        }
    }
}

@Composable
private fun ListeningEventRow(event: ListeningHistoryEvent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(event.primaryResource.title.ifBlank { "未知资源" }, style = MaterialTheme.typography.bodyLarge)
        val details = buildList {
            event.primaryResource.subtitle.takeIf { it.isNotBlank() }?.let(::add)
            add(formatListeningDuration(event.playedMs))
            add(event.startReason.displayName())
            if (event.qualified) add("有效播放")
        }
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ListeningFrequentContent(
    rangePreset: ListeningRangePreset,
    resources: Map<ListeningResourceType, List<ListeningResourceStat>>,
    modifier: Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("title") {
            Text("${rangePreset.label}常听", style = MaterialTheme.typography.titleMedium)
            Text("按有效播放次数、播放时长、最近播放依次排序", style = MaterialTheme.typography.bodySmall)
        }
        listeningStatSections().forEach { section ->
            val rows = resources[section.first].orEmpty().filter { it.qualifiedPlayCount > 0L }.take(10)
            if (rows.isNotEmpty()) {
                item("header:${section.first.name}") {
                    Text(section.second, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(rows, key = { "${section.first}:${it.resource.resourceKey}" }) { stat ->
                    ListeningResourceRow(stat, showRanking = true)
                    HorizontalDivider()
                }
            }
        }
        if (resources.values.all { rows -> rows.none { it.qualifiedPlayCount > 0L } }) {
            item("empty") { ProviderContentMessage("这个时间范围内还没有有效播放") }
        }
    }
}

@Composable
private fun ListeningStatisticsContent(
    rangePreset: ListeningRangePreset,
    insights: ListeningInsights,
    resources: Map<ListeningResourceType, List<ListeningResourceStat>>,
    modifier: Modifier,
) {
    val topTracks = resources[ListeningResourceType.Track].orEmpty().filter { it.qualifiedPlayCount > 0L }
    val recommendationSeeds = topTracks.take(5)
    val overplayed = topTracks.filter { it.qualifiedPlayCount >= 10L }.take(5)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("replay") {
            Text("${rangePreset.replayLabel()} Replay", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ListeningMetric("听歌", "${insights.qualifiedPlayCount} 次")
                ListeningMetric("时长", formatListeningDuration(insights.totalPlayedMs))
                ListeningMetric("活跃", "${insights.activeDays} 天")
            }
        }
        item("selection") {
            Text("播放方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "主动 ${insights.userSelectedPlayCount} 次 · 自动续播 ${insights.automaticPlayCount} 次",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (insights.sourceShares.isNotEmpty()) {
            item("sources-header") { Text("实际播放来源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(insights.sourceShares.take(10), key = { "source:${it.sourceId}" }) { share ->
                Text("${share.sourceId} · ${formatListeningDuration(share.playedMs)} · ${share.eventCount} 次")
            }
        }
        if (recommendationSeeds.isNotEmpty()) {
            item("seed-header") {
                Text("推荐种子", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("来自当前时间范围内最稳定的有效播放偏好", style = MaterialTheme.typography.bodySmall)
            }
            items(recommendationSeeds, key = { "seed:${it.resource.resourceKey}" }) { stat ->
                ListeningResourceRow(stat, showRanking = true)
            }
        }
        if (overplayed.isNotEmpty()) {
            item("overplay-header") {
                Text("高频播放", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("可作为推荐去重/降权输入；阈值为当前范围有效播放 ≥ 10 次", style = MaterialTheme.typography.bodySmall)
            }
            items(overplayed, key = { "overplay:${it.resource.resourceKey}" }) { stat ->
                ListeningResourceRow(stat, showRanking = true)
            }
        }
        if (insights.trend.isNotEmpty()) {
            item("trend-header") { Text("听歌趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(insights.trend.takeLast(30), key = { "trend:${it.bucketStartMillis}" }) { point ->
                Text(
                    "${formatListeningUtcDay(point.bucketStartMillis)} · ${point.qualifiedPlayCount} 次 · ${formatListeningDuration(point.playedMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (insights.eventCount == 0L) item("empty") { ProviderContentMessage("这个时间范围内还没有听歌数据") }
    }
}

@Composable
private fun ListeningMetric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ListeningResourceRow(stat: ListeningResourceStat, showRanking: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(stat.resource.title.ifBlank { stat.resource.sourceResourceId }, style = MaterialTheme.typography.bodyLarge)
        val detail = buildList {
            stat.resource.subtitle.takeIf { it.isNotBlank() }?.let(::add)
            if (showRanking) add("有效 ${stat.qualifiedPlayCount} 次")
            add(formatListeningDuration(stat.playedMs))
            if (stat.contextSessionCount > 0L) add("${stat.contextSessionCount} 次会话")
            if (!showRanking) add("最近 ${formatListeningUtcDay(stat.lastPlayedAtMillis)}")
        }
        Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

private fun listeningStatSections() = listOf(
    ListeningResourceType.Track to "歌曲",
    ListeningResourceType.Artist to "歌手",
    ListeningResourceType.Album to "专辑",
    ListeningResourceType.Playlist to "歌单",
    ListeningResourceType.Feature to "功能 / 推荐",
    ListeningResourceType.LocalDirectory to "本地目录",
)

private fun ListeningRangePreset.toTimeRange(nowMillis: Long): ListeningTimeRange {
    val end = (nowMillis + 1L).coerceAtMost(Long.MAX_VALUE)
    val start = when (this) {
        ListeningRangePreset.SevenDays -> (nowMillis - 7L * LISTENING_DAY_MS).coerceAtLeast(0L)
        ListeningRangePreset.ThirtyDays -> (nowMillis - 30L * LISTENING_DAY_MS).coerceAtLeast(0L)
        ListeningRangePreset.NinetyDays -> (nowMillis - 90L * LISTENING_DAY_MS).coerceAtLeast(0L)
        ListeningRangePreset.ThisYear -> startOfListeningUtcYear(nowMillis)
        ListeningRangePreset.All -> 0L
    }
    return if (this == ListeningRangePreset.All) ListeningTimeRange.All else ListeningTimeRange(start, end)
}

private fun ListeningRangePreset.replayLabel(): String = when (this) {
    ListeningRangePreset.ThisYear -> "今年"
    ListeningRangePreset.All -> "全部"
    else -> label
}

private fun ListeningStartReason.displayName(): String = when (this) {
    ListeningStartReason.UserSelection -> "主动选择"
    ListeningStartReason.PlaylistReplace -> "全部播放"
    ListeningStartReason.AutoNext -> "自动续播"
    ListeningStartReason.Resume -> "继续播放"
    ListeningStartReason.RestoreSession -> "恢复会话"
    ListeningStartReason.Unknown -> "播放"
}

private fun formatListeningDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}小时${minutes}分"
        minutes > 0L -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

private fun startOfListeningUtcYear(timestampMs: Long): Long {
    val (year, _, _) = listeningCivilFromEpochDay(timestampMs / LISTENING_DAY_MS)
    return listeningDaysFromCivil(year, 1, 1) * LISTENING_DAY_MS
}

private fun formatListeningUtcDay(timestampMs: Long): String {
    val (year, month, day) = listeningCivilFromEpochDay(timestampMs.coerceAtLeast(0L) / LISTENING_DAY_MS)
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

/** Gregorian civil-date conversion in UTC, avoiding a platform-specific date API in common UI code. */
private fun listeningCivilFromEpochDay(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719_468L
    val era = z / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    var year = (yearOfEra + era * 400L).toInt()
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * monthPrime + 2L) / 5L + 1L).toInt()
    val month = (monthPrime + if (monthPrime < 10L) 3L else -9L).toInt()
    if (month <= 2) year += 1
    return Triple(year, month, day)
}

private fun listeningDaysFromCivil(yearValue: Int, monthValue: Int, dayValue: Int): Long {
    var year = yearValue.toLong()
    val month = monthValue.toLong()
    if (month <= 2L) year -= 1L
    val era = year / 400L
    val yearOfEra = year - era * 400L
    val monthPrime = month + if (month > 2L) -3L else 9L
    val dayOfYear = (153L * monthPrime + 2L) / 5L + dayValue - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}
