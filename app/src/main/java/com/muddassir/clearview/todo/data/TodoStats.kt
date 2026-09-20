package com.muddassir.clearview.todo.data

import com.muddassir.clearview.todo.model.TodoBehavior
import com.muddassir.clearview.todo.model.TodoItem
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pure weekly + monthly statistics for the Todo screen. No Android
 * dependencies — unit-testable. Every figure is computed from
 * [TodoItem.completions] + the applicable-date rules in [TodoCodec], so
 * archived temporary todos keep contributing to history.
 *
 * DATE RULE (the single most important invariant): only days that have
 * ARRIVED (≤ today) are "applicable". A future day — however many todos are
 * scheduled on it — is empty here: it creates no due/completed counts, no
 * bar, no score effect, no missed/overdue penalty. Future schedules are
 * surfaced separately by the calendar ([monthDayStats], which keeps them for
 * display only).
 *
 * SCORE (max 100, fully explainable — v3, every point must be EARNED):
 *   Completion  45  · priority-weighted: 45 × Σweight(completed) / Σweight(due)
 *   Consistency 18  · 18 × (active days / days with due todos)
 *   Streak      12  · 12 × (min(streak, 7) / 7) — 0 is earned, not excluded
 *   Timeliness  10  · 10 × (1 − overdue/missed ÷ closed items)
 *   Volume      15  · 15 × min(1, completed ÷ your own recent baseline)
 *
 * Exclusion rule: a component only scores when there is something to measure.
 * If its denominator is 0 it is EXCLUDED and the remaining base weights are
 * rescaled by 100 / (sum of included weights) so the total still reaches 100.
 * Nothing defaults to full marks for being empty or unfailed — that was the
 * old bug (free High-Priority +5 and On-track +25 produced a 30/100 score for
 * zero completions). A week with no due todos gets no numeric score at all.
 *
 * WHY VOLUME (v3): the four v2 components all measure RATIOS, so a single
 * trivial todo completed daily — or five easy ones added and ticked off —
 * scored identically to a heavy, disciplined week. Volume compares this
 * week's completed count against the user's OWN recent baseline (the average
 * of the previous [BASELINE_WEEKS] applicable weeks), which (a) rewards doing
 * more than you usually do, (b) cannot be inflated by merely creating more
 * todos (only completions count, and more todos also raise the Completion
 * denominator), and (c) stays out of the score entirely until real history
 * exists, so new users are not penalised for having no past.
 */
object TodoStats {

    // ── v3 base weights (renormalized after exclusions) ────────────────
    private const val W_COMPLETION = 45
    private const val W_CONSISTENCY = 18
    private const val W_STREAK = 12
    private const val W_TIMELINESS = 10
    private const val W_VOLUME = 15

    /** How many past weeks form the volume baseline. */
    private const val BASELINE_WEEKS = 4

    data class WeekDayStats(
        val date: LocalDate,
        val due: Int,
        val completed: Int
    ) {
        val rate: Float
            get() = if (due > 0) (completed.toFloat() / due).coerceIn(0f, 1f) else 0f
    }

    /**
     * The per-component breakdown behind the weekly score (v2). Each earned
     * value is the component's RENORMALIZED weight × its ratio; the Max is
     * that renormalized weight (excluded components have Max 0).
     */
    data class ScoreBreakdown(
        val completion: Int,          // earned, 0..completionMax
        val completionMax: Int,       // renormalized weight (e.g. 56 when Timeliness is excluded)
        val consistency: Int,
        val consistencyMax: Int,
        val streak: Int,
        val streakMax: Int,
        val streakDays: Int,          // raw streak (for the explanation)
        val timeliness: Int,          // 0 when nothing has closed yet
        val timelinessMax: Int,       // 0 when excluded
        val volume: Int,              // v3: 0 when there is no baseline history yet
        val volumeMax: Int,           // 0 when excluded
        val baselineCompleted: Float?, // the user's recent weekly average, null without history
        val closedItems: Int,         // occurrences whose due day has passed this week (completed or not)
        val dueWeight: Float,         // Σ priority weights of all due occurrences
        val doneWeight: Float,        // Σ priority weights × behaviour credit earned
        val overdueCount: Int,        // closed & uncompleted, still-active
        val missedCount: Int,         // closed & uncompleted, expired
        val total: Int                // 0..100
    )

