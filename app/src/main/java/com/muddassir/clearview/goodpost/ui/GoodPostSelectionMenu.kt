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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R

/**
 * The platform's selection menu, captured instead of shown (§7).
 *
 * Highlighting text inside the composer used to raise Android's own menu —
 * **Cut / Copy / Paste / Select all / Read aloud** — in a light bubble that looks
 * nothing like the rest of Good Post and covers the line being edited. §7 asks
 * for WhatsApp's gesture, and WhatsApp does not show that menu: it shows a small
 * dark bar of formatting actions for the highlighted text.
 *
 * So this replaces it. [showMenu] is where Compose would display the system
 * bubble; overriding it to do nothing is what removes it, and the callbacks it
 * was handed are kept in [actions] so OUR bar can still offer Cut, Copy, Paste
 * and Select all. **Nothing is lost** — the same operations are available, in a
 * menu that belongs to this app and sits in the layout instead of over the text.
 *
 * `status` is always [TextToolbarStatus.Hidden]. That is not cosmetic: Compose
 * reads it to decide whether a menu is up, and reporting anything else would make
 * it believe it had shown something it had not.
 *
 * ## Why the callbacks are stored rather than invoked
 *
 * They are closures over the platform's current selection and clipboard, and they
 * are only valid while that selection is live. Holding the LATEST set and calling
 * them from a tap on our own bar is the whole mechanism — there is no other way to
 * reach `copy` or `paste`, because those are the platform's operations and are not
 * re-implementable from a Compose text field.
 */
internal class GoodPostSelectionToolbar(
    /** Called when the platform offers a menu, with what it would have offered. */
    private val onActions: (GoodPostClipboardActions) -> Unit
) : TextToolbar {

    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden

    override fun hide() = Unit

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        onActions(
            GoodPostClipboardActions(
                copy = onCopyRequested,
                paste = onPasteRequested,
                cut = onCutRequested,
                selectAll = onSelectAllRequested
            )
        )
    }
}

/**
 * The clipboard operations the platform offered, none of which is guaranteed.
 *
 * Every field is nullable because Compose passes only the ones that apply — a
 * read-only field gets no `cut`, and a clipboard with nothing on it gets no
 * `paste`. Our bar hides what is absent rather than showing a dead button, which
 * is the same rule the artwork follows.
 */
internal data class GoodPostClipboardActions(
    val copy: (() -> Unit)? = null,
    val paste: (() -> Unit)? = null,
    val cut: (() -> Unit)? = null,
    val selectAll: (() -> Unit)? = null
) {
    /** Nothing to offer, so our bar should not show its clipboard row. */
    val isEmpty: Boolean get() = copy == null && paste == null && cut == null && selectAll == null
}

/**
 * The clipboard half of the selection bar (§7).
 *
 * Rendered beside the formatting buttons so that removing Android's menu did not
 * remove the ability to copy or paste. Only the actions the platform actually
 * offered are drawn: with nothing on the clipboard there is no Paste, and with no
 * selection there is no Copy.
 */
@Composable
internal fun GoodPostClipboardBar(
    actions: GoodPostClipboardActions?,
    modifier: Modifier = Modifier
) {
    if (actions == null || actions.isEmpty) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.cut?.let { ClipboardAction(R.string.goodpost_cut, it) }
        // The same entry the post action bar uses: one word, one string.
        actions.copy?.let { ClipboardAction(R.string.goodpost_copy, it) }
        actions.paste?.let { ClipboardAction(R.string.goodpost_paste, it) }
        actions.selectAll?.let { ClipboardAction(R.string.goodpost_select_all, it) }
    }
}

@Composable
private fun ClipboardAction(labelRes: Int, onClick: () -> Unit) {
    Text(
        text = stringResource(labelRes),
        color = Wa.TextDim,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.Bar)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
