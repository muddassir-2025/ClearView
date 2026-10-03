package com.muddassir.clearview.quran.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.muddassir.clearview.R

/**
 * The typeface every Arabic Quran surface renders with (§1).
 *
 * WHY THIS IS THE HARD PART
 *
 * QUL (Tarteel's Quranic Universal Library — the resource library behind
 * Quran.com) states the rule plainly: "A font on its own is not enough to render
 * the Quran. Most Quran fonts need a matching Quran script, because the text has
 * to use exactly the characters the font was built for." A Quran font carries
 * glyphs AND the anchor rules that place each mark on each letter, and those
 * anchors are built for one specific text. Pair a font with the wrong text and
 * the marks have glyphs but land wrong — too high, too low, colliding — which
 * reads on screen as "some marks are missing".
 *
 * That is why swapping fonts alone never fixed this. Measured over the WHOLE
 * downloaded corpus (all 6236 verses), not one verse:
 *
 * | font                          | vs this app's IndoPak text |
 * |-------------------------------|----------------------------|
 * | Amiri Quran (originally used)  | 13 marks missing           |
 * | Amiri Regular / Bold           | 12 marks missing           |
 * | QuranWBW IndoPak font          | 13 marks missing           |
 * | PDMS Saleem (quran.com IndoPak)| 15 marks missing           |
 * | Noto Naskh Arabic              | 0 missing                  |
 * | **Scheherazade New (Regular)** | **0 missing**              |
 *
 * Every font that is "the official IndoPak font" is missing marks against THIS
 * app's text, because the app's text comes from the fawazahmed0 API
 * (ara-quranindopak), which is a DIFFERENT IndoPak corpus than the one those
 * fonts were built for. Notably PDMS Saleem — the font Quran.com itself ships
 * for IndoPak — has no glyph for U+0658 (noon ghunna, 3027 occurrences).
 *
 * So the face is chosen by measurement against the text this app actually
 * downloads: [R.font.scheherazade_new] covers every codepoint in the corpus.
 *
 * REGULAR, NOT BOLD. Quran text is traditionally set in a regular weight; a
 * Bold Quran face draws the marks heavy enough to merge into the letters, which
 * makes dense harakat harder to read, not easier. The clarity here comes from
 * the font's large x-height and its mark placement, not from weight.
 *
 * Bundled (SIL OFL, see licenses/scheherazade_new) rather than resolved at
 * runtime so a verse renders identically on every device, online or offline.
 *
 * The text itself is authoritative and untouched — this only selects the face.
 */
val QuranFontFamily: FontFamily = FontFamily(Font(R.font.scheherazade_new))

/** The bundled face the Quran renders with — exposed so a test can pin it. */
internal val QURAN_FONT_RES = R.font.scheherazade_new

/**
 * The extra vertical room a Quran line needs, as a multiple of the font size.
 *
 * Combining marks are drawn above and below the base letters, and a line box
 * only gives a mark `(lineHeight + ascent − descent) / 2` of room above the
 * baseline. Measured in this face: ascent 1.343 em, descent 0.697 em, and the
 * tallest mark in the corpus reaches 1.279 em above the baseline — comfortably
 * inside the font's own ascent, so this face needs no extra leading to avoid
 * clipping (the previous Bold face did: its tallest mark reached 1.408 em,
 * past its 1.343 em ascent).
 *
 * 2.0 gives the marks room and keeps the text at the traditional proportion.
 * It is a ratio rather than a fixed dp so the reader (30sp) and the compact
 * surah list (18sp) both stay clear without either hand-tuning its own number.
 */
const val QURAN_LINE_HEIGHT_RATIO = 2.0f
