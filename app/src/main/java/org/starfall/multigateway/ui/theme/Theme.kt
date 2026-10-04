package org.starfall.multigateway.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext

private val DefaultLight = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D33),
    surface = Color(0xFFF8F9FA),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE1E3E1),
    onSurfaceVariant = Color(0xFF444746),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1F1F1F)
)

private val DefaultDark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF002F6C),
    primaryContainer = Color(0xFF041E49),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF7FCFFF),
    onSecondary = Color(0xFF003355),
    secondaryContainer = Color(0xFF004B75),
    onSecondaryContainer = Color(0xFFC2E7FF),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF444746),
    onSurfaceVariant = Color(0xFFC4C7C5),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9)
)

// Emerald palette
private val EmeraldLight = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF89F8C7),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6356),
    onSecondary = Color.White,
    surface = Color(0xFFF6FBF6),
    onSurface = Color(0xFF181D1A),
    background = Color(0xFFF6FBF6),
    onBackground = Color(0xFF181D1A)
)

private val EmeraldDark = darkColorScheme(
    primary = Color(0xFF6CDBAC),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFF89F8C7),
    secondary = Color(0xFFB4CCBD),
    onSecondary = Color(0xFF1F3529),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDFE4DF),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDFE4DF)
)

// Sunset palette
private val SunsetLight = lightColorScheme(
    primary = Color(0xFFA04000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF380D00),
    secondary = Color(0xFF77574C),
    onSecondary = Color.White,
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231916),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231916)
)

private val SunsetDark = darkColorScheme(
    primary = Color(0xFFFFB59D),
    onPrimary = Color(0xFF5B1B00),
    primaryContainer = Color(0xFF7D2C00),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BEAF),
    onSecondary = Color(0xFF442A21),
    surface = Color(0xFF1A110E),
    onSurface = Color(0xFFF1DFD9),
    background = Color(0xFF1A110E),
    onBackground = Color(0xFFF1DFD9)
)

// Crimson palette
private val CrimsonLight = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF9DEDC),
    onPrimaryContainer = Color(0xFF410E0B),
    secondary = Color(0xFF775656),
    onSecondary = Color.White,
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF201A1A),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF201A1A)
)

private val CrimsonDark = darkColorScheme(
    primary = Color(0xFFF2B8B5),
    onPrimary = Color(0xFF601410),
    primaryContainer = Color(0xFF8C1D18),
    onPrimaryContainer = Color(0xFFF9DEDC),
    secondary = Color(0xFFE6BDBC),
    onSecondary = Color(0xFF442929),
    surface = Color(0xFF191212),
    onSurface = Color(0xFFEDE0DF),
    background = Color(0xFF191212),
    onBackground = Color(0xFFEDE0DF)
)

// Violet palette
private val VioletLight = lightColorScheme(
    primary = Color(0xFF6B4FA0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF25025A),
    secondary = Color(0xFF635B70),
    onSecondary = Color.White,
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20)
)

private val VioletDark = darkColorScheme(
    primary = Color(0xFFD4BBFF),
    onPrimary = Color(0xFF3C1D70),
    primaryContainer = Color(0xFF533687),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFCDC2DB),
    onSecondary = Color(0xFF342D40),
    surface = Color(0xFF151218),
    onSurface = Color(0xFFE7E0E8),
    background = Color(0xFF151218),
    onBackground = Color(0xFFE7E0E8)
)

private val MonochromeLight = lightColorScheme(
    primary = Color(0xFF202124), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E3E3), onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF5F6368), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAED), onSecondaryContainer = Color(0xFF202124),
    surface = Color(0xFFFAFAFA), onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE5E5E5), onSurfaceVariant = Color(0xFF464646),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF1F1F1F)
)

