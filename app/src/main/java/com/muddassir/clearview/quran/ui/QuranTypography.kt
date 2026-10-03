package com.muddassir.clearview.quran.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.muddassir.clearview.R

/**
 * The typeface every Arabic Quran surface renders with (§1).
 *
 * The verse text was never the problem: it is stored and passed through intact,
 * harakat and all. The problem was that the app drew it with the platform's
 * generic serif and relied on FONT FALLBACK for the Arabic, and a fallback face
 * does not carry the full Quranic combining-mark set — so zabar/zer/pesh,
 * shadda, sukoon and the small high marks could drop out or sit wrong, and a
 * word with a dense set of them (the very words a reader notices) came out
 * inconsistent.
 *
 * [R.font.amiri_quran] is a Quran typeface with complete harakat coverage, so
 * the marks the source carries are the marks the screen shows. Bundled rather
 * than resolved at runtime because a Quran surface must render identically on
 * every device, online or offline, with no first-run download that could leave
 * a verse mark-less until some font arrives.
 *
 * The text itself is authoritative and untouched — this only selects the face.
 */
val QuranFontFamily: FontFamily = FontFamily(Font(R.font.amiri_quran))
