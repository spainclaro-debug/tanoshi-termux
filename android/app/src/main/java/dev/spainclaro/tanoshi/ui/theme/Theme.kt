package dev.spainclaro.tanoshi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * User-selectable app theme. [id] is the value persisted in
 * [dev.spainclaro.tanoshi.settings.AppSettings.themeId]; unknown/legacy ids
 * fall back to [DARK]. [isDark] drives the system-bar icon contrast.
 *
 * The embedded Tanoshi web UI is served by the native binary and keeps its own
 * theme independently of this selection.
 */
enum class ThemeMode(val id: String, val isDark: Boolean) {
    DARK("dark", true),
    LIGHT("light", false),
    NORD("nord", true),
    CATPPUCCIN("catppuccin", true),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: DARK
    }
}

// ---------------------------------------------------------------------------
// Color schemes
// ---------------------------------------------------------------------------

/**
 * Default dark scheme: near-black blue-grey surfaces with an amber accent and
 * teal/pink support colours. Kept from the original app design.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE8B84B),
    onPrimary = Color(0xFF2A1F04),
    primaryContainer = Color(0xFF3E2F0B),
    onPrimaryContainer = Color(0xFFFFE3A1),
    inversePrimary = Color(0xFF7A5F0E),
    secondary = Color(0xFF5FD0C5),
    onSecondary = Color(0xFF04211E),
    secondaryContainer = Color(0xFF0F3B37),
    onSecondaryContainer = Color(0xFFB9F2EC),
    tertiary = Color(0xFFF29BB6),
    onTertiary = Color(0xFF3C0B1B),
    tertiaryContainer = Color(0xFF4F1729),
    onTertiaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE7EBF0),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE7EBF0),
    surfaceVariant = Color(0xFF2A343E),
    onSurfaceVariant = Color(0xFFA3AFBC),
    surfaceTint = Color(0xFFE8B84B),
    inverseSurface = Color(0xFFE7EBF0),
    inverseOnSurface = Color(0xFF1B222A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF43070A),
    errorContainer = Color(0xFF5C151A),
    onErrorContainer = Color(0xFFFFD9D9),
    outline = Color(0xFF4B5866),
    outlineVariant = Color(0xFF2C3742),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF323C47),
    surfaceDim = Color(0xFF101418),
    surfaceContainerLowest = Color(0xFF0B0F12),
    surfaceContainerLow = Color(0xFF151B21),
    surfaceContainer = Color(0xFF181F26),
    surfaceContainerHigh = Color(0xFF1F272F),
    surfaceContainerHighest = Color(0xFF2A343E),
)

/**
 * Warm light scheme: paper-tinted surfaces with the same amber identity, but a
 * darker amber so it stays readable as a button/primary colour on light
 * backgrounds (it is not just an inverted copy of the dark scheme).
 */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF7A5F0E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE3A1),
    onPrimaryContainer = Color(0xFF251A00),
    inversePrimary = Color(0xFFE8B84B),
    secondary = Color(0xFF00696A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB6F2EC),
    onSecondaryContainer = Color(0xFF00201E),
    tertiary = Color(0xFF9C3F5B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF3C0B1B),
    background = Color(0xFFFFFBF2),
    onBackground = Color(0xFF1D1B16),
    surface = Color(0xFFFFFBF2),
    onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFE9E3D5),
    onSurfaceVariant = Color(0xFF4C4639),
    surfaceTint = Color(0xFF7A5F0E),
    inverseSurface = Color(0xFF32302A),
    inverseOnSurface = Color(0xFFF5EFE3),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF7C7668),
    outlineVariant = Color(0xFFD3CDBF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFBF2),
    surfaceDim = Color(0xFFDDD7C9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF5E9),
    surfaceContainer = Color(0xFFF4EDDF),
    surfaceContainerHigh = Color(0xFFEDE6D6),
    surfaceContainerHighest = Color(0xFFE6DECC),
)

/**
 * Nord: Polar Night surfaces, Snow Storm text, Frost accents and Aurora green
 * as the tertiary colour. Source palette: nordtheme.com.
 */
