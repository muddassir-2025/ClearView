package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.muddassir.clearview.R

/**
 * What is about to be deleted, and who it is being deleted for (§5, §9, §17).
 *
 * ## Why nothing is deleted on the tap any more
 *
 * Every delete used to happen immediately: an icon in a selection bar, a row in
 * the media menu, and the thing was gone. Two different deletions live behind one
 * gesture, they mean opposite things, and one of them cannot be undone — so
 * whichever icon happened to be nearest a thumb decided it. That is not a choice
 * a gesture should be making.
 *
 * ## The two answers, and the third for an account that may publish
 *
 *  * **Delete for me** — this device. The post goes out of this reader's view and
 *    the cached copies behind it go too. Offered to EVERYONE, because it is the
 *    only deletion a reader has any business performing and its whole effect is
 *    their own.
 *  * **Delete for everyone** — the server, for every reader of the channel.
 *    Offered only to an account that may publish here, and enforced by the server
 *    as well: a hidden button is not an authorization.
 *  * **Cancel** — first in reading order, so the way out is the one the eye lands
 *    on before the destructive pair below it.
 *
 * The message is passed in rather than composed here because the callers count
 * different things — one update, four updates, six files — and a dialog that says
 * "this message" over a selection of four is a dialog nobody reads.
 */
@Composable
internal fun WaDeleteDialog(
    title: String,
    message: String,
    /** True when this account may remove it on the server too. */
    canDeleteForEveryone: Boolean,
    onDismiss: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Wa.Bar)
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Text(
                text = title,
                color = Wa.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = Wa.TextDim,
                fontSize = 14.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(16.dp))

            // Cancel first: the safe answer sits where the reading starts, and
            // the red one is the last thing under the thumb rather than the first.
            Choice(
                label = stringResource(R.string.goodpost_cancel),
                onClick = onDismiss
            )
            Spacer(Modifier.height(6.dp))
            Choice(
                label = stringResource(R.string.goodpost_delete_for_me),
                destructive = !canDeleteForEveryone,
                onClick = onDeleteForMe
            )
            if (canDeleteForEveryone) {
                Spacer(Modifier.height(6.dp))
                Choice(
                    label = stringResource(R.string.goodpost_delete_for_everyone),
                    destructive = true,
                    onClick = onDeleteForEveryone
                )
            }
        }
    }
}

/** One answer, on its own row so a tap cannot land between two of them. */
@Composable
private fun Choice(
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (destructive) Wa.Danger else Wa.Text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp)
    )
}