    data class WeekStats(
        val today: LocalDate,
        /** Monday..Sunday of the current week; days AFTER today are empty (due=0). */
        val days: List<WeekDayStats>,
        val completed: Int,
        val due: Int,
        /** 0..100; null when the week had no due todos (nothing to measure). */
        val score: Int?,
        /** The component breakdown behind [score]; null together with it. */
        val breakdown: ScoreBreakdown?,
        /** The best applicable day by completion rate; null when nothing completed. */
        val bestDay: WeekDayStats?,
        /** Consecutive days (ending today or yesterday) with at least one completion. */
        val streak: Int,
        /** Days (of 7) with at least one completion. */
        val activeDays: Int,
        /** Completion-rate percentage points vs the previous week; null when no previous-week data. */
        val improvementPoints: Int?,
        /** True when this is the first week with any todo history — no comparison exists. */
        val firstWeek: Boolean,
        /** Applicable todos due today that are not yet completed. */
        val remainingToday: Int,
        /** Past uncompleted days this week of still-active todos. */
        val overdueCount: Int,
        /** Past uncompleted days this week of expired (archived) todos. */
        val missedCount: Int,
        /** The most productive 2-hour window (startHour..startHour+2) this week, or null below 2 completions. */
        val mostProductiveWindow: Pair<Int, Int>?,
        /** Σ priority weights of the week's due occurrences (Mon..today). */
        val dueWeight: Float,
        /** Σ priority weights × behaviour credit actually earned. */
        val doneWeight: Float,
        /**
         * Occurrences this week that earned PARTIAL credit — marked attempted,
         * or a TIME todo with some minutes logged but not finished. They are
         * why [creditRate] can exceed [rate].
         */
        val partialOccurrences: Int
    ) {
        val percent: Int get() = if (due > 0) (rate * 100).toInt() else 0
        val rate: Float get() = if (due > 0) (completed.toFloat() / due).coerceIn(0f, 1f) else 0f

        /**
         * PROGRESS, not just completions: the same priority-weighted,
         * behaviour-aware credit the score uses (full for a completion, half
         * for an attempt, minutes/target for a time todo), as a 0..1 fraction
         * of the week's due weight.
         *
         * This is the number the dashboard's ring draws, so marking a todo
         * attempted or logging part of a target VISIBLY moves the headline —
         * not just the 100-point score — which is the whole point of partial
         * credit. [rate] stays the raw completed/due figure.
         */
        val creditRate: Float
            get() = if (dueWeight > 0f) (doneWeight / dueWeight).coerceIn(0f, 1f) else rate

        /** [creditRate] as whole percent. */
        val creditPercent: Int get() = (creditRate * 100).roundToInt()

        /** True when partial work has lifted the progress above raw completions. */
        val hasPartialCredit: Boolean get() = creditRate > rate + 0.0001f
    }

    /** One day of the calendar / month grid: raw due + completed counts. */
    data class MonthDayStats(
        val date: LocalDate,
        /** All scheduled occurrences that day (FUTURE days included — for the calendar display only). */
        val due: Int,
        val completed: Int,
        /** True when the day hasn't arrived yet — its schedule is info, never progress. */
        val isFuture: Boolean
    )

