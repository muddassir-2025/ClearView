package com.muddassir.clearview.media.data

import android.util.Log
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/**
 * Instagram profile-embed provider: Instagram's own `/<username>/embed/` page.
 *
 * This is the login-free endpoint Instagram renders for profile iframes, and it
 * is the reason Instagram channels had no feed: the other providers are all
 * spent — `web_profile_info` answers 429, the public RSS-Bridge Instagram bridge
 * answers with an EMPTY feed, and the backend proxy points at a retired tunnel.
 * The embed page still carries the profile AND its latest posts as embedded JSON
 * (`contextJSON` → `context.graphql_media[]`), which is exactly what the Media
 * tab needs.
 *
 * It exposes the newest handful of posts (currently 6) — the same rolling window
 * the other providers give; the cache accumulates them across refreshes.
 */
class ProfileEmbedInstagramSource : InstagramSource {
    override val name: String = "ProfileEmbed"

    companion object {
        private const val TAG = "ProfileEmbedIG"
        private const val CONNECT_TIMEOUT = 12_000
        private const val READ_TIMEOUT = 15_000
        private const val BACKOFF_MS = 10 * 60 * 1000L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val IPHONE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

        /**
         * A 429 here is per IP and applies to every profile, so once Instagram
         * throttles the install the source stands down for a while instead of
         * spending a request on every channel of every refresh.
         */
        @Volatile
        private var backoffUntil = 0L
    }

    override suspend fun fetchProfile(usernameOrUrl: String): InstagramFeedResult =
        withContext(Dispatchers.IO) {
            val username = usernameOrUrl.removePrefix("@").trim()
            if (username.startsWith("http")) {
                return@withContext InstagramFeedResult.Error("Username required")
            }
            if (!InstagramProfileEmbedParser.PROFILE_NAME.matches(username)) {
                return@withContext InstagramFeedResult.Error("Not an Instagram profile name")
            }
            if (System.currentTimeMillis() < backoffUntil) {
                return@withContext InstagramFeedResult.Error("Profile embed is rate-limited")
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://www.instagram.com/$username/embed/").openConnection()
                    as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", IPHONE_USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                val code = connection.responseCode
                if (code == HTTP_TOO_MANY_REQUESTS) {
                    backoffUntil = System.currentTimeMillis() + BACKOFF_MS
                    Log.w(TAG, "Instagram rate-limited the profile embed")
                    // Surface it: an empty Instagram feed is otherwise
                    // indistinguishable from a profile with nothing to show.
                    MediaSourceStatusStore.markThrottled(MediaPlatform.INSTAGRAM, backoffUntil)
                    return@withContext InstagramFeedResult.Error("Instagram is rate-limiting this device")
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    return@withContext InstagramFeedResult.Error("Embed returned $code")
                }
                val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = InstagramProfileEmbedParser.parse(html, username)
                if (parsed != null) MediaSourceStatusStore.markOk(MediaPlatform.INSTAGRAM)
                parsed ?: InstagramFeedResult.Error("No embedded profile data for $username")
            } catch (e: Exception) {
                Log.d(TAG, "fetchProfile failed for $username: ${e.message}")
                InstagramFeedResult.Error("Profile embed fetch failed")
            } finally {
                connection?.disconnect()
            }
        }
}

/**
 * Reads the profile-embed payload. Pure and unit-testable.
 *
 * The page nests the useful JSON as a JSON-ENCODED STRING inside a larger JSON
 * blob, so it is unwrapped one level (`"…"` → its decoded text) and only then
 * parsed: `/feed/` and `/embed/` markup is not HTML to scrape but JSON to read.
 */
object InstagramProfileEmbedParser {

    /** A plausible Instagram profile name (handles, not URLs). */
    val PROFILE_NAME = Regex("^[A-Za-z0-9._]{1,30}$")

    private const val CONTEXT_KEY = "\"contextJSON\":\""

