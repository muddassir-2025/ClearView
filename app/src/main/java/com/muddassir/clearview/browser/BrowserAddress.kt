package com.muddassir.clearview.browser

import java.net.URLEncoder

/**
 * Pure address-bar parsing for the Browser tab.
 *
 * Kept free of Android types so the rules can be unit-tested: what a typed
 * string turns into (a URL or a search) decides where the WebView goes, and a
 * wrong guess is not something to discover only on a device.
 */
object BrowserAddress {

    /** The empty-page URL shown before the first navigation. */
    const val ABOUT_BLANK = "about:blank"

    /** Search engine used for address-bar text that is not a URL. */
    private const val SEARCH_BASE = "https://www.google.com/search?q="

    private val HOST = Regex(
        "^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+" +
            "[A-Za-z]{2,63}\\.?$"
    )

    /**
     * Turn what the reader typed into a URL to load.
     *
     *  - an explicit http(s) URL is used unchanged;
     *  - a bare host ("youtube.com", "x.com/foo") gets an https scheme;
     *  - anything else is a search query.
     *
     * A query is percent-encoded, so "c++ & java" cannot smuggle extra URL
     * parameters into the search request.
     */
    fun toUrl(input: String?): String? {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            return trimmed
        }
        return if (looksLikeHost(trimmed)) {
            "https://$trimmed"
        } else {
            SEARCH_BASE + encode(trimmed)
        }
    }

    /**
     * True when the text starts with something that is shaped like a host.
     *
     * Deliberately requires a dot and a letters-only TLD: "how to cook" and
     * "hello.world how" must stay searches, while "youtube.com/watch?v=1"
     * (no scheme) is a URL. A port or path is allowed after the host.
     */
    fun looksLikeHost(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { it.isWhitespace() }) return false
        val authority = trimmed
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isEmpty()) return false
        // Drop userinfo and port; IPv6 literals are not supported as bare input
        // (they must be typed with a scheme), which keeps the parser simple.
        val hostAndPort = if (authority.contains('@')) {
            authority.substringAfterLast('@')
        } else {
            authority
        }
        val host = hostAndPort.substringBefore(':')
        return HOST.matches(host)
    }

    /**
     * Force SafeSearch on the search engines the browser can reach.
     *
     * This is a browser policy, not a preference: every Google/Bing/DuckDuckGo
     * search the reader starts in the Browser tab gets the strictest setting
     * the engine exposes. Only search URLs are touched; ordinary pages pass
     * through unchanged. Existing parameters are replaced, never duplicated.
     */
    fun withSafeSearch(url: String?): String? {
        if (url == null || !isWebUrl(url)) return url
        val bareHost = host(url) ?: return url
        return when {
            isGoogleSearchUrl(url) -> setQueryParam(url, "safe", "active")
            (bareHost == "bing.com" || bareHost.endsWith(".bing.com")) &&
                url.contains("/search", ignoreCase = true) ->
                setQueryParam(url, "adlt", "strict")
            bareHost == "duckduckgo.com" || bareHost.endsWith(".duckduckgo.com") ->
                setQueryParam(url, "kp", "1")
            else -> url
        }
    }

    /** Replace or append one query parameter without disturbing the rest. */
    private fun setQueryParam(url: String, name: String, value: String): String {
        val hashIndex = url.indexOf('#')
        val base = if (hashIndex >= 0) url.substring(0, hashIndex) else url
        val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""
        val queryIndex = base.indexOf('?')
        val prefix = if (queryIndex >= 0) base.substring(0, queryIndex) else base
        val query = if (queryIndex >= 0) base.substring(queryIndex + 1) else ""
        val params = if (query.isEmpty()) mutableListOf() else query.split('&').toMutableList()
        val existing = params.indexOfFirst {
            it.substringBefore('=').equals(name, ignoreCase = true)
        }
        val pair = "$name=$value"
        if (existing >= 0) params[existing] = pair else params.add(pair)
        return prefix + "?" + params.joinToString("&") + fragment
    }

    /** True for a URL the WebView may load in-app. */
    fun isWebUrl(url: String?): Boolean =
        url != null && (url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true))

    /** Host of a URL, lowercased and without a trailing dot, or null. */
    fun host(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val candidate = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://$url"
        }
        return try {
            java.net.URI(candidate).host
                ?.lowercase()
                ?.removeSuffix(".")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** True when [url] is youtube.com or a subdomain of it. */
    fun isYouTube(url: String?): Boolean = hostIs(url, "youtube.com")

    /** True when [url] is google.com or a country/regional Google host. */
    fun isGoogle(url: String?): Boolean {
        val bare = host(url)?.removePrefix("www.") ?: return false
        return bare == "google.com" ||
            bare.matches(Regex("google\\.[a-z]{2,3}(?:\\.[a-z]{2})?"))
    }

    /** True when [url] is instagram.com or a subdomain of it. */
    fun isInstagram(url: String?): Boolean = hostIs(url, "instagram.com")

    /** A Google results page (`google.com/search?...`). */
    fun isGoogleSearchUrl(url: String?): Boolean {
        if (!isGoogle(url)) return false
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        return path.contains("/search")
    }

    /** A YouTube results page (`/results?...` or the legacy `/search?...`). */
    fun isYouTubeSearchUrl(url: String?): Boolean {
        if (!isYouTube(url)) return false
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        return path.contains("/results") || path.contains("/search")
    }

    /**
     * An Instagram Reels URL. Both `/reels/<id>/` (the shared link) and
     * `/reel/<id>/` (the in-feed permalink) are reels; the bare `/reels/`
     * landing page is the scrolling player as well.
     */
    fun isInstagramReelsUrl(url: String?): Boolean {
        if (!isInstagram(url)) return false
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        return path == "/reels" || path.startsWith("/reels/") ||
            path == "/reel" || path.startsWith("/reel/")
    }

    private fun hostIs(url: String?, domain: String): Boolean {
        val host = host(url) ?: return false
        return host == domain || host.endsWith(".$domain")
    }

    private fun encode(value: String): String =
        try {
            URLEncoder.encode(value, "UTF-8")
        } catch (e: Exception) {
            // UTF-8 is always available on Android/JVM; kept so a broken
            // charset can never crash the browser.
            value
        }
}
