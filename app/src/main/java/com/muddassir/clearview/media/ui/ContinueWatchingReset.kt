package com.muddassir.clearview.media.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirmation for emptying Continue Watching.
 *
 * Shared by the Media feed's ⋮ menu and the player's ⋮ menu (while watching):
 * both are one careless tap away from throwing away every resume position, so
 * both ask first.
 */
@Composable
internal fun ResetContinueWatchingDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset Continue Watching?") },
        text = {
            Text(
                "This clears the resume position of every unfinished video, so " +
                    "Continue Watching starts over. Videos you finished keep their " +
                    "watched progress."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reset") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
