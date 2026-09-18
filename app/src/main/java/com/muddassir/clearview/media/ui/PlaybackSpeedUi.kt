package com.muddassir.clearview.media.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The ONE playback-speed control, shared by the video player and the offline
 * audio player.
 *
 * A single drag bar rather than a list of presets: eleven menu rows to pick
 * between 0.75× and 2.5× is a menu to read before every change, and the value
 * people actually want ("a bit faster than 1.5×") is rarely on the list. The
 * bar reaches every 0.05× step between [MIN_CUSTOM_SPEED] and
 * [MAX_CUSTOM_SPEED], shows the value while you drag it, and commits once —
 * [onSelect] is the same real player call (`setPlaybackRate` through the
 * IFrame API, `PlaybackParams` for the native players) the presets used.
 *
 * @param speed the rate the player is running at right now, so the bar opens
 *   where the reader already is rather than at 1×.
 * @param onSelect commits a rate. Called once, on Set.
 * @param onDismiss closes without changing anything (Cancel, back, tap-out).
 */
@Composable
internal fun PlaybackSpeedDialog(
    speed: Double,
    onSelect: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    // The draft is a local copy so dragging the bar does not re-rate the
    // running player sixty times a second — the rate changes when the reader
    // says so. Keyed on `speed` so reopening the dialog starts from the rate
    // the player is actually at.
    var draft by remember(speed) { mutableStateOf(speed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatSpeedLabel(draft),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = draft.toFloat(),
                    onValueChange = { draft = snapSpeed(it.toDouble()) },
                    valueRange = MIN_CUSTOM_SPEED.toFloat()..MAX_CUSTOM_SPEED.toFloat(),
                    // One stop per 0.05× step, so the same value can be
                    // reached again by hand — a free-floating bar makes "1.5×"
                    // something you can only ever approximate.
                    steps = speedSliderSteps(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatSpeedLabel(MIN_CUSTOM_SPEED),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatSpeedLabel(MAX_CUSTOM_SPEED),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelect(snapSpeed(draft))
                    onDismiss()
                }
            ) { Text("Set") }
        },
        dismissButton = {
            TextButton(
                onClick = { onSelect(1.0) }
            ) {
                Text(
                    text = "Normal",
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    )
}

/**
 * [value] snapped onto the 0.05× grid and clamped into the allowed range.
 *
 * Every speed that leaves this dialog passes through here, so no caller has to
 * remember that the raw slider is continuous (1.3499999999999999), that 0.25 is
 * the floor, or that 5 is the ceiling.
 */
internal fun snapSpeed(value: Double): Double {
    val clamped = value.coerceIn(MIN_CUSTOM_SPEED, MAX_CUSTOM_SPEED)
    val stepped = Math.round(clamped / CUSTOM_SPEED_STEP) * CUSTOM_SPEED_STEP
    return Math.round(stepped * 100.0) / 100.0
}

/** Stop count between the two ends (the ends are not stops themselves). */
internal fun speedSliderSteps(): Int =
    (((MAX_CUSTOM_SPEED - MIN_CUSTOM_SPEED) / CUSTOM_SPEED_STEP).toInt() - 1)
        .coerceAtLeast(0)

/** "1.5×" — the same rate as [formatRate], with the multiplication sign. */
internal fun formatSpeedLabel(rate: Double): String =
    formatRate(rate).trimEnd('x') + "\u00D7"
