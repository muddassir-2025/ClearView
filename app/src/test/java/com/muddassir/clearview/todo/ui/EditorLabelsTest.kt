package com.muddassir.clearview.todo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The labels the redesigned editor shows on its value rows.
 *
 * A row that says "90m" where a reader thinks "1h 30m" is a row they have to
 * decode, and these rows replaced the chip walls where every option was visible
 * at once — so what they say has to carry the whole answer on its own.
 */
class EditorLabelsTest {

    @Test
    fun `durations under an hour are minutes`() {
        assertEquals("15m", durationLabel(15))
        assertEquals("30m", durationLabel(30))
        assertEquals("45m", durationLabel(45))
        assertEquals("59m", durationLabel(59))
    }

    @Test
    fun `durations from an hour are hours, with minutes when they matter`() {
        assertEquals("1h", durationLabel(60))
        assertEquals("2h", durationLabel(120))
        assertEquals("3h", durationLabel(180))
        assertEquals("1h 30m", durationLabel(90))
        assertEquals("2h 15m", durationLabel(135))
    }

    @Test
    fun `a nonsense duration cannot produce a nonsense label`() {
        assertEquals("0m", durationLabel(0))
        assertEquals("0m", durationLabel(-30))
    }

    @Test
    fun `every offered duration has a label of its own`() {
        val labels = DURATION_CHOICES.map(::durationLabel)
        assertEquals("labels must be distinct", labels.size, labels.toSet().size)
        assertTrue("choices must be positive", DURATION_CHOICES.all { it > 0 })
        assertEquals("choices must be ascending", DURATION_CHOICES.sorted(), DURATION_CHOICES)
    }

    @Test
    fun `the period row names what was chosen`() {
        val start = LocalDate.of(2026, 9, 20)
        assertEquals("Today", periodLabel(PeriodChoice.TODAY, start, start))
        assertEquals("Tomorrow", periodLabel(PeriodChoice.TOMORROW, start, start))
        assertEquals("This week", periodLabel(PeriodChoice.THIS_WEEK, start, start))
    }

    @Test
    fun `a custom period shows the days, not the word custom`() {
        val start = LocalDate.of(2026, 9, 20)
        val sameDay = periodLabel(PeriodChoice.CUSTOM, start, start)
        assertTrue("a one-day custom range names the day, got $sameDay", sameDay.contains("20"))

        val range = periodLabel(PeriodChoice.CUSTOM, start, start.plusDays(4))
        assertTrue("a range names its start, got $range", range.contains("20"))
        assertTrue("a range names its end, got $range", range.contains("24"))
        assertTrue("a range shows both ends, got $range", range.contains("–"))
    }
}
