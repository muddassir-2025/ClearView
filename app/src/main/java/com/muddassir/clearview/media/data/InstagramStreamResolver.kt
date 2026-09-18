package com.muddassir.clearview.media.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves direct playable .mp4 video stream URLs for public Instagram posts and Reels
 * completely on-device without login, cookies, or account sessions.
 *
 * Uses Instagram's public embed endpoints (which Meta maintains for public web embedding).
 */
object InstagramStreamResolver {

    private const val TAG = "InstagramStreamResolver"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 12_000

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * A resolved, directly playable stream plus the post's poster (when known).
     * [videoUrl] is a plain progressive `.mp4` on Meta's CDN — exactly what the
     * native MediaPlayer needs.
     */
    data class ResolvedStream(val videoUrl: String, val posterUrl: String?)

    /**
     * The COMPLETE, self-healing resolution path used by the player:
     *
     *  0. what this device already resolved for this shortcode, if the URL it
     *     found is still good ([isStreamUsable]) — see [ResolvedStreamStore],
     *  1. the fast server-rendered HTTP scrape below (still the cheapest when
     *     Meta happens to serve a static embed page), and
     *  2. when that yields nothing — which is now the norm, because the embed
     *     page is client-rendered — [InstagramEmbedResolver], which actually
     *     RENDERS the public embed in a WebView and reads the real `<video>`
     *     src from the resulting DOM.
     *
     * Step zero is why opening a Reel is instant the second time. Steps 1 and 2
     * are seconds of waiting for a FACT that does not change between two taps:
     * a progressive `.mp4` on Meta's CDN, valid for hours and named in its own
     * `oe` parameter. Every resolved answer is written down, so the lookup
     * happens once per Reel per device rather than once per open.
     *
     * [fresh] skips the cache and overwrites it — the Retry path, where the
     * whole point is that the remembered URL is what failed.
     *
     * Returns null only when the post genuinely exposes no playable video
     * (private, deleted, or a still image), so the caller can show a real error
     * state instead of waiting forever.
     */
    suspend fun resolvePlayableStream(
        context: android.content.Context,
        shortcodeOrUrl: String,
        fresh: Boolean = false
    ): ResolvedStream? {
        val shortcode = extractShortcode(shortcodeOrUrl)
        val store = store(context)
        val now = System.currentTimeMillis()

        if (fresh) {
            store.forget(shortcode)
        } else {
            store.get(shortcode)?.let { cached ->
                if (isStreamUsable(cached.stream.videoUrl, cached.atMillis, now)) {
                    Log.d(TAG, "Reusing the stream resolved for $shortcode")
                    return cached.stream
                }
            }
        }

        val httpUrl = resolveStreamUrl(shortcodeOrUrl)
        val resolved = if (httpUrl != null) {
            ResolvedStream(httpUrl, null)
        } else {
            val embedded = InstagramEmbedResolver.resolve(context, shortcodeOrUrl, fresh = fresh)
                ?: return null
            ResolvedStream(embedded.videoUrl, embedded.posterUrl)
        }
        store.put(shortcode, resolved, System.currentTimeMillis())
        return resolved
    }

    /**
     * How long a remembered resolution may be trusted when its URL says nothing
     * about its own expiry.
     *
     * Six hours: Meta signs these URLs for hours, and the two things that can go
     * wrong are both recoverable — the URL's own `oe` parameter is checked first
     * ([isStreamUsable]), and a URL that is dead anyway fails as a playback error
     * whose Retry re-resolves without the cache.
     */
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * How long before a URL's stated expiry it stops being worth handing over.
     *
     * A player handed a URL that is about to lapse buffers for a while and then
     * fails, which is the worst of both: the wait AND the error. Two minutes is
     * longer than a Reel and shorter than a signature's usual life.
     */
    private const val EXPIRY_MARGIN_MS = 2 * 60 * 1000L

    /**
     * The instant a signed CDN URL stops working, from the expiry in its own
     * query string.
     *
     * Two providers, two spellings, and both are read here rather than at each
     * call site because the rule is one rule — a URL that states when it dies
     * is judged on that statement:
     *
     *  - Meta writes `…?oe=68CD1F00&oh=…`, **hexadecimal** seconds.
     *  - Google writes `…&expire=1758300000&…` on YouTube's stream URLs,
     *    **decimal** seconds — and those are the URLs listen mode plays, so
     *    without this the audio cache would fall back to the plain TTL and hand
     *    the player a URL that had already lapsed.
     *
     * Null when neither is present, which is not an error: the plain TTL then
     * decides.
     */
    internal fun expiresAtMillis(url: String): Long? {
        val hex = Regex("[?&]oe=([0-9A-Fa-f]+)").find(url)?.groupValues?.get(1)
        if (hex != null) {
            return hex.toLongOrNull(16)?.let { it * 1000L }
        }
        val decimal = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        return decimal.toLongOrNull()?.let { it * 1000L }
    }

