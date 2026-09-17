package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A post's media, as the backend describes it (§9, §10).
 *
 * [url] is a presigned, expiring read URL — NOT a permanent address. It is
 * minted by the server per response, so it is a hint for the next few minutes
 * and must never be stored as though it were the asset's location. Anything the
 * user keeps is kept as BYTES on the device (§10), which is why [url] is not
 * written to the cache at all.
 *
 * `null` means this deployment has no bucket configured, not "the asset is
 * missing": a text post on a media-less server still renders, and one
 * unservable asset must not take a whole page down with it.
 */
data class GoodPostMedia(
    val id: String,
    val kind: String,
    val contentType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Int?,
    val position: Int,
    val url: String?
) {
    val isImage: Boolean get() = kind == "image"
    val isVideo: Boolean get() = kind == "video"
    val isAudio: Boolean get() = kind == "audio"

    /**
     * The extension for a saved copy.
     *
     * Derived from the content type rather than the URL, because a presigned
     * URL's path ends in the server's own object key — which is a uuid plus an
     * extension the server chose, and parsing it would break the moment the key
     * policy changed.
     */
    val extension: String
        get() = extensionFor(contentType, kind)
}

/**
 * Who published a post, in a feed that spans channels (§4).
 *
 * Three fields and nothing else: no owner, no follower state. The API does not
 * send them, so there is nothing here to forget to hide (§12, §38).
 */
data class GoodPostChannelRef(val id: String, val slug: String, val name: String)

/**
 * A post (§8).
 *
 * [viewerCanManage] is the server's answer to "may I edit or remove this",
 * computed from the caller's role in that channel. It is read, never derived:
 * the UI must not decide an authorization question, and §32 puts that decision
 * server-side.
 */
data class GoodPostPost(
    val id: String,
    val channelId: String,
    val type: String,
    val body: String?,
    val linkUrl: String?,
    val linkTitle: String?,
    val media: List<GoodPostMedia>,
    /** ISO-8601 from the server. Kept as text; see [createdAtMs] for display. */
    val createdAt: String,
    val editedAt: String?,
    val isEdited: Boolean,
    val viewerCanManage: Boolean,
    /** Present only on feed rows, which span channels. */
    val channel: GoodPostChannelRef? = null,
    /**
     * Reactions, views and the poll, already aggregated (§13, §14, §15).
     *
     * Never null, and defaulted to the zero state for the same reason
     * `engagement` is always present server-side: a post nobody has touched
     * still has to render, and a nullable count is how "--" reaches a screen.
     */
    val engagement: GoodPostEngagement = GoodPostEngagement.none
) {
    val isText: Boolean get() = type == "text"
    val isLink: Boolean get() = type == "link"

    /** A poll post's content is its poll (§14). */
    val poll: GoodPostPoll? get() = engagement.poll

    /**
     * When this was published, in epoch milliseconds, or null when the server
     * sent a timestamp this build cannot read.
     *
     * Null rather than 0 or `now`: a post with an unreadable date should show
     * no date at all, not "1970" and certainly not "just now".
     */
    val createdAtMs: Long?
        get() = parseIsoMillis(createdAt)

    /** The first image, which is what a row uses as its thumbnail. */
    val previewImage: GoodPostMedia?
        get() = media.firstOrNull { it.isImage }
}

/** One cursor page of posts. */
data class GoodPostPostPage(val items: List<GoodPostPost>, val nextCursor: String?)

/**
 * Parsing backend JSON into posts, and encoding them for the offline cache.
 *
 * Pure, and unit-tested directly — the same reasoning as [GoodPostChannelCodec].
 * This is the contract boundary with the server: it is where a field rename
 * turns every post into a blank row, and testing it through Compose would be
 * slower and weaker. An unusable row is skipped rather than thrown, so a
 * contract break surfaces as a shorter list instead of a crash.
 */
internal object GoodPostPostCodec {

    /**
     * Parse one post, or null when it is unusable.
     *
     * `id` and `channelId` are required — without them the row cannot be
     * rendered or acted on, and an action needs both to build its path.
     * Everything else degrades: a missing body is null, a missing count is 0.
     * The asymmetry is the same one the channel codec makes, for the same
     * reason — a missing count is cosmetic, a missing id is a bug.
     */
    fun post(json: JSONObject): GoodPostPost? {
        val id = json.optString("id")
        val channelId = json.optString("channelId")
        if (id.isBlank() || channelId.isBlank()) return null

        return GoodPostPost(
            id = id,
            channelId = channelId,
            type = json.optString("type"),
            body = json.nullableString("body"),
            linkUrl = json.nullableString("linkUrl"),
            linkTitle = json.nullableString("linkTitle"),
            media = mediaArray(json.optJSONArray("media")),
            createdAt = json.optString("createdAt"),
            editedAt = json.nullableString("editedAt"),
            isEdited = json.optBoolean("isEdited", false),
            viewerCanManage = json.optBoolean("viewerCanManage", false),
            channel = json.optJSONObject("channel")?.let(::channelRef),
            engagement = GoodPostEngagementCodec.engagement(json.optJSONObject("engagement"))
        )
    }