    data class MonthStats(
        val monthStart: LocalDate,
        /** Every day of the month (Monday→Sunday irrelevant here), raw counts. */
        val days: List<MonthDayStats>,
        /** Applicable (≤ today) due occurrences this month. */
        val due: Int,
        val completed: Int
    ) {
        val percent: Int get() = if (due > 0) (completed.toFloat() / due * 100).toInt() else 0
        /** Scheduled occurrences after today (calendar-only info, never progress). */
        val futureScheduled: Int get() = days.filter { it.isFuture }.sumOf { it.due }
    }

    /** The Monday of the week containing [today]. */
    fun mondayOf(today: LocalDate): LocalDate =
        today.with(DayOfWeek.MONDAY)

    /** The seven days of the current week, Monday first. */
    fun weekDays(today: LocalDate): List<LocalDate> =
        (0..6).map { mondayOf(today).plusDays(it.toLong()) }

    /** Due and completed counts for [items] on [day] (all items, incl. archived ones). */
    fun dayStats(items: List<TodoItem>, day: LocalDate): WeekDayStats {
        val due = items.count { TodoCodec.isActiveOn(it, day) }
        val completed = items.count { TodoCodec.completedOn(it, day) }
        return WeekDayStats(day, due, completed)
    }

    /** Raw per-day counts for [day] (calendar: keeps future scheduled info). */
    fun monthDayStats(items: List<TodoItem>, day: LocalDate, today: LocalDate = LocalDate.now()): MonthDayStats {
        val due = items.count { TodoCodec.isActiveOn(it, day) }
        val completed = items.count { TodoCodec.completedOn(it, day) }
        return MonthDayStats(day, due, completed, day.isAfter(today))
    }

