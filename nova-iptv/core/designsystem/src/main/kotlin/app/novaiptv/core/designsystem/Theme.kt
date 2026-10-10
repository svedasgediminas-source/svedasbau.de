package app.novaiptv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Nova palette: deep "night sky" navy with an electric aurora-teal accent and a soft violet
 * secondary. Everything is dark-first; TVs are watched in dim rooms.
 */
object NovaColors {
    val Background = Color(0xFF0A0E16)
    val Surface = Color(0xFF121826)
    val SurfaceRaised = Color(0xFF1A2233)
    val SurfaceBright = Color(0xFF243049)
    val Accent = Color(0xFF2FE3C8)
    val AccentDim = Color(0xFF1A8F80)
    val OnAccent = Color(0xFF04201C)
    val Secondary = Color(0xFF9C8CFF)
    val TextPrimary = Color(0xFFF3F6FB)
    val TextSecondary = Color(0xFFAAB4C5)
    val TextMuted = Color(0xFF6E7A8F)
    val Error = Color(0xFFFF6B6B)
    val Live = Color(0xFFFF4D6A)
    val Favorite = Color(0xFFFFC94D)
    val Scrim = Color(0xCC05070C)
}

/** Strings used inside design-system components; the app provides localized values. */
@Immutable
data class NovaStrings(
    val retry: String = "Retry",
    val loading: String = "Loading…",
)

val LocalNovaStrings = staticCompositionLocalOf { NovaStrings() }

private val NovaTypography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = NovaColors.Accent,
        onPrimary = NovaColors.OnAccent,
        primaryContainer = NovaColors.AccentDim,
        onPrimaryContainer = NovaColors.TextPrimary,
        secondary = NovaColors.Secondary,
        onSecondary = Color(0xFF15103A),
        secondaryContainer = NovaColors.SurfaceBright,
        onSecondaryContainer = NovaColors.TextPrimary,
        tertiary = NovaColors.Favorite,
        onTertiary = Color(0xFF2A1E00),
        background = NovaColors.Background,
        onBackground = NovaColors.TextPrimary,
        surface = NovaColors.Surface,
        onSurface = NovaColors.TextPrimary,
        surfaceVariant = NovaColors.SurfaceRaised,
        onSurfaceVariant = NovaColors.TextSecondary,
        error = NovaColors.Error,
        onError = Color(0xFF2B0000),
        border = NovaColors.Accent,
        borderVariant = NovaColors.SurfaceBright,
    )
    MaterialTheme(colorScheme = colors, typography = NovaTypography, content = content)
}
