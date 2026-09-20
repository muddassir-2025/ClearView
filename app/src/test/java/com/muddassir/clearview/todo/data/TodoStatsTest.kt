package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoEvent
import com.muddassir.clearview.todo.model.TodoItem
import com.muddassir.clearview.todo.model.TodoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Monday, 2026-08-10 — "today" for every stats assertion. */
private val TODAY: LocalDate = LocalDate.of(2026, 8, 10)

/** Epoch millis for [date] at [hour]:00 in the system zone. */
private fun at(date: LocalDate, hour: Int): Long =
    date.atStartOfDay(ZoneId.systemDefault()).plusHours(hour.toLong()).toInstant().toEpochMilli()

private fun item(
    id: String,
    start: LocalDate,
    end: LocalDate? = null,
    days: Set<Int>? = null,
    type: TodoType = TodoType.TEMPORARY,
    strictInterval: Boolean = false,
    timeStart: Int? = null,
    timeEnd: Int? = null,
    completions: Map<Long, Long> = emptyMap()
): TodoItem = TodoItem(
    id = id,
    title = id,
    type = type,
    startDateEpochDay = start.toEpochDay(),
    endDateEpochDay = end?.toEpochDay(),
    scheduledDays = days,
    strictInterval = strictInterval,
    timeStartMinutes = timeStart,
    timeEndMinutes = timeEnd,
    completions = completions
)

/** Epoch millis for [date] at [minutes] past midnight (system zone). */
private fun atMinutes(date: LocalDate, minutes: Int): Long =
    date.atTime(minutes / 60, minutes % 60)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

class TodoStatsTest {

    private val mon = TODAY
    private val tue = TODAY.plusDays(1)
    private val wed = TODAY.plusDays(2)
    private val sat = TODAY.plusDays(5)
    private val sun = TODAY.plusDays(6)

    private val items = listOf(
        item("t1", start = mon, end = mon, completions = mapOf(mon.toEpochDay() to at(mon, 8))),
        item(
            "t2", type = TodoType.PERMANENT, start = mon, days = setOf(1, 3, 6, 7),
            completions = mapOf(
                mon.toEpochDay() to at(mon, 9),
                wed.toEpochDay() to at(wed, 20),
                sat.toEpochDay() to at(sat, 23)
            )
        ),
        item("t3", type = TodoType.PERMANENT, start = mon, days = setOf(2, 4)),
        // Archived (last week) but still contributes history.
        item("t4", start = mon.minusDays(6), end = mon.minusDays(4),
            completions = mapOf(mon.minusDays(5).toEpochDay() to at(mon.minusDays(5), 10)))
    )

    @Test
    fun `heatmap summary counts completions, active days and the longest run`() {
        // A year with two clear runs and a gap: Mon/Tue/Wed worked, Thursday
        // blank, then Friday and Saturday.
        val start = TODAY.minusDays(10)
        val worked = listOf(0, 1, 2, 4, 5).map { start.plusDays(it.toLong()) }
        val items = listOf(
            item(
                "h1",
                start = start,
                type = TodoType.PERMANENT,
                completions = worked.associate { it.toEpochDay() to at(it, 9) }
            )
        )
        val days = start.datesUntil(start.plusDays(12)).toList()
        val summary = TodoStats.heatmapSummary(
            TodoStats.productivityHeatmap(items, days, at(start.plusDays(20), 12))
        )
        assertEquals("every completion is counted", 5, summary.completed)
        assertEquals("the blank Thursday is not an active day", 5, summary.activeDays)
        assertEquals("the longest run is the first one", 3, summary.maxStreak)
    }

    @Test
    fun `a heatmap streak never counts days outside the shown window`() {
        // Completed on the day BEFORE the window opens: a run that starts at the
        // window's edge must not be extended by it — the graph only shows (and
        // only scores) the days it is handed.
        val first = TODAY
        val items = listOf(
            item(
                "h2",
                start = first.minusDays(3),
                type = TodoType.PERMANENT,
                completions = mapOf(
                    first.minusDays(1).toEpochDay() to at(first.minusDays(1), 9),
                    (first.toEpochDay()) to at(first, 9),
                    first.plusDays(1).toEpochDay() to at(first.plusDays(1), 9)
                )
            )
        )
        val window = listOf(first, first.plusDays(1), first.plusDays(2))
        val summary = TodoStats.heatmapSummary(
            TodoStats.productivityHeatmap(items, window, at(first, 12))
        )
        assertEquals("two days of the window were worked on", 2, summary.activeDays)
        assertEquals("the run is 2, not 3", 2, summary.maxStreak)
        assertEquals(2, summary.completed)
    }