    /**
     * Full weekly statistics for the week containing [today]. Days after
     * today are deliberately EMPTY (due=0) — future schedules never count as
     * progress, missed, or score. [nowMillis] lets strict-interval todos
     * whose window closed TODAY count as missed/overdue immediately (their
     * day has effectively passed for completion purposes).
     */
    fun weekStats(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): WeekStats {
        val rawDays = weekDays(today)
        val days = rawDays.map { day ->
            if (day > today) WeekDayStats(day, 0, 0)
            else dayStats(items, day)
        }
        val completed = days.sumOf { it.completed }
        val due = days.sumOf { it.due }
        val daysWithDue = days.count { it.due > 0 }
        val activeDays = days.count { it.completed > 0 }
        val bestDay = days.filter { it.due > 0 }.maxWithOrNull(
            compareBy<WeekDayStats>({ it.rate }, { it.completed })
        )

        // Previous week (full 7 days — it is entirely in the past, so every
        // day is applicable).
        val lastWeek = weekDays(today.minusWeeks(1)).map { dayStats(items, it) }
        val lastDue = lastWeek.sumOf { it.due }
        val lastCompleted = lastWeek.sumOf { it.completed }
        val improvement = if (due > 0 && lastDue > 0) {
            val thisRate = completed.toFloat() / due
            val lastRate = lastCompleted.toFloat() / lastDue
            ((thisRate - lastRate) * 100).toInt()
        } else null

        val streak = streak(items, today)
        // Remaining = still COMPLETABLE right now. A strict-interval todo
        // whose window closed today is already locked as missed — it can no
        // longer be completed, so it must not count as "remaining".
        val remainingToday = items.count {
            TodoCodec.canCompleteOn(it, today, nowMillis)
        }

        // Overdue vs missed (bounded to the applicable days of THIS week). A
        // normal todo's TODAY is still actionable (skipped); a strict-interval
        // todo's TODAY counts as soon as its window closes uncompleted.
        // `closedItems` counts every occurrence whose due day has PASSED this
        // week — completed or not — and is the Timeliness denominator.
        var overdue = 0
        var missed = 0
        var closedItems = 0
        days.filter { it.date <= today }.forEach { day ->
            items.forEach { item ->
                if (!TodoCodec.isActiveOn(item, day.date)) return@forEach
                val dayClosed = day.date < today || TodoCodec.intervalEnded(item, day.date, nowMillis)
                if (!dayClosed) return@forEach
                closedItems++
                if (!TodoCodec.completedOn(item, day.date)) {
                    if (TodoCodec.isArchived(item, today)) missed++ else overdue++
                }
            }
        }

        // Volume baseline (v3): the average completed count of the previous
        // [BASELINE_WEEKS] weeks that actually had something due. Weeks with
        // nothing due are skipped (no data ≠ zero effort), and when NO previous
        // week has any data the component is excluded — a brand-new user is
        // never scored against an empty past.
        val baselineCompleted: Float? = (1..BASELINE_WEEKS)
            .map { back ->
                val past = weekDays(today.minusWeeks(back.toLong())).map { dayStats(items, it) }
                if (past.any { it.due > 0 }) past.sumOf { it.completed } else null
            }
            .filterNotNull()
            .takeIf { it.isNotEmpty() }
            ?.let { it.average().toFloat() }

        // Priority-weighted, behaviour-aware credit for the applicable week
        // (Mon..today): the numerator/denominator behind BOTH the visible
        // progress ring and the score's completion component, so partial work
        // (attempted, part of a time target) counts in the same way everywhere.
        // Hoisted out of the breakdown block because creditRate needs it even
        // when the breakdown itself is empty.
        var dueWeight = 0f
        var doneWeight = 0f
        var partialOccurrences = 0
        days.filter { it.date <= today }.forEach { day ->
            items.forEach { item ->
                if (TodoCodec.isActiveOn(item, day.date)) {
                    val weight = item.priority.scoreWeight
                    dueWeight += weight
                    val credit = creditFraction(item, day.date)
                    doneWeight += weight * credit
                    // Partial = something earned, but not a completed occurrence.
                    if (credit > 0f && credit < 1f && !TodoCodec.completedOn(item, day.date)) {
                        partialOccurrences++
                    }
                }
            }
        }

        // Score + breakdown (only meaningful with at least one due todo). v3:
        // priority-weighted completion (a single bucket — no separate
        // High-Priority that can sit empty and auto-pass), a volume component
        // measured against the user's own recent baseline, and components that
        // are EXCLUDED — with the remaining weights rescaled — when their
        // denominator is 0. Nothing ever earns points for being empty.
        val breakdown = if (due > 0 && dueWeight > 0f) {
            val completionRaw = W_COMPLETION * (doneWeight / dueWeight)
            val consistencyRaw = if (daysWithDue > 0) {
                W_CONSISTENCY * (activeDays.toFloat() / daysWithDue)
            } else 0f
            val streakRaw = W_STREAK * (streak.coerceAtMost(7).toFloat() / 7f)
            // Timeliness: only when something has actually reached its due
            // time this week — being "on track" on a not-yet-due todo is not
            // an achievement. Overdue/missed shrink it toward 0.
            val timelinessRaw = if (closedItems > 0) {
                W_TIMELINESS * (1f - (overdue + missed).toFloat() / closedItems)
            } else 0f
            // Volume: this week's completions against the recent baseline.
            // At/above your usual volume = full credit (capped at 1 so one
            // huge week can't bank future points); below it scales down. A
            // baseline of 0 (history exists but nothing was ever completed)
            // makes ANY completion a full-credit improvement.
            val hasBaseline = baselineCompleted != null
            val baseline = baselineCompleted ?: 0f
            val volumeRaw = when {
                !hasBaseline -> 0f
                baseline <= 0f -> if (completed > 0) W_VOLUME.toFloat() else 0f
                else -> W_VOLUME * (completed.toFloat() / baseline).coerceIn(0f, 1f)
            }
            val includedWeight = W_COMPLETION + W_CONSISTENCY + W_STREAK +
                (if (closedItems > 0) W_TIMELINESS else 0) +
                (if (hasBaseline) W_VOLUME else 0)
            val scale = 100f / includedWeight
            // Score = round(Σ component scores) — one rounding at the end (per
            // the spec). The breakdown rows round independently, so they may
            // sum to ±1 of this authoritative total.
            val total = (scale * (completionRaw + consistencyRaw + streakRaw + timelinessRaw + volumeRaw))
                .roundToInt().coerceIn(0, 100)
            ScoreBreakdown(
                completion = (scale * completionRaw).roundToInt().coerceIn(0, 100),
                completionMax = (scale * W_COMPLETION).roundToInt().coerceIn(0, 100),
                consistency = (scale * consistencyRaw).roundToInt().coerceIn(0, 100),
                consistencyMax = (scale * W_CONSISTENCY).roundToInt().coerceIn(0, 100),
                streak = (scale * streakRaw).roundToInt().coerceIn(0, 100),
                streakMax = (scale * W_STREAK).roundToInt().coerceIn(0, 100),
                streakDays = streak,
                timeliness = if (closedItems > 0) {
                    (scale * timelinessRaw).roundToInt().coerceIn(0, 100)
                } else 0,
                timelinessMax = if (closedItems > 0) {
                    (scale * W_TIMELINESS).roundToInt().coerceIn(0, 100)
                } else 0,
                volume = if (hasBaseline) {
                    (scale * volumeRaw).roundToInt().coerceIn(0, 100)
                } else 0,
                volumeMax = if (hasBaseline) {
                    (scale * W_VOLUME).roundToInt().coerceIn(0, 100)
                } else 0,
                baselineCompleted = baselineCompleted,
                closedItems = closedItems,
                dueWeight = dueWeight,
                doneWeight = doneWeight,
                overdueCount = overdue,
                missedCount = missed,
                total = total
            )
        } else null

        return WeekStats(
            today = today,
            days = days,
            completed = completed,
            due = due,
            score = breakdown?.total,
            breakdown = breakdown,
            bestDay = bestDay?.takeIf { completed > 0 },
            streak = streak,
            activeDays = activeDays,
            improvementPoints = improvement,
            firstWeek = due > 0 && lastDue == 0,
            remainingToday = remainingToday,
            overdueCount = overdue,
            missedCount = missed,
            mostProductiveWindow = mostProductiveWindow(items, mondayOf(today).toEpochDay(), mondayOf(today).plusDays(7).toEpochDay()),
            dueWeight = dueWeight,
            doneWeight = doneWeight,
            partialOccurrences = partialOccurrences
        )
    }

