package com.muddassir.clearview.media.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.media.model.FeedDateFilter
import com.muddassir.clearview.media.model.FeedFilter
import com.muddassir.clearview.media.model.FeedPlatformFilter
import com.muddassir.clearview.media.model.FeedSortOrder
import com.muddassir.clearview.media.model.FeedSourceFilter
import com.muddassir.clearview.media.model.FeedWatchStatus
import com.muddassir.clearview.media.model.PlaylistTypeFilter
import com.muddassir.clearview.media.model.datePickerMillisToLocalStart
import com.muddassir.clearview.media.util.DEVICE_SOURCE_LABEL
import com.muddassir.clearview.media.util.contentOptionsFor
import com.muddassir.clearview.media.util.filterSummary
import com.muddassir.clearview.media.util.normalizedForPlatform

/**
 * Bottom sheet with the All Feed filter controls: Date presets (+ custom range
 * via date pickers), Content type, Sort, and Reset / Apply. Draft state is
 * only committed on Apply.
 *
 * Every section is one row showing what it is set to until it is tapped, where
 * the options unfold beneath it — a sheet that lists every option of every
 * section at once is taller than the screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheet(
    filter: FeedFilter,
    /** Inside a user playlist only the Source (By URL / From device / By RSS) and Type
     *  (Video / Audio) filters apply — playlists keep their hand-picked order,
     *  so the sheet shows just those two sections there. */
    isPlaylistContext: Boolean = false,
    onApply: (FeedFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(filter) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    // One section open at a time. The sheet used to list every option of every
    // section at once, which made it scroll well past the screen; now each
    // section is a single row showing what it's set to until it's tapped.
    var openSection by remember { mutableStateOf<FilterSection?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Title + what the filter is actually set to, so the sheet reads as
            // one line of state instead of a wall of options.
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Filter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = draft.filterSummary(
                            playlistContext = isPlaylistContext,
                            deviceSourceLabel = DEVICE_SOURCE_LABEL
                        ).ifEmpty {
                            if (isPlaylistContext) "Everything in this playlist"
                            else "Last 3 days · Unwatched"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (draft.isActive) {
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { draft = FeedFilter() }) { Text("Reset") }
                }
            }
            Spacer(Modifier.height(6.dp))

            if (!isPlaylistContext) {
                FilterRow(
                    title = "Date",
                    value = draft.date.label,
                    expanded = openSection == FilterSection.DATE,
                    onToggle = {
                        openSection =
                            if (openSection == FilterSection.DATE) null else FilterSection.DATE
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FeedDateFilter.entries.forEach { option ->
                            FilterOptionPill(
                                label = option.label,
                                selected = draft.date == option,
                                onClick = {
                                    draft = draft.copy(date = option)
                                    // Custom keeps the section open so its range
                                    // buttons are right there; a preset closes it.
                                    openSection = if (option == FeedDateFilter.CUSTOM) {
                                        FilterSection.DATE
                                    } else {
                                        null
                                    }
                                }
                            )
                        }
                    }
                    if (draft.date == FeedDateFilter.CUSTOM) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showStartPicker = true },
                                modifier = Modifier.weight(1f)
                            ) { Text(formatShortDate(draft.customStartEpochMillis, "Start")) }
                            OutlinedButton(
                                onClick = { showEndPicker = true },
                                modifier = Modifier.weight(1f)
                            ) { Text(formatShortDate(draft.customEndEpochMillis, "End")) }
                        }
                    }
                }

                FilterRow(
                    title = "Platform",
                    value = draft.platform.label,
                    expanded = openSection == FilterSection.PLATFORM,
                    onToggle = {
                        openSection =
                            if (openSection == FilterSection.PLATFORM) null else FilterSection.PLATFORM
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FeedPlatformFilter.entries.forEach { option ->
                            FilterOptionPill(
                                label = option.label,
                                selected = draft.platform == option,
                                onClick = {
                                    // A platform only offers its own content
                                    // types: dropping Reels/Shorts when they
                                    // don't apply keeps the feed honest.
                                    draft = draft.copy(platform = option).normalizedForPlatform()
                                    openSection = null
                                }
                            )
                        }
                    }
                }

                FilterRow(
                    title = "Content",
                    value = draft.content.label,
                    expanded = openSection == FilterSection.CONTENT,
                    onToggle = {
                        openSection =
                            if (openSection == FilterSection.CONTENT) null else FilterSection.CONTENT
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        contentOptionsFor(draft.platform).forEach { option ->
                            FilterOptionPill(
                                label = option.label,
                                selected = draft.content == option,
                                onClick = {
                                    draft = draft.copy(content = option)
                                    openSection = null
                                }
                            )
                        }
                    }
                }
            }

            // Type — only meaningful inside a user playlist, which can hold
            // both YouTube videos and audio imported from the device.
            if (isPlaylistContext) {
                FilterRow(
                    title = "Type",
                    value = draft.playlistType.label,
                    expanded = openSection == FilterSection.TYPE,
                    onToggle = {
                        openSection =
                            if (openSection == FilterSection.TYPE) null else FilterSection.TYPE
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlaylistTypeFilter.entries.forEach { option ->
                            FilterOptionPill(
                                label = option.label,
                                selected = draft.playlistType == option,
                                onClick = {
                                    draft = draft.copy(playlistType = option)
                                    openSection = null
                                }
                            )
                        }
                    }
                }
            }

            // Source — always available: it's the only feed filter that also
            // applies inside a user playlist (playlists keep their own order).
            FilterRow(
                title = "Source",
                value = if (isPlaylistContext && draft.source == FeedSourceFilter.SYSTEM) {
                    DEVICE_SOURCE_LABEL
                } else {
                    draft.source.label
                },
                expanded = openSection == FilterSection.SOURCE,
                onToggle = {
                    openSection =
                        if (openSection == FilterSection.SOURCE) null else FilterSection.SOURCE
                }
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // "By RSS" is a playlist-only option — the All Feed already
                    // covers channel-feed videos with "From channels". It stays
                    // visible when already selected (a filter picked inside a
                    // playlist can outlive the playlist), so an active filter is
                    // never left with a hidden chip.
                    val sourceOptions = FeedSourceFilter.entries.filterNot {
                        it == FeedSourceFilter.BY_RSS && !isPlaylistContext &&
                            draft.source != FeedSourceFilter.BY_RSS
                    }
                    sourceOptions.forEach { option ->
                        FilterOptionPill(
                            label = if (option == FeedSourceFilter.SYSTEM && isPlaylistContext) {
                                DEVICE_SOURCE_LABEL
                            } else {
                                option.label
                            },
                            selected = draft.source == option,
                            onClick = {
                                draft = draft.copy(
                                    source = option,
                                    // A source filter means "everything from here"
                                    // — the default Unwatched status would
                                    // otherwise hide already-watched videos and
                                    // make the filter look like it shows the
                                    // wrong videos.
                                    watchStatus = if (option == FeedSourceFilter.ALL) {
                                        draft.watchStatus
                                    } else {
                                        FeedWatchStatus.ALL
                                    }
                                )
                                openSection = null
                            }
                        )
                    }
                }
            }

            if (!isPlaylistContext) {
                FilterRow(
                    title = "Watch status",
                    value = draft.watchStatus.label,
                    expanded = openSection == FilterSection.WATCH,
                    onToggle = {
                        openSection =
                            if (openSection == FilterSection.WATCH) null else FilterSection.WATCH
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FeedWatchStatus.entries.forEach { option ->
                            FilterOptionPill(
                                label = option.label,
                                selected = draft.watchStatus == option,
                                onClick = {
                                    draft = draft.copy(watchStatus = option)
                                    openSection = null
                                }
                            )
                        }
                    }
                }

                FilterRow(
                    title = "Sort by",
                    value = draft.sort.label,
                    expanded = openSection == FilterSection.SORT,
                    onToggle = {
                        openSection =
                            if (openSection == FilterSection.SORT) null else FilterSection.SORT
                    }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FeedSortOrder.entries.forEach { option ->
                            FilterOptionPill(
                                label = option.label,
                                selected = draft.sort == option,
                                onClick = {
                                    draft = draft.copy(sort = option)
                                    openSection = null
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onApply(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Apply", style = MaterialTheme.typography.titleSmall) }
        }
    }

    // Custom range: start date picker.
    if (showStartPicker) {
        val startState = rememberDatePickerState(
            initialSelectedDateMillis = draft.customStartEpochMillis
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    startState.selectedDateMillis?.let { picked ->
                        draft = draft.copy(
                            customStartEpochMillis = datePickerMillisToLocalStart(picked)
                        )
                    }
                    showStartPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showStartPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = startState)
        }
    }

    // Custom range: end date picker.
    if (showEndPicker) {
        val endState = rememberDatePickerState(
            initialSelectedDateMillis = draft.customEndEpochMillis
        )
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    endState.selectedDateMillis?.let { picked ->
                        draft = draft.copy(
                            customEndEpochMillis = datePickerMillisToLocalStart(picked)
                        )
                    }
                    showEndPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEndPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = endState)
        }
    }
}

