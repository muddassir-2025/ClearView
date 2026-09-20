package com.muddassir.clearview.goodpost.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.goodpost.data.GoodPostMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The box a post's attachment is drawn in, and how wide that makes the card (§18).
 *
 * These are not arithmetic for its own sake. Two properties have to hold, and both
 * are properties a screen showed the opposite of at some point:
 *
 *  1. The box IS the file's shape — every shape a phone shoots is here, plus the two
 *     extremes a clamped band used to squeeze. Those two fail if anybody
 *     reintroduces a minimum or maximum ratio, which is exactly how the empty green
 *     areas beside a photo looked on screen.
 *  2. The card is SMALL for a tall file — the width is pulled back until the media
 *     is no more than a share of the feed's height. That is [postMediaWidth], and
 *     the tests below pin both halves of it: a portrait shrinks, a landscape does
 *     not, and nothing is ever taller than the cap or wider than the column.
 */
class GoodPostMediaAspectTest {

    private fun media(width: Int?, height: Int?, kind: String = "image") = GoodPostMedia(
        id = "m1",
        kind = kind,
        contentType = "image/jpeg",
        byteSize = 1_000,
        width = width,
        height = height,
        durationMs = null,
        fileName = null,
        position = 0,
        url = "https://example.invalid/signed"
    )

    /** The fallback the feed passes for a photo the server never measured. */
    private val fallback = 4f / 3f

    /** What the feed's photo box is sized by. */
    private fun boxAspect(width: Int, height: Int): Float =
        postMediaAspect(media(width, height), fallback)

    @Test
    fun `a portrait photo keeps its own tall ratio`() {
        assertEquals(1080f / 1920f, boxAspect(1080, 1920), 0.0001f)
    }

    @Test
    fun `a landscape photo keeps its own wide ratio`() {
        assertEquals(1920f / 1080f, boxAspect(1920, 1080), 0.0001f)
    }

    @Test
    fun `a square photo stays square`() {
        assertEquals(1f, boxAspect(1080, 1080), 0.0001f)
    }

    @Test
    fun `a very tall photo is not squeezed into a band`() {
        // 9:32, far below any 3:4 floor: the old clamp returned 0.75 here and left
        // the difference over as two columns of the bubble's own green.
        assertEquals(1080f / 3840f, boxAspect(1080, 3840), 0.0001f)
    }

    @Test
    fun `a very wide photo is not squeezed into a band`() {
        // 32:9, far above any 16:9 ceiling, for the same reason.
        assertEquals(3840f / 1080f, boxAspect(3840, 1080), 0.0001f)
    }

    @Test
    fun `an arbitrary ratio is preserved exactly`() {
        assertEquals(1512f / 1007f, boxAspect(1512, 1007), 0.0001f)
    }

    @Test
    fun `a file the server never measured falls back rather than guessing zero`() {
        assertEquals(fallback, postMediaAspect(media(null, null), fallback), 0.0001f)
        assertEquals(fallback, postMediaAspect(media(0, 1080), fallback), 0.0001f)
        assertEquals(fallback, postMediaAspect(media(1080, 0), fallback), 0.0001f)
        assertEquals(fallback, postMediaAspect(media(-4, 1080), fallback), 0.0001f)
    }

    @Test
    fun `a clip with no measurement is left to the video card's own fallback`() {
        // The feed hands the video card `aspectOf(...)` directly, and null is how
        // the card is told to draw its fixed-height box instead of a ratio.
        assertNull(aspectOf(null, null))
        assertNull(aspectOf(0, 1080))
        assertEquals(1080f / 1920f, aspectOf(1080, 1920)!!, 0.0001f)
    }

    // ------------------------------------------------------------------------
    // How wide that box makes the card — the "small, like a chat" rule.
    // ------------------------------------------------------------------------

    /** The room inside a bubble on a phone, and the height of the feed itself. */
    private val column = 340.dp
    private val feed = 800.dp

    /**
     * The tallest a card may be: the same share of the feed the feed itself uses,
     * under the same ceiling. Read from the source rather than restated, so a card
     * that grows back is a card these tests notice.
     */
    private val cap =
        (feed * POST_MEDIA_MAX_HEIGHT_FRACTION).coerceAtMost(POST_MEDIA_MAX_HEIGHT)

    private fun widthOf(vararg sizes: Pair<Int, Int>): Dp =
        postMediaWidth(sizes.map { media(it.first, it.second) }, column, feed)

    private fun heightOf(size: Pair<Int, Int>): Float {
        val ratio = aspectOf(size.first, size.second)!!
        return widthOf(size).value / ratio
    }

    @Test
    fun `a landscape photo still fills the column`() {
        // 16:9 at full width is well inside the cap, so nothing is given up.
        assertEquals(column, widthOf(1920 to 1080))
        assertEquals(column, widthOf(3840 to 1080))
    }

    @Test
    fun `a square photo still fills the column`() {
        assertEquals(column, widthOf(1080 to 1080))
    }

    @Test
    fun `a portrait photo is pulled in until it is no taller than the cap`() {
        // The point of the rule: a 9:16 at full width is half again as tall as the
        // phone. Its width is given up instead, so the card is small and the whole
        // picture is still there — which is what a chat does with a portrait.
        val width = widthOf(1080 to 1920)
        assertTrue("portrait should be narrower than the column", width < column)
        assertEquals(cap.value, heightOf(1080 to 1920), 0.05f)
    }

