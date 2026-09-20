package com.muddassir.clearview.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.todo.data.TodoStats
import com.muddassir.clearview.todo.model.TodoItem
import java.time.LocalDate
import java.time.YearMonth

/**
 * The productivity heatmap, as one CONTINUOUS GitHub-style contribution graph:
 * one square per day, one COLUMN per week (Monday at the top of the column), one
 * ROW per weekday, running left to right across a whole rolling year.
 *
 * The structure matters more than the shading: a year of weeks in one grid is
 * what makes consistency — or the lack of it — visible at a glance, whereas a
 * grid per month shows twelve small pictures that each have to be compared by
 * eye. Months are therefore nothing but labels above the week columns where they
 * begin; they never get a block of their own.
 *
 * The graph SCROLLS sideways at a readable square size rather than shrinking a
 * year into the card: squeezed to ~5dp a square stops being data and becomes
 * texture, and the weekday letters stop being readable at all. The squares are
 * therefore a fixed comfortable size, the weekday letters keep their own column
 * outside the scroll (so the rows stay named while the weeks move), and the grid
 * opens at the reader's own week — the part they actually came to see — with the
 * rest of the year one swipe back.
 */

/** Days per column: Monday..Sunday, always — the same Monday-first week as the
 * calendar and the weekly strip above. */
internal const val HEATMAP_ROWS = 7

/**
 * Columns in the graph: 52 whole weeks plus the week in progress. A rolling year
 * (≈53 weeks ≈ 12.2 months) rather than a trimmed one, so the leftmost column
 * can be a partial month — which is exactly how a contribution graph reads.
 */
internal const val HEATMAP_COLUMNS = 53

/** Never start a month label closer than this to the previous one. */
private const val HEATMAP_LABEL_MIN_COLUMNS = 4

/** Days in a week column — the grid's own clock. */
private const val DAYS_PER_WEEK = 7

/**
 * One day square. Big enough to read as a day and to hit with a fingertip —
 * the year is wider than the card because of it, which is the trade the graph
 * makes on purpose.
 */
internal val HEATMAP_CELL = 12.dp

/** Space between two squares, and between two rows of squares. */
internal val HEATMAP_GAP = 2.5.dp

/** Height of the month-label strip above the graph. */
private val HEATMAP_LABEL_ROW = 14.dp

/**
 * Extra space in front of the column that opens a month. Enough to read the
 * months as months at a glance — and deliberately much smaller than a column, so
 * the year still reads as ONE grid rather than twelve little blocks.
 */
private val HEATMAP_MONTH_GAP = 4.dp

/** Space between the weekday gutter and the scrolling weeks. */
private val HEATMAP_GUTTER_GAP = 3.dp

/**
 * The space that precedes column [index]: a little air at a month boundary, and
 * nothing anywhere else. The first column never gets it (there is no month
 * before it to be separated from), so the grid always starts flush after the
 * weekday gutter.
 *
 * Both the label strip and every square row build their columns through this,
 * which is what keeps a month name exactly above the weeks it names.
 */
internal fun heatmapMonthLead(index: Int, monthLabels: Map<Int, String>): Dp =
    if (index > 0 && monthLabels.containsKey(index)) HEATMAP_MONTH_GAP else 0.dp

/**
 * Monday-first weekday initials for the graph's left-hand gutter — the same
 * Monday-first week as the calendar, the weekly strip and the day toggles.
 * Written out here rather than shared: each of those surfaces is a different
 * component with its own copy of the week it renders.
 */
private val WEEKDAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

/** Weekday letters and month names: small, but genuinely readable at this size. */
private val HEATMAP_LETTER_FONT = 8.sp
private val HEATMAP_MONTH_FONT = 9.sp

/**
 * Which stretch of the year the graph shows. The reader picks it from the card's
 * year menu; the grid itself is identical for both.
 */
internal sealed interface HeatmapRange {
    /**
     * The rolling past year: 52 whole weeks plus the week in progress. The
     * default, because "how am I doing lately" is the question the graph is
     * usually opened with — and it never runs out mid-year.
     */
    object PastYear : HeatmapRange {
        override fun contains(date: LocalDate): Boolean = true
    }

    /** One calendar year, January 1st to December 31st. */
    data class Year(val year: Int) : HeatmapRange {
        override fun contains(date: LocalDate): Boolean = date.year == year
    }

    /**
     * Whether [date] is inside the window the reader asked for. A week column
     * can stick out of a calendar year at either end, and those days are not
     * scored — so they must not be labelled as if they were either.
     */
    fun contains(date: LocalDate): Boolean
}

