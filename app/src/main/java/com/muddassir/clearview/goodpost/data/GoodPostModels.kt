package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The public Good Post shapes: what `/api/v1` returns (§3–§13).
 *
 * Every field here is one the backend actually sends, and every one of them is
 * public. There is deliberately NO follower count, post count, reaction, view
 * total or owner field — because Good Post is a broadcast system, not a social
 * network, and a field the client never receives cannot be rendered by
 * accident later (§1, §31).
 *
 * These are the *viewer's* shapes: there is no `isFollowing`, `viewerRole` or
 * `hasUnread`, because there is no viewer account to hang them on. Read-only is
 * the whole product.
 */

/** A channel as it appears in the list, in Explore, and in the feed header. */
data class GoodPostChannel(
    val id: String,
    val slug: String,
    val name: String,
    val description: String?,
    val categorySlug: String?,
    val categoryLabel: String?,
    val countryCode: String?,
    /**
     * A short-lived signed URL for the channel's profile image, or null.
     *
     * Null is a normal state, not a failure: a channel that has never had an
     * image, and equally a deployment with no bucket (§22). The avatar falls back
     * to the channel's initial, which is why nothing here needs an error case.
     *
     * A URL rather than an object key: a key is a permanent name for something
     * meant to be temporary, so the backend never sends one.
     */
    val iconUrl: String?,
    /** ISO instant, used by the channel information page's "Created on". */
    val createdAt: String,
    /** When it last published, or null if it never has. */
    val lastPostAt: String?,
    /** The newest post's kind — `text`, `image`, `video`, `link` — or null. */
    val lastPostType: String?,
    /** The opening of the newest post's text, or null for a media-only post. */
    val lastPostPreview: String?,
    /**
     * How many times the newest post has been reported read (§9).
     *
     * Zero for a channel that has never posted, and zero for a post nobody has
     * read: the server sends a number and never a null, so this is one shape to
     * render rather than two. It describes the Newest post — a channel's total
     * readership is not a thing this product counts — and the row that shows it
     * is the one showing that post's preview, which is what keeps the two from
     * being read as unrelated numbers.
     */
    val lastPostViews: Int = 0,
    /**
     * How many readers follow this channel (§4).
     *
     * A real count of real follows, which is why it is allowed where a social
     * metric is not: every follow is a decision a person made about this channel,
     * and the server reads it from the rows rather than from a number kept
     * beside them.
     */
    val followerCount: Int = 0,
    /** App deep link (§6): `clearview://goodpost/channel/<slug>`. */
    val shareLink: String,
    /**
     * `active` or `suspended`, and only on a payload an administrator received.
     *
     * Null for a reader, because the public read never returns a suspended
     * channel at all — a null here means "you are reading this as a viewer",
     * whereas a suspended status means the row is being shown to somebody who
     * runs it and needs to know it is not public.
     */
    val status: String? = null,

    // ── The reader's own state (§4, §5, §6) ──────────────────────────────
    //
    // Present only on a payload from the reader's own follows; the public
    // channel list carries none of it, because a channel is the same channel to
    // everybody and these three fields are not. Defaults rather than nullables
    // so a row from the public list renders with no badge and no toggle without
    // every call site having to say which list it came from.

    /** Unread posts for this reader (§5). 0 anywhere else. */
    val unreadCount: Int = 0,
    /** True when this row came from the reader's follows (§4). */
    val following: Boolean = false,
    /** True when the reader has muted this channel (§6). */
    val notificationsMuted: Boolean = false,
    /** ISO instant of the follow (§5's ordering); null when not followed. */
    val followedAt: String? = null
)

/**
 * The reader's relationship to one channel (§4–§6).
 *
 * Returned by follow, unfollow, mute and mark-read, which is what lets the
 * screen update the one row it acted on from the answer rather than refetching
 * the list it is already showing.
 */
data class GoodPostFollow(
    val channelId: String,
    val following: Boolean,
    val notificationsMuted: Boolean,
    /** Posts published since the reader last read it (§5). */
    val unreadCount: Int
)

/** The channel a post came from, as a single-post payload names it. */
data class GoodPostChannelRef(val id: String, val slug: String, val name: String)

