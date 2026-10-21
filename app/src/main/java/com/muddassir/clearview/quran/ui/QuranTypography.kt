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
 * HOW THIS APP SOLVES IT — MATCH THE TEXT TO THE FACE, WITH A NET BENEATH IT
 *
 * Two things have to happen, and only the first one fixes the marks:
 *
 *  1. The corpus is RE-SPELLED into the encoding this face was built for
 *     ([com.muddassir.clearview.quran.data.quranDisplayText]). The corpus spells
 *     the sukun `U+06E1` — 62,381 times — which this face has no glyph for, so
 *     every one of those marks was being drawn by the NET below, at anchors
 *     computed for different letters. That is what "the zer and zabr collide with
 *     the letters" was. One mark re-spelling removes ~95% of it and lets the face
 *     draw its own sukun (`U+0652`); measured, the face's own marks produce zero
 *     overlapping ink over every verse tested.
 *  2. [R.font.scheherazade_new] stays behind the Mushaf face as the net for the
 *     marks that are genuinely absent AND have no equivalent spelling: U+0658
 *     (3,027 instances), U+06E0 (25) and U+08D4/U+08DA/U+08E0/U+08E1 (9 between
 *     them). It draws those with its own anchors. The alternative was to
 *     substitute a similar-looking sign, which would change what a verse says in
 *     order to change how it looks.
 *
 * Both faces are SIL OFL (so they may be bundled freely):
 *
 *  1. [R.font.digitalkhatt_indopak] — DigitalKhatt IndoPak (Amine Anane /
 *     Tarteel Inc.), a purpose-built IndoPak Mushaf face derived from the
 *     13-line Quraan Al Majeed. It gives the letters and the common harakat the
 *     Mushaf look the reader expects (the closest legally-bundlable equivalent
 *     of the face Dawat-e-Islami / Sirat-ul-Jinan ship).
 *  2. [R.font.scheherazade_new] — the net described above. Measured over all
 *     6236 verses it covers every mark in the corpus, so the chain is total.
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
 * OTHER FACES WERE TRIED AND DROPPED. Noto Naskh Arabic, Lateef and Harmattan
 * all cover this corpus, but a Naskh set renders the harakat small and light
 * next to the Mushaf face's, and on a phone that reads to a reader as marks
 * going faint or missing — the exact complaint this work started from. The
 * Mushaf face is the one that keeps them legible at reading size.
 *
 * The words are authoritative and untouched: the only thing this changes in the
 * text is which of the two Unicode spellings of the sukun a verse carries — see
 * [com.muddassir.clearview.quran.data.quranDisplayText]. Selecting a face and
 * re-spelling a mark are both presentation; neither adds, removes or reorders a
 * single letter or vowel.
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
 * Combining marks are drawn above and below the base letters and are NOT kept
 * inside a line by the font's own ascent/descent, so the leading has to do it.
 * Measured over all 6236 verses in this chain: a line's ink reaches 1.147 em
 * above the baseline on average and 0.493 em below, up to 1.752 / 0.942 on the
 * densest verses. Two consecutive baselines therefore have to be at least
 * "above of one line + below of the next" apart, and the fraction of adjacent
 * line pairs whose ink actually collides was measured as:
 *
 * | ratio | colliding line pairs |
 * |-------|----------------------|
 * | 2.0   | 0.90%                |
 * | 2.1   | 0.19%                |
 * | 2.2   | 0.04%                |
 * | 2.3   | 0.01%                |
 *
 * It is a ratio rather than a fixed dp so the reader (30sp) and the compact
 * surah list (18sp) both stay clear without either hand-tuning its own number:
 * a fixed line height that fits 30sp would let a 45sp verse's marks collide
 * again.
 *
 * Fixed, not adjustable: it is a property of this face, and the measurement
 * picks the value — a reader who has to tune the leading as well as the size has
 * been handed a typesetting job.
 */
const val QURAN_LINE_HEIGHT_RATIO = 2.3f

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
