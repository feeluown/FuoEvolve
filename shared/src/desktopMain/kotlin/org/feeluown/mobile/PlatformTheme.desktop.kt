package org.feeluown.mobile

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal actual fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
internal actual fun platformWindowSurfaceEffect(surfaceColor: Color) = Unit