/** The filter sheet's collapsible sections — one is open at a time. */
private enum class FilterSection { DATE, PLATFORM, CONTENT, TYPE, SOURCE, WATCH, SORT }

/**
 * One collapsed filter row: what the section is set to on the right, its options
 * only once the row is tapped. Keeps the sheet to a handful of lines instead of
 * a radio list per section.
 */
@Composable
private fun FilterRow(
    title: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // One fraction drives the arrow, the visibility below runs the height: the
    // section unfolds out of its row instead of jumping to the new size.
    val openFraction by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "filterRowChevron"
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (expanded) scheme.primary else scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (expanded) scheme.primary else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                // One glyph only — the rotation carries the state, so the arrow
                // turns with the section rather than being swapped mid-animation.
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = if (expanded) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(openFraction * 180f)
            )
        }
        // The options open in their own block under the row they belong to, with
        // room around them so they never read as the next row's contents. They
        // grow down from the row (the height leads, the fade catches up).
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(durationMillis = 120))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, end = 2.dp)
                    .padding(top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant.copy(alpha = 0.4f))
        )
    }
}

/**
 * A single filter option as a light pill — a filled, borderless shape that reads
 * as a selection instead of a row of boxes.
 */
@Composable
private fun FilterOptionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container =
        if (selected) scheme.secondaryContainer else scheme.surfaceVariant.copy(alpha = 0.4f)
    val contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor
        )
    }
}

/** "12 Jan 2026", or the [fallback] placeholder when no date is picked yet. */
private fun formatShortDate(millis: Long?, fallback: String): String {
    if (millis == null) return fallback
    return java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}