/** One stored asset on a post: an image, a video or an audio clip. */
data class GoodPostMedia(
    val id: String,
    /** `image`, `video` or `audio`. */
    val kind: String,
    val contentType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val position: Int,
    /**
     * A short-lived signed read URL, or null when this deployment has no bucket
     * (§22). Null is a normal state, not a failure: a text-only post needs no
     * storage, and one unservable asset must not break the screen it sits on.
     */
    val url: String?
) {
    val isImage: Boolean get() = kind == "image"
    val isVideo: Boolean get() = kind == "video"
}

/** One item in a channel's "Media and links" gallery (§13). */
data class GoodPostMediaItem(
    val id: String,
    val postId: String,
    val kind: String,
    val contentType: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val createdAt: String,
    val url: String?
) {
    val isVideo: Boolean get() = kind == "video"
}

/** A post, as the channel feed renders it (§9). */
data class GoodPostPost(
    val id: String,
    val channelId: String,
    /** `text`, `image`, `video`, `audio` or `link`. */
    val type: String,
    val body: String?,
    val linkUrl: String?,
    val linkTitle: String?,
    val media: List<GoodPostMedia>,
    val createdAt: String,
    /**
     * When the publisher last changed it, or null.
     *
     * Kept because an edit changes what the channel said, and a reader who saw
     * the earlier version should not have to wonder whether they misremembered
     * it.
     */
    val editedAt: String? = null,
    /** Present only when the post was fetched on its own. */
    val channel: GoodPostChannelRef? = null,
    /**
     * How many readers have reported this post read (§9).
     *
     * Reported by the client rather than counted by the server, because the
     * server does not know when something has been read — it only knows when it
     * was told. So this is a floor and not a truth, which is why it is a quiet
     * stamp on the post rather than a headline number.
     */
    val views: Int = 0
) {
    val hasImage: Boolean get() = media.any { it.isImage }
    val hasVideo: Boolean get() = media.any { it.isVideo }
    val isLink: Boolean get() = !linkUrl.isNullOrBlank()
}

/** A category an Explore filter can offer (§7). */
data class GoodPostCategory(val slug: String, val label: String)

/**
 * An ISO instant from the backend as epoch millis, or null.
 *
 * Null rather than 0 for an unparseable value, because a timestamp of zero
 * renders as 1970 and a wrong date is worse than no date at all.
 */
fun parseIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e2: Exception) {
            null
        }
    }
}

/** One keyset page. `nextCursor` is null once the end is reached. */
data class GoodPostPage<T>(val items: List<T>, val nextCursor: String?)

/**
 * Parsing backend JSON, and reading/writing the offline cache.
 *
 * Pure and unit-tested, like the rest of ClearView's contract boundaries: this
 * is the one place a field rename turns every channel into a blank row, and
 * testing it through the UI would be both slower and weaker.
 *
 * An unusable payload yields null (or an empty page) rather than throwing, so a
 * backend contract break surfaces as a retryable error instead of a crash.
 * `id` and `name` are required because a row without them cannot be rendered;
 * everything else degrades to a default, because a missing count is cosmetic
 * while a missing id is a bug.
 */
internal object GoodPostCodec {

    fun channel(json: JSONObject): GoodPostChannel? {
        val id = json.optString("id")
        val name = json.optString("name")
        if (id.isBlank() || name.isBlank()) return null

        return GoodPostChannel(
            id = id,
            slug = json.optString("slug"),
            name = name,
            description = json.nullableString("description"),
            categorySlug = json.nullableString("categorySlug"),
            categoryLabel = json.nullableString("categoryLabel"),
            countryCode = json.nullableString("countryCode"),
            iconUrl = json.nullableString("iconUrl"),
            createdAt = json.optString("createdAt"),
            lastPostAt = json.nullableString("lastPostAt"),
            lastPostType = json.nullableString("lastPostType"),
            lastPostPreview = json.nullableString("lastPostPreview"),
            lastPostViews = json.optInt("lastPostViews", 0).coerceAtLeast(0),
            followerCount = json.optInt("followerCount", 0).coerceAtLeast(0),
            shareLink = json.optString("shareLink"),
            status = json.nullableString("status"),
            // Absent on every payload except the reader's own follows, where
            // `optInt`'s default and `optBoolean`'s both mean "nothing to show".
            unreadCount = json.optInt("unreadCount", 0).coerceAtLeast(0),
            following = json.optBoolean("following", false),
            notificationsMuted = json.optBoolean("notificationsMuted", false),
            followedAt = json.nullableString("followedAt")
        )
    }