    /** Raw monthly stats for [month] (calendar: keeps future scheduled info). */
    fun monthStats(items: List<TodoItem>, month: YearMonth, today: LocalDate): MonthStats {
        val start = month.atDay(1)
        val days = (0 until start.lengthOfMonth()).map { monthDayStats(items, start.plusDays(it.toLong()), today) }
        val applicable = days.filter { !it.isFuture }
        return MonthStats(
            monthStart = start,
            days = days,
            due = applicable.sumOf { it.due },
            completed = applicable.sumOf { it.completed }
        )
    }

    /** Raw monthly stats for the current month. */
    fun monthStats(items: List<TodoItem>, today: LocalDate): MonthStats =
        monthStats(items, YearMonth.from(today), today)

    /**
     * Consecutive days with at least one completion, ending today when today
     * already has one, otherwise ending yesterday (an unfinished current day
     * must not break the streak).
     */
    fun streak(items: List<TodoItem>, today: LocalDate): Int {
        var day = today
        if (items.none { TodoCodec.completedOn(it, day) }) day = day.minusDays(1)
        var count = 0
        while (items.any { TodoCodec.completedOn(it, day) }) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /**
     * The most productive 2-HOUR WINDOW (startHour..startHour+2) among the
     * completions in [fromEpoch, toEpoch): the window containing the most
     * completion timestamps (earliest wins ties). Null below 2 completions.
     */
    fun mostProductiveWindow(
        items: List<TodoItem>,
        fromEpoch: Long,
        toEpoch: Long
    ): Pair<Int, Int>? {
        val hours = items.asSequence()
            .flatMap { item -> item.completions.entries.asSequence() }
            .filter { it.key in fromEpoch until toEpoch }
            .mapNotNull { (_, at) ->
                Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).hour
            }
            .toList()
        if (hours.size < 2) return null
        var bestStart = 0
        var bestCount = -1
        for (start in 0..22) {
            val count = hours.count { it in start until start + 2 }
            if (count > bestCount) {
                bestCount = count
                bestStart = start
            }
        }
        return bestStart to bestStart + 2
    }