    private fun channelRef(json: JSONObject): GoodPostChannelRef? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        return GoodPostChannelRef(
            id = id,
            slug = json.optString("slug"),
            name = json.optString("name")
        )
    }

    private fun mediaArray(array: JSONArray?): List<GoodPostMedia> {
        if (array == null) return emptyList()
        val parsed = ArrayList<GoodPostMedia>(array.length())
        for (i in 0 until array.length()) {
            val element = array.optJSONObject(i) ?: continue
            val id = element.optString("id")
            if (id.isBlank()) continue
            parsed.add(
                GoodPostMedia(
                    id = id,
                    kind = element.optString("kind"),
                    contentType = element.optString("contentType"),
                    byteSize = element.optLong("byteSize", 0L),
                    width = element.optInt("width").takeIf { it > 0 },
                    height = element.optInt("height").takeIf { it > 0 },
                    durationMs = element.optInt("durationMs").takeIf { it > 0 },
                    position = element.optInt("position", 0),
                    // Absent and JSON-null both mean "no URL for you", which is
                    // the honest reading: the server sends an explicit null
                    // when it has no bucket.
                    url = element.nullableString("url")
                )
            )
        }
        return parsed.sortedBy { it.position }
    }

    /** Parse a `{ items, nextCursor }` page. Unusable rows are skipped. */
    fun page(body: JSONObject): GoodPostPostPage {
        val items = body.optJSONArray("items") ?: JSONArray()
        val parsed = ArrayList<GoodPostPost>(items.length())
        for (i in 0 until items.length()) {
            val element = items.optJSONObject(i) ?: continue
            post(element)?.let(parsed::add)
        }
        return GoodPostPostPage(
            items = parsed,
            // An empty string would be sent back as a cursor and rejected as
            // invalid, turning the end of a list into an error.
            nextCursor = body.nullableString("nextCursor")?.takeIf { it.isNotBlank() }
        )
    }

    /** Parse a `{ post: {...} }` body, which every write returns. */
    fun single(body: JSONObject): GoodPostPost? =
        body.optJSONObject("post")?.let(::post)

    /** Parse a `{ media: {...} }` body (the upload and confirm responses). */
    fun media(body: JSONObject): GoodPostMedia? {
        val json = body.optJSONObject("media") ?: return null
        val id = json.optString("id")
        if (id.isBlank()) return null
        return GoodPostMedia(
            id = id,
            kind = json.optString("kind"),
            contentType = json.optString("contentType"),
            byteSize = json.optLong("byteSize", 0L),
            width = json.optInt("width").takeIf { it > 0 },
            height = json.optInt("height").takeIf { it > 0 },
            durationMs = json.optInt("durationMs").takeIf { it > 0 },
            position = json.optInt("position", 0),
            url = json.nullableString("url")
        )
    }

    /**
     * Append a page to a list.
     *
     * Keyed by id, last-write-wins — which matters for the two cases paging
     * otherwise gets wrong: a post can legitimately appear on two pages when
     * enough new posts arrive between requests, and an edit or a delete
     * performed in between returns a newer version of a row already on screen.
     * Deduplicating on append means the list can never show one post twice, and
     * the fresher copy wins.
     */
    fun mergePage(
        existing: List<GoodPostPost>,
        incoming: List<GoodPostPost>
    ): List<GoodPostPost> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming.distinctBy { it.id }

        val merged = LinkedHashMap<String, GoodPostPost>(existing.size + incoming.size)
        existing.forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    /** Replace one post in a list, so an edit updates the row in place. */
    fun replace(list: List<GoodPostPost>, updated: GoodPostPost): List<GoodPostPost> =
        list.map { if (it.id == updated.id) updated else it }

    /**
     * Replace one post's engagement, keeping the rest of the row.
     *
     * Used after a reaction, a vote or a view ping, none of which change what
     * the post SAYS — rebuilding the whole post from a response would risk
     * dropping a field the response does not carry.
     */
    fun replaceEngagement(
        list: List<GoodPostPost>,
        postId: String,
        engagement: GoodPostEngagement
    ): List<GoodPostPost> = list.map {
        if (it.id == postId) it.copy(engagement = engagement) else it
    }

    /** Drop a post, for a removal that has been confirmed by the server. */
    fun remove(list: List<GoodPostPost>, id: String): List<GoodPostPost> =
        list.filterNot { it.id == id }

    // ── Encoding, for the offline cache ──────────────────────────────────

    /**
     * Serialise a page for local storage.
     *
     * Media is stored WITHOUT its URL. A presigned URL is a short-lived
     * capability — writing one to disk would leave the app presenting an
     * expired link as though it were a saved asset, which is exactly the
     * "claims more than it can honour" behaviour §36 forbids. Anything the user
     * keeps is kept as bytes by [GoodPostMediaStore] instead (§10).
     */
    fun encodePage(page: GoodPostPostPage): String {
        val items = JSONArray()
        page.items.forEach { items.put(encode(it)) }
        return JSONObject().apply {
            put("items", items)
            put("nextCursor", page.nextCursor ?: JSONObject.NULL)
        }.toString()
    }

    private fun encode(post: GoodPostPost): JSONObject = JSONObject().apply {
        put("id", post.id)
        put("channelId", post.channelId)
        put("type", post.type)
        put("body", post.body ?: JSONObject.NULL)
        put("linkUrl", post.linkUrl ?: JSONObject.NULL)
        put("linkTitle", post.linkTitle ?: JSONObject.NULL)
        put("createdAt", post.createdAt)
        put("editedAt", post.editedAt ?: JSONObject.NULL)
        put("isEdited", post.isEdited)
        put("viewerCanManage", post.viewerCanManage)
        // Counts are cached WITH the post. A saved feed whose every row showed
        // zero reactions would be presenting a different post rather than a
        // saved one (§36).
        put("engagement", GoodPostEngagementCodec.encode(post.engagement))
        post.channel?.let { ref ->
            put("channel", JSONObject().apply {
                put("id", ref.id)
                put("slug", ref.slug)
                put("name", ref.name)
            })
        }
        val media = JSONArray()
        post.media.forEach { item ->
            media.put(JSONObject().apply {
                put("id", item.id)
                put("kind", item.kind)
                put("contentType", item.contentType)
                put("byteSize", item.byteSize)
                put("position", item.position)
                // Deliberately no "url": see the note on [encodePage].
            })
        }
        put("media", media)
    }

    /**
     * Read a cached page back, or null when it is absent or unreadable.
     *
     * The cursor is dropped on the way out. A cached cursor points into an
     * ordering that has almost certainly moved, and keeping it would offer a
     * "load more" that fails the moment it is tapped offline — §36 wants saved
     * content to be READABLE, not to be a basis for paging a stale list.
     *
     * Null for damage rather than an empty page: an empty page would show "no
     * posts yet" to someone whose cache held a feed a minute ago.
     */
    fun decodePage(raw: String?): GoodPostPostPage? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            if (root.opt("items") !is JSONArray) return null
            page(root).copy(nextCursor = null)
        } catch (e: Exception) {
            null
        }
    }

    /** A JSON value that is absent or JSON-null reads as null, not "". */
    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

