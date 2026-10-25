package com.muddassir.clearview.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muddassir.clearview.repository.BlockRepository
import org.json.JSONArray
import org.json.JSONObject

/** A shortcut shown on the Browser tab's start page. */
data class BrowserShortcut(val name: String, val url: String)

/**
 * Pure parsing for the "add shortcut" form.
 *
 * Kept outside [BrowserSettings] so it can be unit-tested without Android. A
 * shortcut is a website, not a search: arbitrary text is refused rather than
 * silently becoming a Google query, because a shortcut labelled "News" that
 * opens a search page is not the shortcut the reader thought they made.
 */
object BrowserShortcutInput {

    /** A validated https URL for [input], or null when it is not a website. */
    fun normalize(input: String?): String? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (BrowserAddress.isWebUrl(trimmed)) {
            return trimmed.takeIf { BrowserAddress.host(it) != null }
        }
        if (!BrowserAddress.looksLikeHost(trimmed)) return null
        return runCatching { "https://$trimmed" }.getOrNull()
    }

    /** A short, human label for a URL when the reader did not type a name. */
    fun nameFor(url: String): String {
        val host = BrowserAddress.host(url)?.removePrefix("www.") ?: return "Shortcut"
        val base = host.substringBefore('.')
        return base.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Browser-tab protection toggles.
 *
 * Two kinds of setting live here, deliberately:
 *
 *  - The controls ClearView already owns ([strictMode], [blockShorts]) are read
 *    and written through the SAME [BlockRepository] the Protection dashboard
 *    uses. The Browser tab must never grow a second "Block Shorts" flag that
 *    disagrees with the Chrome/extensions policy.
 *  - Controls that only make sense inside an embedded browser (hiding Google's
 *    search categories, hiding Instagram Reels, removing blocked feed cards,
 *    refusing YouTube ad requests) are stored in this tab's own preferences.
 *
 * Every value is Compose state as well as persisted, so a switch updates the
 * open WebView immediately instead of waiting for the next app launch.
 */
class BrowserSettings(context: Context) {

    companion object {
        private const val PREFS = "clearview_browser_prefs"

        private const val KEY_INSTAGRAM_REELS = "hide_instagram_reels"
        private const val KEY_INSTAGRAM_SEARCH = "hide_instagram_search"
        private const val KEY_INSTAGRAM_SUGGESTIONS = "hide_instagram_suggestions"
        private const val KEY_INSTAGRAM_HOME = "hide_instagram_home"
        private const val KEY_GOOGLE_CATEGORIES = "hide_google_categories"
        private const val KEY_REMOVE_BLOCKED = "remove_blocked_from_feed"
        private const val KEY_BLOCK_YOUTUBE_ADS = "block_youtube_ads"
        private const val KEY_DARK_PAGES = "dark_pages"
        private const val KEY_SHORTCUTS = "shortcuts"
        private const val KEY_REMOVED_BUILT_INS = "removed_builtin_shortcuts"

        /** The shortcuts ClearView ships on the start page. */
        val BUILT_IN_SHORTCUTS: List<BrowserShortcut> = listOf(
            BrowserShortcut("Google", "https://www.google.com/"),
            BrowserShortcut("YouTube", "https://www.youtube.com/"),
            BrowserShortcut("Instagram", "https://www.instagram.com/")
        )

        /** A cap that keeps the start page usable; the + button says so. */
        const val MAX_SHORTCUTS = 12
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val blockRepository = BlockRepository(context.applicationContext)

    // ── Shared with the app's Protection dashboard ────────────────

    var strictMode by mutableStateOf(blockRepository.isStrictMode)
        private set

    var blockShorts by mutableStateOf(blockRepository.blockShorts)
        private set

    // ── Browser-only protection ───────────────────────────────────

    /**
     * Instagram Reels are a separate surface from YouTube Shorts: the URL shape
     * and DOM are unrelated, so they get their own switch. On by default, like
     * the ad blocker — both only ever remove something the reader did not ask
     * to see.
     */
    var hideInstagramReels by mutableStateOf(prefs.getBoolean(KEY_INSTAGRAM_REELS, true))
        private set

    /**
     * Hide Instagram's search icon. Explore is the second endless
     * recommendation feed on the site, so leaving the magnifier in place made
     * "hide Reels" a one-tap detour. Its own switch, because a reader may want
     * to search Instagram without the Reels surface.
     */
    var hideInstagramSearch by mutableStateOf(prefs.getBoolean(KEY_INSTAGRAM_SEARCH, true))
        private set

    /**
     * Hide "Suggested for you" units on the Instagram home feed.
     *
     * They are the only part of the feed the reader never chose to follow, so
     * they are where new channels come from — the opposite of what a blocking
     * app should feed.
     */
    var hideInstagramSuggestions by mutableStateOf(prefs.getBoolean(KEY_INSTAGRAM_SUGGESTIONS, true))
        private set

    /**
     * Hide Instagram's Home tab altogether.
     *
     * The home feed is the one surface a blocking browser cannot make safe by
     * filtering: it is an endless, server-chosen list, and the reader can
     * always pull another screen of it. Off by default — hiding the front door
     * of a site is a decision, not a default — but when it is on, the tab and
     * its URL are both gone (a hidden tab that a typed address can still open
     * is not hidden).
     */
    var hideInstagramHome by mutableStateOf(prefs.getBoolean(KEY_INSTAGRAM_HOME, false))
        private set

    /** Hide every Google result category except "All" (Images, Videos, News...). */
    var hideGoogleCategories by mutableStateOf(prefs.getBoolean(KEY_GOOGLE_CATEGORIES, true))
        private set

    /**
     * Remove a blocked YouTube feed card instead of only covering it. The
     * extension supports both; removal is the default here because a feed is a
     * stream of recommendations, and a covered card still takes its cell.
     */
    var removeBlockedFromFeed by mutableStateOf(prefs.getBoolean(KEY_REMOVE_BLOCKED, true))
        private set

    /** Refuse YouTube ad requests and chase off video ads (extension parity). */
    var blockYouTubeAds by mutableStateOf(prefs.getBoolean(KEY_BLOCK_YOUTUBE_ADS, true))
        private set

    /**
     * Render websites that ship no dark theme in dark colours.
     *
     * On by default: ClearView itself is a dark app, and a white page in the
     * middle of it is the one surface the reader cannot dim. Sites that already
     * support a dark theme keep their own colours — the WebView's darkening is
     * only applied when the page asks for a light scheme, so a site's designed
     * dark mode is never inverted into something unreadable.
     */
    var darkPages by mutableStateOf(prefs.getBoolean(KEY_DARK_PAGES, true))
        private set

    /** The reader's own shortcuts, in the order they were added. */
    var shortcuts by mutableStateOf(loadShortcuts())
        private set

    /**
     * URLs of ClearView's own shortcuts the reader removed from the start page.
     *
     * The built-ins used to be fixed furniture. They are now removable like any
     * shortcut, and the removal is a preference, not a deletion of the site: no
     * bookmark, history or session is touched — the tile simply is not drawn.
     */
    var removedBuiltIns by mutableStateOf(loadRemovedBuiltIns())
        private set

    /** The built-in shortcuts still on the start page, in their shipped order. */
    val visibleBuiltInShortcuts: List<BrowserShortcut>
        get() = BUILT_IN_SHORTCUTS.filterNot { it.url in removedBuiltIns }

    /** True when [shortcut] is one of ClearView's own (not reader-added). */
    fun isBuiltInShortcut(shortcut: BrowserShortcut): Boolean =
        BUILT_IN_SHORTCUTS.any { it.url == shortcut.url }

    /**
     * Removes a built-in shortcut from the start page (persisted). Only the
     * tile goes: the website, its cookies and the browser's history are
     * untouched, so the reader can still reach the site by address.
     */
    fun removeBuiltInShortcut(shortcut: BrowserShortcut) {
        if (!isBuiltInShortcut(shortcut) || shortcut.url in removedBuiltIns) return
        val next = removedBuiltIns + shortcut.url
        removedBuiltIns = next
        prefs.edit().putStringSet(KEY_REMOVED_BUILT_INS, next).apply()
    }

    /**
     * Pick up a change made in the Protection dashboard while this session was
     * alive. SharedPreferences is the source of truth, so the browser asks it
     * again whenever its settings screen opens.
     */
    fun refreshFromApp() {
        strictMode = blockRepository.isStrictMode
        blockShorts = blockRepository.blockShorts
    }

    fun applyStrictMode(enabled: Boolean) {
        if (strictMode == enabled) return
        blockRepository.isStrictMode = enabled
        strictMode = enabled
    }

    fun applyBlockShorts(enabled: Boolean) {
        if (blockShorts == enabled) return
        blockRepository.blockShorts = enabled
        blockShorts = enabled
    }

    fun applyHideInstagramReels(enabled: Boolean) {
        if (hideInstagramReels == enabled) return
        prefs.edit().putBoolean(KEY_INSTAGRAM_REELS, enabled).apply()
        hideInstagramReels = enabled
    }

    fun applyHideInstagramSearch(enabled: Boolean) {
        if (hideInstagramSearch == enabled) return
        prefs.edit().putBoolean(KEY_INSTAGRAM_SEARCH, enabled).apply()
        hideInstagramSearch = enabled
    }

    fun applyHideInstagramSuggestions(enabled: Boolean) {
        if (hideInstagramSuggestions == enabled) return
        prefs.edit().putBoolean(KEY_INSTAGRAM_SUGGESTIONS, enabled).apply()
        hideInstagramSuggestions = enabled
    }

    fun applyHideInstagramHome(enabled: Boolean) {
        if (hideInstagramHome == enabled) return
        prefs.edit().putBoolean(KEY_INSTAGRAM_HOME, enabled).apply()
        hideInstagramHome = enabled
    }

    fun applyHideGoogleCategories(enabled: Boolean) {
        if (hideGoogleCategories == enabled) return
        prefs.edit().putBoolean(KEY_GOOGLE_CATEGORIES, enabled).apply()
        hideGoogleCategories = enabled
    }

    fun applyRemoveBlockedFromFeed(enabled: Boolean) {
        if (removeBlockedFromFeed == enabled) return
        prefs.edit().putBoolean(KEY_REMOVE_BLOCKED, enabled).apply()
        removeBlockedFromFeed = enabled
    }

    fun applyBlockYouTubeAds(enabled: Boolean) {
        if (blockYouTubeAds == enabled) return
        prefs.edit().putBoolean(KEY_BLOCK_YOUTUBE_ADS, enabled).apply()
        blockYouTubeAds = enabled
    }

    fun applyDarkPages(enabled: Boolean) {
        if (darkPages == enabled) return
        prefs.edit().putBoolean(KEY_DARK_PAGES, enabled).apply()
        darkPages = enabled
    }

    /** [true] when the shortcut was added; false when the list is full. */
    fun addShortcut(name: String, url: String): Boolean {
        val normalized = BrowserShortcutInput.normalize(url) ?: return false
        if (shortcuts.size >= MAX_SHORTCUTS &&
            shortcuts.none { it.url.equals(normalized, ignoreCase = true) }
        ) {
            return false
        }
        val label = name.trim().ifBlank { BrowserShortcutInput.nameFor(normalized) }
        // Adding the same address again renames the existing shortcut instead of
        // producing two tiles that go to the same place.
        val next = shortcuts
            .filterNot { it.url.equals(normalized, ignoreCase = true) }
            .plus(BrowserShortcut(label.take(24), normalized))
        shortcuts = next
        saveShortcuts(next)
        return true
    }

    fun removeShortcut(shortcut: BrowserShortcut) {
        val next = shortcuts.filterNot { it.url == shortcut.url }
        if (next.size == shortcuts.size) return
        shortcuts = next
        saveShortcuts(next)
    }

    private fun saveShortcuts(items: List<BrowserShortcut>) {
        val array = JSONArray()
        items.forEach { shortcut ->
            array.put(
                JSONObject()
                    .put("name", shortcut.name)
                    .put("url", shortcut.url)
            )
        }
        prefs.edit().putString(KEY_SHORTCUTS, array.toString()).apply()
    }

    private fun loadRemovedBuiltIns(): Set<String> =
        prefs.getStringSet(KEY_REMOVED_BUILT_INS, emptySet())?.toSet() ?: emptySet()

    private fun loadShortcuts(): List<BrowserShortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = BrowserShortcutInput.normalize(item.optString("url")) ?: continue
                    val name = item.optString("name").trim()
                        .ifBlank { BrowserShortcutInput.nameFor(url) }
                    add(BrowserShortcut(name.take(24), url))
                }
            }
        }.getOrDefault(emptyList())
    }
}