    /**
     * Whether a remembered resolution is still worth playing.
     *
     * Two rules, because the URLs arrive two ways: one that states its expiry is
     * judged on that, and one that does not is judged on how long ago it was
     * resolved. Both are needed — the `oe` in a resolved URL is the only exact
     * signal there is, and a URL from a provider that omits it must not be
     * treated as eternal.
     */
    internal fun isStreamUsable(url: String, resolvedAtMillis: Long, nowMillis: Long): Boolean {
        if (url.isBlank()) return false
        if (nowMillis - resolvedAtMillis > CACHE_TTL_MS) return false
        val expiry = expiresAtMillis(url) ?: return true
        return expiry - nowMillis > EXPIRY_MARGIN_MS
    }

    /**
     * The one store for this process.
     *
     * Built from an application context taken here rather than held as a field
     * of an object that outlives an Activity: this object is a singleton and a
     * Context stored on it is a leak waiting for the first rotation.
     */
    @Volatile
    private var cachedStore: ResolvedStreamStore? = null

    private fun store(context: android.content.Context): ResolvedStreamStore {
        cachedStore?.let { return it }
        return synchronized(this) {
            cachedStore ?: ResolvedStreamStore(context.applicationContext).also { cachedStore = it }
        }
    }

    /**
     * Resolves the direct .mp4 media stream URL for [shortcodeOrUrl].
     * Returns null if the post is an image post or resolution fails.
     *
     * Both the /p/ and /reel/ embed pages are tried, in both their captioned
     * and plain forms: the page that carries the video JSON varies by post
     * type, and older/newer embeds differ in which one they render.
     */
    suspend fun resolveStreamUrl(shortcodeOrUrl: String): String? = withContext(Dispatchers.IO) {
        val shortcode = extractShortcode(shortcodeOrUrl)
        if (shortcode.isBlank()) return@withContext null

        val candidateUrls = listOf(
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            "https://www.instagram.com/p/$shortcode/embed/",
            "https://www.instagram.com/reel/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/embed/"
        )

        for (candidateUrl in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(candidateUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                    findStreamInPayload(html)?.let { clean ->
                        Log.d(TAG, "Resolved stream for $shortcode from $candidateUrl")
                        return@withContext clean
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed resolving stream for $shortcode from $candidateUrl: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }

        null
    }

    /**
     * The payload patterns that carry the post's mp4 URL, most specific first.
     * Public so the resolution rules are unit-testable without network access.
     * Returns the unescaped http(s) .mp4 URL, or null when none is present.
     */
    internal fun findStreamInPayload(html: String): String? {
        if (html.isBlank()) return null
        for (regex in STREAM_PATTERNS) {
            val match = regex.find(html)?.groupValues?.get(1) ?: continue
            val clean = unescapeUrl(match)
            if (clean.startsWith("http") && clean.contains(".mp4", ignoreCase = true)) {
                return clean
            }
        }
        // Last resort: a bare .mp4 CDN link, with no key in front of it.
        val raw = RAW_MP4.find(html)?.value?.let { unescapeUrl(it) }
        if (!raw.isNullOrBlank() && raw.startsWith("http")) return raw
        return null
    }

    private val STREAM_PATTERNS = listOf(
        // Embedded JSON: "video_url":"https:\/\/...mp4..."
        Regex(""""video_url"\s*:\s*"([^"]+)""""),
        // JSON-LD / og metadata: "contentUrl":"...mp4" or og:video content.
        Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
        Regex("""property=["']og:video["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        // video_versions / progressive download entries: "url":"...mp4"
        Regex(""""url"\s*:\s*"([^"]+\.mp4[^"]*)"""", RegexOption.IGNORE_CASE),
        // HTML5 <video src> / <source src>
        Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    )

    // A bare CDN link, with or without the JSON-style `\/` escaping. The
    // backslashes are ALLOWED in the character classes here (the match is
    // unescaped afterwards) — excluding them is what made the old pattern miss
    // every escaped URL.
    private val RAW_MP4 = Regex(
        """https?:(?:\\?/){2}[^\s"'<>]+?\.mp4[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )

    fun extractShortcode(input: String): String {
        val trimmed = input.trim().removePrefix("ig_")
        if (!trimmed.contains("/")) {
            // Already a shortcode (e.g. C_abc123)
            return trimmed.substringBefore('?').substringBefore('#')
        }
        val patterns = listOf(
            Regex("""instagram\.com/(?:p|reel|tv)/([^/?#&]+)"""),
            Regex("""instagr\.am/(?:p|reel|tv)/([^/?#&]+)""")
        )
        for (p in patterns) {
            val match = p.find(trimmed)
            if (match != null) return match.groupValues[1]
        }
        return trimmed.substringAfterLast('/').substringBefore('?').substringBefore('#')
    }

    private fun unescapeUrl(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .trim()
    }

    /**
     * True when [url] is plausibly a PLAYABLE video stream rather than a page
     * or an image. The feed providers occasionally hand out an HTML permalink
     * (e.g. `instagram.com/p/<code>/media?size=l`) in the media slot; handing
     * that to MediaPlayer can only ever fail, so it is rejected here and the
     * real stream is resolved instead.
     */
    fun isPlayableVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("http")) return false
        val lower = url.lowercase()
        if (lower.contains("instagram.com/p/") || lower.contains("instagram.com/reel/")) return false
        return lower.contains(".mp4") || lower.contains(".m4v") ||
            lower.contains(".webm") || lower.contains(".mov") ||
            lower.contains("/video/") || lower.contains("video_url")
    }
}
