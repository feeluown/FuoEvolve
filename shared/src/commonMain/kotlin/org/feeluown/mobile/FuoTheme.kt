package org.feeluown.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FuoEvolveTheme(
    themeMode: ThemeMode,
    themeColorScheme: ThemeColorScheme,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolvedDarkTheme(themeMode, isSystemInDarkTheme())
    MaterialExpressiveTheme(
        colorScheme = fuoColorScheme(themeColorScheme, darkTheme),
        content = content,
    )
}

internal fun resolvedDarkTheme(themeMode: ThemeMode, systemDark: Boolean): Boolean {
    return when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

@Composable
private fun fuoColorScheme(themeColorScheme: ThemeColorScheme, darkTheme: Boolean): ColorScheme {
    if (themeColorScheme == ThemeColorScheme.Dynamic) {
        platformDynamicColorScheme(darkTheme)?.let { return it }
    }
    return presetColorScheme(
        preset = if (themeColorScheme == ThemeColorScheme.Dynamic) {
            ThemeColorScheme.ExpressiveDefault
        } else {
            themeColorScheme
        },
        darkTheme = darkTheme,
    )
}

@Composable
internal expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?

internal fun themePreviewColor(themeColorScheme: ThemeColorScheme, darkTheme: Boolean): Color {
    if (themeColorScheme == ThemeColorScheme.Dynamic) {
        return if (darkTheme) Color(0xFFB8C8FF) else Color(0xFF3F5FDC)
    }
    return when (themeColorScheme) {
        ThemeColorScheme.Dynamic -> Color.Unspecified
        ThemeColorScheme.ExpressiveDefault -> if (darkTheme) Color(0xFFD0BCFF) else Color(0xFF6750A4)
        ThemeColorScheme.FuoGreen -> if (darkTheme) Color(0xFF93DDAA) else Color(0xFF246B43)
        ThemeColorScheme.OceanBlue -> if (darkTheme) Color(0xFFA7C8FF) else Color(0xFF0066B3)
        ThemeColorScheme.Violet -> if (darkTheme) Color(0xFFD7B8FF) else Color(0xFF7650B4)
        ThemeColorScheme.Rose -> if (darkTheme) Color(0xFFFFB1C8) else Color(0xFFB13F66)
        ThemeColorScheme.Amber -> if (darkTheme) Color(0xFFFFCF73) else Color(0xFF8A5A00)
    }
}

private fun presetColorScheme(preset: ThemeColorScheme, darkTheme: Boolean): ColorScheme {
    return when (preset) {
        ThemeColorScheme.Dynamic,
        ThemeColorScheme.ExpressiveDefault -> expressiveDefaultColorScheme(darkTheme)
        ThemeColorScheme.FuoGreen -> if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFF93DDAA),
                onPrimary = Color(0xFF00391F),
                primaryContainer = Color(0xFF0A5430),
                onPrimaryContainer = Color(0xFFB0FAC5),
                secondary = Color(0xFFAFCDB7),
                onSecondary = Color(0xFF1B3524),
                secondaryContainer = Color(0xFF314C39),
                onSecondaryContainer = Color(0xFFCAE9D2),
                tertiary = Color(0xFFA4D1DC),
                onTertiary = Color(0xFF05363F),
                tertiaryContainer = Color(0xFF224D57),
                onTertiaryContainer = Color(0xFFC0EDF8),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF246B43),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFA9F5C1),
                onPrimaryContainer = Color(0xFF00210F),
                secondary = Color(0xFF4D6353),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFD0E8D6),
                onSecondaryContainer = Color(0xFF0A1F13),
                tertiary = Color(0xFF3D6670),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFC0ECF7),
                onTertiaryContainer = Color(0xFF001F27),
            )
        }
        ThemeColorScheme.OceanBlue -> if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFFA7C8FF),
                onPrimary = Color(0xFF00315F),
                primaryContainer = Color(0xFF004786),
                onPrimaryContainer = Color(0xFFD5E3FF),
                secondary = Color(0xFFBBC7DB),
                onSecondary = Color(0xFF253140),
                secondaryContainer = Color(0xFF3B4858),
                onSecondaryContainer = Color(0xFFD7E3F7),
                tertiary = Color(0xFFD8BDE8),
                onTertiary = Color(0xFF3B2948),
                tertiaryContainer = Color(0xFF523F5F),
                onTertiaryContainer = Color(0xFFF5D9FF),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF0066B3),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD5E3FF),
                onPrimaryContainer = Color(0xFF001C39),
                secondary = Color(0xFF53606F),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFD7E3F7),
                onSecondaryContainer = Color(0xFF101C2B),
                tertiary = Color(0xFF6A5778),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFF2DAFF),
                onTertiaryContainer = Color(0xFF241432),
            )
        }
        ThemeColorScheme.Violet -> if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFFD7B8FF),
                onPrimary = Color(0xFF40206F),
                primaryContainer = Color(0xFF583688),
                onPrimaryContainer = Color(0xFFEEDBFF),
                secondary = Color(0xFFCDC1D7),
                onSecondary = Color(0xFF342D3E),
                secondaryContainer = Color(0xFF4B4355),
                onSecondaryContainer = Color(0xFFE9DDF3),
                tertiary = Color(0xFFF0B8C8),
                onTertiary = Color(0xFF4A2533),
                tertiaryContainer = Color(0xFF643B49),
                onTertiaryContainer = Color(0xFFFFD9E3),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF7650B4),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFEEDBFF),
                onPrimaryContainer = Color(0xFF2A0055),
                secondary = Color(0xFF625A6B),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFE9DDF3),
                onSecondaryContainer = Color(0xFF1E1726),
                tertiary = Color(0xFF7E5260),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFFD9E3),
                onTertiaryContainer = Color(0xFF31101D),
            )
        }
        ThemeColorScheme.Rose -> if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFFFFB1C8),
                onPrimary = Color(0xFF651B3B),
                primaryContainer = Color(0xFF8B2A52),
                onPrimaryContainer = Color(0xFFFFD9E4),
                secondary = Color(0xFFE3BDC7),
                onSecondary = Color(0xFF422932),
                secondaryContainer = Color(0xFF5A3F49),
                onSecondaryContainer = Color(0xFFFFD9E4),
                tertiary = Color(0xFFF4C09B),
                onTertiary = Color(0xFF4A280D),
                tertiaryContainer = Color(0xFF653E21),
                onTertiaryContainer = Color(0xFFFFDCC5),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFFB13F66),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFFFD9E4),
                onPrimaryContainer = Color(0xFF3F001F),
                secondary = Color(0xFF735762),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFFFD9E4),
                onSecondaryContainer = Color(0xFF2A151F),
                tertiary = Color(0xFF805433),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFFDCC5),
                onTertiaryContainer = Color(0xFF2E1500),
            )
        }
        ThemeColorScheme.Amber -> if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFFFFCF73),
                onPrimary = Color(0xFF482900),
                primaryContainer = Color(0xFF664000),
                onPrimaryContainer = Color(0xFFFFDFA6),
                secondary = Color(0xFFD9C3A4),
                onSecondary = Color(0xFF3C2E1B),
                secondaryContainer = Color(0xFF554430),
                onSecondaryContainer = Color(0xFFF6DEBF),
                tertiary = Color(0xFFBBD0A4),
                onTertiary = Color(0xFF273619),
                tertiaryContainer = Color(0xFF3D4D2E),
                onTertiaryContainer = Color(0xFFD7ECC0),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF8A5A00),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFFFDFA6),
                onPrimaryContainer = Color(0xFF2B1700),
                secondary = Color(0xFF6D5C43),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFF6DEBF),
                onSecondaryContainer = Color(0xFF251A08),
                tertiary = Color(0xFF54643D),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFD7ECC0),
                onTertiaryContainer = Color(0xFF121F04),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun expressiveDefaultColorScheme(darkTheme: Boolean): ColorScheme {
    return if (darkTheme) {
        darkColorScheme()
    } else {
        expressiveLightColorScheme()
    }
}
