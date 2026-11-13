package dev.spainclaro.tanoshi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tanoshi is dark-first: the app is a server dashboard meant to be read next to
 * the manga web UI, and the native launch theme (values/themes.xml) already
 * paints the window dark. A single custom dark scheme keeps system bars,
 * dialogs and the window background consistent from the very first frame.
 */
private val TanoshiDarkColors = darkColorScheme(
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
fun TanoshiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TanoshiDarkColors,
        typography = TanoshiTypography,
        content = content,
    )
}
