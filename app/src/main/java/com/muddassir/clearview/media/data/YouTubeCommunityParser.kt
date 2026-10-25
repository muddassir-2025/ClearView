package com.muddassir.clearview.media.data

import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a YouTube channel's COMMUNITY tab into [MediaVideo] posts.
 *
 * Why not the Data API: YouTube's public Data API v3 has NO endpoint for
 * community posts — only videos, playlists and channels — and the channel RSS
 * feed (`feeds/videos.xml`) carries uploads only. This parser therefore reads
 * the channel's own `/community` page, whose `ytInitialData` embeds the posts
 * the page renders (the same server-rendered data every browser gets). It is
 * the extraction path the app already uses for playlist pages, applied to the
 * post renderers instead of video renderers.
 *
 * Pure (no Android dependencies beyond org.json) so it is unit-testable.
 * Parsing is defensive: a malformed post is skipped, and a page with no
 * recognisable posts returns an empty list.
 *
 * Two renderer shapes exist in the wild and both are read:
 *  - `backstagePostRenderer` (the classic community tab), and
 *  - `postRenderer` (the newer tab used by an increasing number of channels).
 */
object YouTubeCommunityParser {

    /**
     * Posts found on [html], newest first (the page's own order).
     *
     * @param channelId the saved subscription's id — authoritative, so a post
     *   always files under the channel the reader subscribed to.
     * @param channelName fallback author name when the post carries none.
     */
    fun parse(
        html: String,
        channelId: String,
        channelName: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): List<MediaVideo> {
        val json = extractInitialData(html) ?: return emptyList()
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val posts = ArrayList<MediaVideo>()
        collect(root, channelId, channelName, nowEpochMillis, posts)
        // The page is newest-first; a stable sort by the (approximate) publish
        // time keeps that order even when a relative label is missing.
        return posts
            .distinctBy { it.videoId }
            .sortedByDescending { it.publishedAtEpochMillis }
    }