    @Test
    fun `an empty heatmap window summarises as zero, not as a fake streak`() {
        val summary = TodoStats.heatmapSummary(emptyList())
        assertEquals(0, summary.completed)
        assertEquals(0, summary.activeDays)
        assertEquals(0, summary.maxStreak)
    }

    @Test
    fun `weekly due and completed counts only include applicable days`() {
        val stats = TodoStats.weekStats(items, TODAY)
        assertEquals(7, stats.days.size)
        assertEquals(mon, stats.days.first().date)
        assertEquals(sun, stats.days.last().date)

        assertEquals(2, stats.days[0].due)      // Mon: t1 + t2
        assertEquals(2, stats.days[0].completed)
        // Days after today are EMPTY — a future schedule is never a bar.
        assertEquals(0, stats.days[1].due)      // Tue (future)
        assertEquals(0, stats.days[2].due)      // Wed (future)
        assertEquals(0, stats.days[4].due)      // Fri (future)
        assertEquals(2, stats.due)
        assertEquals(2, stats.completed)
    }

    @Test
    fun `score is null without due todos and positive otherwise`() {
        assertNull(TodoStats.weekStats(emptyList(), TODAY).score)
        val stats = TodoStats.weekStats(items, TODAY)
        assertNotNull(stats.score)
        assertTrue(stats.score!! in 1..100)
        // v3: Completion 45 + Consistency 18 + Streak 12×(1/7) + Volume 15
        // (this week's 2 completions meet the 1/week baseline from t4) —
        // Timeliness is EXCLUDED (nothing closed yet this week), so the 90 base
        // rescales: 100/90 × (45 + 18 + 1.71 + 15) ≈ 89.
        assertEquals(89, stats.score)
    }

    @Test
    fun `score breakdown is explainable and sums to the total`() {
        val stats = TodoStats.weekStats(items, TODAY)
        val b = stats.breakdown!!
        // Timeliness excluded (nothing closed yet) → 90 rescales to 100.
        assertEquals(50, b.completion)     // 100/90 × 45 × 2/2
        assertEquals(20, b.consistency)    // 100/90 × 18 × 1/1
        assertEquals(2, b.streak)          // 100/90 × 12 × 1/7
        assertEquals(0, b.timeliness)      // excluded → no points, no max
        assertEquals(0, b.timelinessMax)
        assertEquals(0, b.closedItems)
        assertEquals(50, b.completionMax)
        assertEquals(20, b.consistencyMax)
        assertEquals(13, b.streakMax)
        // Volume: 2 completed vs the 1/week baseline → full credit.
        assertEquals(17, b.volume)         // 100/90 × 15 × 1
        assertEquals(0, b.overdueCount)
        assertEquals(0, b.missedCount)
        assertEquals(89, b.total)
    }

    @Test
    fun `overdue days reduce the score via completion and timeliness`() {
        // Permanent daily todo due Mon..Wed; completed only Monday.
        val items = listOf(
            item("p", type = TodoType.PERMANENT, start = mon,
                completions = mapOf(mon.toEpochDay() to at(mon, 8)))
        )
        val today = wed // Wednesday
        val stats = TodoStats.weekStats(items, today)
        val b = stats.breakdown!!
        assertEquals(1, b.overdueCount)   // Tuesday passed uncompleted
        assertEquals(0, b.missedCount)
        // v3: the missed occurrence is reflected INSIDE the components — no
        // separate penalty lines. Closed items = Mon (done) + Tue (missed);
        // Timeliness = 10 × (1 − 1/2) = 5.
        assertEquals(2, b.closedItems)
        // 10 × (1 − 1/2) = 5, rescaled by 100/85 (Volume is excluded here) → 6.
        assertEquals(6, b.timeliness)
        // Volume is EXCLUDED here (the todo only started today, so there is no
        // earlier week to compare against). Completion 45×(2/6)=15 +
        // Consistency 18×(1/3)=6 + Streak 0 (yesterday had no completion — the
        // streak must end today or yesterday) + Timeliness 5 = 26, rescaled by
        // 100/(45+18+12+10) → 31.
        assertNull(b.baselineCompleted)
        assertEquals(0, b.volumeMax)
        assertEquals(
            "completion=${b.completion}/${b.completionMax} consistency=${b.consistency}/${b.consistencyMax} " +
                "streak=${b.streak}/${b.streakMax} streakDays=${b.streakDays} timeliness=${b.timeliness}/${b.timelinessMax} " +
                "volume=${b.volume}/${b.volumeMax} " +
                "dueW=${b.dueWeight} doneW=${b.doneWeight} score=${stats.score}",
            31, stats.score
        )
    }

