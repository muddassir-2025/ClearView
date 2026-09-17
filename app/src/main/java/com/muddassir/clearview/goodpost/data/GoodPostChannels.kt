package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Good Post channel, as the backend describes it (§6).
 *
 * Only fields the backend actually returns are modelled, and every one of them
 * is public. There is no owner field and no follower list, because the API
 * does not provide them (§12, §38) — a client cannot leak what it never
 * receives, which is a stronger guarantee than remembering not to display it.
 *
 * The viewer's own relationship to the channel ([isFollowing],
 * [notificationsEnabled], [isBlocked], [viewerRole]) IS modelled, because that
 * state belongs to the signed-in user rather than to anyone else.
 */
data class GoodPostChannel(
    val id: String,
    val slug: String,
    val name: String,
    val description: String?,
    val categorySlug: String?,
    val categoryLabel: String?,
    val countryCode: String?,
    val followerCount: Int,
    val postCount: Int,
    val allowFollowerMessages: Boolean,
    val shareLink: String,
    val isFollowing: Boolean,
    val notificationsEnabled: Boolean,
    val isBlocked: Boolean,
    /** `owner` / `editor` / `responder`, or null when the viewer holds none. */
    val viewerRole: String?,
    val hasUnread: Boolean,

    /**
     * When the channel last published, as an ISO timestamp.
     *
     * A channel row reads like a chat row — "3:45 PM" beside the preview — and
     * this is where that time comes from. Null means it has never posted, which
     * is a state worth saying rather than rendering as 1970.
     */
    val lastPostAt: String? = null,

    /**
     * The opening of the newest post's text, if any (§4).
     *
     * The second line of a row. Null for a channel whose newest post is only
     * media or a poll, which is why the row falls back to the description
     * rather than showing an empty line.
     */
    val lastPostPreview: String? = null,

    /** Present only on the detail payload; null in list payloads. */
    val status: String? = null
) {
    /** A channel the viewer owns — the only one they may edit (§7). */
    val isOwner: Boolean get() = viewerRole == "owner"

    /** Following, but with notifications off (§17). */
    val isMuted: Boolean get() = isFollowing && !notificationsEnabled

    /** A moderator has acted on this channel, so it cannot be followed. */
    val isAvailable: Boolean get() = status == null || status == "active"
}

/** One cursor page of channels. */
data class GoodPostChannelPage(
    val items: List<GoodPostChannel>,
    val nextCursor: String?
)

data class GoodPostCategory(val slug: String, val label: String)

/** How Discover orders results (§5). Mirrors the backend's `sort` enum. */
enum class ChannelSort(val wire: String) {
    Popular("popular"),
    Active("active"),
    New("new")
}

/**
 * Parsing backend JSON into [GoodPostChannel], and merging pages.
 *
 * Pure on purpose — the same reasoning as [GoodPostSessionCodec]. This is the
 * contract boundary with the server, it is the one place a field rename turns
 * every channel into a blank row, and testing it through the UI would be both
 * slower and weaker. An unexpected payload yields null (or an empty page)
 * rather than throwing, so a contract break surfaces as a retryable error
 * instead of a crash.
 */
internal object GoodPostChannelCodec {

