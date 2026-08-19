package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal object FuoSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

internal object FuoMotion {
    const val pageTransitionMillis = 300
    const val pageFadeMillis = 180
    const val coverTransitionMillis = 360
    const val coverFadeMillis = 220
    const val coverColorTransitionMillis = 420
    const val progressAnimationMillis = 180
    const val overlayEnterMillis = 240
    const val overlayExitMillis = 200
    const val overlayFadeMillis = 160
}

internal object FuoMediaOverlay {
    val background = Color.Black
    val scrim = Color.Black.copy(alpha = 0.6f)
}

internal val FuoMinimumTouchTarget = 48.dp

internal fun Modifier.fuoInteractive(): Modifier = sizeIn(
    minWidth = FuoMinimumTouchTarget,
    minHeight = FuoMinimumTouchTarget,
)

@Composable
internal fun FuoSectionCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactiveModifier = if (onClick == null) {
        modifier
    } else {
        modifier
            .fuoInteractive()
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
    }
    Surface(
        modifier = interactiveModifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
            content = content,
        )
    }
}

@Composable
internal fun FuoListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val itemModifier = if (onClick == null) {
        modifier
    } else {
        modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }
    ListItem(
        headlineContent = headlineContent,
        modifier = if (onClick == null) itemModifier else itemModifier.fuoInteractive(),
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                supportingColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors()
        },
    )
}

@Composable
internal fun FuoSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    FuoListItem(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { text ->
            { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

@Composable
internal fun FuoMetadataChip(
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val chipModifier = modifier.height(AssistChipDefaults.Height)
    val chipShape = AssistChipDefaults.shape
    val chipColors = AssistChipDefaults.assistChipColors()
    val chipBorder = AssistChipDefaults.assistChipBorder(enabled = true)
    if (onClick != null) {
        AssistChip(
            modifier = chipModifier,
            onClick = onClick,
            label = { Text(label, maxLines = 1) },
            leadingIcon = leadingIcon?.let { icon ->
                { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) }
            },
            shape = chipShape,
            colors = chipColors,
            border = chipBorder,
        )
    } else {
        Surface(
            modifier = chipModifier,
            color = chipColors.containerColor,
            contentColor = chipColors.labelColor,
            shape = chipShape,
            border = chipBorder,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = chipColors.leadingIconContentColor,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = chipColors.labelColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun FuoEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FuoSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun FuoIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fuoInteractive()
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FuoSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "歌曲、歌手或专辑",
    trailingContent: (@Composable () -> Unit)? = null,
) {
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onSearch() },
                expanded = false,
                onExpandedChange = {},
                enabled = enabled,
                placeholder = { androidx.compose.material3.Text(placeholder) },
                leadingIcon = {
                    androidx.compose.material3.Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = trailingContent,
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier,
        content = {},
    )
}

internal fun standardContentPadding(): PaddingValues = PaddingValues(
    horizontal = FuoSpacing.lg,
    vertical = FuoSpacing.md,
)
