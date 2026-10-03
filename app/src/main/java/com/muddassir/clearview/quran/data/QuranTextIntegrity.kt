package com.muddassir.clearview.quran.data

/**
 * Cheap checks that the Quran Arabic a surface is about to render is still the
 * authoritative text — not a string that some transform quietly ate the harakat
 * out of (§1).
 *
 * The pipeline was audited and does NOT strip or normalize Arabic, but a check
 * that says so is worth keeping: the text is immutable content, and a future
 * change that splits a string, lower-cases it, or slices it by code unit could
 * corrupt it silently. These functions are pure and cheap, so they can run in
 * debug builds (and in tests) without touching production behaviour.
 */
internal object QuranTextIntegrity {

    /** The Unicode replacement character — the fingerprint of a decoding failure. */
    private const val REPLACEMENT = '\uFFFD'

    /** True when [code] is an Arabic-script letter (the bases a verse is made of). */
    fun isArabicLetter(code: Int): Boolean =
        code in 0x0620..0x064A ||
            code in 0x066E..0x06D3 ||
            code in 0x06EE..0x06EF ||
            code in 0x0750..0x077F ||
            code in 0x08A0..0x08FF ||
            code in 0xFB50..0xFDFF ||
            code in 0xFE70..0xFEFF

    /** True when [code] is a combining mark (zabar, zer, pesh, shadda, sukoon…). */
    fun isCombiningMark(code: Int): Boolean = when (Character.getType(code)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true

        else -> false
    }

    /** How many combining marks [text] carries. */
    fun combiningMarkCount(text: String): Int = text.count { isCombiningMark(it.code) }

    /**
     * A problem with [text], or null when it looks like real Quranic Arabic.
     *
     * Flags a decoding failure ([REPLACEMENT]) and a string with no Arabic base
     * letters at all. A blank string is reported as null: "no Arabic yet" is a
     * normal, expected state (an English-only install), not corruption.
     */
    fun problemWith(text: String): String? {
        if (text.isBlank()) return null
        if (text.contains(REPLACEMENT)) return "contains U+FFFD (decoding failure)"
        if (text.none { isArabicLetter(it.code) }) return "no Arabic letters"
        return null
    }

    /**
     * True when [text] has Arabic letters but not a single combining mark.
     *
     * A diacritics-stripping bug looks exactly like this, so it is worth being
     * able to ask. It is NOT treated as an error on its own: some short or
     * non-Quranic Arabic is legitimately unmarked, and refusing to render real
     * text would be a worse failure than the one it guards against.
     */
    fun looksUnmarked(text: String): Boolean =
        text.isNotBlank() &&
            text.any { isArabicLetter(it.code) } &&
            combiningMarkCount(text) == 0
}