/** The Monday of [date]'s week (the day a contribution-graph column starts on). */
private fun mondayOf(date: LocalDate): LocalDate =
    date.minusDays((date.dayOfWeek.value - 1).toLong())

/**
 * The Monday that opens each week column, oldest first.
 *
 * For a rolling year the last column is the week containing [today] — the graph
 * grows a column every Monday and never shifts the reader's place mid-week. For
 * a calendar year the columns span exactly that year: the first week is the one
 * January 1st falls in and the last is the one December 31st falls in (so the
 * edges can hold a few days of the neighbouring years, which the grid leaves
 * unshaded because they are outside the window being scored).
 */
internal fun heatmapColumns(range: HeatmapRange, today: LocalDate): List<LocalDate> =
    when (range) {
        is HeatmapRange.PastYear -> {
            val thisMonday = mondayOf(today)
            List(HEATMAP_COLUMNS) { thisMonday.minusWeeks((HEATMAP_COLUMNS - 1 - it).toLong()) }
        }

        is HeatmapRange.Year -> {
            val first = mondayOf(LocalDate.of(range.year, 1, 1))
            val last = mondayOf(LocalDate.of(range.year, 12, 31))
            val count = ((last.toEpochDay() - first.toEpochDay()) / DAYS_PER_WEEK).toInt() + 1
            List(count) { first.plusWeeks(it.toLong()) }
        }
    }

/**
 * Every day the graph scores, column-major — the exact order the grid lays its
 * squares out, so a level list and a date list can never be indexed apart.
 */
internal fun heatmapDays(columns: List<LocalDate>): List<LocalDate> =
    columns.flatMap { monday -> List(HEATMAP_ROWS) { monday.plusDays(it.toLong()) } }

/**
 * The windows the card's menu offers: the rolling past year first, then every
 * calendar year there is anything to show for, newest first.
 *
 * Rebuilt from the data every time the screen's clock ticks, so it keeps up on
 * its own: a new year appears in the menu the moment it starts (the current year
 * is always offered, even before anything has been completed in it), and a year
 * that has data behind it can never quietly drop out of the list.
 *
 * [selected] is always kept in the list, so a reader who is looking at a year
 * they have not completed anything in (yet, or any more) cannot end up with a
 * menu that does not contain their own selection.
 */
internal fun heatmapRangeOptions(
    items: List<TodoItem>,
    today: LocalDate,
    selected: HeatmapRange
): List<HeatmapRange> {
    val years = buildSet {
        add(today.year)
        (selected as? HeatmapRange.Year)?.let { add(it.year) }
        items.forEach { item ->
            item.completions.keys.forEach { epochDay -> add(LocalDate.ofEpochDay(epochDay).year) }
        }
    }.filter { it <= today.year }.sortedDescending()
    return listOf(HeatmapRange.PastYear) + years.map { HeatmapRange.Year(it) }
}

/**
 * The column the graph opens on, so the reader lands on what they came to see
 * instead of on a stretch of empty future weeks.
 *
 * A rolling year and the current calendar year both end at the week in progress
 * — the freshest data is on the right, and the grid is scrolled there. A year
 * that has finished opens at its beginning, because its story starts in
 * January.
 */
internal fun heatmapAnchorColumn(
    columns: List<LocalDate>,
    range: HeatmapRange,
    today: LocalDate
): Int = when (range) {
    is HeatmapRange.PastYear -> columns.lastIndex
    is HeatmapRange.Year ->
        if (range.year < today.year) 0
        else columns.indexOfLast { !it.isAfter(today) }.coerceAtLeast(0)
}

/**
 * The month abbreviations to draw, keyed by the column each one sits above.
 *
 * A column is labelled when it CONTAINS the 1st of a month — the week the
 * month begins in, which is where its label belongs. That is what makes the
 * grid continuous: the labels mark month boundaries wherever they land, and a
 * leading column that started mid-month simply has no boundary to name. A
 * label that would crowd the one before it is dropped rather than printed
 * overlapping. Abbreviations carry no year — over a rolling year the year would
 * repeat on every January and cost more width than the squares can spare.
 */
internal fun heatmapMonthLabels(
    columns: List<LocalDate>,
    range: HeatmapRange,
    label: (LocalDate) -> String
): Map<Int, String> {
    val labels = LinkedHashMap<Int, String>()
    var lastIndex = -HEATMAP_LABEL_MIN_COLUMNS
    columns.forEachIndexed { index, monday ->
        val firstOfMonth = (0 until HEATMAP_ROWS)
            .map { monday.plusDays(it.toLong()) }
            .firstOrNull { it.dayOfMonth == 1 && range.contains(it) } ?: return@forEachIndexed
        if (index - lastIndex < HEATMAP_LABEL_MIN_COLUMNS) return@forEachIndexed
        labels[index] = label(firstOfMonth)
        lastIndex = index
    }
    return labels
}