    @Test
    fun `expired temporaries count as missed not overdue`() {
        // A temp todo that ended Tuesday is archived by Wednesday — its
        // uncompleted days are MISSED, never overdue.
        val items = listOf(
            item("t", start = mon, end = tue)
        )
        val today = wed
        val stats = TodoStats.weekStats(items, today)
        val b = stats.breakdown!!
        assertEquals(0, b.overdueCount)
        assertEquals(2, b.missedCount)
        // Both closed items missed → Timeliness = 10 × (1 − 2/2) = 0.
        assertEquals(2, b.closedItems)
        assertEquals(0, b.timeliness)
        // Nothing was completed → every component is 0; no free points.
        assertEquals(0, stats.score)
    }

    @Test
    fun `best day picks the highest completion rate with count tiebreak`() {
        val stats = TodoStats.weekStats(items, TODAY)
        // Only Mon is applicable (2/2) — the best (and only) measurable day.
        assertEquals(mon, stats.bestDay?.date)
        assertEquals(1.0f, stats.bestDay!!.rate)
    }

    @Test
    fun `improvement compares against last week and first week has none`() {
        val stats = TodoStats.weekStats(items, TODAY)
        // This week 2/2 = 100%; last week t4 completed 1 of 3 ≈ 33% → +66 points.
        assertEquals(66, stats.improvementPoints)
        assertFalse(stats.firstWeek)

        // A single-week user has no previous data → first-week message instead.
        val fresh = listOf(
            item("a", start = mon, end = mon, completions = mapOf(mon.toEpochDay() to 1L))
        )
        val first = TodoStats.weekStats(fresh, TODAY)
        assertTrue(first.firstWeek)
        assertNull(first.improvementPoints)
    }

    @Test
    fun `most productive time is a two hour window`() {
        val stats = TodoStats.weekStats(items, TODAY)
        // Mon 8am + Mon 9am → the 8-10 AM window wins.
        assertEquals(8 to 10, stats.mostProductiveWindow)
    }

    @Test
    fun `late night productive window ends at 24 and labels without crashing`() {
        // Two completions at 22:xx and 23:xx → the window is 22:00–24:00. The
        // end (24) used to crash TodoCodec.timeLabel with a DateTimeException
        // when the Statistics section rendered it; it now wraps to midnight.
        val late = listOf(
            item("a", start = TODAY, end = TODAY, completions = mapOf(TODAY.toEpochDay() to at(TODAY, 22))),
            item("b", start = TODAY, end = TODAY, completions = mapOf(TODAY.toEpochDay() to at(TODAY, 23)))
        )
        val stats = TodoStats.weekStats(late, TODAY)
        assertEquals(22 to 24, stats.mostProductiveWindow)
        assertEquals(
            "10:00 PM – 12:00 AM",
            TodoCodec.timeLabel(stats.mostProductiveWindow!!.first * 60) + " – " +
                TodoCodec.timeLabel(stats.mostProductiveWindow!!.second * 60)
        )
    }

    @Test
    fun `streak counts consecutive completed days ending today or yesterday`() {
        // Completions on Mon, Wed, Sat this week (+ last Wed). Today (Mon) has
        // one → streak = 1 (Mon), since Sunday has none.
        assertEquals(1, TodoStats.streak(items, TODAY))
    }