    /** Parse a `{ follow: {...} }` body. */
    fun follow(body: JSONObject): GoodPostFollow? {
        val row = body.optJSONObject("follow") ?: return null
        val channelId = row.optString("channelId")
        if (channelId.isBlank()) return null

        return GoodPostFollow(
            channelId = channelId,
            following = row.optBoolean("following", false),
            notificationsMuted = row.optBoolean("notificationsMuted", false),
            unreadCount = row.optInt("unreadCount", 0).coerceAtLeast(0)
        )
    }

    fun channelRef(json: JSONObject): GoodPostChannelRef? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        return GoodPostChannelRef(
            id = id,
            slug = json.optString("slug"),
            name = json.optString("name")
        )
    }

    fun media(json: JSONObject): GoodPostMedia? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        return GoodPostMedia(
            id = id,
            kind = json.optString("kind"),
            contentType = json.optString("contentType"),
            byteSize = json.optLong("byteSize", 0L),
            width = json.optIntOrNull("width"),
            height = json.optIntOrNull("height"),
            durationMs = json.optLongOrNull("durationMs"),
            position = json.optInt("position", 0),
            url = json.nullableString("url")
        )
    }

    fun post(json: JSONObject): GoodPostPost? {
        val id = json.optString("id")
        val channelId = json.optString("channelId")
        if (id.isBlank() || channelId.isBlank()) return null

        return GoodPostPost(
            id = id,
            channelId = channelId,
            type = json.optString("type").ifBlank { "text" },
            body = json.nullableString("body"),
            linkUrl = json.nullableString("linkUrl"),
            linkTitle = json.nullableString("linkTitle"),
            media = mediaArray(json.optJSONArray("media")),
            createdAt = json.optString("createdAt"),
            editedAt = json.nullableString("editedAt"),
            channel = json.optJSONObject("channel")?.let(::channelRef),
            views = json.optInt("views", 0).coerceAtLeast(0)
        )
    }

    fun mediaItem(json: JSONObject): GoodPostMediaItem? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        return GoodPostMediaItem(
            id = id,
            postId = json.optString("postId"),
            kind = json.optString("kind"),
            contentType = json.optString("contentType"),
            width = json.optIntOrNull("width"),
            height = json.optIntOrNull("height"),
            durationMs = json.optLongOrNull("durationMs"),
            createdAt = json.optString("createdAt"),
            url = json.nullableString("url")
        )
    }

    /** Parse a `{ items, nextCursor }` page with [element] as the row parser. */
    fun <T> page(body: JSONObject, element: (JSONObject) -> T?): GoodPostPage<T> {
        val items = body.optJSONArray("items") ?: JSONArray()
        val parsed = ArrayList<T>(items.length())
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            element(row)?.let(parsed::add)
        }
        return GoodPostPage(
            items = parsed,
            // An empty string handed back as a cursor would be rejected as
            // invalid, turning the end of a list into an error.
            nextCursor = body.nullableString("nextCursor")?.takeIf { it.isNotBlank() }
        )
    }

    fun channelPage(body: JSONObject): GoodPostPage<GoodPostChannel> = page(body, ::channel)

    fun postPage(body: JSONObject): GoodPostPage<GoodPostPost> = page(body, ::post)

    fun mediaPage(body: JSONObject): GoodPostPage<GoodPostMediaItem> = page(body, ::mediaItem)

    /** Parse a `{ channel: {...} }` body. */
    fun singleChannel(body: JSONObject): GoodPostChannel? =
        body.optJSONObject("channel")?.let(::channel)

    /** Parse a `{ post: {...} }` body. */
    fun singlePost(body: JSONObject): GoodPostPost? =
        body.optJSONObject("post")?.let(::post)

    /** Parse a `{ categories: [...] }` body. */
    fun categories(body: JSONObject): List<GoodPostCategory> {
        val items = body.optJSONArray("categories") ?: JSONArray()
        val parsed = ArrayList<GoodPostCategory>(items.length())
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            val slug = row.optString("slug")
            if (slug.isBlank()) continue
            parsed.add(GoodPostCategory(slug, row.optString("label").ifBlank { slug }))
        }
        return parsed
    }

    /**
     * Append a freshly fetched page to what is already on screen.
     *
     * Keyed by id and last-write-wins, because a channel can legitimately appear
     * twice across pages when it publishes between two requests and moves up the
     * recent-activity ranking. Deduplicating on append means the list cannot show
     * the same channel twice, and the fresher copy wins.
     */
    fun <T> merge(
        existing: List<T>,
        incoming: List<T>,
        idOf: (T) -> String
    ): List<T> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming.distinctBy(idOf)

        val merged = LinkedHashMap<String, T>(existing.size + incoming.size)
        existing.forEach { merged[idOf(it)] = it }
        incoming.forEach { merged[idOf(it)] = it }
        return merged.values.toList()
    }

    // ── Encoding, for the offline cache (§27) ────────────────────────────

    fun encodeChannels(channels: List<GoodPostChannel>): String {
        val items = JSONArray()
        channels.forEach { channel ->
            items.put(JSONObject().apply {
                put("id", channel.id)
                put("slug", channel.slug)
                put("name", channel.name)
                put("description", channel.description ?: JSONObject.NULL)
                put("categorySlug", channel.categorySlug ?: JSONObject.NULL)
                put("categoryLabel", channel.categoryLabel ?: JSONObject.NULL)
                put("countryCode", channel.countryCode ?: JSONObject.NULL)
                // Cached, unlike a POST's media URLs, and the difference is what
                // this URL is for. A post's asset is the point of the post, so a
                // stale URL there is a hole in the content; an avatar is a
                // decoration, and the list renders the channel's initial when
                // the signature has expired. Keeping it means the tab opens with
                // real faces rather than letters (§26).
                put("iconUrl", channel.iconUrl ?: JSONObject.NULL)
                put("createdAt", channel.createdAt)
                put("lastPostAt", channel.lastPostAt ?: JSONObject.NULL)
                put("lastPostType", channel.lastPostType ?: JSONObject.NULL)
                put("lastPostPreview", channel.lastPostPreview ?: JSONObject.NULL)
                put("shareLink", channel.shareLink)
            })
        }
        return items.toString()
    }

    fun decodeChannels(raw: String?): List<GoodPostChannel> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val items = JSONArray(raw)
            val parsed = ArrayList<GoodPostChannel>(items.length())
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { channel(it)?.let(parsed::add) }
            }
            parsed
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Serialise posts for the cache.
     *
     * Signed media URLs are stripped on the way in. A presigned URL is a
     * short-lived capability, and writing one to disk presents an expired
     * address as a saved asset — the image renders as a broken box offline, which
     * is worse than an honest placeholder. Only the metadata is kept.
     */
    fun encodePosts(posts: List<GoodPostPost>): String {
        val items = JSONArray()
        posts.forEach { post ->
            items.put(JSONObject().apply {
                put("id", post.id)
                put("channelId", post.channelId)
                put("type", post.type)
                put("body", post.body ?: JSONObject.NULL)
                put("linkUrl", post.linkUrl ?: JSONObject.NULL)
                put("linkTitle", post.linkTitle ?: JSONObject.NULL)
                put("createdAt", post.createdAt)
                put("media", JSONArray().apply {
                    post.media.forEach { asset ->
                        put(JSONObject().apply {
                            put("id", asset.id)
                            put("kind", asset.kind)
                            put("contentType", asset.contentType)
                            put("byteSize", asset.byteSize)
                            put("width", asset.width ?: JSONObject.NULL)
                            put("height", asset.height ?: JSONObject.NULL)
                            put("durationMs", asset.durationMs ?: JSONObject.NULL)
                            put("position", asset.position)
                            put("url", JSONObject.NULL)
                        })
                    }
                })
            })
        }
        return items.toString()
    }

    fun decodePosts(raw: String?): List<GoodPostPost> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val items = JSONArray(raw)
            val parsed = ArrayList<GoodPostPost>(items.length())
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { post(it)?.let(parsed::add) }
            }
            parsed.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encodeCategories(categories: List<GoodPostCategory>): String {
        val items = JSONArray()
        categories.forEach { category ->
            items.put(JSONObject().apply {
                put("slug", category.slug)
                put("label", category.label)
            })
        }
        return JSONObject().apply { put("categories", items) }.toString()
    }

    fun decodeCategories(raw: String?): List<GoodPostCategory> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            categories(JSONObject(raw))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun mediaArray(items: JSONArray?): List<GoodPostMedia> {
        if (items == null) return emptyList()
        val parsed = ArrayList<GoodPostMedia>(items.length())
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { media(it)?.let(parsed::add) }
        }
        return parsed.sortedBy { it.position }
    }

    /** A value that is absent or JSON-null reads as null, not as "". */
    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)
}
