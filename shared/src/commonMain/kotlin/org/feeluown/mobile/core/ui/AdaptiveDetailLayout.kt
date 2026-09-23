package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun shouldShowDetailBackNavigation(isAdaptiveDetailPane: Boolean): Boolean =
    !isAdaptiveDetailPane

@Composable
internal fun AdaptiveDetailNavigationIcon(content: @Composable () -> Unit) {
    if (shouldShowDetailBackNavigation(LocalAppIsAdaptiveDetailPane.current)) content()
}

/**
 * Shared detail-page shell used by provider and local-library resources.
 *
 * [LocalAppLayoutInfo] is evaluated at the navigation entry's bounds. An adaptive Navigation 3
 * pane uses the compact layout instead of creating a second split inside its available space.
 * Very wide desktop windows center a bounded reading area rather than stretching track rows.
 */
@Composable
internal fun AdaptiveDetailLayout(
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.(stacked: Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalAppLayoutInfo.current.useWideLayout) {
        BoxWithConstraints(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .width(maxWidth.coerceAtMost(1200.dp))
                    .fillMaxHeight()
                    .padding(horizontal = FuoSpacing.xl, vertical = FuoSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(FuoSpacing.xl),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 260.dp, max = 360.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                ) {
                    header(true)
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                ) {
                    content()
                }
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
        ) {
            header(false)
            content()
        }
    }
}