    /**
     * Walks the data tree in document order and turns every post renderer into
     * a post. Recursion is bounded by the JSON depth of the page, and only
     * object/array nodes are visited (never runs of text).
     */
    private fun collect(
        node: Any?,
        channelId: String,
        channelName: String,
        nowEpochMillis: Long,
        into: MutableList<MediaVideo>
    ) {
        when (node) {
            is JSONObject -> {
                val renderer =
                    node.optJSONObject("backstagePostRenderer")
                        ?: node.optJSONObject("postRenderer")
                if (renderer != null) {
                    postFrom(renderer, channelId, channelName, nowEpochMillis)?.let(into::add)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    // Skip the renderer we just consumed: its children are the
                    // post's own fields, and visiting them again would be work
                    // with nothing to find.
                    if (renderer != null && (key == "backstagePostRenderer" || key == "postRenderer")) {
                        continue
                    }
                    when (val child = node.opt(key)) {
                        is JSONObject, is JSONArray -> collect(
                            child,
                            channelId,
                            channelName,
                            nowEpochMillis,
                            into
                        )
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    when (val child = node.opt(i)) {
                        is JSONObject, is JSONArray -> collect(
                            child,
                            channelId,
                            channelName,
                            nowEpochMillis,
                            into
                        )
                    }
                }
            }
        }
    }

    /** One post renderer → a post, or null when it carries no usable id. */
    private fun postFrom(
        renderer: JSONObject,
        channelId: String,
        channelName: String,
        nowEpochMillis: Long
    ): MediaVideo? {
        val postId = renderer.optString("postId", "").trim()
        if (postId.isBlank()) return null
        // Both shapes keep the words in `contentText`, with newer pages using
        // `content`; read whichever holds text.
        val text = runsText(renderer.optJSONObject("contentText"))
            .ifBlank { runsText(renderer.optJSONObject("content")) }
            .trim()
        val author = runsText(renderer.optJSONObject("authorText"))
            .ifBlank { channelName }
        val publishedLabel = runsText(renderer.optJSONObject("publishedTimeText"))
            .ifBlank { simpleText(renderer.optJSONObject("publishedTimeText")) }
        val publishedAt = relativeTimeToEpochMillis(publishedLabel, nowEpochMillis)
            // No label at all: keep the page's order with a small descending
            // nudge off "now" rather than filing every such post at 0.
            ?: nowEpochMillis
        val attachment = renderer.optJSONObject("backstageAttachment")
        val thumbnail = pickThumbnail(attachment) ?: pickContentImage(renderer)
        val linkedVideoId = videoIdOf(attachment)
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(120)
            ?: if (linkedVideoId != null) "Video post" else "Post"
        return MediaVideo(
            // Prefixed so a post id can never collide with a video id: nothing
            // may hand this to the player, and the feed tells them apart by id.
            videoId = MediaVideo.COMMUNITY_ID_PREFIX + postId,
            title = title,
            channelId = channelId,
            channelName = author.ifBlank { channelName },
            publishedAtEpochMillis = publishedAt,
            thumbnailUrl = thumbnail.orEmpty(),
            // The post's FULL text: the card shows a short label, the reader
            // shows all of it (same contract as an Instagram caption).
            bodyText = text,
            platform = MediaPlatform.YOUTUBE,
            sourceUrl = "https://www.youtube.com/post/$postId"
        )
    }

    /**
     * The largest image attached to a post: its own photo (`backstageImageRenderer`)
     * or, for a video post, the linked video's poster. Polls carry no image and
     * return null, so the card falls back to its text backdrop.
     */
    private fun pickThumbnail(attachment: JSONObject?): String? {
        attachment ?: return null
        attachment.optJSONObject("backstageImageRenderer")
            ?.optJSONObject("image")
            ?.let { largestThumbnail(it) }
            ?.let { return it }
        attachment.optJSONObject("videoRenderer")
            ?.optJSONObject("thumbnail")
            ?.let { largestThumbnail(it) }
            ?.let { return it }
        // A shared playlist carries a poster too.
        attachment.optJSONObject("playlistRenderer")
            ?.optJSONObject("thumbnail")
            ?.let { largestThumbnail(it) }
            ?.let { return it }
        return null
    }

    /** Some post shapes keep the image beside the text instead of an attachment. */
    private fun pickContentImage(renderer: JSONObject): String? =
        renderer.optJSONObject("contentImage")
            ?.let { largestThumbnail(it) }

    /** The video a community post shares, if it shares one. */
    private fun videoIdOf(attachment: JSONObject?): String? {
        val renderer = attachment?.optJSONObject("videoRenderer") ?: return null
        return renderer.optString("videoId", "").takeIf { it.isNotBlank() }
    }

    /** Last (largest) thumbnail URL from a `{thumbnails:[...]}` object. */
    private fun largestThumbnail(container: JSONObject): String? {
        val thumbnails = container.optJSONArray("thumbnails") ?: return null
        for (i in thumbnails.length() - 1 downTo 0) {
            val url = thumbnails.optJSONObject(i)?.optString("url", "").orEmpty()
            if (url.isNotBlank()) return url
        }
        return null
    }

    /** Concatenated `runs[].text` of a text object. */
    private fun runsText(text: JSONObject?): String {
        text ?: return ""
        text.optString("simpleText", "").takeIf { it.isNotBlank() }?.let { return it }
        val runs = text.optJSONArray("runs") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            builder.append(run.optString("text", ""))
        }
        return builder.toString()
    }

    /** `{"simpleText": "…"}` helper for the newer shapes. */
    private fun simpleText(text: JSONObject?): String =
        text?.optString("simpleText", "").orEmpty()

    /**
     * YouTube's community page labels a post with a relative time
     * ("2 days ago", "3 weeks ago", "1 year ago"). It is the only time signal
     * on the page, so it is turned into an approximate instant — good enough
     * for feed ordering and for the reader's relative date, and never presented
     * as exact.
     */
    internal fun relativeTimeToEpochMillis(
        label: String,
        nowEpochMillis: Long
    ): Long? {
        val match = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?""", RegexOption.IGNORE_CASE)
            .find(label.trim()) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unitMillis = when (match.groupValues[2].lowercase()) {
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 7L * 86_400_000L
            "month" -> 30L * 86_400_000L
            "year" -> 365L * 86_400_000L
            else -> return null
        }
        return (nowEpochMillis - amount * unitMillis).coerceAtLeast(0L)
    }

    /**
     * The page's embedded `ytInitialData` object, found by a balanced-brace scan
     * from the first assignment.
     *
     * A regex cannot do this safely: the JSON contains braces inside strings
     * (post text, titles), and a non-greedy match stops at the first `};` it
     * sees — which truncates the document and silently loses posts. The scan
     * tracks string state and escapes, so it always returns one complete object.
     */
    internal fun extractInitialData(html: String): String? {
        val key = html.indexOf("ytInitialData")
        if (key < 0) return null
        val start = html.indexOf('{', key)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < html.length) {
            val ch = html[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, index + 1)
                    }
                }
            }
            index++
        }
        return null
    }
}