    // ── Scoring (single source of truth) ───────────────────────────

    /** Base points for one completed occurrence, before behavior weighting. */
    const val BASE_POINTS = 10f

    /**
     * Behaviour credit for one occurrence as a fraction of full value, i.e.
     * [occurrenceScore] normalized against [BASE_POINTS]: NORMAL completion
     * 1.0, ATTEMPTED 0.5, TIME minutes/target (capped at 1.0). Callers multiply
     * this by an occurrence's priority weight to keep every surface consistent.
     */
    fun creditFraction(item: TodoItem, day: LocalDate): Float =
        (occurrenceScore(item, day) / BASE_POINTS).coerceIn(0f, 1f)

    /**
     * Minutes logged on [day] against a TIME todo's target, as a 0..1 fraction
     * (1.0 when no target is set — the todo then only tracks time).
     */
    private fun timeFraction(item: TodoItem, day: LocalDate): Float {
        val target = item.targetDurationMinutes ?: 0
        if (target <= 0) return 1f
        return (TodoCodec.timeSpentOn(item, day).toFloat() / target).coerceIn(0f, 1f)
    }

    /**
     * THE score for one occurrence — every UI and summary figure must use this
     * so they can never disagree. Partial states are always worth SOMETHING
     * (never 0), because effort spent is real progress even when the todo was
     * not ticked off:
     *  - completed NORMAL         → full base points
     *  - completed ATTEMPTED      → 50% partial credit
     *  - completed TIME           → (minutes logged / target).coerceIn(0,1) × base,
     *                               with a 50% floor (ticking it off at all is
     *                               worth something even with no time logged)
     *  - attempted, not completed → 50% partial credit
     *  - TIME with minutes logged → (minutes logged / target) × base, whether or
     *    not it was completed: logging half the target earns half the points.
     *    Ticking it off does not change the points — it changes whether the
     *    occurrence counts as COMPLETED in the completion/consistency figures.
     *  - TIME marked attempted    → the better of the logged-time credit and
     *                               50%, so a worked-on session never scores 0
     *  - otherwise                → 0
     */
    fun occurrenceScore(
        item: TodoItem,
        day: LocalDate,
        basePoints: Float = BASE_POINTS
    ): Float {
        val attempted = TodoCodec.isAttemptedOn(item, day)
        val minutes = TodoCodec.timeSpentOn(item, day)
        if (TodoCodec.completedOn(item, day)) {
            return when (item.behavior) {
                TodoBehavior.NORMAL -> basePoints
                TodoBehavior.ATTEMPTED -> basePoints * 0.5f
                TodoBehavior.TIME -> {
                    val target = item.targetDurationMinutes ?: 0
                    if (target <= 0) basePoints
                    // Completing it is worth at least half, even with nothing
                    // logged (the user asserted they did the work).
                    else maxOf(timeFraction(item, day), 0.5f) * basePoints
                }
            }
        }
        return when {
            item.behavior == TodoBehavior.ATTEMPTED && attempted -> basePoints * 0.5f
            item.behavior == TodoBehavior.TIME -> {
                val byTime = if (minutes > 0) timeFraction(item, day) else 0f
                val byAttempt = if (attempted) 0.5f else 0f
                maxOf(byTime, byAttempt) * basePoints
            }
            else -> 0f
        }
    }

