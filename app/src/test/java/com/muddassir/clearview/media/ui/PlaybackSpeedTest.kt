package com.muddassir.clearview.media.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback-speed surface shared by the video, Shorts and audio players.
 *
 * There are no presets any more: every surface is ONE custom bar, so what must
 * hold is that the bar's bounds are valid, that the range divides evenly into
 * the 0.05 step grid (the slider's `steps` math depends on it), and that
 * [formatRate] / [formatBound] render clean, locale-independent labels.
 */
class PlaybackSpeedTest {

    @Test
    fun `custom speed bounds are valid`() {
        assertTrue(MIN_CUSTOM_SPEED > 0.0)
        assertTrue("custom range must reach at least 5x", MAX_CUSTOM_SPEED >= 5.0)
        assertTrue(MAX_CUSTOM_SPEED > MIN_CUSTOM_SPEED)
        assertTrue(CUSTOM_SPEED_STEP > 0.0)
        // 1x — the rate everyone expects to be reachable — sits inside the bar.
        assertTrue(1.0 in MIN_CUSTOM_SPEED..MAX_CUSTOM_SPEED)
    }

    @Test
    fun `the audio bar is narrower than the video bar`() {
        // Speech, not film: the audio bar stops short of the video bar's ends.
        assertTrue(MIN_AUDIO_SPEED > MIN_CUSTOM_SPEED)
        assertTrue(MAX_AUDIO_SPEED < MAX_CUSTOM_SPEED)
        assertTrue(1.0 in MIN_AUDIO_SPEED..MAX_AUDIO_SPEED)
    }

    @Test
    fun `range divides evenly into whole steps for every bar`() {
        listOf(MIN_CUSTOM_SPEED to MAX_CUSTOM_SPEED, MIN_AUDIO_SPEED to MAX_AUDIO_SPEED)
            .forEach { (min, max) ->
                val steps = (max - min) / CUSTOM_SPEED_STEP
                assertEquals(
                    "range $min..$max must divide evenly into $CUSTOM_SPEED_STEP steps",
                    steps,
                    Math.round(steps).toDouble(),
                    1e-9
                )
                // Material's `steps` excludes the two ends; it must be sane.
                assertTrue(
                    "step count for $min..$max must be positive",
                    speedBarSteps(min, max) > 0
                )
            }
    }

    @Test
    fun `formatRate drops the decimal for whole speeds`() {
        assertEquals("1x", formatRate(1.0))
        assertEquals("2x", formatRate(2.0))
        assertEquals("5x", formatRate(5.0))
    }

    @Test
    fun `formatRate keeps up to two decimals and strips trailing zeros`() {
        assertEquals("1.25x", formatRate(1.25))
        assertEquals("1.5x", formatRate(1.5))
        assertEquals("1.75x", formatRate(1.75))
        assertEquals("0.25x", formatRate(0.25))
        assertEquals("2.5x", formatRate(2.5))
    }

    @Test
    fun `formatRate rounds away floating point noise`() {
        // A slider value like 1.3500000000000001 must render as "1.35x", never
        // the raw double's toString().
        assertEquals("1.35x", formatRate(1.3500000000000001))
        assertEquals("3.05x", formatRate(3.0500000000000003))
        // Locale independence: a dot, never a comma.
        assertTrue(formatRate(1.35).contains("."))
        assertTrue(!formatRate(1.35).contains(","))
    }

    @Test
    fun `formatBound never shows trailing zeros and stays locale-independent`() {
        assertEquals("0.5", formatBound(0.5))
        assertEquals("3", formatBound(3.0))
        assertEquals("0.25", formatBound(0.25))
        // Both ends of both bars are what the axis labels show.
        assertEquals("0.25", formatBound(MIN_CUSTOM_SPEED))
        assertEquals("5", formatBound(MAX_CUSTOM_SPEED))
        assertTrue(!formatBound(MIN_AUDIO_SPEED).contains(","))
    }
}