    /**
     * Parse one channel, or null when it is unusable.
     *
     * `id` and `name` are required because a row without them cannot be
     * rendered or acted on. Everything else degrades to a sane default: a
     * missing follower count is 0, a missing flag is false. That asymmetry is
     * deliberate — a missing count is cosmetic, a missing id is a bug.
     */
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
            followerCount = json.optInt("followerCount", 0),
            postCount = json.optInt("postCount", 0),
            allowFollowerMessages = json.optBoolean("allowFollowerMessages", false),
            shareLink = json.optString("shareLink"),
            isFollowing = json.optBoolean("isFollowing", false),
            notificationsEnabled = json.optBoolean("notificationsEnabled", false),
            isBlocked = json.optBoolean("isBlocked", false),
            viewerRole = json.nullableString("viewerRole"),
            hasUnread = json.optBoolean("hasUnread", false),
            lastPostAt = json.nullableString("lastPostAt"),
            lastPostPreview = json.nullableString("lastPostPreview"),
            status = json.nullableString("status")
        )
    }

    /** Parse a `{ items, nextCursor }` page. Unparseable rows are skipped. */
    fun page(body: JSONObject): GoodPostChannelPage {
        val items = body.optJSONArray("items") ?: JSONArray()
        val parsed = ArrayList<GoodPostChannel>(items.length())
        for (i in 0 until items.length()) {
            val element = items.optJSONObject(i) ?: continue
            channel(element)?.let(parsed::add)
        }
        return GoodPostChannelPage(
            items = parsed,
            // An empty string would be sent back as a cursor and rejected as
            // invalid, turning the end of a list into an error.
            nextCursor = body.nullableString("nextCursor")?.takeIf { it.isNotBlank() }
        )
    }

    /** Parse a `{ channel: {...} }` single-channel body. */
    fun single(body: JSONObject): GoodPostChannel? =
        body.optJSONObject("channel")?.let(::channel)

    /** Parse a `{ channels: [...] }` body. */
    fun many(body: JSONObject): List<GoodPostChannel> {
        val items = body.optJSONArray("channels") ?: JSONArray()
        val parsed = ArrayList<GoodPostChannel>(items.length())
        for (i in 0 until items.length()) {
            val element = items.optJSONObject(i) ?: continue
            channel(element)?.let(parsed::add)
        }
        return parsed
    }

    /** Parse a `{ categories: [...] }` body. */
    fun categories(body: JSONObject): List<GoodPostCategory> {
        val items = body.optJSONArray("categories") ?: JSONArray()
        val parsed = ArrayList<GoodPostCategory>(items.length())
        for (i in 0 until items.length()) {
            val element = items.optJSONObject(i) ?: continue
            val slug = element.optString("slug")
            val label = element.optString("label")
            if (slug.isBlank()) continue
            parsed.add(GoodPostCategory(slug, label.ifBlank { slug }))
        }
        return parsed
    }

    /**
     * Append a freshly fetched page to what is already on screen.
     *
     * Keyed by id and last-write-wins, which matters for two cases paging
     * otherwise gets wrong: a channel can legitimately appear twice across
     * pages when it gains followers between requests and moves up the ranking,
     * and an action taken in between (follow, mute) returns a newer version of
     * a row that is already in the list. Deduplicating on append means the list
     * cannot show the same channel twice, and the fresher copy wins.
     */
    fun mergePage(
        existing: List<GoodPostChannel>,
        incoming: List<GoodPostChannel>
    ): List<GoodPostChannel> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming.distinctBy { it.id }

        val merged = LinkedHashMap<String, GoodPostChannel>(existing.size + incoming.size)
        existing.forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    /** Replace one channel in a list, so an action updates the row in place. */
    fun replace(
        list: List<GoodPostChannel>,
        updated: GoodPostChannel
    ): List<GoodPostChannel> = list.map { if (it.id == updated.id) updated else it }

    /** Remove a channel from a list, for a block or unfollow that hides it. */
    fun remove(list: List<GoodPostChannel>, id: String): List<GoodPostChannel> =
        list.filterNot { it.id == id }

    /** A JSON value that is absent or JSON-null reads as null, not "". */
    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    // ── Encoding, for the offline cache ──────────────────────────────────

    /**
     * Serialise a channel for local storage.
     *
     * Writes exactly the fields [channel] reads, using the same key names the
     * server uses — so one payload shape covers both directions and a
     * round-trip test can catch the drift that would otherwise show up as
     * blank rows after an app update.
     *
     * Only the caller's own relationship flags are stored. Nothing here is
     * another user's data, so a cached copy on disk is not a privacy concern
     * (§38) — and [clear] exists for sign-out regardless.
     */
    fun encode(channel: GoodPostChannel): JSONObject = JSONObject().apply {
        put("id", channel.id)
        put("slug", channel.slug)
        put("name", channel.name)
        put("description", channel.description ?: JSONObject.NULL)
        put("categorySlug", channel.categorySlug ?: JSONObject.NULL)
        put("categoryLabel", channel.categoryLabel ?: JSONObject.NULL)
        put("countryCode", channel.countryCode ?: JSONObject.NULL)
        put("followerCount", channel.followerCount)
        put("postCount", channel.postCount)
        put("allowFollowerMessages", channel.allowFollowerMessages)
        put("shareLink", channel.shareLink)
        put("isFollowing", channel.isFollowing)
        put("notificationsEnabled", channel.notificationsEnabled)
        put("isBlocked", channel.isBlocked)
        put("viewerRole", channel.viewerRole ?: JSONObject.NULL)
        put("hasUnread", channel.hasUnread)
        put("lastPostAt", channel.lastPostAt ?: JSONObject.NULL)
        put("lastPostPreview", channel.lastPostPreview ?: JSONObject.NULL)
        put("status", channel.status ?: JSONObject.NULL)
    }

    /** Serialise a page for the offline cache. */
    fun encodePage(page: GoodPostChannelPage): String {
        val items = JSONArray()
        page.items.forEach { items.put(encode(it)) }
        return JSONObject().apply {
            put("items", items)
            put("nextCursor", page.nextCursor ?: JSONObject.NULL)
        }.toString()
    }

    /**
     * Read a cached page back, or null when it is absent or unreadable.
     *
     * The cursor is deliberately dropped on the way out. A cached page's
     * cursor points into a ranking that has almost certainly moved, and §36
     * wants saved content to be READABLE offline — not to be a basis for
     * fetching "the next page" of a stale list.
     */
    fun decodePage(raw: String?): GoodPostChannelPage? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            // Stricter than [page], which tolerates a missing `items` because a
            // server response might legitimately omit it. A cached entry is
            // only ever written by [encodePage], so anything else means the
            // blob is damaged — and returning an empty page for damage would
            // show "no channels yet" to someone whose cache was fine a minute
            // ago. Null makes the caller say it could not load instead.
            if (root.opt("items") !is JSONArray) return null

            // The cursor is dropped even though [encodePage] stores it. A
            // cached cursor points into a ranking that has almost certainly
            // moved, and keeping it would offer a "load more" that fails the
            // moment it is tapped offline. Cached content is for reading.
            page(root).copy(nextCursor = null)
        } catch (e: Exception) {
            null
        }
    }

    /** Serialise the category list for the offline cache. */
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
}
