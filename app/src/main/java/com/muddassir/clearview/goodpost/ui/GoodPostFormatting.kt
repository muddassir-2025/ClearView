package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
 * The formatting toolbar over the composer (§17).
 *
 * Four buttons that operate on the SELECTION, which is what makes this a
 * formatting control rather than a mode: the text stays exactly as it was and
 * the delimiters move in or out around what is highlighted. Nothing here is a
 * rich-text engine, and there is deliberately no toolbar for anything else — no
 * headings, no lists, no fonts, no colours.
 *
 * [isActive] is answered by the state holder (the character-level test lives in
 * [GoodPostFormat.wraps]), so a button can show that the selection already
 * carries its format and read as a toggle.
 */
@Composable
internal fun GoodPostFormatToolbar(
    isActive: (GoodPostFormat) -> Boolean,
    onToggle: (GoodPostFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FormatButton(
            icon = Icons.Filled.FormatBold,
            label = stringResource(R.string.goodpost_format_bold),
            active = isActive(GoodPostFormat.Bold),
            onClick = { onToggle(GoodPostFormat.Bold) }
        )
        FormatButton(
            icon = Icons.Filled.FormatItalic,
            label = stringResource(R.string.goodpost_format_italic),
            active = isActive(GoodPostFormat.Italic),
            onClick = { onToggle(GoodPostFormat.Italic) }
        )
        FormatButton(
            icon = Icons.Filled.Code,
            label = stringResource(R.string.goodpost_format_monospace),
            active = isActive(GoodPostFormat.Monospace),
            onClick = { onToggle(GoodPostFormat.Monospace) }
        )
        FormatButton(
            icon = Icons.Filled.FormatStrikethrough,
            label = stringResource(R.string.goodpost_format_strikethrough),
            active = isActive(GoodPostFormat.Strikethrough),
            onClick = { onToggle(GoodPostFormat.Strikethrough) }
        )
    }
}

@Composable
private fun FormatButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (active) Wa.Accent else Wa.TextDim,
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Wa.Pressed else Wa.Bar)
            .clickable(onClick = onClick)
            .padding(6.dp)
    )
}
