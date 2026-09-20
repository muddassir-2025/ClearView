package com.muddassir.clearview.todo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The heatmap's STRUCTURE, which is the part that was wrong: one continuous
 * year of week columns (not a block of columns per month), one square per day,
 * Monday at the top, and month names above the weeks their months begin in.
 *
 * Everything here is pure geometry, so a layout that would silently drift — a
 * column that is not a Monday, a gap in the day sequence, a label floating over
 * the wrong week, an off-by-one that leaves out a month — fails here instead of
 * having to be spotted in a screenshot.
 */
class HeatmapGeometryTest {

    private val today = LocalDate.of(2026, 9, 20) // a Sunday, mid-month

    private fun label(date: LocalDate): String =
        date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** The month a labelled column names: the one whose 1st falls inside it. */
    private fun monthNamed(columns: List<LocalDate>, column: Int): YearMonth =
        (0 until HEATMAP_ROWS)
            .map { columns[column].plusDays(it.toLong()) }
            .first { it.dayOfMonth == 1 }
            .let(YearMonth::from)

    /** Every month the window shows a 1st for, oldest first — the label-able ones. */
    private fun monthsInWindow(columns: List<LocalDate>): List<YearMonth> =
        columns.indices
            .filter { column -> (0 until HEATMAP_ROWS).any { columns[column].plusDays(it.toLong()).dayOfMonth == 1 } }
            .map { monthNamed(columns, it) }

    @Test
    fun `the graph is 53 Monday-aligned week columns ending with this week`() {
        val columns = heatmapColumns(HeatmapRange.PastYear, today)
        assertEquals(HEATMAP_COLUMNS, columns.size)
        assertEquals(53, columns.size)
        columns.forEach { monday ->
            assertEquals("every column opens on a Monday", DayOfWeek.MONDAY, monday.dayOfWeek)
        }
        columns.zipWithNext { a, b ->
            assertEquals("columns are exactly one week apart", 7L, b.toEpochDay() - a.toEpochDay())
        }
        // The last column is the week the reader is living in, so "now" is on it.
        val last = columns.last()
        assertTrue(last <= today)
        assertTrue(today <= last.plusDays(6))
    }

    @Test
    fun `the window is a year long, not a few months`() {
        val columns = heatmapColumns(HeatmapRange.PastYear, today)
        val span = today.toEpochDay() - columns.first().toEpochDay()
        // 52 weeks back, plus however far into this week today is.
        assertTrue("span was $span days, expected a full year", span in 364..370)
        val months = ChronoUnit.MONTHS.between(
            YearMonth.from(columns.first()),
            YearMonth.from(columns.last())
        ) + 1
        assertTrue("the window should cover a year of months, was $months", months in 12..14)
    }

    @Test
    fun `every day appears exactly once, oldest first, a row per weekday`() {
        val columns = heatmapColumns(HeatmapRange.PastYear, today)
        val days = heatmapDays(columns)
        assertEquals(HEATMAP_COLUMNS * HEATMAP_ROWS, days.size)
        assertEquals(371, days.size)
        // Column-major: the first seven days are the first week, Monday..Sunday.
        assertEquals(columns[0], days[0])
        assertEquals(DayOfWeek.MONDAY, days[0].dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, days[6].dayOfWeek)
        assertEquals(columns[1], days[7])
        days.zipWithNext { a, b ->
            assertEquals("the days run without a gap or a repeat", 1L, b.toEpochDay() - a.toEpochDay())
        }
    }

    @Test
    fun `a month label sits on the week that month begins in`() {
        val columns = heatmapColumns(HeatmapRange.PastYear, today)
        val labels = heatmapMonthLabels(columns, HeatmapRange.PastYear, ::label)
        assertTrue("a year-long window labels months", labels.isNotEmpty())
        labels.forEach { (column, text) ->
            assertTrue("label column $column is inside the graph", column in columns.indices)
            val first = (0 until HEATMAP_ROWS)
                .map { columns[column].plusDays(it.toLong()) }
                .first { it.dayOfMonth == 1 }
            assertEquals("'$text' names the month that starts in this week", label(first), text)
        }
        // …and a week with no month boundary in it is never labelled.
        labels.keys.forEach { column ->
            assertTrue(
                "column $column must contain a 1st",
                (0 until HEATMAP_ROWS).any { columns[column].plusDays(it.toLong()).dayOfMonth == 1 }
            )
        }
        // The month the reader is living in is always named.
        assertTrue(
            "this month is labelled",
            labels.keys.map { monthNamed(columns, it) }.contains(YearMonth.from(today))
        )
    }