    @Test
    fun `streak continues when today already has a completion`() {
        val done = listOf(
            item("a", start = TODAY.minusDays(2), end = TODAY, completions = mapOf(
                TODAY.minusDays(2).toEpochDay() to 1L,
                TODAY.minusDays(1).toEpochDay() to 2L,
                TODAY.toEpochDay() to 3L
            ))
        )
        assertEquals(3, TodoStats.streak(done, TODAY))
    }

    @Test
    fun `active days counts only days with completions`() {
        val stats = TodoStats.weekStats(items, TODAY)
        // Only Monday is applicable and completed this week.
        assertEquals(1, stats.activeDays)
    }

    @Test
    fun `future todos never affect this weeks applicable stats`() {
        val future = listOf(
            item("f", start = TODAY.plusDays(1), end = TODAY.plusDays(1)),  // due tomorrow
            item("p", type = TodoType.PERMANENT, start = TODAY, days = setOf(2)) // due Tue
        )
        val stats = TodoStats.weekStats(future, TODAY)
        assertEquals(0, stats.due)
        assertEquals(0, stats.completed)
        assertNull(stats.score)
        assertNull(stats.bestDay)
        assertEquals(0, stats.remainingToday)

        // The calendar still SHOWS the future schedule (info, not progress):
        // 1 occurrence tomorrow + 3 future Tuesdays this month.
        val month = TodoStats.monthStats(future, TODAY)
        assertEquals(4, month.futureScheduled)
        assertEquals(0, month.due)
        assertEquals(0, month.percent)
    }

    @Test
    fun `closed strict window today counts as overdue and not remaining`() {
        // A strict-interval todo with a window that ended at 11:00 — and it is
        // now 11:01. The day is due and cannot be redone, so it counts as
        // overdue (not remaining) the moment the window closes.
        val strict = item(
            "s", start = mon,
            strictInterval = true, timeStart = 9 * 60, timeEnd = 11 * 60
        )
        val after = atMinutes(mon, 11 * 60 + 1)
        val stats = TodoStats.weekStats(listOf(strict), TODAY, after)
        assertEquals(1, stats.due)
        assertEquals(0, stats.completed)
        assertEquals(0, stats.remainingToday)
        assertEquals(1, stats.overdueCount)
        assertEquals(0, stats.missedCount)
        val b = stats.breakdown!!
        // The closed window is one closed item that was missed → Timeliness 0.
        assertEquals(1, b.closedItems)
        assertEquals(0, b.timeliness)
        // 1 due, 0 done, 0 active days, 0 streak → every component 0.
        assertEquals(0, stats.score)

        // While the window is still OPEN, the same todo is actionable — and
        // Timeliness is excluded (nothing has reached its due time yet).
        val open = TodoStats.weekStats(listOf(strict), TODAY, atMinutes(mon, 10 * 60))
        assertEquals(1, open.remainingToday)
        assertEquals(0, open.overdueCount)
        assertEquals(0, open.breakdown!!.timelinessMax)
        assertEquals(0, open.score) // still pending → 0/100, not a free score
    }

    @Test
    fun `month stats count only applicable days`() {
        val items = listOf(
            item("a", start = TODAY, end = TODAY),
            item("b", start = TODAY.plusDays(5), end = TODAY.plusDays(5)) // future
        )
        val month = TodoStats.monthStats(items, TODAY)
        assertEquals(1, month.due)
        assertEquals(0, month.completed)
        assertEquals(1, month.futureScheduled)
        assertEquals(0, month.percent)
    }

    // ── Centralized scoring (attempted / time proportional) ──

