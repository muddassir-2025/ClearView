package com.muddassir.clearview.media.data

import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Public X/Twitter feed sources. No X login and no API key is required.
 *
 * X shut the community mirrors down in September 2026 — Nitter and Xcancel both
 * received cease-and-desist notices and the Twitter bridge was removed from the
 * public RSS-Bridge instances — so the endpoint X itself renders into its
 * embedded timeline widget is the only source that still answers without a key.
 *
 * That endpoint returns an HTML page with the posts embedded as JSON, NOT an
 * XML feed. The old code sent every body that started with "<" to the RSS
 * parser, so the syndication response could never produce a single post: every
 * X profile stayed permanently empty no matter how many were added.
 * [parseBody] now routes by the shape of the response instead.
 *
 * Requests are also deliberately sequential and memoised. The endpoint
 * rate-limits per IP and answers 429 "Rate limit exceeded" (which is what a
 * refresh storm of every profile × every mirror produces), so one successful
 * fetch is reused for a short while and the whole source backs off after a 429
 * instead of hammering it again on the next refresh.
 */
object XSource {
    private val RESERVED_PATHS = setOf(
        "home", "explore", "notifications", "messages", "search", "settings",
        "i", "intent", "hashtag", "hashtags", "login", "signup", "compose"
    )

    /**
     * Atom mirrors, kept only as best-effort fallbacks: they usually answer
     * 4xx/5xx now, so they are tried AFTER the primary source and only when it
     * produced nothing.
     */
    private val bridges = listOf(
        "https://rss-bridge.org/bridge01/",
        "https://bridge.suumitsu.eu/"
    )

    /** How long one profile's result is reused before X is asked again. */
    private const val CACHE_TTL_MS = 2 * 60 * 1000L

    /**
     * After a 429 the endpoint is skipped for this long. The limit is per IP,
     * so backing off the source (not just the profile) is what stops a refresh
     * from spending every request on a wall of rejections.
     */
    private const val RATE_LIMIT_BACKOFF_MS = 10 * 60 * 1000L

    private const val HTTP_TOO_MANY_REQUESTS = 429

    /** A realistic browser UA — the endpoints serve the timeline to it. */
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private data class CacheEntry(val atEpochMillis: Long, val videos: List<MediaVideo>)

    private class Response(val code: Int, val body: String)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Avatars resolved from the profile API — one fetch per handle per process. */
    private val avatarCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var endpointBackoffUntil = 0L

