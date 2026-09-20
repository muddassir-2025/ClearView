package com.muddassir.clearview.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Locks the two rules that decide whether the app's own schemes look right.
 *
 * 1. NO LAVENDER. `lightColorScheme()` / `darkColorScheme()` only fill the roles
 *    they are passed, and every role left out falls back to the BASELINE
 *    Material palette — which is purple. The `surfaceContainer` family is what
 *    the navigation bar, bottom sheets, dialogs and dropdowns paint themselves
 *    with, so leaving it undefined put a pale lavender (#F3EDF7) under a teal
 *    scheme. Every container role must therefore be defined, and must belong to
 *    the app's own green-grey family.
 *
 * 2. READABILITY AND SEPARATION. Text on its surface must clear WCAG AA, a card
 *    must be visibly separate from the page it sits on, and the light surface
 *    must not be pure white (a screen of #FFFFFF glares).
 */
class PaletteTest {

    private val Color.r255: Double get() = red * 255.0
    private val Color.g255: Double get() = green * 255.0
    private val Color.b255: Double get() = blue * 255.0

    private fun luminance(c: Color): Double {
        fun f(v: Double): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * f(c.r255) + 0.7152 * f(c.g255) + 0.0722 * f(c.b255)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Green-dominant: the teal-grey family the app designs in. */
    private fun isGreenDominant(c: Color): Boolean =
        c.g255 >= c.r255 - 0.5 && c.g255 >= c.b255 - 0.5

    private fun containers(s: ColorScheme): List<Pair<String, Color>> = listOf(
        "surfaceBright" to s.surfaceBright,
        "surfaceDim" to s.surfaceDim,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
        "surfaceTint" to s.surfaceTint
    )

    private fun checkNoLavender(name: String, s: ColorScheme) {
        containers(s).forEach { (role, color) ->
            assertTrue(
                "$name $role ($color) is not in the app's green-grey family — an " +
                    "undefined role falls back to baseline Material purple",
                isGreenDominant(color)
            )
        }
        // The tint is the accent, so it must BE the accent, not a stray colour.
        assertEquals("$name surfaceTint must be the accent", s.primary, s.surfaceTint)
    }

    @Test
    fun `no container role is left at the purple baseline in light`() {
        checkNoLavender("light", LightColorScheme)
    }

    @Test
    fun `no container role is left at the purple baseline in dark`() {
        checkNoLavender("dark", DarkColorScheme)
    }

    @Test
    fun `the light surface is off-white rather than glaring pure white`() {
        assertNotEquals(
            "the reading surface must not be pure #FFFFFF — a full screen of it glares",
            Color(0xFFFFFFFF),
            LightColorScheme.surface
        )
        // …but it is still a LIGHT surface, near the top of the range.
        assertTrue("light surface must stay light", luminance(LightColorScheme.surface) > 0.85)
    }

    @Test
    fun `a card is visibly separate from the page in light`() {
        val bg = LightColorScheme.background
        // The feed paints cards with surfaceVariant at 60% over the page, so the
        // blended card colour is what must separate — not the raw role.
        val card = blend(LightColorScheme.surfaceVariant, bg, 0.6)
        assertTrue(
            "card ${card} barely differs from background $bg",
            abs(luminance(card) - luminance(bg)) > 0.01
        )
        assertTrue(
            "surface must separate from background",
            abs(luminance(LightColorScheme.surface) - luminance(bg)) > 0.005
        )
        assertTrue(
            "surfaceVariant must separate from surface",
            contrast(LightColorScheme.surface, LightColorScheme.surfaceVariant) > 1.05
        )
    }

    @Test
    fun `text clears WCAG AA on every surface it is drawn on`() {
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, s) ->
            assertTrue(
                "$name onSurface on surface must clear AA",
                contrast(s.onSurface, s.surface) >= 4.5
            )
            assertTrue(
                "$name onSurfaceVariant on surfaceVariant must clear AA",
                contrast(s.onSurfaceVariant, s.surfaceVariant) >= 4.5
            )
            assertTrue(
                "$name onSurface on background must clear AA",
                contrast(s.onSurface, s.background) >= 4.5
            )
            assertTrue(
                "$name onPrimary on primary must clear AA",
                contrast(s.onPrimary, s.primary) >= 4.5
            )
            // The accent itself has to be visible against the page it sits on.
            assertTrue(
                "$name primary must be visible on surface",
                contrast(s.primary, s.surface) >= 3.0
            )
        }
    }

    @Test
    fun `the dark reading surface stays dark`() {
        assertTrue(luminance(DarkColorScheme.surface) < 0.05)
        assertTrue(luminance(DarkColorScheme.surfaceContainerHigh) < 0.10)
    }

    /** [color] laid over [base] at [alpha], on a plain sRGB blend. */
    private fun blend(color: Color, base: Color, alpha: Double): Color {
        fun mix(c: Double, b: Double) = (c * alpha + b * (1 - alpha)) / 255.0
        return Color(
            red = mix(color.r255, base.r255).toFloat(),
            green = mix(color.g255, base.g255).toFloat(),
            blue = mix(color.b255, base.b255).toFloat()
        )
    }
}
