package com.muddassir.clearview.quran.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.muddassir.clearview.R

/**
 * The typeface every Arabic Quran surface renders with (§1).
 *
 * The text is the IndoPak edition, and IndoPak Arabic leans on a WIDE set of
 * combining marks: this app's corpus uses 38 of them, including the small-high
 * waqf signs (U+08D4–U+08E2) and the takhallus (U+0614) — not just the everyday
 * zabar / zer / pesh / shadda / sukoon.
 *
 * The face therefore has to cover all of them. The previous face did not: Amiri
 * Quran (and full Amiri, Regular or Bold) has no glyph for 12–13 of those marks,
 * so the engine fell back to another font for them or dropped them outright —
 * which is exactly "some zabar, zer … are missing". Coverage was measured against
 * the whole downloaded IndoPak edition, not one verse:
 *
 * | face                     | marks missing |
 * |--------------------------|---------------|
 * | Amiri Quran (old)        | 13            |
 * | Amiri Regular / Bold     | 12            |
 * | KFGQPC Uthmanic HAFS     | 12            |
 * | Noto Naskh Arabic        | 0             |
 * | Scheherazade New (Bold)  | 0             |
 *
 * [R.font.scheherazade_new_bold] is the choice: zero missing marks over the whole
 * corpus, real shaping (init / medi / fina / rlig / calt / mark / mkmk), a
 * traditional Naskh design, and a true BOLD weight — so the glyphs are thick and
 * dark instead of thin and faint. Bundled (SIL OFL, see licenses/scheherazade_new)
 * rather than resolved at runtime so a verse renders identically on every device,
 * online or offline, with no first-run download.
 *
 * The text itself is authoritative and untouched — this only selects the face.
 */
/** The bundled face the Quran renders with — exposed so a test can pin it. */
internal val QURAN_FONT_RES = R.font.scheherazade_new_bold

/** The Quran face's weight — BOLD, so the glyphs are thick and dark, not faint. */
internal val QURAN_FONT_WEIGHT = FontWeight.Bold

val QuranFontFamily: FontFamily = FontFamily(Font(QURAN_FONT_RES, QURAN_FONT_WEIGHT))

/**
 * The extra vertical room a Quran line needs, as a multiple of the font size.
 *
 * Combining marks are drawn ABOVE and BELOW the base letters, and they are the
 * reason a Quran line is taller than its letter height: at the dashboard's 30sp
 * the shadda, the small-high waqfs and the superscript alef stack well above the
 * baseline, and the subscript alef and small-low meem sit below it. A line height
 * that only fits the letters CLIPS the outer marks, which reads as "some harakat
 * are missing" even when the text and the font are both perfect.
 *
 * The number is measured, not guessed. The corpus's ink spans 2.09 em in this
 * face (tallest mark 1.41 em above the baseline, lowest 0.68 em below), so a
 * line has to be at least that tall to hold the outer marks — the old code gave
 * 50/30 = 1.67 (reader) and 52/30 = 1.73 (surah page), which is why marks were
 * clipped even when the text and the font were both fine. 2.1 clears the
 * measured 2.09 with a hair to spare.
 *
 * It is a ratio rather than a fixed dp so the reader (30sp) and the compact
 * surah list (18sp) both stay clear of the marks without either one
 * hand-tuning its own number.
 */
const val QURAN_LINE_HEIGHT_RATIO = 2.1f