    suspend fun fetchProfile(handleOrUrl: String): List<MediaVideo>? = withContext(Dispatchers.IO) {
        val username = extractUsername(handleOrUrl) ?: return@withContext null
        val key = username.lowercase()
        val now = System.currentTimeMillis()
        // Fresh enough: reuse it rather than asking X again (the same profile is
        // fetched by the All Feed, its own channel view and the notification
        // worker — all within the same minute).
        cache[key]?.takeIf { now - it.atEpochMillis < CACHE_TTL_MS }
            ?.let { return@withContext it.videos }
        // Inside a rate-limit window: serve what we already know (or nothing)
        // instead of joining the wall of 429s.
        if (now < endpointBackoffUntil) return@withContext cache[key]?.videos

        val encoded = URLEncoder.encode(username, "UTF-8")
        val candidates = listOf(
            "https://syndication.twitter.com/srv/timeline-profile/screen-name/$encoded"
        ) + bridges.map { bridge ->
            bridge + "?action=display&bridge=TwitterBridge&context=Username" +
                "&u=$encoded&format=Atom"
        }

        // Sequential, first non-empty wins: one request in the normal case
        // instead of the old seven concurrent ones (five of which point at
        // dead nitter/rss-bridge hosts).
        var answered = false
        for (url in candidates) {
            val response = fetch(url) ?: continue
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                endpointBackoffUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                // Surface the throttle: an empty X feed is otherwise
                // indistinguishable from an account with nothing to show.
                MediaSourceStatusStore.markThrottled(MediaPlatform.X, endpointBackoffUntil)
                continue
            }
            answered = true
            val videos = parseBody(response.body, username)
            if (videos.isNotEmpty()) {
                MediaSourceStatusStore.markOk(MediaPlatform.X)
                cache[key] = CacheEntry(now, videos)
                return@withContext videos
            }
        }
        if (answered) {
            // The endpoint answered (the account simply has nothing, or the
            // mirror had no entries) — that is not a failure.
            MediaSourceStatusStore.markOk(MediaPlatform.X)
        } else if (System.currentTimeMillis() >= endpointBackoffUntil) {
            MediaSourceStatusStore.markFailing(MediaPlatform.X)
        }
        // Never fabricate an empty result here — the caller falls back to its
        // own on-disk cache — but hand back a previously fetched page when we
        // have one.
        cache[key]?.videos
    }

    /**
     * The profile's avatar URL (pbs.twimg.com), or null.
     *
     * The syndication timeline page carries `profile_image_url_https`, but it
     * is also the endpoint that rate-limits hardest, so the public FxTwitter
     * profile API is asked first — it answers with the same CDN URL and stays
     * reachable when the timeline does not.
     */
    suspend fun fetchAvatar(handleOrUrl: String): String? = withContext(Dispatchers.IO) {
        val username = extractUsername(handleOrUrl) ?: return@withContext null
        val cacheKey = username.lowercase()
        avatarCache[cacheKey]?.let { return@withContext it }
        val encoded = URLEncoder.encode(username, "UTF-8")
        val candidates = listOf(
            "https://api.fxtwitter.com/$encoded",
            "https://api.vxtwitter.com/$encoded"
        )
        for (url in candidates) {
            val response = fetch(url) ?: continue
            if (response.code != HttpURLConnection.HTTP_OK) continue
            val avatar = extractAvatarUrl(response.body, username) ?: continue
            avatarCache[cacheKey] = avatar
            return@withContext avatar
        }
        null
    }

    /** The avatar URL inside an FxTwitter / vxTwitter profile response. */
    internal fun extractAvatarUrl(body: String, username: String): String? = runCatching {
        val root = JSONObject(body)
        val user = root.optJSONObject("user") ?: root
        user.optString("avatar_url").ifBlank { user.optString("profile_image_url") }
            .takeIf { looksLikeAvatarUrl(it, username) }
    }.getOrNull()

    /** A real twimg avatar (never a placeholder or another account's image). */
    private fun looksLikeAvatarUrl(url: String, username: String): Boolean {
        if (!url.startsWith("http")) return false
        val lower = url.lowercase()
        if (!lower.contains("twimg.com")) return false
        if (lower.contains("default_profile") || lower.contains("sticky/default")) return false
        // The profile image path embeds the numeric id, not the handle, so the
        // handle cannot be checked against the URL — the host and the absence
        // of a default marker are the usable signals.
        return username.isNotBlank()
    }

    fun extractUsername(input: String): String? {
        val value = input.trim()
        val raw = when {
            value.contains("x.com/", ignoreCase = true) ->
                value.substring(value.indexOf("x.com/", ignoreCase = true) + 6)
            value.contains("twitter.com/", ignoreCase = true) ->
                value.substring(value.indexOf("twitter.com/", ignoreCase = true) + 12)
            else -> value.removePrefix("@")
        }
        val username = raw.substringBefore('/').substringBefore('?').substringBefore('#')
        return username.takeIf {
            it.matches(Regex("[A-Za-z0-9_]{1,15}")) && it.lowercase() !in RESERVED_PATHS
        }
    }

    /**
     * Routes a response by its actual shape:
     *  - a JSON body is the syndication JSON variant;
     *  - an XML body that really is a feed goes to the Atom/RSS parser;
     *  - any other "<" body is the syndication profile page (HTML whose posts
     *    are embedded as JSON) — the branch the old code never reached.
     */
    private fun parseBody(body: String, username: String): List<MediaVideo> {
        val trimmed = body.trimStart()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return XFeedParser.parseSyndication(body, username)
        }
        if (trimmed.startsWith("<")) {
            val looksLikeFeed = trimmed.contains("<entry", true) ||
                trimmed.contains("<item", true) ||
                trimmed.contains("<rss", true) ||
                trimmed.contains("<feed", true)
            if (looksLikeFeed) {
                val parsed = XFeedParser.parse(body, username)
                if (parsed.isNotEmpty()) return parsed
            }
            return XFeedParser.parseSyndication(body, username)
        }
        return XFeedParser.parseSyndication(body, username)
    }

    private fun fetch(url: String): Response? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                setRequestProperty(
                    "Accept",
                    "application/json, application/atom+xml, application/rss+xml, text/html, */*"
                )
            }
            val code = connection.responseCode
            // Read the error stream too (a 429 body says why) and always close
            // the connection, so a rejected request never leaks an idle socket.
            val stream = if (code == HttpURLConnection.HTTP_OK) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            Response(code, body)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** Parses Atom/RSS feeds and X's public syndication timeline page. */