/**
 * An ISO-8601 instant to epoch milliseconds, or null.
 *
 * `Instant.parse` because the server serialises with `toISOString()`, which is
 * exactly ISO-8601 — and because it is strict: a hand-rolled `SimpleDateFormat`
 * with a permissive pattern would happily accept the wrong format and print a
 * confidently wrong time.
 */
internal fun parseIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        null
    }
}

/**
 * Epoch milliseconds to the ISO-8601 text the server uses.
 *
 * `Instant` rather than `SimpleDateFormat` so the value written into the cache
 * is the same shape [parseIsoMillis] reads back — a formatter that used the
 * device's locale and time zone would produce a string the app could not read
 * on the next run, on a device set to a different region.
 */
internal fun isoFromMillis(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).toString()

/** The file extension for a content type, falling back by media kind. */
internal fun extensionFor(contentType: String, kind: String): String {
    val base = contentType.substringBefore(';').trim().lowercase()
    return when (base) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg" -> "ogg"
        "audio/wav" -> "wav"
        else -> when {
            base.startsWith("image/") -> base.removePrefix("image/")
            base.startsWith("video/") -> base.removePrefix("video/")
            base.startsWith("audio/") -> base.removePrefix("audio/")
            kind == "image" -> "jpg"
            kind == "video" -> "mp4"
            else -> "bin"
        }
    }
}
