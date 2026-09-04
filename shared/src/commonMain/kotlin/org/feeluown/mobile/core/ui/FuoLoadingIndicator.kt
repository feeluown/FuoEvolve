package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

private const val PageLoadingFadeOutMillis = 100
private const val PageContentFadeInDelayMillis = PageLoadingFadeOutMillis
private const val PageContentFadeInMillis = 160
private const val ShapeMorphIntervalMillis = 650
private const val ShapeRotationDurationMillis = 4666
private const val ShapePointCount = 36
private val ExpressiveLoadingShapes = listOf(
    radialShape(lobes = 10, depth = 0.17f, phase = 0.08f),
    radialShape(lobes = 9, depth = 0.10f, phase = 0.20f),
    radialShape(lobes = 5, depth = 0.08f, phase = -0.18f),
    superellipseShape(xScale = 1f, yScale = 0.58f, exponent = 0.52f),
    radialShape(lobes = 8, depth = 0.18f, phase = 0.12f),
    radialShape(lobes = 4, depth = 0.12f, phase = PI.toFloat() / 4f),
    ellipseShape(xScale = 0.78f, yScale = 1f),
)

/**
 * Material Expressive-style indeterminate indicator for the Compose Multiplatform Material3
 * version currently used by the app. The upstream Material3 component is newer than the available
 * multiplatform artifact, so this keeps the same seven-shape morphing treatment in commonMain.
 */
@Composable
fun ExpressiveLoadingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "expressive-loading")
    val shapeCycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = ExpressiveLoadingShapes.size.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ShapeMorphIntervalMillis * ExpressiveLoadingShapes.size,
                easing = LinearEasing,
            ),
        ),
        label = "expressive-loading-shape",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ShapeRotationDurationMillis,
                easing = LinearEasing,
            ),
        ),
        label = "expressive-loading-rotation",
    )
    val indicatorColor = MaterialTheme.colorScheme.primary
    // These objects are mutated only by the Canvas draw pass. Reusing them avoids allocating a
    // FloatArray and a native-backed Path on every animation frame, which is especially noticeable
    // on lower-end Android devices and the Skia desktop targets.
    val morphPoints = remember { FloatArray(ShapePointCount * 2) }
    val morphPath = remember { Path() }

    Canvas(
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = "加载中" },
    ) {
        val shapeCount = ExpressiveLoadingShapes.size
        val cycle = if (shapeCycle >= shapeCount.toFloat()) 0f else shapeCycle
        val shapeIndex = cycle.toInt().coerceIn(0, shapeCount - 1)
        val morphProgress = FastOutSlowInEasing.transform(cycle - shapeIndex)
        val start = ExpressiveLoadingShapes[shapeIndex]
        val end = ExpressiveLoadingShapes[(shapeIndex + 1) % shapeCount]
        val radius = min(size.width, size.height) / 3f
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        for (index in morphPoints.indices step 2) {
            morphPoints[index] = centerX + lerp(start[index], end[index], morphProgress) * radius
            morphPoints[index + 1] = centerY + lerp(start[index + 1], end[index + 1], morphProgress) * radius
        }

        morphPath.setSmoothClosed(morphPoints)
        rotate(degrees = rotation, pivot = center) {
            drawPath(path = morphPath, color = indicatorColor)
        }
    }
}

/** Shared indeterminate loading treatment for smaller content-area loading states. */
@Composable
fun LoadingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = fadeIn(animationSpec = tween(PageLoadingFadeOutMillis)),
        exit = fadeOut(animationSpec = tween(PageLoadingFadeOutMillis)),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            ExpressiveLoadingIndicator()
        }
    }
}

/**
 * Keeps page content composed while an initial load is in progress, but only reveals it after the
 * centered loading indicator has exited. Keeping the content composed avoids resetting list/scroll
 * state and page-side effects when loading completes.
 */
@Composable
fun PageLoadingContent(
    loading: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (loading) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (loading) PageLoadingFadeOutMillis else PageContentFadeInMillis,
            delayMillis = if (loading) 0 else PageContentFadeInDelayMillis,
        ),
        label = "page-loading-content-alpha",
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier.fillMaxSize().alpha(contentAlpha),
        ) {
            content()
        }
        AnimatedVisibility(
            visible = loading,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(PageLoadingFadeOutMillis)),
            exit = fadeOut(animationSpec = tween(PageLoadingFadeOutMillis)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                ExpressiveLoadingIndicator()
            }
        }
    }
}

private fun radialShape(lobes: Int, depth: Float, phase: Float): FloatArray =
    FloatArray(ShapePointCount * 2).also { points ->
        repeat(ShapePointCount) { index ->
            val angle = (2.0 * PI * index / ShapePointCount).toFloat()
            val radius = 0.83f + depth * cos(lobes * angle + phase)
            points[index * 2] = cos(angle) * radius
            points[index * 2 + 1] = sin(angle) * radius
        }
    }

private fun ellipseShape(xScale: Float, yScale: Float): FloatArray =
    FloatArray(ShapePointCount * 2).also { points ->
        repeat(ShapePointCount) { index ->
            val angle = (2.0 * PI * index / ShapePointCount).toFloat()
            points[index * 2] = cos(angle) * xScale
            points[index * 2 + 1] = sin(angle) * yScale
        }
    }

private fun superellipseShape(xScale: Float, yScale: Float, exponent: Float): FloatArray =
    FloatArray(ShapePointCount * 2).also { points ->
        repeat(ShapePointCount) { index ->
            val angle = (2.0 * PI * index / ShapePointCount).toFloat()
            val cosine = cos(angle)
            val sine = sin(angle)
            points[index * 2] = sign(cosine) * abs(cosine).pow(exponent) * xScale
            points[index * 2 + 1] = sign(sine) * abs(sine).pow(exponent) * yScale
        }
    }

private fun Path.setSmoothClosed(points: FloatArray) {
    reset()
    val pointCount = points.size / 2
    fun x(index: Int): Float = points[((index + pointCount) % pointCount) * 2]
    fun y(index: Int): Float = points[((index + pointCount) % pointCount) * 2 + 1]

    moveTo(x(0), y(0))
    repeat(pointCount) { index ->
        val p0 = index - 1
        val p1 = index
        val p2 = index + 1
        val p3 = index + 2
        cubicTo(
            x(p1) + (x(p2) - x(p0)) / 6f,
            y(p1) + (y(p2) - y(p0)) / 6f,
            x(p2) - (x(p3) - x(p1)) / 6f,
            y(p2) - (y(p3) - y(p1)) / 6f,
            x(p2),
            y(p2),
        )
    }
    close()
}

private fun lerp(start: Float, end: Float, progress: Float): Float =
    start + (end - start) * progress