private val MonochromeDark = darkColorScheme(
    primary = Color(0xFFE8EAED), onPrimary = Color(0xFF202124),
    primaryContainer = Color(0xFF3C4043), onPrimaryContainer = Color(0xFFF1F3F4),
    secondary = Color(0xFFBDC1C6), onSecondary = Color(0xFF292A2D),
    secondaryContainer = Color(0xFF444746), onSecondaryContainer = Color(0xFFE8EAED),
    surface = Color(0xFF121212), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF353535), onSurfaceVariant = Color(0xFFC7C7C7),
    background = Color(0xFF121212), onBackground = Color(0xFFE8EAED)
)

data class ThemePreset(val name: String, val preview: Color)

val ThemePresets = listOf(
    ThemePreset("DEFAULT", Color(0xFF0B57D0)), ThemePreset("EMERALD", Color(0xFF006C4C)),
    ThemePreset("SUNSET", Color(0xFFA04000)), ThemePreset("CRIMSON", Color(0xFFB3261E)),
    ThemePreset("VIOLET", Color(0xFF6B4FA0)), ThemePreset("MONOCHROME", Color(0xFF444746))
)

private fun completeScheme(source: ColorScheme, dark: Boolean): ColorScheme = source.copy(
    tertiary = source.secondary,
    onTertiary = source.onSecondary,
    tertiaryContainer = source.secondaryContainer,
    onTertiaryContainer = source.onSecondaryContainer,
    error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
    onError = if (dark) Color(0xFF690005) else Color.White,
    errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6),
    onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002),
    outline = if (dark) Color(0xFF8E918F) else Color(0xFF747775),
    outlineVariant = if (dark) Color(0xFF444746) else Color(0xFFC4C7C5),
    scrim = Color.Black,
    inverseSurface = source.onSurface,
    inverseOnSurface = source.surface,
    inversePrimary = source.primaryContainer,
    surfaceDim = if (dark) source.surface else source.surfaceVariant,
    surfaceBright = if (dark) source.surfaceVariant else source.surface,
    surfaceContainerLowest = if (dark) Color(0xFF090909) else Color.White,
    surfaceContainerLow = if (dark) source.surface.copy(alpha = 1f) else source.surface,
    surfaceContainer = source.surfaceVariant.copy(alpha = if (dark) 0.45f else 0.55f).compositeOver(source.surface.copy(alpha = 1f)),
    surfaceContainerHigh = source.surfaceVariant.copy(alpha = if (dark) 0.7f else 0.8f).compositeOver(source.surface.copy(alpha = 1f)),
    surfaceContainerHighest = source.surfaceVariant
)

private fun ColorScheme.withAmoledSurfaces(): ColorScheme = copy(
    background = Color.Black, surface = Color.Black,
    surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808), surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF181818), surfaceContainerHighest = Color(0xFF202020),
    surfaceBright = Color(0xFF242424), surfaceVariant = Color(0xFF202020)
)

@Composable
fun MultiGatewayTheme(
    themeMode: String = "SYSTEM",
    amoledMode: Boolean = false,
    dynamicColor: Boolean = true,
    colorSchemeName: String = "DEFAULT",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> systemDark
    }
    val isAmoled = amoledMode && isDark

    val selectedScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        colorSchemeName == "EMERALD" -> if (isDark) EmeraldDark else EmeraldLight
        colorSchemeName == "SUNSET" -> if (isDark) SunsetDark else SunsetLight
        colorSchemeName == "CRIMSON" -> if (isDark) CrimsonDark else CrimsonLight
        colorSchemeName == "VIOLET" -> if (isDark) VioletDark else VioletLight
        colorSchemeName == "MONOCHROME" -> if (isDark) MonochromeDark else MonochromeLight
        else -> if (isDark) DefaultDark else DefaultLight
    }
    val colorScheme = completeScheme(selectedScheme, isDark).let {
        if (isAmoled) it.withAmoledSurfaces() else it
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = Shapes(extraSmall = RoundedCornerShape(16.dp)),
        content = content
    )
}