    @Test
    fun `occurrence score rewards attempted and time based todos proportionally`() {
        val normal = TodoItem(
            id = "n", title = "n", startDateEpochDay = TODAY.toEpochDay(),
            completions = mapOf(TODAY.toEpochDay() to 1L)
        )
        val attemptedDone = TodoItem(
            id = "a", title = "a", startDateEpochDay = TODAY.toEpochDay(),
            behavior = TodoBehavior.ATTEMPTED,
            completions = mapOf(TODAY.toEpochDay() to 1L)
        )
        val attemptedOnly = TodoItem(
            id = "ao", title = "ao", startDateEpochDay = TODAY.toEpochDay(),
            behavior = TodoBehavior.ATTEMPTED,
            events = listOf(TodoEvent.Attempted(1L, TODAY.toEpochDay()))
        )
        val timeHalf = TodoItem(
            id = "th", title = "th", startDateEpochDay = TODAY.toEpochDay(),
            behavior = TodoBehavior.TIME, targetDurationMinutes = 60,
            completions = mapOf(TODAY.toEpochDay() to 1L),
            events = listOf(TodoEvent.TimeAdded(2L, TODAY.toEpochDay(), 30))
        )
        val timeOver = TodoItem(
            id = "to", title = "to", startDateEpochDay = TODAY.toEpochDay(),
            behavior = TodoBehavior.TIME, targetDurationMinutes = 60,
            completions = mapOf(TODAY.toEpochDay() to 1L),
            events = listOf(TodoEvent.TimeAdded(2L, TODAY.toEpochDay(), 120))
        )
        val open = TodoItem(
            id = "open", title = "open", startDateEpochDay = TODAY.toEpochDay()
        )

        assertEquals(10f, TodoStats.occurrenceScore(normal, TODAY), 0.001f)
        assertEquals(5f, TodoStats.occurrenceScore(attemptedDone, TODAY), 0.001f)
        assertEquals(5f, TodoStats.occurrenceScore(attemptedOnly, TODAY), 0.001f)
        // 30 of 60 target minutes → half credit; over-target is capped at full.
        assertEquals(5f, TodoStats.occurrenceScore(timeHalf, TODAY), 0.001f)
        assertEquals(10f, TodoStats.occurrenceScore(timeOver, TODAY), 0.001f)
        assertEquals(0f, TodoStats.occurrenceScore(open, TODAY), 0.001f)
    }

    @Test
    fun `time based todos earn points before they are ever completed`() {
        fun timeItem(id: String, minutes: Int, attempted: Boolean = false, done: Boolean = false) =
            TodoItem(
                id = id, title = id, startDateEpochDay = TODAY.toEpochDay(),
                behavior = TodoBehavior.TIME, targetDurationMinutes = 60,
                completions = if (done) mapOf(TODAY.toEpochDay() to 1L) else emptyMap(),
                events = buildList {
                    if (minutes > 0) add(TodoEvent.TimeAdded(2L, TODAY.toEpochDay(), minutes))
                    if (attempted) add(TodoEvent.Attempted(3L, TODAY.toEpochDay()))
                }
            )

        // 30 of 60 minutes logged and NOT ticked off → half credit, not zero.
        assertEquals(5f, TodoStats.occurrenceScore(timeItem("half", 30), TODAY), 0.001f)
        // A quarter of the target earns a quarter.
        assertEquals(2.5f, TodoStats.occurrenceScore(timeItem("quarter", 15), TODAY), 0.001f)
        // Nothing logged, nothing attempted → still zero (no free points).
        assertEquals(0f, TodoStats.occurrenceScore(timeItem("untouched", 0), TODAY), 0.001f)
        // Marked attempted with no time logged → the 50% floor.
        assertEquals(
            5f,
            TodoStats.occurrenceScore(timeItem("attempted", 0, attempted = true), TODAY),
            0.001f
        )
        // Completing it is worth at least half even with nothing logged.
        assertEquals(
            5f,
            TodoStats.occurrenceScore(timeItem("ticked", 0, done = true), TODAY),
            0.001f
        )
    }

    @Test
    fun `partial credit lifts the progress ring and the overall score`() {
        val attempted = TodoItem(
            id = "a", title = "a",
            startDateEpochDay = TODAY.toEpochDay(), endDateEpochDay = TODAY.toEpochDay(),
            behavior = TodoBehavior.ATTEMPTED
        )
        val before = TodoStats.weekStats(listOf(attempted), TODAY)
        assertEquals(0, before.creditPercent)
        assertEquals(0, before.score ?: 0)

        val after = TodoStats.weekStats(
            TodoCodec.attempted(listOf(attempted), "a", TODAY, 1L), TODAY
        )
        // The visible ring (credit-weighted progress) and the 100-point score
        // BOTH move — partial work is not just a "+5 pts" chip on the card.
        assertTrue(
            "ring did not move: ${before.creditPercent} -> ${after.creditPercent}",
            after.creditPercent > before.creditPercent
        )
        assertTrue(
            "score did not move: ${before.score} -> ${after.score}",
            (after.score ?: 0) > (before.score ?: 0)
        )
        assertTrue(after.hasPartialCredit)
        assertEquals(1, after.partialOccurrences)
        // The raw completed figure is untouched by an attempt.
        assertEquals(before.percent, after.percent)
    }

