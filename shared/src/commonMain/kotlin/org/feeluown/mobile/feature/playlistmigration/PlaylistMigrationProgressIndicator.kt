package org.feeluown.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

// Only the visual indicator advances within the song currently being processed. Persisted
// progress and the displayed count always use completed checkpoints from the coordinator.
private const val InFlightSongFraction = 0.9f
private const val InFlightSongEstimateMillis = 3_000

internal fun migrationActualFraction(completed: Int, total: Int): Float =
    if (total <= 0) 0f else completed.coerceIn(0, total).toFloat() / total

internal fun migrationInFlightFraction(completed: Int, total: Int): Float {
    if (total <= 0) return 0f
    val done = completed.coerceIn(0, total)
    if (done == total) return 1f
    // Never show a completed song (or 100%) until a real checkpoint confirms it.
    return ((done + InFlightSongFraction) / total).coerceIn(0f, 1f)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaylistMigrationProgressIndicator(
    progress: PlaylistMigrationBackgroundProgress,
    modifier: Modifier = Modifier,
) {
    if (progress.indeterminate) {
        LinearWavyProgressIndicator(modifier = modifier)
        return
    }

    val total = progress.total.coerceAtLeast(1)
    val completed = progress.completed.coerceIn(0, total)
    val actual = migrationActualFraction(completed, total)
    val inFlight = migrationInFlightFraction(completed, total)
    // Reinitialise on task/stage changes, but not after every checkpoint. Animatable retains
    // its current frame when a newer checkpoint cancels the previous interpolation.
    val animated = remember(progress.taskId, progress.stage) { Animatable(actual) }
    val catchUpSpec = FuoMotion.fastEffectsSpec<Float>()
    LaunchedEffect(progress.taskId, progress.stage, completed, total) {
        if (actual > animated.value) {
            animated.animateTo(actual, animationSpec = catchUpSpec)
        }
        if (inFlight > animated.value) {
            // Gradually fill at most 90% of the next song's segment. A slow request stops
            // below the next checkpoint; a fast request smoothly catches up without jumping.
            animated.animateTo(
                targetValue = inFlight,
                animationSpec = tween(InFlightSongEstimateMillis, easing = LinearEasing),
            )
        }
    }
    // Even if a theme motion spring overshoots, do not render ahead of a real checkpoint.
    LinearWavyProgressIndicator(
        progress = { animated.value.coerceIn(0f, inFlight) },
        modifier = modifier,
    )
}
