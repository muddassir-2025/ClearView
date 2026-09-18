package com.muddassir.clearview.media.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.media.playback.SleepTimer

/**
 * The sleep timer's one UI, shared by both players.
 *
 * One implementation rather than one per screen, for the same reason the
 * playback engine is one: the control means the same thing in both places, and
 * two copies is how the video player ends up offering an option the offline
 * player cannot honour.
 */

/**
 * What the pill reads: the countdown, "End" for end-of-track, or "Off".
 *
 * Deliberately not the raw remaining milliseconds — an end-of-track timer has
 * no number to show, and a timer that read "0:00" would look like one about to
 * fire rather than one set to wait.
 */
internal fun sleepTimerLabel(remainingMs: Long, endOfTrack: Boolean): String =
    if (endOfTrack) "End" else SleepTimer.labelFor(remainingMs)

/** True when any kind of timer is set (drives the pill's emphasis). */
internal fun sleepTimerArmed(remainingMs: Long, endOfTrack: Boolean): Boolean =
    endOfTrack || remainingMs > 0L

/** "5 minutes", "1 hour" — the dialog's rows. */
private fun minutesLabel(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} hour${if (minutes >= 120) "s" else ""}"
    else "$minutes minutes"

/**
 * The timer picker.
 *
 * A list rather than a slider or a number field: this is set in the dark, on
 * the way to sleep, and choosing one of six rows with a thumb is a different
 * thing from aiming a thumb at a specific pixel.
 *
 * The two kinds of timer live in one list on purpose. "Off", the countdowns and
 * "End of this track" are all answers to the same question, and exactly one
 * check mark shows which answer is in force — an end-of-track switch beside a
 * list of minutes would be two controls for one setting.
 *
 * [chosenMinutes] is the option that was PICKED, held alongside the deadline
 * rather than re-derived from it. Deriving it would mean rounding the remaining
 * time back up to a whole minute, and a check mark that drifts off its own row
 * two minutes into a countdown reads as the timer having reset itself.
 */
@Composable
internal fun SleepTimerDialog(
    chosenMinutes: Int?,
    endOfTrack: Boolean,
    onCountdown: (Int?) -> Unit,
    onEndOfTrack: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Sleep timer") },
        text = {
            Column {
                SleepTimerRow(
                    label = "Off",
                    selected = !endOfTrack && chosenMinutes == null,
                    onClick = { onCountdown(null) }
                )
                Spacer(Modifier.height(4.dp))
                SleepTimer.MINUTES.forEach { minutes ->
                    SleepTimerRow(
                        label = minutesLabel(minutes),
                        selected = !endOfTrack && chosenMinutes == minutes,
                        onClick = { onCountdown(minutes) }
                    )
                }
                Spacer(Modifier.height(4.dp))
                SleepTimerRow(
                    label = "End of this track",
                    selected = endOfTrack,
                    onClick = onEndOfTrack
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun SleepTimerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * The timer pill, matching the playback-speed pill beside it in the offline
 * player's bottom row.
 *
 * Tinted while a timer is armed and plain while it is not, so the state of a
 * control whose whole job is to act later — while nobody is looking — is legible
 * at a glance when they look back.
 */
@Composable
internal fun SleepTimerPill(
    remainingMs: Long,
    endOfTrack: Boolean,
    onClick: () -> Unit
) {
    val armed = sleepTimerArmed(remainingMs, endOfTrack)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (armed) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Icon(
                Icons.Filled.Bedtime,
                contentDescription = "Sleep timer",
                tint = if (armed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = sleepTimerLabel(remainingMs, endOfTrack),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