/**
 * The graph itself: the month-label strip, the seven weekday rows, and the
 * squares — the labels and the squares inside one horizontal scroll so a month
 * name can never drift away from the weeks it names, and the weekday letters
 * outside it so the rows stay named while the year moves under them.
 *
 * Days still ahead are left empty — a schedule is not progress.
 */
@Composable
internal fun HeatmapGrid(
    columns: List<LocalDate>,
    byDate: Map<LocalDate, TodoStats.DayProductivity>,
    today: LocalDate,
    monthLabels: Map<Int, String>,
    levelColors: List<Color>,
    onSelect: (TodoStats.DayProductivity) -> Unit,
    cellDescription: (LocalDate, TodoStats.DayProductivity) -> String,
    /** The column to open on, from [heatmapAnchorColumn]. */
    anchorColumn: Int,
    modifier: Modifier = Modifier
) {
    val weekdayColor = MaterialTheme.colorScheme.onSurfaceVariant
    val monthColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Open with the anchor column at the RIGHT edge, so the weeks up to it
        // are the ones on screen. The value is clamped by the scroll container,
        // which also covers the two ends: a value past the end settles on the
        // end, and a negative one settles on the start.
        val initialScroll = with(LocalDensity.current) {
            val pitch = HEATMAP_CELL + HEATMAP_GAP
            (pitch * (anchorColumn + 1) - maxWidth).roundToPx().coerceAtLeast(0)
        }
        val scroll = rememberScrollState(initialScroll)
        Row(modifier = Modifier.fillMaxWidth()) {
        // ── Weekday gutter (fixed, outside the scroll) ──
        Column(
            modifier = Modifier.padding(top = HEATMAP_LABEL_ROW + HEATMAP_GAP),
            verticalArrangement = Arrangement.spacedBy(HEATMAP_GAP)
        ) {
            repeat(HEATMAP_ROWS) { row ->
                Box(
                    modifier = Modifier.size(HEATMAP_CELL),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = WEEKDAY_LETTERS[row],
                        style = TextStyle(
                            fontSize = HEATMAP_LETTER_FONT,
                            fontWeight = FontWeight.SemiBold,
                            color = weekdayColor
                        ),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentHeight(unbounded = true)
                    )
                }
            }
        }
        Spacer(Modifier.width(HEATMAP_GUTTER_GAP))
        // ── One continuous year of weeks, scrolling sideways ──
        Column(modifier = Modifier.horizontalScroll(scroll)) {
            // Month labels: one slot per column, so a label can only ever sit
            // above the week it names. The text overflows its narrow slot
            // instead of being truncated to "S…".
            Row(
                modifier = Modifier.height(HEATMAP_LABEL_ROW),
                horizontalArrangement = Arrangement.spacedBy(HEATMAP_GAP)
            ) {
                columns.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .padding(start = heatmapMonthLead(index, monthLabels))
                            .width(HEATMAP_CELL),
                        contentAlignment = Alignment.TopStart
                    ) {
                        monthLabels[index]?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = HEATMAP_MONTH_FONT,
                                color = monthColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.wrapContentWidth(
                                    unbounded = true,
                                    align = Alignment.Start
                                )
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(HEATMAP_GAP))
            // ── One row per weekday, Monday first ──
            repeat(HEATMAP_ROWS) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(HEATMAP_GAP)) {
                    columns.forEachIndexed { index, monday ->
                        val date = monday.plusDays(row.toLong())
                        val day = byDate[date]
                        val ahead = date.isAfter(today)
                        val desc = day?.takeIf { !ahead }?.let { cellDescription(date, it) }
                        Box(
                            modifier = Modifier
                                // The month's own lead-in space lives OUTSIDE the
                                // square, so the squares stay identical and the
                                // months are merely a little further apart.
                                .padding(start = heatmapMonthLead(index, monthLabels))
                                .size(HEATMAP_CELL)
                                .clip(RoundedCornerShape(HEATMAP_CELL * 0.24f))
                                .background(
                                    if (ahead || day == null) Color.Transparent
                                    else levelColors[day.level.coerceIn(0, levelColors.lastIndex)]
                                )
                                .then(
                                    if (desc != null) {
                                        Modifier.semantics { contentDescription = desc }
                                    } else Modifier
                                )
                                .then(
                                    if (day != null && !ahead) {
                                        Modifier.clickable { onSelect(day) }
                                    } else Modifier
                                )
                        )
                    }
                }
                if (row < HEATMAP_ROWS - 1) Spacer(Modifier.height(HEATMAP_GAP))
            }
        }
        }
    }
}
