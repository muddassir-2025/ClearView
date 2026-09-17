package com.muddassir.clearview.goodpost.data

/**
 * Good Post's inline formatting, in the WhatsApp dialect (§17).
 *
 * The stored representation is the text ITSELF, with the markers left in it:
 * `*bold*`, `_italic_`, `~strikethrough~` and ``` ```monospace``` ```. There is
 * no rich-text model, no HTML and no span table — a post body is one string, and
 * this file is the only place that knows the markers mean anything.
 *
 * That choice is deliberate, and it is what makes formatting survive every step
 * of the round trip for free:
 *
 *  * **Storage** — the server stores an opaque `text` column. It never parses,
 *    never validates and never rewrites the body, so there is no server-side
 *    formatter to keep in step with the client.
 *  * **Reload and edit** — the raw string is what comes back, and it is what the
 *    editor re-opens with, so an edit cannot lose formatting it never decoded.
 *  * **Safety** — nothing is ever rendered as HTML. The parser below turns
 *    markers into Compose spans, so the worst a body can do is make text bold.
 *
 * The trade is that markers are visible while typing, which is exactly how
 * WhatsApp behaves and why it needs no explanation.
 */
enum class GoodPostFormat(
    /** The exact sequence that opens and closes this format. */
    val marker: String
) {
    Bold("*"),
    Italic("_"),
    Monospace("```"),
    Strikethrough("~");

    /**
     * True when [selected] is exactly ONE run of this format, so the toolbar can
     * show it as active and a second tap can take it off.
     *
     * The content test is what makes this conservative. `*a* and *b*` selected
     * whole begins and ends with the marker, but it is two bold runs rather than
     * one, and stripping only the outer pair would leave the mangled `a* and *b`.
     * So a run counts only when the delimiters it is asked to remove are the only
     * ones inside it.
     */
    fun wraps(selected: String): Boolean {
        val width = marker.length
        if (selected.length <= width * 2) return false
        if (!selected.startsWith(marker) || !selected.endsWith(marker)) return false
        val inner = selected.substring(width, selected.length - width)
        return inner.isNotBlank() && !inner.contains(marker)
    }

    /**
     * True when any of this format's markers appear in [body] at all.
     *
     * Used for the toolbar's active state when there is no selection: the button
     * lights up for text that already contains the marker rather than for the
     * caret's position, which would be arbitrary.
     */
    fun presentIn(body: String): Boolean = body.contains(marker)
}

/**
 * The text and selection a formatting action leaves behind.
 *
 * The selection is returned with the result rather than recomputed by the
 * caller because the two are not independent: wrapping text moves the caret, and
 * a caller that kept its own offsets would put the caret in the middle of a
 * marker.
 */
data class GoodPostFormatEdit(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

/**
 * Toggle [format] on the selection in [text] (§17).
 *
 * Four cases, and each is the one a person expects from a toolbar:
 *
 *  * **Nothing selected** — the marker pair is inserted with the caret between
 *    it, so typing lands inside the format. This is the same as typing `**` by
 *    hand, which is the behaviour the visible markers imply.
 *  * **Selected, already formatted** — the pair is removed. This is what makes
 *    the button a toggle rather than an "add another pair" button that would grow
 *    `*bold*` into `**bold**`.
 *  * **Selected, with the markers immediately outside it** — the same removal,
 *    found by looking around the highlight instead of inside it. It is what makes
 *    a hand-selected word inside `*bold*` unwrap when the button is tapped, which
 *    is how someone who typed the markers themselves would expect it to behave.
 *  * **Selected, not formatted** — the selection is wrapped, and the WHOLE wrapped
 *    run stays selected (delimiters included). Selecting the whole run is what
 *    lets the next tap find case two rather than wrapping a second time; the
 *    alternative — leaving only the inner text highlighted — made the button add
 *    a pair on every tap.
 *
 * Pure, clamped and total: offsets outside [text] are pulled back into it rather
 * than throwing, because a selection raced by a recomposition must not crash the
 * editor.
 */
fun applyGoodPostFormat(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    format: GoodPostFormat
): GoodPostFormatEdit {
    val length = text.length
    val start = selectionStart.coerceIn(0, length)
    val end = selectionEnd.coerceIn(0, length)
    val from = minOf(start, end)
    val to = maxOf(start, end)
    val marker = format.marker
    val width = marker.length

    // No selection: plant the pair and stand in the middle of it.
    if (from == to) {
        val caret = from + width
        return GoodPostFormatEdit(
            text = text.substring(0, from) + marker + marker + text.substring(from),
            selectionStart = caret,
            selectionEnd = caret
        )
    }

    val selected = text.substring(from, to)

    // Already wrapped inside the selection: take the outer pair off.
    if (format.wraps(selected)) {
        val inner = selected.substring(width, selected.length - width)
        return GoodPostFormatEdit(
            text = text.substring(0, from) + inner + text.substring(to),
            selectionStart = from,
            selectionEnd = from + inner.length
        )
    }

    // The markers sit immediately OUTSIDE the selection, so the highlighted text
    // is already this format. Removing them keeps the same characters highlighted.
    val outerStart = from - width
    val outerEnd = to + width
    if (
        outerStart >= 0 && outerEnd <= length &&
        text.startsWith(marker, outerStart) && text.startsWith(marker, to)
    ) {
        return GoodPostFormatEdit(
            text = text.substring(0, outerStart) + selected + text.substring(outerEnd),
            selectionStart = outerStart,
            selectionEnd = outerStart + selected.length
        )
    }

    // Wrap it, and select the whole wrapped run so the button toggles from here.
    return GoodPostFormatEdit(
        text = text.substring(0, from) + marker + selected + marker + text.substring(to),
        selectionStart = from,
        selectionEnd = to + (width * 2)
    )
}
