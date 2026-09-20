package com.muddassir.clearview.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The light scheme, designed rather than inherited.
 *
 * Two things make light mode comfortable, and both are set here rather than
 * left to the framework.
 *
 * 1. THE WHOLE PALETTE. Material's `lightColorScheme()` only fills the roles it
 *    is given; everything else falls back to the BASELINE Material palette,
 *    which is purple. That is the entire `surfaceContainer` family — the colour
 *    every modern Material component paints itself with: the navigation bar, the
 *    bottom sheets, the dialogs, the dropdowns. Leaving them undefined put a
 *    wall of pale lavender (`#F3EDF7`, the baseline `surfaceContainer`) under a
 *    teal scheme, which is what made light mode look like two themes fighting.
 *    Every container role is therefore defined here, in the same teal-grey
 *    family as the accent.
 *
 * 2. NO GLARE, REAL SEPARATION. A paper-like background with surfaces a hair
 *    off pure white: a full screen of `#FFFFFF` cards on a near-white page
 *    glares AND makes every card edge disappear. The background is tinted
 *    further down, the card surfaces sit just above it, and `surfaceVariant`
 *    (what the feed's cards are actually filled with, at
 *    `surfaceVariant.copy(alpha = 0.6f)`) is deeper still, so a card reads as a
 *    card without needing a heavy border.
 *
 * The accent family is a deep teal instead of template purple, chosen to stay
 * legible on the tinted background in both its filled (white-on-teal) and
 * container forms — the pills, progress bars and selected states all use it.
 */
internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA7F0E4),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E3),
    onSecondaryContainer = Color(0xFF06201D),
    tertiary = Color(0xFF43617A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC9E6FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFEDF1EF),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFFAFCFB),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFD8E3DF),
    onSurfaceVariant = Color(0xFF3F4947),
    // A visible-but-soft hairline: the app draws card and avatar borders with
    // outlineVariant, and an invisible one left avatars and cards with no edge
    // at all on light backgrounds.
    outline = Color(0xFF6B7472),
    outlineVariant = Color(0xFFC6D1CE),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    // ── The container family: never left to the framework (see above) ──
    surfaceBright = Color(0xFFFCFEFD),
    surfaceDim = Color(0xFFDBE2DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F8F6),
    surfaceContainer = Color(0xFFEEF3F1),
    surfaceContainerHigh = Color(0xFFE7EEEC),
    surfaceContainerHighest = Color(0xFFE0E8E6),
    surfaceTint = Color(0xFF00695C),
    inverseSurface = Color(0xFF2C3231),
    inverseOnSurface = Color(0xFFEFF2F0),
    inversePrimary = Color(0xFF63DACB),
    scrim = Color(0xFF000000)
)

/**
 * The dark scheme, in the same accent family as the light one.
 *
 * Only reached on Android 11 and older, or when dynamic colour is turned off:
 * from Android 12 the app takes the wallpaper's own dark scheme, which is what
 * it has always shown. Keeping the two schemes related means the app does not
 * switch brand when the platform does.
 *
 * Its container family is defined for the same reason as the light one's: an
 * undefined `surfaceContainer` is baseline purple, and a purple-tinted sheet
 * over a near-black teal app is just as wrong in dark as it is in light.
 */
internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF63DACB),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFA7F0E4),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B48),
    onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFABCAE6),
    onTertiary = Color(0xFF11334B),
    tertiaryContainer = Color(0xFF2A4A62),
    onTertiaryContainer = Color(0xFFC9E6FF),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFDDE4E3),
    surface = Color(0xFF0F1514),
    onSurface = Color(0xFFDDE4E3),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C7),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4947),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    // ── The container family, ramped up from the app's own near-black ──
    surfaceBright = Color(0xFF353F3E),
    surfaceDim = Color(0xFF0F1514),
    surfaceContainerLowest = Color(0xFF0B100F),
    surfaceContainerLow = Color(0xFF131A19),
    surfaceContainer = Color(0xFF171F1E),
    surfaceContainerHigh = Color(0xFF1D2625),
    surfaceContainerHighest = Color(0xFF232D2C),
    surfaceTint = Color(0xFF63DACB),
    inverseSurface = Color(0xFFDDE4E3),
    inverseOnSurface = Color(0xFF2C3231),
    inversePrimary = Color(0xFF00695C),
    scrim = Color(0xFF000000)
)

/**
 * The scheme the app is currently set to render in.
 *
 * Reads the persisted [ThemeMode] (seeded on the first composition, so a cold
 * start never flashes the wrong scheme) and tracks it as state, which is what
 * makes a theme change in the More tab repaint the whole app — every tab, and
 * every activity that draws through [UrlblockerTheme] — immediately.
 */
@Composable
private fun darkThemeNow(context: android.content.Context): Boolean {
    ThemeStore.ensureInitialized(context)
    val mode by ThemeStore.mode.collectAsState()
    val systemDark = isSystemInDarkTheme()
    return resolveDarkTheme(mode, systemDark)
}

@Composable
fun UrlblockerTheme(
    /**
     * Forces a scheme for this screen only (the block overlay is deliberately
     * always dark); null — the normal case — follows the app's theme setting
     * and so respects the user's choice on every tab.
     */
    forcedDarkTheme: Boolean? = null,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = forcedDarkTheme ?: darkThemeNow(context)
    // Dynamic colour is taken in DARK only. A wallpaper-derived LIGHT scheme is
    // built from muted, low-contrast pastels — on a full feed of cards it reads
    // as grey-on-grey — so light uses the scheme above, while dark keeps the
    // wallpaper look the app has always had.
    val colorScheme = when {
        dynamicColor && darkTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // System bar icons must contrast with the scheme the APP is drawing, not
    // with the phone's own setting. enableEdgeToEdge() derives its light/dark
    // icons from the system, so an app switched to Light on a phone in dark
    // mode (or the reverse) ends up with white status-bar icons on a pale bar —
    // invisible. Re-applied whenever the scheme changes, and it also covers the
    // activities that force a dark scheme (the block overlay).
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}