object XFeedParser {

    /** How far past a post id its own JSON is scanned (text + media live there). */
    private const val POST_WINDOW = 8_000

    /** How far past a post id to look for evidence that it really is a post. */
    private const val EVIDENCE_WINDOW = 3_000

    /** Markers that only appear on post objects. */
    private val POST_MARKERS = listOf(
        "full_text",
        "tweet_id",
        "data-tweet-id",
        "conversation_id",
        "favorite_count",
        "timeline-Tweet-text",
        "tweet\":{"
    )

    /**
     * Post ids, in the order they appear on the page. The profile's own user
     * object carries an `id_str` too, so a candidate is rejected when its
     * neighbourhood looks like a user rather than a post.
     *
     * `rest_id` and bare `/status/<id>` links only count when the id is in the
     * tweet (snowflake) length range — user ids are much shorter than the 15+
     * digit post ids, so this cannot mistake a profile for a post.
     */
    private val ID_PATTERNS = listOf(
        Regex("""\bid_str\b["']?\s*[:=]\s*["']?(\d{10,25})"""),
        Regex("""\bdata-tweet-id\b["']?\s*[:=]\s*["']?(\d{10,25})"""),
        Regex("""\b(?:tweet_id|tweetId|status_id|statusId)\b["']?\s*[:=]\s*["']?(\d{10,25})"""),
        Regex("""\brest_id\b["']?\s*[:=]\s*["']?(\d{15,25})"""),
        Regex("""/status(?:es)?/(\d{15,25})""")
    )

    /** Post text: the JSON field first, then the server-rendered paragraph. */
    private val TEXT_PATTERNS = listOf(
        Regex("""\bfull_text\b["']?\s*[:=]\s*["']([^"]{1,1000})"""),
        Regex("""timeline-Tweet-text[^>]*>([^<]{1,1000})"""),
        Regex("""\btext\b["']?\s*[:=]\s*["']([^"]{1,1000})""")
    )

