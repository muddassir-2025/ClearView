package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostFormat

/**
 * Turn a stored post body into the styled text the feed draws (§17).
 *
 * The body arrives as plain text with WhatsApp's markers left in it — see
 * [GoodPostFormat] for why that is the storage format. This is the only place
 * those markers become anything, and the only place that decides what a marker
 * means.
 *
 * **Nothing here renders HTML**, and nothing can: the output is an
 * [AnnotatedString], which is a list of spans over the original characters. A body
 * containing `<b>` is displayed as `<b>`, which is the point — a post's text is
 * text.
 *
 * The rules are deliberately the conservative ones a person already knows from
 * WhatsApp, so nobody has to be taught them:
 *
 *  * A delimiter must not be part of a word: `a*b*c` is arithmetic, `*bold*` is
 *    bold. Same for `_` and `~`.
 *  * The content must not begin or end with whitespace, so a stray `*` in prose
 *    cannot open a format that swallows the rest of the paragraph.
 *  * Both markers must be present. An unclosed `*` renders literally, which is
 *    what someone typing an asterisk about a footnote wants.
 *
 * Nesting is not interpreted. It is not that the format forbids it (the toolbar
 * can produce `*_both_*`, and it WILL be stored and reloaded intact) but that one
 * pass, left to right, is predictable: the outer marker wins, and the inner one
 * shows as text. A span stack would be a second parser for a feature the
 * composer does not offer.
 */
internal fun parseGoodPostText(raw: String): AnnotatedString {
    if (raw.isEmpty()) return AnnotatedString("")

    return buildAnnotatedString {
        var index = 0
        while (index < raw.length) {
            val next = FORMAT_PATTERNS
                .mapNotNull { (format, regex) -> regex.find(raw, index)?.let { format to it } }
                .minWithOrNull(
                    // Earliest match wins; a tie goes to the longer delimiter, so
                    // ``` ``` ``` is read as monospace rather than as two stray
                    // characters around a monospace pair.
                    compareBy({ it.second.range.first }, { -it.second.value.length })
                )

            if (next == null) {
                append(raw.substring(index))
                break
            }

            val (format, match) = next
            append(raw.substring(index, match.range.first))
            withStyle(styleOf(format)) {
                append(match.groupValues[1])
            }
            index = match.range.last + 1
        }
    }
}

/** The visual meaning of each format. Kept in one place so none can drift. */
private fun styleOf(format: GoodPostFormat): SpanStyle = when (format) {
    GoodPostFormat.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    GoodPostFormat.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    GoodPostFormat.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    // The one format that changes the face rather than the weight. The body's
    // own font is the platform default, which is what makes monospace worth
    // having as a choice at all.
    GoodPostFormat.Monospace -> SpanStyle(fontFamily = FontFamily.Monospace)
}

/**
 * The four marker pairs this parser understands.
 *
 * Ordered so the three-character delimiter is tried first at any position, and
 * with word-boundary guards on the single-character ones. `DOT_MATCHES_ALL` lets
 * a format span a line break, which is what a multi-line post needs.
 */
private val FORMAT_PATTERNS: List<Pair<GoodPostFormat, Regex>> = listOf(
    GoodPostFormat.Monospace to
        Regex("```(?=\\S)(.+?)(?<=\\S)```", RegexOption.DOT_MATCHES_ALL),
    GoodPostFormat.Bold to
        Regex("(?<![A-Za-z0-9])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![A-Za-z0-9])", RegexOption.DOT_MATCHES_ALL),
    GoodPostFormat.Italic to
        Regex("(?<![A-Za-z0-9])_(?=\\S)(.+?)(?<=\\S)_(?![A-Za-z0-9])", RegexOption.DOT_MATCHES_ALL),
    GoodPostFormat.Strikethrough to
        Regex("(?<![A-Za-z0-9])~(?=\\S)(.+?)(?<=\\S)~(?![A-Za-z0-9])", RegexOption.DOT_MATCHES_ALL)
)

/**
 * The formatting menu for the highlighted text (§7).
 *
 * **Words, not icons.** This was four little glyphs — a bold B, a slanted I, a
 * pair of angle brackets and a struck S — and they were the wrong control twice
 * over: the monospace one (a `</>`-shaped glyph) named a font nobody could
 * recognise from a picture, and all four made the reader decode a symbol before
 * they could format a word. WhatsApp's own menu offers the same four options as
 * a list, and a list of four words is unambiguous in a way four glyphs are not.
 *
 * It appears WITH a selection and only then, and it is in the layout rather than
 * floating over the text, so it can never cover the line it acts on. Four words
 * and the clipboard actions are wider than a small phone, so the CALLER puts the
 * whole bar in one horizontally scrolling row — one scroll for the bar, rather
 * than a nested one here that would swallow the gesture before the clipboard
 * half of it could be reached.
 */
@Composable
internal fun GoodPostFormatMenu(
    isActive: (GoodPostFormat) -> Boolean,
    onToggle: (GoodPostFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FORMAT_ORDER.forEach { format ->
            FormatMenuEntry(
                label = stringResource(labelFor(format)),
                active = isActive(format),
                onClick = { onToggle(format) }
            )
        }
    }
}

/**
 * The order the menu lists them in.
 *
 * WhatsApp's order, and the order of a formatting menu anyone has seen before:
 * the two that change emphasis, then the two that change the text itself.
 */
private val FORMAT_ORDER = listOf(
    GoodPostFormat.Bold,
    GoodPostFormat.Italic,
    GoodPostFormat.Strikethrough,
    GoodPostFormat.Monospace
)

private fun labelFor(format: GoodPostFormat): Int = when (format) {
    GoodPostFormat.Bold -> R.string.goodpost_format_bold
    GoodPostFormat.Italic -> R.string.goodpost_format_italic
    GoodPostFormat.Strikethrough -> R.string.goodpost_format_strikethrough
    GoodPostFormat.Monospace -> R.string.goodpost_format_monospace
}

/**
 * One entry in the formatting menu.
 *
 * A word on a pill. [active] is the state of the highlighted text rather than a
 * mode: a selection that already carries this format shows it lit, and tapping it
 * takes the format off — so the menu reads as four toggles over one selection,
 * which is what it is.
 */
@Composable
private fun FormatMenuEntry(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) Wa.Accent else Wa.TextDim,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Wa.Pressed else Wa.Bar)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * The seam between the formatting words and the clipboard actions (§7).
 *
 * They are two different kinds of thing — one changes what the post will say, the
 * other moves text around — and running them together in one row of pills made
 * the bar look like seven equal choices.
 */
@Composable
internal fun GoodPostMenuDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(18.dp)
            .background(Wa.Divider)
    )
}
