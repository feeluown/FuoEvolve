package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * User-facing diagnostics export.
 *
 * The former raw log viewer intentionally no longer exposes individual log lines. Users only need
 * a single support action while AppLogger and the platform repository own collection, redaction,
 * rotation, and archive generation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogFeatureScreen(
    controller: DebugLogFeatureController,
    onBack: () -> Unit,
) {
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        controller.refresh()
    }
    LaunchedEffect(uiState.feedback) {
        val feedback = uiState.feedback ?: return@LaunchedEffect
        controller.dismissFeedback(feedback)
        snackbarHostState.showSnackbar(feedback)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(28.dp))
            }

            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.BugReport,
                    contentDescription = null,
                    modifier = Modifier.padding(20.dp).size(40.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "导出诊断信息",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "遇到播放、登录、更新或平台集成问题时，可将诊断文件随 Issue 一并提交，帮助定位问题。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "诊断文件包含应用版本、平台环境和经过脱敏的滚动日志，不包含登录凭据或应用数据库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            uiState.errorMessage?.let { error ->
                Spacer(Modifier.height(20.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                enabled = !uiState.isLoading && uiState.lines.isNotEmpty(),
                onClick = { controller.export(uiState.lines) },
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("导出诊断信息")
            }

            if (!uiState.isLoading && uiState.lines.isEmpty() && uiState.errorMessage == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "当前暂无可导出的运行日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
