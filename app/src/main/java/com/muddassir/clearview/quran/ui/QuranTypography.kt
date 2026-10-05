package com.muddassir.clearview.quran.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
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
 * Every "official IndoPak font" is missing marks against THIS app's text,
 * because the app's text comes from the fawazahmed0 API (ara-quranindopak),
 * which is a DIFFERENT IndoPak corpus than the one those fonts were built for.
 *
 * HOW THIS APP SOLVES IT — A TWO-FACE FALLBACK CHAIN
 *
 * Both faces are SIL OFL (so they may be bundled freely):
 *
 *  1. [R.font.digitalkhatt_indopak] — DigitalKhatt IndoPak (Amine Anane /
 *     Tarteel Inc.), a purpose-built IndoPak Mushaf face derived from the
 *     13-line Quraan Al Majeed. It gives the letters and the common harakat the
 *     Mushaf look the reader expects (the closest legally-bundlable equivalent
 *     of the face Dawat-e-Islami / Sirat-ul-Jinan ship).
 *  2. [R.font.scheherazade_new] — kept as the fallback for the handful of
 *     codepoints the Mushaf face does not carry. This is what makes the chain
 *     total: the corpus uses a NEWER IndoPak encoding than the Mushaf face was
 *     built for, and no single redistributable face covers both. Measured over
 *     all 6236 verses, Scheherazade New covers every mark in the corpus; the
 *     Mushaf face misses exactly seven (U+0658, U+06E0, U+06E1, U+08D4, U+08DA,
 *     U+08E0, U+08E1).
 *
 * Compose resolves a [FontFamily] with several faces as a fallback chain for the
 * same weight/style, so a codepoint missing from the first face is drawn by the
 * second with that face's own mark anchors — never .notdef, never a silent
 * system font. (See QuranFontCoverageTest, which pins both faces.)
 *
 * REGULAR, NOT BOLD. Quran text is traditionally set in a regular weight; a
 * Bold Quran face draws the marks heavy enough to merge into the letters, which
 * makes dense harakat harder to read, not easier.
 *
 * Bundled (see licenses/digitalkhatt_indopak and licenses/scheherazade_new)
 * rather than resolved at runtime so a verse renders identically on every
 * device, online or offline.
 *
 * The text itself is authoritative and untouched — this only selects the face.
 */
val QuranFontFamily: FontFamily = FontFamily(
    Font(R.font.digitalkhatt_indopak),
    Font(R.font.scheherazade_new),
)

/** The primary Quran face (DigitalKhatt IndoPak) — exposed so a test can pin it. */
internal val QURAN_FONT_RES = R.font.digitalkhatt_indopak

/** The full-coverage fallback face — exposed so a test can pin it. */
internal val QURAN_FALLBACK_FONT_RES = R.font.scheherazade_new

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

/**
 * The reader's scale factor for the Quran Arabic text, provided once at the
 * content root ([com.muddassir.clearview.ui.ContentHubTabContent]) and read by
 * every Arabic surface.
 *
 * A CompositionLocal rather than a parameter on purpose: an Arabic `Text` that
 * forgets to read it renders at a fixed size and sticks out, and as the number
 * of Arabic surfaces grows (verse, basmala, surah reader, bookmark preview) a
 * value threaded by hand is a value that eventually misses one. Reading the
 * local is the default, and the default is correct.
 */
val LocalQuranTextScale = staticCompositionLocalOf { 1f }

/**
 * The Arabic font size for a base size of [baseSp], at the reader's chosen
 * scale. Every Arabic surface sizes through here, so "make the Quran text
 * bigger" moves all of them together and none is left behind.
 */
@Composable
@ReadOnlyComposable
fun quranFontSize(baseSp: Float): TextUnit =
    (baseSp * LocalQuranTextScale.current).sp

/**
 * The line height that keeps pace with [quranFontSize] at [baseSp].
 *
 * Derived rather than set independently, because the marks only stay unclipped
 * if the line grows with the glyphs: a fixed line height that fits 30sp clips a
 * 45sp verse. Scaling both by the same factor is what keeps the harakat inside
 * the line at every size the reader picks.
 */
@Composable
@ReadOnlyComposable
fun quranLineHeight(baseSp: Float): TextUnit =
    (baseSp * LocalQuranTextScale.current * QURAN_LINE_HEIGHT_RATIO).sp