private val NordColorScheme = darkColorScheme(
    primary = Color(0xFF88C0D0),
    onPrimary = Color(0xFF2E3440),
    primaryContainer = Color(0xFF434C5E),
    onPrimaryContainer = Color(0xFFE5E9F0),
    inversePrimary = Color(0xFF5E81AC),
    secondary = Color(0xFF81A1C1),
    onSecondary = Color(0xFF2E3440),
    secondaryContainer = Color(0xFF3B4252),
    onSecondaryContainer = Color(0xFFD8DEE9),
    tertiary = Color(0xFFA3BE8C),
    onTertiary = Color(0xFF2E3440),
    tertiaryContainer = Color(0xFF3F4A38),
    onTertiaryContainer = Color(0xFFE4EDDA),
    background = Color(0xFF2E3440),
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF2E3440),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF3B4252),
    onSurfaceVariant = Color(0xFFD8DEE9),
    surfaceTint = Color(0xFF88C0D0),
    inverseSurface = Color(0xFFECEFF4),
    inverseOnSurface = Color(0xFF2E3440),
    error = Color(0xFFBF616A),
    onError = Color(0xFF2E3440),
    errorContainer = Color(0xFF4A3138),
    onErrorContainer = Color(0xFFFFD9DC),
    outline = Color(0xFF616E88),
    outlineVariant = Color(0xFF434C5E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF434C5E),
    surfaceDim = Color(0xFF232831),
    surfaceContainerLowest = Color(0xFF232831),
    surfaceContainerLow = Color(0xFF2A303C),
    surfaceContainer = Color(0xFF333A47),
    surfaceContainerHigh = Color(0xFF3B4252),
    surfaceContainerHighest = Color(0xFF434C5E),
)

/**
 * Catppuccin Mocha: base/mantle/crust surfaces with mauve, blue and peach
 * accents. Source palette: catppuccin.com (Mocha flavour).
 */
private val CatppuccinColorScheme = darkColorScheme(
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF1E1E2E),
    primaryContainer = Color(0xFF453C63),
    onPrimaryContainer = Color(0xFFE9D8FF),
    inversePrimary = Color(0xFF9A6ED4),
    secondary = Color(0xFF89B4FA),
    onSecondary = Color(0xFF1E1E2E),
    secondaryContainer = Color(0xFF313D5E),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFFFAB387),
    onTertiary = Color(0xFF1E1E2E),
    tertiaryContainer = Color(0xFF4A3A2E),
    onTertiaryContainer = Color(0xFFFFE0CB),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFA6ADC8),
    surfaceTint = Color(0xFFCBA6F7),
    inverseSurface = Color(0xFFCDD6F4),
    inverseOnSurface = Color(0xFF1E1E2E),
    error = Color(0xFFF38BA8),
    onError = Color(0xFF1E1E2E),
    errorContainer = Color(0xFF4A2B38),
    onErrorContainer = Color(0xFFFFD9E1),
    outline = Color(0xFF6C7086),
    outlineVariant = Color(0xFF45475A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF45475A),
    surfaceDim = Color(0xFF11111B),
    surfaceContainerLowest = Color(0xFF11111B),
    surfaceContainerLow = Color(0xFF181825),
    surfaceContainer = Color(0xFF232336),
    surfaceContainerHigh = Color(0xFF313244),
    surfaceContainerHighest = Color(0xFF45475A),
)

/** Resolves the Material3 scheme for a theme mode (also used by the picker swatches). */
internal fun colorSchemeFor(mode: ThemeMode): ColorScheme = when (mode) {
    ThemeMode.DARK -> DarkColorScheme
    ThemeMode.LIGHT -> LightColorScheme
    ThemeMode.NORD -> NordColorScheme
    ThemeMode.CATPPUCCIN -> CatppuccinColorScheme
}

/** Accent colours for the server lifecycle badge. */
object TanoshiStatusColors {
    val Running = Color(0xFF63C77E)
    val Starting = Color(0xFFE8B84B)
    val Stopping = Color(0xFFF29BB6)
    val Error = Color(0xFFFF6B6B)
    val Stopped = Color(0xFF8A97A5)
}

private val TanoshiTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/** Shared monospace style for log output, URLs and the config editor. */
val TanoshiMonoTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

@Composable
fun TanoshiTheme(
    mode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorSchemeFor(mode),
        typography = TanoshiTypography,
        content = content,
    )
}