    /** Parses the embed page; null when it carries no profile payload at all. */
    fun parse(html: String, fallbackUsername: String): InstagramFeedResult.Success? {
        val raw = embeddedContextJson(html) ?: return null
        val inner = runCatching {
            JSONTokener("\"" + raw + "\"").nextValue() as? String
        }.getOrNull()
        if (inner.isNullOrBlank()) return null
        val root = runCatching { JSONObject(inner) }.getOrNull() ?: return null
        val context = root.optJSONObject("context") ?: return null
        val username = context.optString("username").takeIf { it.isNotBlank() }
            ?: fallbackUsername.takeIf { it.isNotBlank() }
            ?: return null
        val fullName = context.optString("full_name").takeIf { it.isNotBlank() } ?: username
        val avatarUrl = context.optString("profile_pic_url")
            .takeIf { InstagramRssParser.isRealAvatarUrl(it) }

        val media = context.optJSONArray("graphql_media")
        val items = if (media == null) {
            emptyList()
        } else {
            (0 until media.length()).mapNotNull { index ->
                media.optJSONObject(index)
                    ?.optJSONObject("shortcode_media")
                    ?.let { post(it, username, fullName, index) }
            }
        }
        return InstagramFeedResult.Success(
            username = username,
            fullName = fullName,
            avatarUrl = avatarUrl,
            items = items
        )
    }

    /**
     * The raw `contextJSON` value, exactly as it appears: the scan stops at the
     * first UNESCAPED quote, so the escaped quotes and slashes inside the value
     * survive for the JSON decoder to resolve.
     */
    private fun embeddedContextJson(html: String): String? {
        val start = html.indexOf(CONTEXT_KEY)
        if (start < 0) return null
        val builder = StringBuilder()
        var i = start + CONTEXT_KEY.length
        while (i < html.length) {
            val c = html[i]
            if (c == '\\' && i + 1 < html.length) {
                builder.append(c).append(html[i + 1])
                i += 2
                continue
            }
            if (c == '"') break
            builder.append(c)
            i++
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    private fun post(
        media: JSONObject,
        username: String,
        channelName: String,
        index: Int
    ): MediaVideo? {
        val shortcode = media.optString("shortcode").takeIf { it.isNotBlank() } ?: return null
        val isVideo = media.optBoolean("is_video", false)
        val typename = media.optString("__typename")
        val type = when {
            typename.equals("GraphSidecar", true) -> InstagramMediaType.CAROUSEL
            isVideo -> InstagramMediaType.REEL
            else -> InstagramMediaType.IMAGE
        }
        // The embed omits `video_url`; playback resolves the stream from the
        // permalink on demand (the same path Reels already use). The poster is
        // the post's own still, handed to the shared thumbnail rule so an
        // expiring CDN signature is replaced by the stable media endpoint.
        val poster = media.optString("display_url").ifBlank {
            media.optJSONArray("display_resources")?.optJSONObject(0)?.optString("src").orEmpty()
        }
        val caption = media.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")
            ?.optJSONObject(0)
            ?.optJSONObject("node")
            ?.optString("text")
            .orEmpty()
        val title = caption.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
            ?: "$channelName ${if (isVideo) "Reel" else "Post"}"
        val published = publishedAt(media.optLong("taken_at_timestamp", 0L), index)

        return MediaVideo(
            videoId = "ig_$shortcode",
            title = title,
            channelId = "ig_${username.lowercase()}",
            channelName = channelName,
            publishedAtEpochMillis = published,
            thumbnailUrl = InstagramEmbedPayload.thumbnailFor(shortcode, poster),
            viewCount = media.optJSONObject("edge_liked_by")?.optLong("count", 0L) ?: 0L,
            isShort = isVideo,
            isLive = false,
            durationSeconds = 0L,
            platform = MediaPlatform.INSTAGRAM,
            instagramType = type,
            mediaUrl = media.optString("video_url").takeIf { it.isNotBlank() },
            instagramUrl = "https://www.instagram.com/p/$shortcode/",
            // The card shows the first line; the viewer shows all of it.
            bodyText = caption.trim()
        )
    }

    /** `taken_at_timestamp` is seconds; a value already in millis is kept as is. */
    private fun publishedAt(timestamp: Long, index: Int): Long = when {
        timestamp <= 0L -> System.currentTimeMillis() - index * 3_600_000L
        timestamp > 10_000_000_000L -> timestamp
        else -> timestamp * 1000L
    }
}
