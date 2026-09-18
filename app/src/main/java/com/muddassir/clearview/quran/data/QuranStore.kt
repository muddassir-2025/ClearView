package com.muddassir.clearview.quran.data

import android.content.Context
import com.muddassir.clearview.quran.model.QuranVerse
import java.io.File

/**
 * Local persistence for the Quran reminder.
 *
 * Two stores:
 *  - A raw JSON cache file (internal storage) holding the full downloaded
 *    translation, so everything works fully offline after the first download.
 *  - SharedPreferences holding the currently displayed verse, so widget reads
 *    and detail-screen reads are instant (no file parsing on the UI thread).
 */
class QuranStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    private val arabicCacheFile = File(context.filesDir, AR_CACHE_FILE_NAME)

    init {
        // One-time migration: editions are "The Clear Quran" (English) + the
        // IndoPak script. Older builds cached other editions (Sahih/Uthmani
        // from AlQuran.Cloud, and a broken khattab/indopak pair whose URLs
        // silently fell back to Arabic). When any of those old files existed we
        // remove them so they don't linger on disk — and clear the persisted
        // current verse (which may hold old/wrong text) so the next worker run
        // picks a fresh verse in the new edition.
        val hadOldCache = OLD_CACHE_FILE_NAMES.any { name ->
            File(context.filesDir, name).delete()
        }
        if (hadOldCache) {
            prefs.edit()
                .remove(KEY_SURAH_NUMBER)
                .remove(KEY_AYAH_NUMBER)
                .remove(KEY_SURAH_NAME)
                .remove(KEY_SURAH_TRANSLATION)
                .remove(KEY_VERSE_TEXT)
                .remove(KEY_ARABIC_TEXT)
                .remove(KEY_TOTAL_AYAHS)
                .apply()
        }
    }

    /** True when the full translation has been downloaded and cached. */
    fun isCached(): Boolean = cacheFile.exists() && cacheFile.length() > 0L

    /** True when the Arabic (IndoPak) edition is downloaded and cached. */
    fun isArabicCached(): Boolean = arabicCacheFile.exists() && arabicCacheFile.length() > 0L

    /** Writes the raw downloaded JSON to the cache file. */
    fun saveJson(json: String) {
        cacheFile.writeText(json, Charsets.UTF_8)
    }

    /** Writes the raw downloaded Arabic JSON to its cache file. */
    fun saveArabicJson(json: String) {
        arabicCacheFile.writeText(json, Charsets.UTF_8)
    }

    /**
     * Reads + parses the cached Arabic edition into a (surah, ayah) → text
     * lookup; null when not cached or corrupt. Parsing is IO-heavy, so call
     * from a background thread.
     */
    fun loadArabicTexts(): Map<Pair<Int, Int>, String>? {
        if (!isArabicCached()) return null
        return try {
            QuranJsonParser.parseArabicTexts(arabicCacheFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A stamp identifying the CURRENT English cache file contents (size and
     * mtime combined). Callers use it to know when a previously parsed list is
     * stale (e.g. the file was (re)downloaded) without re-reading the file.
     */
    fun cacheStamp(): Long {
        return try {
            cacheFile.length() * 100_003L + cacheFile.lastModified()
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * A stamp identifying the CURRENT Arabic cache file contents (size and
     * mtime combined). Callers use it to know when a previously parsed map is
     * stale (e.g. the file was (re)downloaded) without re-reading the file.
     */
    fun arabicCacheStamp(): Long {
        return try {
            arabicCacheFile.length() * 100_003L + arabicCacheFile.lastModified()
        } catch (e: Exception) {
            -1L
        }
    }

    /** Reads + parses the cached translation into verses; null when not cached/corrupt. */
    fun loadVerses(): List<QuranVerse>? {
        if (!isCached()) return null
        return try {
            QuranJsonParser.parse(cacheFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The interval (in hours) between automatic new-verse refreshes.
     * Defaults to 6; the user can pick 1, 2, 3… hours from the verse screen.
     */
    fun getRefreshIntervalHours(): Int =
        prefs.getInt(KEY_REFRESH_INTERVAL_HOURS, DEFAULT_REFRESH_INTERVAL_HOURS)

    /** Persists the user-chosen interval (in hours) between new-verse refreshes. */
    fun setRefreshIntervalHours(hours: Int) {
        prefs.edit().putInt(KEY_REFRESH_INTERVAL_HOURS, hours).apply()
    }

    /** Whether the app posts an OS notification when a new verse is chosen. */
    fun getQuranNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_QURAN_NOTIFICATIONS_ENABLED, DEFAULT_QURAN_NOTIFICATIONS_ENABLED)

    /** Persists the Quran-verse notification toggle. */
    fun setQuranNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QURAN_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    /** Persists the verse currently shown on the widget. */
    fun saveCurrentVerse(verse: QuranVerse) {
        prefs.edit()
            .putInt(KEY_SURAH_NUMBER, verse.surahNumber)
            .putInt(KEY_AYAH_NUMBER, verse.ayahNumber)
            .putString(KEY_SURAH_NAME, verse.surahName)
            .putString(KEY_SURAH_TRANSLATION, verse.surahTranslation)
            .putString(KEY_VERSE_TEXT, verse.text)
            .putString(KEY_ARABIC_TEXT, verse.arabicText)
            .putInt(KEY_TOTAL_AYAHS, verse.totalAyahs)
            .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
            .apply()
    }

    /** The currently displayed verse, or null before the first download. */
    fun readCurrentVerse(): QuranVerse? {
        val text = prefs.getString(KEY_VERSE_TEXT, null) ?: return null
        val surahNumber = prefs.getInt(KEY_SURAH_NUMBER, 0)
        val ayahNumber = prefs.getInt(KEY_AYAH_NUMBER, 0)
        if (surahNumber <= 0 || ayahNumber <= 0) return null
        return QuranVerse(
            surahNumber = surahNumber,
            ayahNumber = ayahNumber,
            surahName = prefs.getString(KEY_SURAH_NAME, "") ?: "",
            surahTranslation = prefs.getString(KEY_SURAH_TRANSLATION, "") ?: "",
            text = text,
            arabicText = prefs.getString(KEY_ARABIC_TEXT, "") ?: "",
            // 0 on verses persisted by a build that predates the surah counts;
            // the repository backfills it from the cached edition.
            totalAyahs = prefs.getInt(KEY_TOTAL_AYAHS, 0)
        )
    }

    // ── Bookmarks ────────────────────────────────────────────────────

    /** Bookmarks are stored as "surahNumber:ayahNumber" references. */
    fun getBookmarks(): Set<String> =
        prefs.getStringSet(KEY_BOOKMARKS, emptySet()) ?: emptySet()

    fun isBookmarked(surahNumber: Int, ayahNumber: Int): Boolean =
        "$surahNumber:$ayahNumber" in getBookmarks()

    /** Toggles a bookmark. Returns true when it is now bookmarked. */
    fun toggleBookmark(surahNumber: Int, ayahNumber: Int): Boolean {
        val key = "$surahNumber:$ayahNumber"
        val current = getBookmarks().toMutableSet()
        val added = if (key in current) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        prefs.edit().putStringSet(KEY_BOOKMARKS, current).apply()
        return added
    }

    /** Removes the bookmark for a verse (no-op when not bookmarked). */
    fun removeBookmark(surahNumber: Int, ayahNumber: Int) {
        val key = "$surahNumber:$ayahNumber"
        val current = getBookmarks().toMutableSet()
        if (current.remove(key)) {
            prefs.edit().putStringSet(KEY_BOOKMARKS, current).apply()
        }
    }

    // ── Starred surahs ───────────────────────────────────────────────

    /**
     * Starred surahs, stored as the surah number alone.
     *
     * A second set rather than a flag on the verse keys: "2:255" and "2" are
     * two different intents — a passage to come back to, and a whole surah to
     * come back to — and one collection holding both would make starring a surah
     * turn up in the verse list as a row for a verse nobody starred.
     */
    fun getSurahBookmarks(): Set<String> =
        prefs.getStringSet(KEY_SURAH_BOOKMARKS, emptySet()) ?: emptySet()

    fun isSurahBookmarked(surahNumber: Int): Boolean =
        surahNumber.toString() in getSurahBookmarks()

    /** Toggles a surah star. Returns true when it is now starred. */
    fun toggleSurahBookmark(surahNumber: Int): Boolean {
        val key = surahNumber.toString()
        val current = getSurahBookmarks().toMutableSet()
        val added = if (key in current) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        prefs.edit().putStringSet(KEY_SURAH_BOOKMARKS, current).apply()
        return added
    }

    /** Removes a surah star (no-op when not starred). */
    fun removeSurahBookmark(surahNumber: Int) {
        val key = surahNumber.toString()
        val current = getSurahBookmarks().toMutableSet()
        if (current.remove(key)) {
            prefs.edit().putStringSet(KEY_SURAH_BOOKMARKS, current).apply()
        }
    }

    // ── Where the reader left off ────────────────────────────────────

    /**
     * One "surah:ayah" per surah: the ayah that was at the top of the page.
     *
     * The same shape the bookmarks use, and one set rather than a key per surah,
     * because a position is a single fact about a surah — writing a new one
     * replaces the old, so prefs holds at most 114 entries however long the app
     * is used, and [setReadingPosition] can clear by surah without enumerating
     * anything.
     */
    fun getReadingPositions(): Set<String> =
        prefs.getStringSet(KEY_READING_POSITIONS, emptySet()) ?: emptySet()

    /** The ayah last left at the top of [surahNumber], or null. */
    fun getReadingPosition(surahNumber: Int): Int? =
        readingPositionOf(getReadingPositions(), surahNumber)

    /** Remembers the ayah at the top of [surahNumber]. */
    fun setReadingPosition(surahNumber: Int, ayahNumber: Int) {
        val updated = withReadingPosition(getReadingPositions(), surahNumber, ayahNumber)
        prefs.edit().putStringSet(KEY_READING_POSITIONS, updated).apply()
    }

    private companion object {
        const val PREFS_NAME = "quran_reminder_prefs"
        // The Clear Quran (Mustafa Khattab) English translation.
        const val CACHE_FILE_NAME = "quran_en_clear.json"
        // IndoPak Arabic script (v2 suffix: a previous build cached Arabic
        // under the plain "quran_ar_indopak.json" name, so the versioned name
        // forces a fresh download of the correct script).
        const val AR_CACHE_FILE_NAME = "quran_ar_indopak_v2.json"
        // Cache file names from older builds (deleted on first run of the new
        // build so stale/wrong content can't be mistaken for the current
        // edition).
        val OLD_CACHE_FILE_NAMES = listOf(
            "quran_en_sahih.json",
            "quran_ar_uthmani.json",
            "quran_en_khattab.json",
            "quran_ar_indopak.json"
        )

        const val KEY_SURAH_NUMBER = "current_surah_number"
        const val KEY_AYAH_NUMBER = "current_ayah_number"
        const val KEY_SURAH_NAME = "current_surah_name"
        const val KEY_SURAH_TRANSLATION = "current_surah_translation"
        const val KEY_VERSE_TEXT = "current_verse_text"
        const val KEY_ARABIC_TEXT = "current_verse_arabic_text"
        const val KEY_TOTAL_AYAHS = "current_verse_total_ayahs"
        const val KEY_LAST_UPDATED = "current_verse_updated_at"
        const val KEY_REFRESH_INTERVAL_HOURS = "refresh_interval_hours"
        const val KEY_QURAN_NOTIFICATIONS_ENABLED = "quran_notifications_enabled"
        const val KEY_BOOKMARKS = "bookmarked_verses"
        const val KEY_SURAH_BOOKMARKS = "bookmarked_surahs"
        // Deliberately NOT cleared by the old-edition migration above: where a
        // reader stopped is about the reader, not about which translation was on
        // disk when they stopped.
        const val KEY_READING_POSITIONS = "reading_positions"
        const val DEFAULT_REFRESH_INTERVAL_HOURS = 6
        const val DEFAULT_QURAN_NOTIFICATIONS_ENABLED = true
    }
}

/**
 * The ayah recorded for [surahNumber] in a reading-position set, or null.
 *
 * Top-level and pure so the rule can be tested without a Context, the way
 * [DhikrCodec] is: anything in this store that is a RULE rather than a prefs call
 * belongs outside the prefs call.
 *
 * The colon is what keeps surah 1 from reading surah 11's entry — "11:5" does not
 * start with "1:" — so the separator is load-bearing, not decorative.
 */
internal fun readingPositionOf(positions: Set<String>, surahNumber: Int): Int? {
    val prefix = "$surahNumber:"
    return positions.firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.toIntOrNull()
}

/**
 * [positions] with [surahNumber]'s entry REPLACED by [ayahNumber] — or removed
 * entirely when that ayah is the first one.
 *
 * Ayah 1 clears rather than stores. The first ayah is the top of the surah, so
 * reopening there needs no scroll; keeping an entry for it would spell "the
 * beginning" the same way as "never opened", and the reader would have to scroll
 * to the top on every open to find out which it was. It is also the only reason
 * this set stays as small as it is — otherwise every reader who scrolled back up
 * would leave an entry behind.
 *
 * Returning a NEW set rather than editing one: [QuranStore.getReadingPositions]
 * hands back the set SharedPreferences is holding, so mutating it in place would
 * be writing to the prefs' own instance behind their back.
 */
internal fun withReadingPosition(
    positions: Set<String>,
    surahNumber: Int,
    ayahNumber: Int
): Set<String> {
    val prefix = "$surahNumber:"
    val updated = positions.filterNotTo(mutableSetOf()) { it.startsWith(prefix) }
    if (ayahNumber > 1) updated.add("$prefix$ayahNumber")
    return updated
}
