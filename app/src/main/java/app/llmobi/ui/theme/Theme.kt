package app.llmobi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The "Signal" palette: charcoal, warning red, bone.
 * Deliberately not Material You - the brand is the point.
 */
object Ink {
    val bg = Color(0xFF16181A)
    val bg2 = Color(0xFF1D2124)
    val bg3 = Color(0xFF242A2D)
    val line = Color(0xFF2C3033)
    val bone = Color(0xFFE8E6E1)
    val grey = Color(0xFF9B978F)
    val grey2 = Color(0xFF6E6A63)
    val grey3 = Color(0xFF4E4A45)
    val red = Color(0xFFE5342A)
    val redDim = Color(0xFFB32A22)
    val green = Color(0xFF5E8A6E)
    val amber = Color(0xFFC9903F)
}

object Paper {
    val bg = Color(0xFFEDEDEA)
    val bg2 = Color(0xFFFFFFFF)
    val bg3 = Color(0xFFE2E1DC)
    val line = Color(0xFFD6D4CE)
    val bone = Color(0xFF16181A)
    val grey = Color(0xFF565A5E)
    val grey2 = Color(0xFF7E827F)
    val grey3 = Color(0xFF9A9E9B)
    val red = Color(0xFFC42B1F)
    val redDim = Color(0xFF9A2118)
    val green = Color(0xFF1F7A57)
    val amber = Color(0xFFA8720E)
}

/** The subset of colours the whole app draws from, so light/dark is one swap. */
data class Skin(
    val bg: Color,
    val bg2: Color,
    val bg3: Color,
    val line: Color,
    val fg: Color,
    val grey: Color,
    val grey2: Color,
    val grey3: Color,
    val red: Color,
    val redDim: Color,
    val green: Color,
    val amber: Color,
    val onRed: Color,
    val dark: Boolean,
)

private val DarkSkin = Skin(
    bg = Ink.bg, bg2 = Ink.bg2, bg3 = Ink.bg3, line = Ink.line,
    fg = Ink.bone, grey = Ink.grey, grey2 = Ink.grey2, grey3 = Ink.grey3,
    red = Ink.red, redDim = Ink.redDim, green = Ink.green, amber = Ink.amber,
    onRed = Color.White, dark = true,
)

private val LightSkin = Skin(
    bg = Paper.bg, bg2 = Paper.bg2, bg3 = Paper.bg3, line = Paper.line,
    fg = Paper.bone, grey = Paper.grey, grey2 = Paper.grey2, grey3 = Paper.grey3,
    red = Paper.red, redDim = Paper.redDim, green = Paper.green, amber = Paper.amber,
    onRed = Color.White, dark = false,
)

val LocalSkin = staticCompositionLocalOf { DarkSkin }

/** Matches the three choices on the Appearance screen. */
enum class ThemeChoice { DARK, LIGHT, AUTO }

private val AppType = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 20.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp,
    ),
)

@Composable
fun LLMobiTheme(choice: ThemeChoice = ThemeChoice.DARK, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.AUTO -> isSystemInDarkTheme()
    }
    val skin = if (dark) DarkSkin else LightSkin
    val scheme = if (dark) {
        darkColorScheme(
            primary = skin.red, onPrimary = skin.onRed,
            background = skin.bg, onBackground = skin.fg,
            surface = skin.bg2, onSurface = skin.fg,
        )
    } else {
        lightColorScheme(
            primary = skin.red, onPrimary = skin.onRed,
            background = skin.bg, onBackground = skin.fg,
            surface = skin.bg2, onSurface = skin.fg,
        )
    }
    CompositionLocalProvider(LocalSkin provides skin) {
        MaterialTheme(colorScheme = scheme, typography = AppType, content = content)
    }
}