    /** Total earned points across [from]..[to] (inclusive). */
    fun earnedPoints(
        items: List<TodoItem>,
        from: LocalDate,
        to: LocalDate,
        basePoints: Float = BASE_POINTS
    ): Float {
        var total = 0f
        var day = from
        while (!day.isAfter(to)) {
            items.forEach { item ->
                if (TodoCodec.isActiveOn(item, day)) total += occurrenceScore(item, day, basePoints)
            }
            day = day.plusDays(1)
        }
        return total
    }

    /** Completed / attempted / incomplete counts over a range. */
    data class BehaviorCounts(
        val completed: Int,
        val attempted: Int,
        val incomplete: Int,
        /** Minutes logged against TIME-behavior todos in the range. */
        val productiveMinutes: Int
    )

    /**
     * Behavior breakdown for [from]..[to]: completed occurrences, ATTEMPTED
     * (marked attempted but not completed), and incomplete occurrences on days
     * that have PASSED (a normal todo due today is still actionable, never
     * incomplete) — plus the total minutes logged against TIME todos.
     */
    fun behaviorCounts(
        items: List<TodoItem>,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate = LocalDate.now(),
        nowMillis: Long = System.currentTimeMillis()
    ): BehaviorCounts {
        var completed = 0
        var attempted = 0
        var incomplete = 0
        var minutes = 0
        var day = from
        while (!day.isAfter(to) && !day.isAfter(today)) {
            items.forEach { item ->
                if (!TodoCodec.isActiveOn(item, day)) return@forEach
                minutes += TodoCodec.timeSpentOn(item, day)
                when {
                    TodoCodec.completedOn(item, day) -> completed++
                    TodoCodec.isAttemptedOn(item, day) -> attempted++
                    day < today || TodoCodec.intervalEnded(item, day, nowMillis) -> incomplete++
                }
            }
            day = day.plusDays(1)
        }
        return BehaviorCounts(completed, attempted, incomplete, minutes)
    }

    /** Longest run of consecutive days with ≥1 completion in [from]..[to]. */
    fun longestStreak(items: List<TodoItem>, from: LocalDate, to: LocalDate): Int {
        var best = 0
        var run = 0
        var day = from
        while (!day.isAfter(to)) {
            run = if (items.any { TodoCodec.completedOn(it, day) }) run + 1 else 0
            if (run > best) best = run
            day = day.plusDays(1)
        }
        return best
    }

    /** The earliest day any todo started, or null when there are no todos. */
    private fun firstActivityDay(items: List<TodoItem>): LocalDate? =
        items.minOfOrNull { it.startDateEpochDay }?.let(LocalDate::ofEpochDay)

    // ── Daily productivity (heatmap) ───────────────────────────────

    /** One heatmap/stat day: raw behaviour counts plus the earned score. */
    data class DayProductivity(
        val date: LocalDate,
        val due: Int,
        val completed: Int,
        val attempted: Int,
        val incomplete: Int,
        val productiveMinutes: Int,
        val earnedPoints: Float,
        val maxPoints: Float
    ) {
        val ratio: Float
            get() = if (maxPoints > 0f) (earnedPoints / maxPoints).coerceIn(0f, 1f) else 0f

        /** 0..4 intensity (LeetCode-style), from the day's earned score. */
        val level: Int
            get() = when {
                ratio <= 0f -> 0
                ratio < 0.25f -> 1
                ratio < 0.5f -> 2
                ratio < 0.75f -> 3
                else -> 4
            }
    }

    /** Raw productivity for one [day]. */
    fun dayProductivity(
        items: List<TodoItem>,
        day: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): DayProductivity {
        var due = 0
        var completed = 0
        var attempted = 0
        var incomplete = 0
        var minutes = 0
        var earned = 0f
        items.forEach { item ->
            if (!TodoCodec.isActiveOn(item, day)) return@forEach
            due++
            minutes += TodoCodec.timeSpentOn(item, day)
            earned += occurrenceScore(item, day)
            when {
                TodoCodec.completedOn(item, day) -> completed++
                TodoCodec.isAttemptedOn(item, day) -> attempted++
                TodoCodec.intervalEnded(item, day, nowMillis) -> incomplete++
            }
        }
        return DayProductivity(
            date = day,
            due = due,
            completed = completed,
            attempted = attempted,
            incomplete = incomplete,
            productiveMinutes = minutes,
            earnedPoints = earned,
            maxPoints = due * BASE_POINTS
        )
    }