    // Media URLs point at the pbs.twimg.com/media (images) and
    // video.twimg.com (playable mp4) hosts. Query separators arrive escaped as
    // \u0026 / &amp; and are normalised by [unescape] before matching.
    private val MEDIA_IMAGE = Regex(
        """https?://pbs\.twimg\.com/(?:media|ext_tw_video_thumb|amplify_video_thumb|tweet_video_thumb)/[^\s"'\\<>]+""",
        RegexOption.IGNORE_CASE
    )
    private val ANY_IMAGE = Regex(
        """https?://[^\s"'\\<>]+\.(?:jpg|jpeg|png|webp|gif)[^\s"'\\<>]*""",
        RegexOption.IGNORE_CASE
    )
    private val VIDEO_URL = Regex(
        """https?://[^\s"'\\<>]+\.(?:mp4|m3u8)[^\s"'\\<>]*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The clip's own length, in milliseconds. X publishes it as
     * `duration_millis` inside the media's `video_info`, immediately before the
     * variant list — so the value that belongs to the chosen URL is the last one
     * seen before it.
     */
    private val DURATION_MS = Regex(""""duration_millis"\s*:\s*(\d{2,9})""")

    /** Parses an Atom/RSS feed body. */
    fun parse(xml: String, username: String): List<MediaVideo> = runCatching {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            try { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
        }
        val document = factory.newDocumentBuilder().parse(
            java.io.ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
        val entries = document.getElementsByTagNameNS("*", "entry").let {
            if (it.length > 0) it else document.getElementsByTagName("item")
        }
        (0 until entries.length).mapNotNull { index ->
            val entry = entries.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
            val id = text(entry, "id") ?: link(entry).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val postLink = link(entry).ifBlank { id }
            val stableId = extractPostId(id, postLink).ifBlank { index.toString() }
            val description = text(entry, "content") ?: text(entry, "summary") ?: text(entry, "description").orEmpty()
            val title = (text(entry, "title") ?: cleanText(description)).trim()
                .ifBlank { "@$username post" }
            val published = parseDate(
                text(entry, "published") ?: text(entry, "updated") ?: text(entry, "pubDate").orEmpty()
            ).takeIf { it > 0L } ?: (System.currentTimeMillis() - index * 3_600_000L)
            item(
                username = username,
                stableId = stableId,
                title = title,
                link = postLink,
                published = published,
                imageUrl = firstImageUrl(entry, description),
                mediaUrl = firstVideoUrl(entry, description),
                bodyText = cleanText(description)
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Handles X's syndication timeline response — an HTML page (or the plain
     * JSON variant) whose posts are embedded as escaped JSON — and the older
     * server-rendered markup (`<div class="timeline-Tweet" data-tweet-id="…">`).
     */
    fun parseSyndication(body: String, username: String): List<MediaVideo> {
        val source = unescape(body)
        val ids = postIds(source)
        if (ids.isEmpty()) return emptyList()
        return ids.mapIndexed { index, (id, position) ->
            val window = source.substring(
                position,
                (position + POST_WINDOW).coerceAtMost(source.length)
            )
            // The full window can run past the end of THIS post into the next
            // one, and a text-only tweet that borrows the following tweet's
            // mp4 renders as a video card playing someone else's clip. So
            // media is read from the post's OWN span only — from its id up to
            // the next post id — and never from the wider window. A clip the
            // parser can't attribute to this post is better left unattached
            // than shown as this post's video. Text keeps the wide fallback
            // because taking the FIRST match is already this post's own text.
            val ownSpan = ids.getOrNull(index + 1)?.second
                ?.takeIf { it > position }
                ?.let { window.substring(0, (it - position).coerceAtMost(window.length)) }
                ?: window
            val text = postText(ownSpan).ifBlank { postText(window) }
            val mediaUrl = firstMediaVideo(ownSpan)
            item(
                username = username,
                stableId = id,
                title = text.ifBlank { "@$username post" },
                link = "https://x.com/$username/status/$id",
                published = snowflakeTime(id) ?: (System.currentTimeMillis() - index * 3_600_000L),
                imageUrl = firstMediaImage(ownSpan),
                mediaUrl = mediaUrl,
                durationSeconds = durationFor(ownSpan, mediaUrl),
                bodyText = text
            )
        }
    }

    /** Candidate post ids (id → offset of its first occurrence), in page order. */
    private fun postIds(source: String): List<Pair<String, Int>> {
        val positions = LinkedHashMap<String, Int>()
        ID_PATTERNS.forEach { pattern ->
            pattern.findAll(source).forEach { match ->
                val id = match.groupValues[1]
                if (id !in positions) positions[id] = match.range.first
            }
        }
        return positions.entries
            .sortedBy { it.value }
            .map { it.key to it.value }
            .filter { (_, position) -> isPost(source, position) }
    }

    /**
     * Whether the neighbourhood of [position] looks like a post rather than the
     * profile's own user object (whose `id_str` also appears on the page).
     */
    private fun isPost(source: String, position: Int): Boolean {
        val window = source.substring(
            position,
            (position + EVIDENCE_WINDOW).coerceAtMost(source.length)
        )
        if (POST_MARKERS.any { window.contains(it, ignoreCase = true) }) return true
        val looksLikeUser = window.contains("screen_name") ||
            window.contains("followers_count") ||
            window.contains("profile_image_url")
        return !looksLikeUser
    }

    /** The post's text, or "" when the window carries none. */
    private fun postText(window: String): String {
        TEXT_PATTERNS.forEach { pattern ->
            val raw = pattern.find(window)?.groupValues?.getOrNull(1)
            if (!raw.isNullOrBlank()) {
                val clean = cleanText(unescape(raw))
                if (clean.isNotBlank()) return clean
            }
        }
        return ""
    }

    /**
     * How long the clip [mediaUrl] runs, from X's own `duration_millis`.
     *
     * Without this every X card showed no length at all: YouTube resolves
     * durations from the watch page and Instagram from the container, so X was
     * the one source that could never say how long its clips were — even though
     * the number is in the very payload the media URL is read from.
     */
    private fun durationFor(window: String, mediaUrl: String?): Long {
        if (mediaUrl.isNullOrBlank()) return 0L
        val at = window.indexOf(mediaUrl)
        if (at <= 0) return 0L
        val millis = DURATION_MS.findAll(window.substring(0, at))
            .lastOrNull()?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return 0L
        return millis / 1000L
    }

    private fun item(
        username: String,
        stableId: String,
        title: String,
        link: String,
        published: Long,
        imageUrl: String?,
        mediaUrl: String?,
        durationSeconds: Long = 0L,
        bodyText: String = ""
    ) = MediaVideo(
        videoId = "x_$stableId",
        // The card shows a short label; the FULL post text rides along in
        // bodyText so the post viewer can show all of it.
        title = title.take(160),
        channelId = "x_${username.lowercase()}",
        channelName = "@$username",
        publishedAtEpochMillis = published,
        thumbnailUrl = imageUrl.orEmpty(),
        isShort = false,
        durationSeconds = durationSeconds,
        platform = MediaPlatform.X,
        mediaUrl = mediaUrl,
        sourceUrl = link,
        bodyText = bodyText
    )

    private fun snowflakeTime(id: String): Long? = runCatching {
        ((id.toLong() shr 22) + 1288834974657L)
    }.getOrNull()?.takeIf { it > 0L }

    /**
     * Normalises the escaped payloads so one set of patterns reads both the
     * JSON-escaped syndication page and plain HTML: escaped slashes/quotes
     * become literal, `\u0026` / `&amp;` become `&` (media URLs carry query
     * separators) and escaped whitespace becomes a space.
     */
    private fun unescape(value: String): String = value
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u0026", "&")
        .replace("\\u003C", "<")
        .replace("\\u003c", "<")
        .replace("\\u003E", ">")
        .replace("\\u003e", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("\\n", " ")
        .replace("\\r", " ")
        .replace("\\t", " ")

    private fun extractPostId(id: String, link: String): String {
        val source = if (link.contains("/status/")) link else id
        return source.substringAfter("/status/").substringBefore('?').substringBefore('#')
            .substringBefore('/').takeIf { it.isNotBlank() }
            ?: source.substringAfterLast('/').substringBefore('?').substringBefore('#')
    }

    private fun cleanText(html: String): String = html.replace(Regex("<[^>]*>"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** Post image: the pbs.twimg.com media host first, any other image after. */
    private fun firstMediaImage(window: String): String? {
        MEDIA_IMAGE.find(window)?.value?.let { return it }
        return ANY_IMAGE.findAll(window)
            .map { it.value }
            .firstOrNull { !it.contains("profile_images") && !it.contains("profile_banner") }
    }

    /**
     * The post's video URL, PROGRESSIVE FIRST.
     *
     * A tweet's media list carries both an HLS playlist (`…/playlist.m3u8`) and
     * progressive variants (`.mp4`), and X lists the playlist first. The in-app
     * player is a plain MediaPlayer, which needs the progressive file: handed
     * the playlist it either fails to prepare or stalls on the segments, which
     * is how an X post with a clip ended up playing nothing at all. The
     * playlist stays as the last resort for a tweet that offers no mp4.
     */
    private fun firstMediaVideo(window: String): String? {
        val urls = VIDEO_URL.findAll(window)
            .map { it.value }
            .filter { it.startsWith("http") }
            .toList()
        return urls.firstOrNull { it.contains(".mp4", ignoreCase = true) } ?: urls.firstOrNull()
    }

    private fun firstImageUrl(parent: org.w3c.dom.Element, html: String): String? {
        val image = parent.getElementsByTagName("image").item(0) as? org.w3c.dom.Element
        image?.textContent?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val nodes = parent.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.localName ?: node.tagName
            if (name.equals("thumbnail", true) || name.equals("content", true) || name.equals("enclosure", true)) {
                val url = node.getAttribute("url").ifBlank { node.getAttribute("href") }.trim()
                if (url.isNotBlank() && !url.contains(".mp4", true)) return url
            }
        }
        return Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
    }

    private fun firstVideoUrl(parent: org.w3c.dom.Element, html: String): String? {
        val nodes = parent.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.localName ?: node.tagName
            if (name.equals("enclosure", true) || name.equals("content", true) || name.equals("media", true)) {
                val type = node.getAttribute("type")
                val url = node.getAttribute("url").ifBlank { node.getAttribute("href") }.trim()
                if ((type.startsWith("video", true) || url.contains(".mp4", true)) && url.isNotBlank()) return url
            }
        }
        return Regex("""<(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
    }

    private fun text(parent: org.w3c.dom.Element, name: String): String? {
        val all = parent.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is org.w3c.dom.Element && (node.localName == name || node.tagName == name)) return node.textContent
        }
        return null
    }

    private fun link(parent: org.w3c.dom.Element): String {
        val direct = text(parent, "link").orEmpty().trim()
        if (direct.startsWith("http")) return direct
        val all = parent.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node is org.w3c.dom.Element && (node.localName == "link" || node.tagName == "link")) {
                node.getAttribute("href").trim().takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun parseDate(value: String): Long = runCatching {
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.recoverCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).parse(value)?.time ?: 0L
    }.getOrDefault(0L)
}