    @Test
    fun `logged time lifts the progress ring before a time todo is finished`() {
        val timed = TodoItem(
            id = "t", title = "t",
            startDateEpochDay = TODAY.toEpochDay(), endDateEpochDay = TODAY.toEpochDay(),
            behavior = TodoBehavior.TIME, targetDurationMinutes = 60
        )
        val empty = TodoStats.weekStats(listOf(timed), TODAY)
        val half = TodoStats.weekStats(
            listOf(timed.copy(events = listOf(TodoEvent.TimeAdded(1L, TODAY.toEpochDay(), 30)))),
            TODAY
        )
        assertEquals(0, empty.creditPercent)
        assertEquals(50, half.creditPercent)
        assertTrue((half.score ?: 0) > (empty.score ?: 0))
        assertEquals(1, half.partialOccurrences)
        // 15 of 60 minutes is a quarter of the plan, not half.
        val quarter = TodoStats.weekStats(
            listOf(timed.copy(events = listOf(TodoEvent.TimeAdded(1L, TODAY.toEpochDay(), 15)))),
            TODAY
        )
        assertEquals(25, quarter.creditPercent)
    }

    @Test
    fun `a completed week reports progress equal to completion`() {
        val done = item(
            "done", start = TODAY, end = TODAY,
            completions = mapOf(TODAY.toEpochDay() to 1L)
        )
        val stats = TodoStats.weekStats(listOf(done), TODAY)
        assertEquals(stats.percent, stats.creditPercent)
        assertFalse(stats.hasPartialCredit)
        assertEquals(0, stats.partialOccurrences)
    }

    @Test
    fun `behavior counts split completed attempted and incomplete`() {
        val items = listOf(
            item("done", start = mon, end = mon, completions = mapOf(mon.toEpochDay() to at(mon, 8))),
            TodoItem(
                id = "attempt", title = "attempt", startDateEpochDay = mon.toEpochDay(),
                endDateEpochDay = mon.toEpochDay(), behavior = TodoBehavior.ATTEMPTED,
                events = listOf(TodoEvent.Attempted(1L, mon.toEpochDay()))
            ),
            item("missed", start = mon, end = mon)
        )
        // Wednesday: Mon is past.
        val counts = TodoStats.behaviorCounts(items, mon, wed, today = wed)
        assertEquals(1, counts.completed)
        assertEquals(1, counts.attempted)
        assertEquals(1, counts.incomplete)
    }

    @Test
    fun `productivity summary exposes streaks, counts and week over week delta`() {
        val done = listOf(
            item(
                "a", start = mon.minusDays(2), end = TODAY,
                completions = mapOf(
                    mon.minusDays(2).toEpochDay() to at(mon.minusDays(2), 9),
                    mon.minusDays(1).toEpochDay() to at(mon.minusDays(1), 9),
                    mon.toEpochDay() to at(mon, 9)
                )
            )
        )
        val summary = TodoStats.productivitySummary(done, TODAY)
        assertEquals(3, summary.currentStreak)
        assertEquals(3, summary.longestStreak)
        assertEquals(1, summary.completed)   // this week (Monday) only
        assertEquals(0, summary.attempted)
        assertNotNull(summary.weekScore)
    }

    @Test
    fun `heatmap level tracks the daily score intensity`() {
        val done = item("a", start = TODAY, end = TODAY, completions = mapOf(TODAY.toEpochDay() to 1L))
        val full = TodoStats.dayProductivity(listOf(done), TODAY)
        assertEquals(1, full.completed)
        assertEquals(4, full.level)

        val future = item("b", start = TODAY.plusDays(1), end = TODAY.plusDays(1))
        val empty = TodoStats.dayProductivity(listOf(future), TODAY)
        assertEquals(0, empty.due)
        assertEquals(0, empty.level)
    }

    // ── Volume / effort vs the user's own baseline (v3) ──