    /** Productivity for every day in [days] (order preserved). */
    fun productivityHeatmap(
        items: List<TodoItem>,
        days: List<LocalDate>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<DayProductivity> = days.map { dayProductivity(items, it, nowMillis) }

    /**
     * The headlines above the heatmap: how much was completed, how many days
     * were worked on, and the longest run of consecutive working days — all
     * WITHIN the window the graph is showing.
     */
    data class HeatmapSummary(
        val completed: Int,
        /** Days the reader actually did something on (the days the grid shades). */
        val activeDays: Int,
        /** Longest run of consecutive active days inside the window. */
        val maxStreak: Int
    )

    /**
     * The heatmap's headline numbers, read off the very day list the squares
     * are drawn from — so the summary can never disagree with the grid it sits
     * on, and a streak can never be credited to a day the reader is not looking
     * at (which matters at a calendar year's edges).
     *
     * "Active" is the grid's own test for ink: the day earned something. A day
     * nobody worked on stays blank and breaks the run.
     */
    fun heatmapSummary(days: List<DayProductivity>): HeatmapSummary {
        var completed = 0
        var active = 0
        var streak = 0
        var best = 0
        days.forEach { day ->
            completed += day.completed
            if (day.earnedPoints > 0f) {
                active++
                streak++
                if (streak > best) best = streak
            } else {
                streak = 0
            }
        }
        return HeatmapSummary(completed, active, best)
    }

    // ── Consolidated productivity summary ──────────────────────────

    /** Everything the unified Productivity dashboard renders. */
    data class ProductivitySummary(
        val today: LocalDate,
        val currentStreak: Int,
        val longestStreak: Int,
        val weekScore: Int?,
        val previousWeekScore: Int?,
        /** Percentage change vs last week; null when either week is unscoreable. */
        val scoreDeltaPercent: Int?,
        val completed: Int,
        val attempted: Int,
        val incomplete: Int,
        val productiveMinutes: Int,
        val earnedPoints: Float
    )

    /**
     * The single call behind the Productivity dashboard, so every number on
     * screen agrees. Counts/points cover the current week (Monday→today); the
     * previous-week score uses the FULL previous week.
     */
    fun productivitySummary(
        items: List<TodoItem>,
        today: LocalDate,
        nowMillis: Long = System.currentTimeMillis()
    ): ProductivitySummary {
        val week = weekStats(items, today, nowMillis)
        // The day before this Monday is last Sunday → weekStats scores the
        // whole previous Monday..Sunday week (nothing within it is "future").
        val prev = weekStats(items, mondayOf(today).minusDays(1), nowMillis)
        val monday = mondayOf(today)
        val counts = behaviorCounts(items, monday, monday.plusDays(6), today, nowMillis)
        val first = firstActivityDay(items) ?: today
        val delta = if (week.score != null && prev.score != null && prev.score!! > 0) {
            ((week.score!! - prev.score!!) * 100f / prev.score!!).roundToInt()
        } else {
            null
        }
        return ProductivitySummary(
            today = today,
            currentStreak = week.streak,
            longestStreak = longestStreak(items, first, today),
            weekScore = week.score,
            previousWeekScore = prev.score,
            scoreDeltaPercent = delta,
            completed = counts.completed,
            attempted = counts.attempted,
            incomplete = counts.incomplete,
            productiveMinutes = counts.productiveMinutes,
            earnedPoints = earnedPoints(items, monday, today)
        )
    }
}
