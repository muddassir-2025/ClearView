package com.muddassir.clearview.quran.data

import android.content.Context
import com.muddassir.clearview.quran.model.QuranVerse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single entry point for Quran verse data.
 *
 * Responsibilities:
 *  - Download the full translation once and cache it locally.
 *  - Select a random verse from the cache and persist it as the current verse.
 *  - Read the current verse instantly for widget/detail-screen rendering.
 *
 * All file/network IO happens on [Dispatchers.IO]; only [getCurrentVerse]
 * (SharedPreferences read) is safe to call from the main thread.
 */
class QuranRepository(context: Context) {

    private val store = QuranStore(context.applicationContext)

    /** True when the full translation is already cached locally. */
    suspend fun isCached(): Boolean = withContext(Dispatchers.IO) { store.isCached() }

    /**
     * Downloads + caches the full translation. No-op when a VALID cache exists
     * (valid = the cached JSON actually parses, so a truncated/corrupt file is
     * re-downloaded instead of blocking the widget on a forever-loading state).
     * @return true when a usable cache is present afterwards.
     */
    suspend fun downloadIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val cached = store.loadVerses()
        if (cached != null && cached.isNotEmpty()) {
            true
        } else {
            val json = QuranApi.download()
            if (json != null) {
                store.saveJson(json)
                true
            } else {
                false
            }
        }
    }

    /**
     * Ensures BOTH editions are cached: English (required — the verse text
     * depends on it) and Arabic Uthmani (supplementary — the verse screen shows
     * Arabic when available, but English-only still works offline). The Arabic
     * fetch is best-effort: a failure must not fail the worker, since the
     * reminder remains fully functional in English.
     * @return true when the English cache is usable afterwards.
     */
    suspend fun ensureEnglishAndArabic(): Boolean = withContext(Dispatchers.IO) {
        val englishOk = downloadIfNeeded()
        if (englishOk && !store.isArabicCached()) {
            val json = QuranApi.downloadArabic()
            if (json != null) store.saveArabicJson(json)
        }
        englishOk
    }

    /**
     * Process-wide cache of the parsed Arabic lookup so repeated verse picks
     * in the same process don't re-parse the ~8MB file on every repository
     * instance. Stamped against the cache file so a later (re)download of the
     * Arabic edition is picked up. Only ever accessed from background threads.
     */
    private fun arabicTexts(): Map<Pair<Int, Int>, String>? {
        val stamp = store.arabicCacheStamp()
        if (arabicTextsStamp != stamp) {
            ArabicTextsCache = store.loadArabicTexts()
            arabicTextsStamp = stamp
        }
        return ArabicTextsCache
    }

    /**
     * Process-wide cache of the parsed English verses (the flat Quran-ordered
     * list), so repeated picks AND prev/next navigation don't re-parse the
     * cache file on every call. Stamped against the cache file so a later
     * (re)download is picked up. Only ever accessed from background threads.
     */
    private fun englishVerses(): List<QuranVerse>? {
        val stamp = store.cacheStamp()
        if (versesStamp != stamp) {
            EnglishVersesCache = store.loadVerses()
            versesStamp = stamp
        }
        return EnglishVersesCache
    }

    /**
     * Process-wide cache of per-surah ayah counts, derived from the parsed
     * English edition (e.g. Al-Faatiha → 7, Al-Baqara → 286). Stamped against
     * the cache file, same as [englishVerses]. Only used on background threads.
     */
    private fun surahCounts(): Map<Int, Int>? {
        val stamp = store.cacheStamp()
        if (surahCountsStamp != stamp) {
            SurahCountsCache = englishVerses()?.let { QuranJsonParser.surahAyahCounts(it) }
            surahCountsStamp = stamp
        }
        return SurahCountsCache
    }

    /**
     * Fills in [QuranVerse.totalAyahs] for a verse loaded from persistence that
     * predates the surah counts, re-saving it so the reader + widget show the
     * "ayah / total" progress instantly afterwards. No-op (returns the verse
     * unchanged) when the count is already known or the cache is missing.
     */
    suspend fun backfillCurrentVerseTotals(): QuranVerse? = withContext(Dispatchers.IO) {
        val current = store.readCurrentVerse() ?: return@withContext null
        if (current.totalAyahs > 0) return@withContext current
        val total = surahCounts()?.get(current.surahNumber) ?: return@withContext current
        val updated = current.copy(totalAyahs = total)
        store.saveCurrentVerse(updated)
        updated
    }

    /**
     * Picks a random verse from the cached translation, persists it as the
     * current verse and returns it. Avoids repeating the verse that is
     * currently displayed (when there is more than one choice). Returns null
     * only when no data is available (offline first run).
     */
    suspend fun pickRandomVerse(): QuranVerse? = withContext(Dispatchers.IO) {
        val verses = englishVerses() ?: return@withContext null
        if (verses.isEmpty()) return@withContext null

        val current = store.readCurrentVerse()
        var verse = verses.random()
        var attempts = 0
        // Don't show the exact same verse twice in a row when there is choice.
        while (verse == current && attempts < 5 && verses.size > 1) {
            verse = verses.random()
            attempts++
        }

        // Attach the Arabic text + surah ayah count when available (best-effort;
        // stays empty on English-only installs).
        val enriched = enrich(verse)

        store.saveCurrentVerse(enriched)
        enriched
    }

    /** The currently displayed verse (instant; null before first download). */
    fun getCurrentVerse(): QuranVerse? = store.readCurrentVerse()

    /** Persists [verse] as the currently displayed verse (instant). */
    fun saveCurrentVerse(verse: QuranVerse) = store.saveCurrentVerse(verse)

    /**
     * Returns the verse [step] positions away (+1 = next ayah, -1 = previous
     * ayah) from (surahNumber, ayahNumber). The flat cache is Quran-ordered,
     * so surah boundaries wrap naturally: previous of 2:1 is 1:286, next of
     * 1:7 is 2:1, etc. The result is enriched with Arabic text when available
     * and persisted as the current verse. Null at the very first/last verse of
     * the Quran, or when no data is cached yet.
     */
    suspend fun getAdjacentVerse(surahNumber: Int, ayahNumber: Int, step: Int): QuranVerse? =
        withContext(Dispatchers.IO) {
            val verses = englishVerses() ?: return@withContext null
            if (verses.isEmpty()) return@withContext null
            val index = verses.indexOfFirst {
                it.surahNumber == surahNumber && it.ayahNumber == ayahNumber
            }
            if (index < 0) return@withContext null
            val targetIndex = index + step
            if (targetIndex !in verses.indices) return@withContext null

            val raw = verses[targetIndex]
            val enriched = enrich(raw)
            store.saveCurrentVerse(enriched)
            enriched
        }

    /**
     * Whether a verse exists [step] positions away (+1 / -1) from
     * (surahNumber, ayahNumber) — used to disable the Previous/Next buttons at
     * the start and end of the Quran.
     */
    suspend fun hasAdjacentVerse(surahNumber: Int, ayahNumber: Int, step: Int): Boolean =
        withContext(Dispatchers.IO) {
            val verses = englishVerses() ?: return@withContext false
            val index = verses.indexOfFirst {
                it.surahNumber == surahNumber && it.ayahNumber == ayahNumber
            }
            index >= 0 && (index + step) in verses.indices
        }

    /** User-chosen interval (hours) between automatic new-verse refreshes. */
    fun getRefreshIntervalHours(): Int = store.getRefreshIntervalHours()

    /** Sets + persists the interval (hours) between automatic new-verse refreshes. */
    fun setRefreshIntervalHours(hours: Int) = store.setRefreshIntervalHours(hours)

    /** Whether the app posts an OS notification when a new verse is chosen. */
    fun getQuranNotificationsEnabled(): Boolean = store.getQuranNotificationsEnabled()

    /** Persists the Quran-verse notification toggle. */
    fun setQuranNotificationsEnabled(enabled: Boolean) = store.setQuranNotificationsEnabled(enabled)

    // ── Search ───────────────────────────────────────────────────────

    /**
     * Searches the cached English translation for [query] (case-insensitive,
     * substring match on the translation text, surah name and reference).
     *
     * The query can also be a reference:
     *  - "2:255" / "2 255" / "2.255"  → that exact surah:ayah
     *  - "255" (a plain number)        → every verse numbered 255 (any surah)
     *
     * Results are enriched with Arabic text when the Arabic edition is cached.
     * Returns at most [limit] verses (the flat list is Quran-ordered, so the
     * matches come back in order). Empty when nothing is cached yet.
     */
    suspend fun searchVerses(query: String, limit: Int = 200): List<QuranVerse> =
        withContext(Dispatchers.IO) {
            val verses = englishVerses() ?: return@withContext emptyList()
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()

            val lower = q.lowercase()
            // "2:255", "2 255" or "2.255" → exact reference.
            val ref = verseReference(q)
            val refSurah = ref?.first
            val refAyah = ref?.second
            val plainNumber = q.toIntOrNull()

            val matches = ArrayList<QuranVerse>(minOf(limit, 64))
            for (v in verses) {
                if (matches.size >= limit) break
                val hit = when {
                    refSurah != null && refAyah != null ->
                        v.surahNumber == refSurah && v.ayahNumber == refAyah
                    plainNumber != null ->
                        v.surahNumber == plainNumber || v.ayahNumber == plainNumber
                    else ->
                        v.text.lowercase().contains(lower) ||
                            v.surahName.lowercase().contains(lower) ||
                            "${v.surahNumber}:${v.ayahNumber}".contains(lower)
                }
                if (hit) matches.add(enrich(v))
            }
            matches
        }

    /**
     * Every verse of one surah, in order, for the continuous reader (§2).
     *
     * Read from the cached edition rather than searched for one verse at a time:
     * the translation is already on the device, and a surah is a contiguous run
     * of it, so this is a filter over parsed data — no network, no per-verse
     * request, and no limit to page around. An empty list means only that the
     * edition is not cached yet, which the reader says in words.
     */
    suspend fun getSurahVerses(surahNumber: Int): List<QuranVerse> =
        withContext(Dispatchers.IO) {
            englishVerses()
                ?.filter { it.surahNumber == surahNumber }
                ?.sortedBy { it.ayahNumber }
                ?.map { enrich(it) }
                ?: emptyList()
        }

    // ── Bookmarks ────────────────────────────────────────────────────

    /** Set of "surah:ayah" strings the user has bookmarked. */
    fun getBookmarks(): Set<String> = store.getBookmarks()

    fun isBookmarked(surahNumber: Int, ayahNumber: Int): Boolean =
        store.isBookmarked(surahNumber, ayahNumber)

    /** Toggles the bookmark for a verse. Returns true when now bookmarked. */
    fun toggleBookmark(surahNumber: Int, ayahNumber: Int): Boolean =
        store.toggleBookmark(surahNumber, ayahNumber)

    /** Removes the bookmark for a verse. */
    fun removeBookmark(surahNumber: Int, ayahNumber: Int) =
        store.removeBookmark(surahNumber, ayahNumber)

    /**
     * Resolves every saved bookmark into its full verse (enriched with Arabic
     * when cached), in Quran order (surah, then ayah) — deterministic regardless
     * of the underlying prefs set. Empty when none are bookmarked or the
     * translation isn't cached yet.
     */
    suspend fun getBookmarkedVerses(): List<QuranVerse> = withContext(Dispatchers.IO) {
        val verses = englishVerses() ?: return@withContext emptyList()
        val byRef = HashMap<Pair<Int, Int>, QuranVerse>(verses.size)
        for (v in verses) byRef[Pair(v.surahNumber, v.ayahNumber)] = v

        store.getBookmarks().mapNotNull { key ->
            val parts = key.split(":")
            val surah = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val ayah = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            byRef[Pair(surah, ayah)]?.let { enrich(it) }
        }.sortedWith(compareBy<QuranVerse> { it.surahNumber }.thenBy { it.ayahNumber })
    }

    // ── Starred surahs ───────────────────────────────────────────────

    /** Set of surah numbers ("2") the user has starred. */
    fun getSurahBookmarks(): Set<String> = store.getSurahBookmarks()

    fun isSurahBookmarked(surahNumber: Int): Boolean = store.isSurahBookmarked(surahNumber)

    /** Toggles the star for a surah. Returns true when it is now starred. */
    fun toggleSurahBookmark(surahNumber: Int): Boolean = store.toggleSurahBookmark(surahNumber)

    /** Removes the star for a surah. */
    fun removeSurahBookmark(surahNumber: Int) = store.removeSurahBookmark(surahNumber)

    /**
     * Every starred surah, in Quran order.
     *
     * Numbers only, and read straight from prefs: a surah's name and its
     * translation come from [QuranJsonParser], which is bundled, so there is
     * nothing here to wait for and nothing that can fail. The alternative —
     * resolving each marked surah to its verses to prove it exists — would be
     * 114 lookups to answer a question the number already answers.
     */
    fun getBookmarkedSurahs(): List<Int> =
        store.getSurahBookmarks().mapNotNull { it.toIntOrNull() }.sorted()

    /** Enriches [v] with Arabic text and the surah ayah count when cached. */
    private fun enrich(v: QuranVerse): QuranVerse {
        val arabic = if (v.arabicText.isBlank()) {
            arabicTexts()?.get(Pair(v.surahNumber, v.ayahNumber)) ?: ""
        } else {
            v.arabicText
        }
        val total = if (v.totalAyahs > 0) v.totalAyahs else surahCounts()?.get(v.surahNumber) ?: 0
        return if (arabic == v.arabicText && total == v.totalAyahs) {
            v
        } else {
            v.copy(arabicText = arabic, totalAyahs = total)
        }
    }

    private companion object {
        @Volatile
        private var ArabicTextsCache: Map<Pair<Int, Int>, String>? = null
        private var arabicTextsStamp = -1L

        @Volatile
        private var SurahCountsCache: Map<Int, Int>? = null
        private var surahCountsStamp = -1L

        @Volatile
        private var EnglishVersesCache: List<QuranVerse>? = null
        private var versesStamp = -1L
    }
}

// "2:255", "2 255" or "2.255" → a surah and an ayah.
private val VERSE_REFERENCE = Regex("""^\s*(\d+)\s*[:.\s]\s*(\d+)\s*$""")

/**
 * The surah and ayah a term names, or null when it is not a reference.
 *
 * One parser for the forms the app accepts, shared by the two places that take a
 * reference from a reader: the Quran search, where it means "that verse anywhere",
 * and the continuous surah reader, where it means "that verse, if this is its
 * surah". Two copies of this regex would drift — whichever was edited first would
 * leave the other refusing a form the rest of the app accepts.
 *
 * The pattern is anchored, so `2:255` is a reference while `surah 2:255` is a term
 * to search for, which is what keeps a reader typing words from being read as
 * coordinates. Digits that overflow an Int fall through as a term rather than
 * crashing, which is the same answer a term gets.
 */
internal fun verseReference(query: String): Pair<Int, Int>? {
    val match = VERSE_REFERENCE.find(query.trim()) ?: return null
    val surah = match.groupValues[1].toIntOrNull() ?: return null
    val ayah = match.groupValues[2].toIntOrNull() ?: return null
    return surah to ayah
}