    /** Four past weeks that each completed two of two applicable days. */
    private fun baselineHistory(): List<TodoItem> = (1..4).map { back ->
        val monday = TodoStats.mondayOf(TODAY).minusWeeks(back.toLong())
        item(
            "h$back",
            start = monday,
            end = monday.plusDays(1),
            completions = mapOf(
                monday.toEpochDay() to at(monday, 9),
                monday.plusDays(1).toEpochDay() to at(monday.plusDays(1), 9)
            )
        )
    }

    @Test
    fun `volume compares this week against the user's own recent baseline`() {
        val history = baselineHistory()
        val dueTwo = (1..2).map { i ->
            item(
                "t$i", start = TODAY, end = TODAY,
                completions = mapOf(TODAY.toEpochDay() to at(TODAY, 8 + i))
            )
        }

        // Both due todos completed → matches the 2/week baseline: full volume.
        val atBaseline = TodoStats.weekStats(history + dueTwo, TODAY).breakdown!!
        assertEquals(2f, atBaseline.baselineCompleted!!, 0.001f)
        assertEquals(atBaseline.volumeMax, atBaseline.volume)

        // Only half the usual volume → roughly half the volume points.
        val half = TodoStats.weekStats(history + dueTwo.take(1), TODAY).breakdown!!
        assertTrue("half volume should score below the baseline", half.volume < atBaseline.volume)
        assertTrue("half volume should still earn something", half.volume > 0)

        // No earlier week at all → the component is excluded, never zeroed in.
        val fresh = TodoStats.weekStats(dueTwo, TODAY).breakdown!!
        assertNull(fresh.baselineCompleted)
        assertEquals(0, fresh.volumeMax)
        assertEquals(0, fresh.volume)
    }

    @Test
    fun `padding the week with undone todos cannot raise the score`() {
        val history = baselineHistory()
        val done = item(
            "d", start = TODAY, end = TODAY,
            completions = mapOf(TODAY.toEpochDay() to at(TODAY, 8))
        )
        val padded = listOf(done) + (1..9).map { i -> item("p$i", start = TODAY, end = TODAY) }

        val focused = TodoStats.weekStats(history + listOf(done), TODAY).score!!
        val paddedScore = TodoStats.weekStats(history + padded, TODAY).score!!
        assertTrue(
            "adding undone todos must not inflate the score ($focused → $paddedScore)",
            paddedScore <= focused
        )
    }

    @Test
    fun `attempted and time todos raise the weekly score with partial credit`() {
        val plainMiss = item("m", start = TODAY, end = TODAY)
        val attempted = TodoItem(
            id = "at", title = "at", startDateEpochDay = TODAY.toEpochDay(),
            endDateEpochDay = TODAY.toEpochDay(), behavior = TodoBehavior.ATTEMPTED,
            events = listOf(TodoEvent.Attempted(1L, TODAY.toEpochDay()))
        )
        val timeHalf = TodoItem(
            id = "th", title = "th", startDateEpochDay = TODAY.toEpochDay(),
            endDateEpochDay = TODAY.toEpochDay(), behavior = TodoBehavior.TIME,
            targetDurationMinutes = 60,
            completions = mapOf(TODAY.toEpochDay() to 1L),
            events = listOf(TodoEvent.TimeAdded(2L, TODAY.toEpochDay(), 30))
        )
        val full = item("f", start = TODAY, end = TODAY, completions = mapOf(TODAY.toEpochDay() to at(TODAY, 8)))
        val noCredit = TodoStats.weekStats(listOf(plainMiss), TODAY).score ?: 0
        val attemptedCredit = TodoStats.weekStats(listOf(attempted), TODAY).score ?: 0
        val timeCredit = TodoStats.weekStats(listOf(timeHalf), TODAY).score ?: 0
        val fullCredit = TodoStats.weekStats(listOf(full), TODAY).score ?: 0
        assertTrue("attempted ($attemptedCredit) should beat none ($noCredit)", attemptedCredit > noCredit)
        assertTrue("time ($timeCredit) should beat none ($noCredit)", timeCredit > noCredit)
        // Partial credit lands strictly between nothing and a full completion.
        assertTrue(attemptedCredit in (noCredit + 1)..(fullCredit - 1))
        assertTrue(timeCredit in (noCredit + 1)..(fullCredit - 1))
    }
}
