package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared detail-page shell used by provider and local-library resources.
 *
 * [LocalAppLayoutInfo] is intentionally evaluated at the current navigation entry's bounds. When a
 * route is hosted inside an adaptive Navigation 3 pane, the route therefore falls back to the
 * compact header/content composition instead of nesting another desktop split inside the pane.
 */
@Composable
internal fun AdaptiveDetailLayout(
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.(stacked: Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalAppLayoutInfo.current.useWideLayout) {
        Row(
            modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 360.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                header(true)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header(false)
            content()
        }
    }
}
