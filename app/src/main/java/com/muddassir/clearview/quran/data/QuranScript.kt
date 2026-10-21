package com.muddassir.clearview.quran.data

/**
 * The Quran text, re-spelled in the mark encoding the bundled face was drawn for.
 *
 * WHY THIS EXISTS
 *
 * A Quran font is not only glyphs. It carries anchor rules saying where each mark
 * sits on each letter, and those rules are built for ONE text. The bundled face
 * (DigitalKhatt IndoPak — see [com.muddassir.clearview.quran.ui.QuranFontFamily])
 * was drawn for a classic IndoPak Mushaf, which spells the sukun/jazm `U+0652`.
 * This app's corpus comes from the fawazahmed0 API (`ara-quranindopak`), which is
 * quran.com's NEWER IndoPak encoding, and that text spells the very same mark
 * `U+06E1` — 62,381 times, in almost every verse.
 *
 * The face has no glyph for `U+06E1`, so every one of those marks is drawn by a
 * FALLBACK face, whose anchors were computed for its own letterforms. That is
 * what "the zer and zabr collide with the letters in some places" is: a mark
 * positioned for one set of letters landing on another. It is also why the page
 * mixes two styles of harakat.
 *
 * MEASURED, NOT ASSUMED (fontTools + HarfBuzz over the corpus)
 *
 *  - `U+06E1` is 62,381 of the 65,442 codepoint instances the face cannot draw —
 *    95% of everything that needs the fallback face.
 *  - Re-spelling it drops those instances by 95% (13,838 -> 620 over 800 verses),
 *    so the face draws its own sukun with its own anchors — and the face does
 *    have a sukun: `U+0652`, ink 0.217 em above the baseline.
 *  - With the face drawing its own marks, the overlap is ZERO: measured over 800
 *    verses, there are 0 px of a mark overlapping another cluster's ink and 0 px
 *    of two marks overlapping, before or after this mapping. The collisions the
 *    reader sees come from the fallback face, not from this one.
 *
 * WHAT IT IS NOT
 *
 * Not a correction to the scripture, and not word-by-word. `U+06E1` and `U+0652`
 * are two Unicode spellings of the same mark, the mapping is one-to-one and
 * length-preserving, and no letter, vowel, word or order changes. Only which of
 * the two spellings of one mark the text carries.
 *
 * DELIBERATELY NOT MAPPED: `U+0658` (3,027), `U+06E0` (25), `U+08D4`, `U+08DA`,
 * `U+08E0`, `U+08E1` (9 between them). This face has no shape for any of them,
 * and each is a different sign — a noon ghunna is not a madda and a waqf sign is
 * not another waqf sign. Substituting something similar-looking would change what
 * a verse says in order to change how it looks, which is not a trade a text of
 * scripture gets to make. They stay, and the fallback face draws them.
 *
 * WHERE IT IS APPLIED — the two boundaries every verse's Arabic text crosses, and
 * nowhere else:
 *
 *  1. [QuranRepository.enrich] — text attached from the downloaded edition, which
 *     is what the search results, the surah reader and the bookmarks see.
 *  2. [QuranStore.readCurrentVerse] and [QuranStore.saveCurrentVerse] — the
 *     persisted verse, which the Quran tab and the home-screen widget read
 *     directly, bypassing the repository; normalising on the way out is also what
 *     repairs a value written by an older build.
 *
 * Not at each render site: a mapping applied per screen is a mapping that
 * eventually misses one, and the reader would see one verse spelled two ways.
 */
fun quranDisplayText(arabic: String): String {
    if (arabic.none { it in MARKS_TO_CLASSIC }) return arabic
    return buildString(arabic.length) {
        for (c in arabic) append(MARKS_TO_CLASSIC[c] ?: c)
    }
}

/**
 * Corpus spelling -> classic IndoPak spelling, for the marks where the two are
 * the SAME SIGN written two ways. Every value here must be a codepoint the
 * bundled face actually has; a mapping to another gap would move the fallback,
 * not remove it.
 */
private val MARKS_TO_CLASSIC: Map<Char, Char> = mapOf(
    '\u06E1' to '\u0652', // small high dotless head of khah -> sukun (jazm)
)