    @Test
    fun `labels never crowd each other and never drop a month block`() {
        val columns = heatmapColumns(HeatmapRange.PastYear, today)
        val labels = heatmapMonthLabels(columns, HeatmapRange.PastYear, ::label)
        val indices = labels.keys.sorted()
        indices.zipWithNext { a, b ->
            assertTrue("$a and $b are too close to both be readable", b - a >= 3)
        }
        // A rolling year shows twelve (sometimes thirteen) months of boundaries;
        // the only label that may be missing is one crowded out by its neighbour.
        val months = monthsInWindow(columns)
        assertTrue(
            "expected at least ${months.size - 1} labels, got ${labels.keys}",
            labels.size >= months.size - 1
        )
        val named = labels.keys.map { monthNamed(columns, it) }
        assertTrue("the newest month is always named", named.contains(months.last()))
    }

    @Test
    fun `the squares stay big enough to read and to tap`() {
        // The graph scrolls sideways instead of shrinking to fit: a square much
        // under 10dp stops being a day and becomes texture, and the weekday
        // letters stop being readable at all. This is the guard for exactly the
        // regression that made the fitted version useless on a phone.
        assertTrue("cell was $HEATMAP_CELL", HEATMAP_CELL >= 10.dp)
        assertTrue("the gap must stay visible", HEATMAP_GAP >= 1.dp)
        assertTrue("the gap must stay a gap", HEATMAP_GAP < HEATMAP_CELL)
        // …and the year is deliberately wider than any phone, which is why the
        // grid is scrollable at all.
        val yearWidth = (HEATMAP_CELL + HEATMAP_GAP) * HEATMAP_COLUMNS
        assertTrue("a year of columns should not fit a phone: $yearWidth", yearWidth >= 600.dp)
    }

    @Test
    fun `a calendar year spans exactly that year, January to December`() {
        val columns = heatmapColumns(HeatmapRange.Year(2026), today)
        // The first week is the one January 1st falls in, the last the one
        // December 31st falls in — so a few neighbouring days can edge into the
        // columns, which the grid leaves unshaded.
        assertTrue("January 1st is in the first column", contains(columns[0], LocalDate.of(2026, 1, 1)))
        assertTrue("December 31st is in the last column", contains(columns.last(), LocalDate.of(2026, 12, 31)))
        assertTrue("a year is 52 or 53 weeks", columns.size in 52..53)
        columns.forEach { assertEquals(DayOfWeek.MONDAY, it.dayOfWeek) }
        columns.zipWithNext { a, b -> assertEquals(7L, b.toEpochDay() - a.toEpochDay()) }
        // Every month of that year gets a boundary to be labelled at — and the
        // year's own edges do not leak their neighbours' months in: the first
        // week can start in the previous December and the last can end in the
        // next January, and neither of those is part of this window.
        val labels = heatmapMonthLabels(columns, HeatmapRange.Year(2026), ::label)
        assertEquals(12, labels.keys.map { monthNamed(columns, it) }.distinct().size)
    }

    @Test
    fun `the two windows are different lengths and never mix`() {
        val past = heatmapColumns(HeatmapRange.PastYear, today)
        val year = heatmapColumns(HeatmapRange.Year(2026), today)
        assertTrue("the rolling window still ends at the current week", past.last() <= today)
        assertTrue(today <= past.last().plusDays(6))
        assertNotEquals("a calendar year is not the rolling window", past, year)
    }

    /** True when [day] falls inside the week column that starts on [monday]. */
    private fun contains(monday: LocalDate, day: LocalDate): Boolean =
        (0 until HEATMAP_ROWS).any { monday.plusDays(it.toLong()) == day }

    @Test
    fun `the year menu keeps up with the calendar`() {
        val items = listOf(todoCompletedOn(LocalDate.of(2026, 5, 4)))
        assertEquals(
            listOf(HeatmapRange.PastYear, HeatmapRange.Year(2026)),
            heatmapRangeOptions(items, today, HeatmapRange.PastYear)
        )
        // The next year is offered the moment it starts — with a screen clock
        // that keeps ticking, this is the menu that greets the reader in January
        // without anyone having to ship a new list.
        assertEquals(
            listOf(HeatmapRange.PastYear, HeatmapRange.Year(2027), HeatmapRange.Year(2026)),
            heatmapRangeOptions(items, LocalDate.of(2027, 1, 1), HeatmapRange.PastYear)
        )
    }

