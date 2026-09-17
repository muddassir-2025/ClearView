package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GOODPOST_REPORT_REASONS
import com.muddassir.clearview.goodpost.data.GoodPostReportTargetRef

/**
 * Filing a report (§18).
 *
 * The reasons are the server's own set, shown with readable labels, so the
 * client cannot invent a reason the API refuses — and the wire value, not the
 * label, is what travels. Details are optional, because "why" is answered by the
 * reason, and a required free-text box is how reports become unusable.
 *
 * The dialog names what is being reported. A report is an accusation, and
 * letting someone send one they cannot see the target of is how the wrong thing
 * gets reported.
 */
@Composable
internal fun ReportDialog(
    target: GoodPostReportTargetRef,
    busy: Boolean,
    errorCode: String?,
    onSend: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }

    WaDialog(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.goodpost_report_title),
            color = Wa.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(6.dp))

        Text(text = target.label, color = Wa.TextDim, fontSize = 14.sp)

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.goodpost_report_reason),
            color = Wa.TextDim,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(8.dp))

        // Reasons wrap onto more than one line, so they scroll horizontally
        // rather than being clipped into unreachable chips.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GOODPOST_REPORT_REASONS.forEach { option ->
                WaFilterPill(
                    label = option.label,
                    selected = reason == option.wire,
                    onClick = { reason = option.wire }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Wa.List)
                    .border(1.dp, Wa.Divider, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp)
            ) {
                TextField(
                    value = details,
                    onValueChange = { details = it },
                    enabled = !busy,
                    minLines = 2,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.goodpost_report_details),
                            color = Wa.TextDim,
                            fontSize = 15.sp
                        )
                    },
                    textStyle = TextStyle(fontSize = 15.sp, color = Wa.Text),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = Wa.Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (errorCode != null) {
                Spacer(Modifier.height(12.dp))
                WaErrorNotice(errorCode)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            WaTextAction(
                text = stringResource(R.string.goodpost_cancel),
                enabled = !busy,
                onClick = onDismiss
            )
            Spacer(Modifier.width(8.dp))
            WaTextAction(
                text = stringResource(R.string.goodpost_report_send),
                // Nothing to send until a reason is chosen: a report with no
                // reason is a report a moderator cannot act on.
                enabled = !busy && reason != null,
                onClick = {
                    reason?.let { onSend(it, details.takeIf { text -> text.isNotBlank() }) }
                }
            )
        }
    }
}

/**
 * The confirmation after the server accepted a report.
 *
 * Its own small dialog rather than a snackbar, because the outcome is the whole
 * point of the action: §18's queue is only trustworthy if a reporter knows their
 * report arrived, and a message that can be missed does not establish that.
 */
@Composable
internal fun ReportSentDialog(onDismiss: () -> Unit) {
    WaDialog(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.goodpost_report_sent),
            color = Wa.Text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.goodpost_report_sent_note),
            color = Wa.TextDim,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            WaTextAction(
                text = stringResource(R.string.goodpost_save),
                onClick = onDismiss
            )
        }
    }
}