    @Test
    fun `a very tall photo is a narrow card, not a tall one`() {
        // 9:32 — a scrolled screenshot. Nothing is cropped and nothing is left
        // over to show the bubble through: the card just gets narrow.
        assertEquals(cap.value, heightOf(1080 to 3840), 0.05f)
        assertTrue(widthOf(1080 to 3840) < column / 2)
    }

    @Test
    fun `no attachment is ever taller than the cap or wider than the column`() {
        val shapes = listOf(
            1080 to 1920,
            1920 to 1080,
            1080 to 1080,
            1080 to 3840,
            3840 to 1080,
            1512 to 1007
        )
        shapes.forEach { shape ->
            val width = widthOf(shape)
            assertTrue("$shape is wider than the column", width <= column)
            val ratio = aspectOf(shape.first, shape.second)!!
            assertTrue("$shape is taller than the cap", width.value / ratio <= cap.value + 0.05f)
        }
    }

    @Test
    fun `the tightest item decides the column, when they are stacked`() {
        // A stack is a column of EQUAL width, so a 16:9 clip beside a 9:16 one is
        // drawn at the portrait's width — otherwise the wide one would stick out of
        // the bubble the tall one had sized. (Several PHOTOS are not a stack: they
        // are an album, which takes the whole column — see the grid tests below.)
        assertEquals(
            widthOf(1080 to 1920),
            postMediaWidth(
                listOf(media(1080, 1920, kind = "video"), media(1920, 1080, kind = "video")),
                column,
                feed
            )
        )
        assertEquals(
            widthOf(1080 to 1920),
            postMediaWidth(
                listOf(media(1920, 1080, kind = "video"), media(1080, 1920, kind = "video")),
                column,
                feed
            )
        )
    }

    @Test
    fun `a text post gets the whole column`() {
        assertEquals(column, postMediaWidth(emptyList(), column, feed))
    }

    @Test
    fun `several photos get the whole column, because they are a grid`() {
        // An album's tiles are cropped to their cells, so the pictures' own shapes
        // no longer decide the width — and sizing it from them would make the grid
        // small for a reason that stopped applying (§18).
        val album = listOf(media(1080, 1920), media(1920, 1080))
        assertEquals(column, postMediaWidth(album, column, feed))
    }

    @Test
    fun `several videos are still sized by the tightest clip`() {
        // Two clips are NOT a grid: each one keeps its own poster and controls, so
        // they are stacked, and the rule for a stack still applies.
        val clips = listOf(media(1080, 1920, kind = "video"), media(1080, 1920, kind = "video"))
        assertTrue(postMediaWidth(clips, column, feed) < column)
    }

    @Test
    fun `two photos make a block twice as wide as it is tall`() {
        assertEquals(2f, postAlbumAspect(2), 0.0001f)
    }

    @Test
    fun `three or four photos make a square block`() {
        assertEquals(1f, postAlbumAspect(3), 0.0001f)
        assertEquals(1f, postAlbumAspect(4), 0.0001f)
        // More than the grid holds is still the grid: the rest are counted.
        assertEquals(1f, postAlbumAspect(7), 0.0001f)
    }

    @Test
    fun `a document's size reads the way a file manager writes it`() {
        // A card that said "1.0 MB" for a file the device calls 1.2 MB would look
        // like the wrong file.
        assertEquals("900 B", waFileSize(900))
        assertEquals("1.0 KB", waFileSize(1024))
        // Above ten, the tenth is noise: a file manager says 240 KB, not 240.0.
        assertEquals("240 KB", waFileSize(245_760))
        assertEquals("1.2 MB", waFileSize(1_258_291))
        assertEquals("12 MB", waFileSize(12_582_912))
        assertEquals("1.0 GB", waFileSize(1_073_741_824))
    }

    @Test
    fun `an album draws four tiles at most`() {
        assertEquals(2, postAlbumTileCount(2))
        assertEquals(3, postAlbumTileCount(3))
        assertEquals(4, postAlbumTileCount(4))
        assertEquals(4, postAlbumTileCount(9))
    }

    @Test
    fun `a file the server never measured does not shrink the column`() {
        // There is no shape to preserve, so it is not allowed to change the card:
        // it fills the width and is cropped to the old guess, the way this tab has
        // always drawn those rows.
        assertEquals(column, postMediaWidth(listOf(media(null, null)), column, feed))
    }

    @Test
    fun `with no viewport to measure against the media fills the bubble`() {
        // A preview outside a list has no feed height to take a share of, so the
        // cap is off rather than zero — a card of zero width is the failure mode
        // this guards.
        assertEquals(column, postMediaWidth(listOf(media(1080, 1920)), column, Dp.Unspecified))
        assertEquals(column, postMediaWidth(listOf(media(1080, 1920)), column, 0.dp))
    }

    @Test
    fun `a short viewport makes the cap a share of what is there`() {
        // Landscape, or a small window: the cap follows the feed down instead of
        // being a fixed number of dp, so a portrait is still a thumbnail.
        val shortFeed = 600.dp
        val shortCap = (shortFeed * POST_MEDIA_MAX_HEIGHT_FRACTION)
            .coerceAtMost(POST_MEDIA_MAX_HEIGHT)
        val short = postMediaWidth(listOf(media(1080, 1920)), column, shortFeed)
        assertEquals(shortCap.value * (1080f / 1920f), short.value, 0.05f)
    }
}