    @Test
    fun `the menu never drops the period the reader is looking at`() {
        // A year with nothing completed in it is still in the list while it is
        // the selected one, so the button can never point at an entry the menu
        // does not contain.
        val options = heatmapRangeOptions(
            emptyList(),
            LocalDate.of(2027, 3, 1),
            HeatmapRange.Year(2026)
        )
        assertTrue("2026 was dropped", options.contains(HeatmapRange.Year(2026)))
        assertTrue("the current year is always offered", options.contains(HeatmapRange.Year(2027)))
        assertEquals("the rolling year is always first", HeatmapRange.PastYear, options.first())
    }

    @Test
    fun `the rolling year slides forward a week at a time`() {
        val now = heatmapColumns(HeatmapRange.PastYear, today)
        val nextWeek = heatmapColumns(HeatmapRange.PastYear, today.plusWeeks(1))
        assertEquals("the window moves by exactly one column", now.drop(1), nextWeek.dropLast(1))
        assertTrue("the newest column is the week in progress", nextWeek.last() > now.last())
        assertTrue(today.plusWeeks(1) <= nextWeek.last().plusDays(6))
        assertTrue(nextWeek.last() <= today.plusWeeks(1))
    }

    @Test
    fun `the graph opens on the current week, and on January for a finished year`() {
        val rolling = heatmapColumns(HeatmapRange.PastYear, today)
        assertEquals(
            "the rolling year opens at its newest week",
            rolling.lastIndex,
            heatmapAnchorColumn(rolling, HeatmapRange.PastYear, today)
        )
        val thisYear = heatmapColumns(HeatmapRange.Year(2026), today)
        val anchor = heatmapAnchorColumn(thisYear, HeatmapRange.Year(2026), today)
        assertTrue("today is on the anchor column", thisYear[anchor] <= today)
        assertTrue(today <= thisYear[anchor].plusDays(6))
        // A finished year has no "now" to land on, so it opens at its start
        // instead of on a year of empty future weeks.
        val lastYear = heatmapColumns(HeatmapRange.Year(2025), today)
        assertEquals(0, heatmapAnchorColumn(lastYear, HeatmapRange.Year(2025), today))
    }

    /** One permanent todo completed on each of [days]. */
    private fun todoCompletedOn(vararg days: LocalDate): TodoItem = TodoItem(
        id = "heatmap",
        title = "heatmap",
        type = TodoType.PERMANENT,
        startDateEpochDay = days.min().toEpochDay(),
        completions = days.associate { it.toEpochDay() to 0L }
    )

    @Test
    fun `months are separated by a little air, not by blocks`() {
        val labels = mapOf(0 to "Sep", 4 to "Oct", 9 to "Nov")
        // A month boundary opens the gap…
        assertTrue("a month boundary needs air", heatmapMonthLead(4, labels) > 0.dp)
        // …the columns inside a month do not…
        assertEquals(0.dp, heatmapMonthLead(5, labels))
        assertEquals(0.dp, heatmapMonthLead(8, labels))
        // …and the first column never does, so the grid starts flush.
        assertEquals(0.dp, heatmapMonthLead(0, labels))
        // The air has to stay smaller than a column, or the year stops reading
        // as one continuous grid and starts reading as twelve blocks.
        assertTrue(
            "month gap $${heatmapMonthLead(4, labels)} is too wide",
            heatmapMonthLead(4, labels) < HEATMAP_CELL
        )
    }

    @Test
    fun `a Monday today opens the window exactly 52 weeks back`() {
        val monday = LocalDate.of(2026, 9, 21)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        val columns = heatmapColumns(HeatmapRange.PastYear, monday)
        assertEquals(monday.minusWeeks(52), columns.first())
        assertEquals("the reader's own week is the last column", monday, columns.last())
    }

    @Test
    fun `crossing a new year still names the months around it`() {
        val columns = heatmapColumns(HeatmapRange.PastYear, LocalDate.of(2027, 1, 3))
        val labels = heatmapMonthLabels(columns, HeatmapRange.PastYear, ::label)
        val named = labels.keys.map { monthNamed(columns, it) }
        assertTrue("the January the window starts in is named", named.contains(YearMonth.of(2026, 1)))
        assertTrue("the January the window ends in is named", named.contains(YearMonth.of(2027, 1)))
        assertTrue("December is named", named.contains(YearMonth.of(2026, 12)))
        // The columns run forwards, so the labels must too — a year boundary is
        // exactly where a label list built by index could silently invert.
        named.zipWithNext { a, b -> assertTrue("$a should come before $b", b > a) }
        assertFalse("no label can be out of the window", named.size > labels.size)
    }
}
