package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider

/**
 * The platform's selection menu, refused (§7).
 *
 * Highlighting text inside the composer used to raise Android's own menu —
 * **Cut / Copy / Paste / Select all / Read aloud** — as a light bubble over the
 * line being edited, in an app that is otherwise dark. §7 asks for WhatsApp's
 * gesture, and WhatsApp does not show that menu: it offers formatting for the
 * highlighted text and keeps the editing line visible.
 *
 * ## Why this class replaces the previous one
 *
 * The old implementation provided a `TextToolbar` through `LocalTextToolbar`,
 * which is the 1.x API for exactly this and was believed to be the whole
 * mechanism. It is not any more: Compose 1.12 routes the selection menu through
 * `LocalTextContextMenuToolbarProvider` (and its dropdown twin) and only falls
 * back to `TextToolbar` when nothing is provided — so the override was never
 * consulted, the system bubble appeared exactly as before, and the code that was
 * meant to prevent it sat there looking correct. That is what this file fixes.
 *
 * ## What it does
 *
 * `showTextContextMenu` is where the platform would draw its menu. This
 * implementation reads the items it was about to offer, hands them to
 * [onItems] for our own bar to draw in the layout, and shows nothing itself.
 * **Nothing is lost**: Cut, Copy, Paste and Select all are still available, in
 * a menu that belongs to this app and sits under the text instead of on top of
 * it.
 *
 * Returning straight away is deliberate rather than lazy. The platform's
 * implementation suspends until its menu is dismissed; ours has no menu to wait
 * for, and Compose treats a completed request as a finished one — the selection
 * stays where the reader put it and the next highlight offers the menu again.
 */
internal class GoodPostTextContextMenuProvider(
    /** Called with what the platform would have offered. */
    private val onItems: (List<GoodPostContextMenuAction>) -> Unit
) : TextContextMenuProvider {

    override suspend fun showTextContextMenu(provider: TextContextMenuDataProvider) {
        // Only the actions. `TextContextMenuData.components` can also hold
        // separators and text-classification entries ("Call this number"), and
        // those have no place in a four-item formatting bar.
        val actions = provider.data().components
            .filterIsInstance<TextContextMenuItem>()
            .map { item ->
                GoodPostContextMenuAction(
                    label = item.label,
                    // The session is the item's own handle on the menu it came
                    // from; it is closed after the action runs, which is what
                    // tells Compose the request is finished.
                    invoke = { item.onClick(it) }
                )
            }

        if (actions.isNotEmpty()) onItems(actions)
    }
}

/**
 * One action the platform offered, with the words it would have used.
 *
 * The label is the platform's own string ("Cut", "Copy", "Paste", "Select all"),
 * already localised, rather than a resource of ours: these are Android's
 * operations, and inventing our own words for them would mean translating
 * operations we do not implement.
 */
internal data class GoodPostContextMenuAction(
    val label: String,
    val invoke: (TextContextMenuSession) -> Unit
)

/**
 * A session our own bar hands back when it runs an action.
 *
 * Compose's session is one method — [TextContextMenuSession.close] — and it is
 * what the menu implementation is expected to call once it has done whatever it
 * was asked to do. Our menu is a bar in the layout rather than a popup, so there
 * is nothing to dismiss, and the call is kept rather than skipped: an item that
 * performs its action and closes its session is the whole contract, and a
 * no-op close is a truthful implementation of it.
 */
internal val GoodPostNoopMenuSession: TextContextMenuSession = object : TextContextMenuSession {
    override fun close() = Unit
}

/**
 * The clipboard half of the selection bar (§7).
 *
 * Rendered beside the formatting words so that removing Android's menu did not
 * remove the ability to copy or paste. Only the actions the platform actually
 * offered are drawn: with nothing on the clipboard there is no Paste, and with
 * no selection there is no Copy.
 */
@Composable
internal fun GoodPostClipboardBar(
    actions: List<GoodPostContextMenuAction>,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            Text(
                text = action.label,
                color = Wa.TextDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Wa.Bar)
                    .clickable { action.invoke(GoodPostNoopMenuSession) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
