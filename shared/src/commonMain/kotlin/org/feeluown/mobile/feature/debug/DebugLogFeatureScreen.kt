package org.feeluown.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val FEATURE_DEBUG_LOG_THREADTIME_LEVEL_REGEX =
    Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([DIWEAF])\s+""")
private val FEATURE_DEBUG_LOG_TIME_LEVEL_REGEX =
    Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+([DIWEAF])/""")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DebugLogFeatureScreen(
    controller: DebugLogFeatureController,
    onBack: () -> Unit,
) {
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    // Compose Multiplatform does not yet expose a common plain-text ClipEntry factory for Android and iOS.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedLineIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val visibleLogLines = remember(uiState.lines, uiState.levelFilters) {
        uiState.lines.mapIndexedNotNull { index, line ->
            val level = parseFeatureDebugLogLevel(line) ?: DebugLogLevel.Info
            if (level in uiState.levelFilters) index to line else null
        }
    }
    val selectedLines = remember(uiState.lines, selectedLineIndexes) {
        selectedLineIndexes.sorted().mapNotNull { uiState.lines.getOrNull(it) }
    }

    LaunchedEffect(Unit) {
        controller.refresh()
    }
    LaunchedEffect(uiState.lines) {
        selectedLineIndexes = selectedLineIndexes.filter { it in uiState.lines.indices }.toSet()
    }
    LaunchedEffect(selectedLineIndexes) {
        if (selectedLineIndexes.isEmpty()) {
            selectionMode = false
        }
    }
    LaunchedEffect(uiState.feedback) {
        val feedback = uiState.feedback ?: return@LaunchedEffect
        controller.dismissFeedback(feedback)
        snackbarHostState.showSnackbar(feedback)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("应用日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !uiState.isLoading,
                        onClick = controller::refresh,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            uiState.errorMessage?.let { ProviderContentMessage(it) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DebugLogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = level in uiState.levelFilters,
                        onClick = {
                            controller.onLevelFilterChange(
                                level,
                                level !in uiState.levelFilters,
                            )
                        },
                        label = { Text(featureDebugLogLevelLabel(level)) },
                        colors = settingsFilterChipColors(),
                    )
                }
            }
            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已选 ${selectedLineIndexes.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = visibleLogLines.isNotEmpty(),
                        onClick = {
                            val visibleIndexes = visibleLogLines.map { it.first }.toSet()
                            selectedLineIndexes = if (visibleIndexes.all { it in selectedLineIndexes }) {
                                selectedLineIndexes - visibleIndexes
                            } else {
                                selectedLineIndexes + visibleIndexes
                            }
                        },
                    ) {
                        Text("全选")
                    }
                    TextButton(
                        enabled = selectedLines.isNotEmpty(),
                        onClick = {
                            clipboardManager.setText(AnnotatedString(selectedLines.joinToString(separator = "\n")))
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("复制所选")
                    }
                    TextButton(
                        enabled = selectedLines.isNotEmpty() && !uiState.isLoading,
                        onClick = { controller.export(selectedLines) },
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("导出所选")
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (visibleLogLines.isEmpty() && !uiState.isLoading && uiState.errorMessage == null) {
                    item { ProviderContentMessage("暂无日志") }
                } else {
                    itemsIndexed(visibleLogLines) { _, indexedLine ->
                        val originalIndex = indexedLine.first
                        val line = indexedLine.second
                        val level = parseFeatureDebugLogLevel(line)
                        val selected = originalIndex in selectedLineIndexes
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedLineIndexes = if (selected) {
                                                selectedLineIndexes - originalIndex
                                            } else {
                                                selectedLineIndexes + originalIndex
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        selectionMode = true
                                        selectedLineIndexes = selectedLineIndexes + originalIndex
                                    },
                                ),
                            color = featureDebugLogLevelContainerColor(level),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (selectionMode) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { checked ->
                                            selectedLineIndexes = if (checked) {
                                                selectedLineIndexes + originalIndex
                                            } else {
                                                selectedLineIndexes - originalIndex
                                            }
                                        },
                                    )
                                }
                                Text(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(top = 10.dp),
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = featureDebugLogLevelContentColor(level),
                                )
                                IconButton(
                                    modifier = Modifier.fuoInteractive().size(48.dp),
                                    onClick = { clipboardManager.setText(AnnotatedString(line)) },
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun parseFeatureDebugLogLevel(line: String): DebugLogLevel? {
    val code = FEATURE_DEBUG_LOG_THREADTIME_LEVEL_REGEX.find(line)?.groupValues?.getOrNull(1)
        ?: FEATURE_DEBUG_LOG_TIME_LEVEL_REGEX.find(line)?.groupValues?.getOrNull(1)
        ?: return null
    return when (code) {
        "D" -> DebugLogLevel.Debug
        "I" -> DebugLogLevel.Info
        "W" -> DebugLogLevel.Warning
        "E", "A", "F" -> DebugLogLevel.Error
        else -> null
    }
}

private fun featureDebugLogLevelLabel(level: DebugLogLevel): String = when (level) {
    DebugLogLevel.Debug -> "Debug"
    DebugLogLevel.Info -> "Info"
    DebugLogLevel.Warning -> "Warn"
    DebugLogLevel.Error -> "Error"
}

@Composable
private fun featureDebugLogLevelContainerColor(level: DebugLogLevel?) = when (level) {
    DebugLogLevel.Debug -> MaterialTheme.colorScheme.surfaceContainerHigh
    DebugLogLevel.Info -> MaterialTheme.colorScheme.secondaryContainer
    DebugLogLevel.Warning -> MaterialTheme.colorScheme.tertiaryContainer
    DebugLogLevel.Error -> MaterialTheme.colorScheme.errorContainer
    null -> MaterialTheme.colorScheme.surface
}

@Composable
private fun featureDebugLogLevelContentColor(level: DebugLogLevel?) = when (level) {
    DebugLogLevel.Info -> MaterialTheme.colorScheme.onSecondaryContainer
    DebugLogLevel.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
    DebugLogLevel.Error -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurface
}
