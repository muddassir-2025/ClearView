package com.muddassir.clearview.todo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R

/**
 * The snooze picker: **10 minutes, 30 minutes, 1 hour, or a CUSTOM number of
 * minutes** (1 minute up to [MAX_SNOOZE_MINUTES]).
 *
 * Shared by the notification's Snooze action ([TodoSnoozeActivity], a
 * dialog-styled activity) and by the full-screen alarm overlay, so the two
 * entry points offer the exact same options (§3, §4) — a lock-screen snooze is
 * never a second-class flow that sends the reader into the app.
 */
internal const val MAX_SNOOZE_MINUTES = 1_440L // 24 hours

@Composable
internal fun SnoozePickerContent(
    onSnooze: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    val customValue = customText.toLongOrNull()
    val customValid = customValue != null && customValue in 1..MAX_SNOOZE_MINUTES
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.todo_snooze_for),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            listOf(
                10L to R.string.todo_snooze_10m,
                30L to R.string.todo_snooze_30m,
                60L to R.string.todo_snooze_1h
            ).forEach { (minutes, label) ->
                OutlinedButton(
                    onClick = { onSnooze(minutes) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(label), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
            // Custom: any number of minutes within the allowed range.
            OutlinedButton(
                onClick = { customOpen = !customOpen },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.todo_snooze_custom), modifier = Modifier.weight(1f))
            }
            if (customOpen) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { input ->
                            // Digits only; the value is range-checked before it
                            // can be accepted so a snooze can never exceed the
                            // supported range.
                            customText = input.filter { it.isDigit() }.take(5)
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.todo_snooze_custom_minutes)) },
                        supportingText = {
                            Text(stringResource(R.string.todo_snooze_custom_range))
                        },
                        isError = customText.isNotEmpty() && !customValid,
                        singleLine = true
                    )
                    Spacer(Modifier.width(10.dp))
                    TextButton(
                        onClick = { customValue?.let { if (it in 1..MAX_SNOOZE_MINUTES) onSnooze(it) } },
                        enabled = customValid
                    ) {
                        Text(stringResource(R.string.todo_ok))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.todo_cancel))
            }
        }
    }
}
