package com.muddassir.clearview.goodpost.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device theme's storage format, and the edit state that drives the editor.
 *
 * Both are pure JVM logic with no Android dependency, which is exactly why they
 * are worth pinning here: a stored preference that fails to parse must fall back
 * to the default rather than crash the tab on every open, and an "is this
 * picture untouched?" answer that is wrong decides whether the editor uploads a
 * re-encoded copy of a photo nobody asked to change.
 */
class GoodPostThemeTest {

    @Test
    fun `round trips every choice`() {
        AccentChoice.entries.forEach { accent ->
            BackdropChoice.entries.forEach { backdrop ->
                GlassChoice.entries.forEach { glass ->
                    val theme = GoodPostTheme(accent, backdrop, glass)
                    assertEquals(theme, GoodPostThemeStore.decode(GoodPostThemeStore.encode(theme)))
                }
            }
        }
    }

    @Test
    fun `an unknown choice in stored json falls back instead of throwing`() {
        // A preference written by a newer build, then downgraded. Theming is not
        // worth refusing to open the app over.
        val decoded = GoodPostThemeStore.decode(
            """{"accent":"Neon","backdrop":"Sunset","glass":"Invisible"}"""
        )

        assertEquals(GoodPostTheme.Default, decoded)
    }

    @Test
    fun `malformed json is the default, not a crash`() {
        assertEquals(GoodPostTheme.Default, GoodPostThemeStore.decode("not json at all"))
        assertEquals(GoodPostTheme.Default, GoodPostThemeStore.decode(""))
        assertEquals(GoodPostTheme.Default, GoodPostThemeStore.decode("""{"accent":}"""))
    }

    @Test
    fun `a partial object keeps the defaults for what is missing`() {
        val decoded = GoodPostThemeStore.decode("""{"accent":"Lilac"}""")

        assertEquals(AccentChoice.Lilac, decoded.accent)
        assertEquals(BackdropChoice.Dream, decoded.backdrop)
        assertEquals(GlassChoice.Balanced, decoded.glass)
    }

    @Test
    fun `every backdrop is dark enough for the fixed text colours`() {
        // The palette's text is a light grey on a dark canvas, and it is NOT
        // themeable. So the themeable half has to stay dark: a backdrop option
        // that is too light would make every screen unreadable, and no amount of
        // picking would fix it.
        BackdropChoice.entries.forEach { choice ->
            assertTrue(
                "${choice.label} top stop is too light",
                luminance(choice.top) < 0.12
            )
            assertTrue(
                "${choice.label} bottom stop is too light",
                luminance(choice.bottom) < 0.12
            )
        }
    }

    @Test
    fun `every accent is bright enough to read on the dark canvas`() {
        AccentChoice.entries.forEach { choice ->
            assertTrue(
                "${choice.label} is too dark to be an accent",
                luminance(choice.fill) > 0.25
            )
        }
    }

    @Test
    fun `glass presets get more transparent and blurrier together`() {
        // The three are ordered on purpose: asking for a more see-through pane
        // has to mean more blur behind it, or the text on it stops being legible.
        val ordered = GlassChoice.entries.sortedBy { it.blur }
        assertEquals(listOf(GlassChoice.Subtle, GlassChoice.Balanced, GlassChoice.Airy), ordered)

        val alphas = ordered.map { (it.fill ushr 24) and 0xFF }
        assertEquals(alphas.sortedDescending(), alphas)
    }

    /** Rough perceived brightness of a packed 0xRRGGBB value, 0–1. */
    private fun luminance(rgb: Long): Double {
        val r = ((rgb shr 16) and 0xFF) / 255.0
        val g = ((rgb shr 8) and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

class EditStateTest {

    @Test
    fun `a fresh state is pristine`() {
        assertTrue(EditState().isPristine)
    }

    @Test
    fun `every kind of change makes it not pristine`() {
        assertFalse(EditState(turns = 1).isPristine)
        assertFalse(EditState(flipHorizontal = true).isPristine)
        assertFalse(EditState(aspect = EditAspect.Square).isPristine)
        assertFalse(EditState(filter = EditFilter.Dream).isPristine)
        assertFalse(EditState(brightness = 1.1f).isPristine)
        assertFalse(EditState(contrast = 0.9f).isPristine)
        assertFalse(EditState(saturation = 1.2f).isPristine)
    }

    @Test
    fun `a full turn is the original picture again`() {
        // Four taps on Rotate must not count as an edit: it is the same pixels,
        // and treating it as a change would re-encode a photo for nothing.
        assertTrue(EditState(turns = 4).isPristine)
    }

    @Test
    fun `crop ratios are width over height, and Original means uncropped`() {
        assertEquals(1f, EditAspect.Square.ratio)
        assertTrue(EditAspect.Portrait.ratio!! < 1f)
        assertTrue(EditAspect.Wide.ratio!! > 1f)
        assertTrue(EditAspect.Story.ratio!! < 1f)
        assertEquals(null, EditAspect.Original.ratio)
    }
}
