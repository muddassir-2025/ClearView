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
    /**
     * The platform's own badge (§7).
     *
     * Set by a super administrator, never earned: it is drawn beside the name on
     * discovery rows and nowhere else, because it is a statement about who runs
     * the channel and a discovery row is where that is being decided.
     */
    val verified: Boolean = false,
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

/** One stored asset on a post: an image, a video or a document. */
data class GoodPostMedia(
    val id: String,
    /** `image`, `video` or `document`. */
    val kind: String,
    val contentType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    /**
     * The name the sender's file had, for a document.
     *
     * Null for an image or a clip: a reader sees the picture rather than what it
     * was called on disk. For a document it is the whole card — there is no
     * preview to draw — so the server refuses a document without one (§21).
     */
    val fileName: String?,
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

    /**
     * A file the app hands to another app rather than drawing.
     *
     * Its card is a name and a size, and a tap opens it in whatever the device
     * has registered for a PDF (§21). Nothing here renders a page of one — an
     * in-app reader is a screen of its own, and a document that is worth keeping
     * is worth opening where it can be zoomed, searched and printed.
     */
    val isDocument: Boolean get() = kind == "document"
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
    /** The sender's file name, for a document. Null for the other kinds. */
    val fileName: String?,
    val createdAt: String,
    val url: String?
) {
    val isImage: Boolean get() = kind == "image"
    val isVideo: Boolean get() = kind == "video"
    val isDocument: Boolean get() = kind == "document"
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
    val views: Int = 0,
    /**
     * What readers have reacted with, commonest first (§9).
     *
     * Counts of readers and nothing else: the names behind them never leave the
     * server, so this is not a list of who reacted and cannot become one.
     */
    val reactions: List<GoodPostReaction> = emptyList(),
    /**
     * Which emoji THIS reader picked, if any.
     *
     * Not part of the payload: the public read takes no token, so the app fetches
     * the reader's own reactions for the channel it is showing and merges them in
     * (see `GoodPostReactions`). Null means "not asked yet", which is why the
     * chip is drawn as the reader's only when this is set.
     */
    val myReaction: String? = null
) {
    val hasImage: Boolean get() = media.any { it.isImage }
    val hasVideo: Boolean get() = media.any { it.isVideo }
    val isLink: Boolean get() = !linkUrl.isNullOrBlank()

    /** The reaction this reader has on the post, if the server listed it. */
    fun reactionCount(emoji: String): Int = reactions.firstOrNull { it.emoji == emoji }?.count ?: 0
}

/**
 * One emoji and how many readers chose it (§9).
 *
 * A total, not a list. The Android side never learns who, because the API never
 * says — a reaction count is a property of the post, and the reader's own choice
 * travels separately as [GoodPostPost.myReaction].
 */
data class GoodPostReaction(val emoji: String, val count: Int) {
    val isEmpty: Boolean get() = count <= 0
}

/**
 * The server's answer to setting or clearing one reaction (§9).
 *
 * Carried back rather than discarded because the count it holds is the number of
 * readers currently on that emoji, as the server just saw it — which is what a
 * card should show. Guessing it locally ("mine, so +1") is right until somebody
 * else reacts at the same moment, and then the card is quietly wrong until the
 * next refresh.
 */
data class GoodPostReactionResult(
    val postId: String,
    /** Null after an un-react. */
    val emoji: String?,
    val count: Int
)

/** A category an Explore filter can offer (§7). */
data class GoodPostCategory(val slug: String, val label: String)

/**
 * One entry in the global Brain Rot repository, as an administrator sees it.
 *
 * Keyword and channel share this type because they are managed identically —
 * the queue, the two lists and the enable/disable action all describe one row.
 * [value] is the keyword or the `@handle`; [kind] says which, so a caller that
 * needs to know does not have to infer it from the leading "@".
 */
data class GoodPostBrainRotRule(
    val id: String,
    /** `keyword` or `channel`. */
    val kind: String,
    val value: String,
    /** A channel's display name, when the repository has one. */
    val displayName: String?,
    val reason: String?,
    /** Disabled rules stay in the repository but stop being served. */
    val enabled: Boolean,
    /** Distinct devices that reported this. */
    val reports: Int,
    val createdAt: String?
) {
    val isChannel: Boolean get() = kind == "channel"
}

/**
 * A community suggestion awaiting review.
 *
 * [reports] is the number of distinct devices that reported the same target, so
 * a reviewer can weigh "one person asked" against "many asked" in the same row
 * they approve from.
 */
data class GoodPostBrainRotSubmission(
    val id: String,
    /** `keyword` or `channel`. */
    val kind: String,
    val value: String,
    /** A channel's name as the user saw it. Null for keywords. */
    val displayName: String?,
    val note: String?,
    /** Where it came from: `youtube_not_interested`, `app` or `unknown`. */
    val source: String,
    /** `pending`, `approved`, `rejected` or `under_review`. */
    val status: String,
    /** Distinct devices that reported the target. */
    val reports: Int,
    /** Distinct devices that asked for it globally. */
    val requesters: Int,
    val createdAt: String?
) {
    val isChannel: Boolean get() = kind == "channel"

    /**
     * True while a decision is still open.
     *
     * "Under review" counts: it is an operator saying they are looking at it,
     * not a decision, so the Approve/Reject actions stay available.
     */
    val isOpen: Boolean get() = status == "pending" || status == "under_review"
}

/**
 * One target's demand, as the dashboard lists it.
 *
 * Two numbers, deliberately different: [usersBlocking] is how many devices
 * reported it, [globalRequests] is how many asked for it to be blocked for
 * everyone. An operator weighing a queue needs both, and collapsing them into
 * one "popularity" number would hide which question the count answers.
 */
data class GoodPostBrainRotDemand(
    val kind: String,
    val value: String,
    val displayName: String?,
    val usersBlocking: Int,
    val globalRequests: Int,
    val status: String?
) {
    val isChannel: Boolean get() = kind == "channel"
}

/** The dashboard's totals and its two most-requested lists. */
data class GoodPostBrainRotDashboard(
    val pending: Int = 0,
    val approved: Int = 0,
    val rejected: Int = 0,
    val underReview: Int = 0,
    val total: Int = 0,
    val topChannels: List<GoodPostBrainRotDemand> = emptyList(),
    val topKeywords: List<GoodPostBrainRotDemand> = emptyList()
)

/**
 * An advertisement card (§9–§17).
 *
 * Platform-controlled content shown at the top of Channels and Explore. It is
 * NOT a third-party ad SDK: a super administrator writes it, uploads its picture
 * and decides where and when it runs. This is the shape BOTH audiences receive —
 * the public read and the admin list — because it describes one row, and the
 * fields the admin editor needs (placement, enabled, schedule, priority) are the
 * same ones that decide whether a reader is ever sent it.
 *
 * A text card and an image card share the type: [text] is what is drawn, and
 * [imageUrl] is non-null only for an image card (and only when the deployment
 * has a bucket to sign one from, §22). What is rendered is therefore a property
 * of the card rather than of which screen fetched it.
 */
data class GoodPostAd(
    val id: String,
    /** `image` or `text`. */
    val contentType: String,
    /**
     * A short-lived signed URL for the card's picture, or null.
     *
     * Null for a text card, and null for an image card on a deployment with no
     * storage — the second is worded by the editor rather than by a reader, who
     * is simply never shown an image card there.
     */
    val imageUrl: String?,
    /** The words on a text card; null for an image card. */
    val text: String?,
    /** Where a tap goes: an `http(s):` page or a `mailto:` address (§12). */
    val targetUrl: String?,
    /** Placement: shown at the top of Channels. */
    val showInChannels: Boolean,
    /** Placement: shown at the top of Explore. */
    val showInExplore: Boolean,
    /** Admin only in effect — the public read never returns a disabled card. */
    val enabled: Boolean,
    /** ISO instant, or null. */
    val startsAt: String?,
    /** ISO instant, or null for a card that never expires. */
    val expiresAt: String?,
    /** Lower sorts first; ties fall back to newest. */
    val priority: Int,
    /**
     * Text cards: the `#RRGGBB` the words are drawn in.
     *
     * A colour rather than a named style, because a card is a poster and the
     * administrator is laying it out: a plain card on a dark surface is one look,
     * and a card that has to shout is another. Empty or malformed falls back to
     * the reader's ordinary ink.
     */
    val textColor: String = AD_DEFAULT_TEXT_COLOR,
    /**
     * The card's own surface, `#RRGGBB`.
     *
     * The card used to be drawn on the app's bar colour, which made every
     * advertisement look like a piece of the interface. It is a poster: its
     * background is part of the design, so the administrator chooses it.
     */
    val backgroundColor: String = AD_DEFAULT_BACKGROUND_COLOR,
    /**
     * Image cards: `cover` or `contain`.
     *
     * `cover` fills the card and crops; `contain` shows the whole picture with the
     * card's surface behind it. Two different jobs — a photograph is usually
     * `cover`, a logo with words in it is always `contain` — and the editor offers
     * both rather than deciding for the administrator.
     */
    val imageFit: String = "cover",
    /**
     * Image cards: the point of the picture to keep when it is cropped, 0..1.
     *
     * Set by the crop step in the editor and sent with the card, so the SAME
     * rectangle a reader sees was chosen by the person who made the card instead
     * of by a default in the middle.
     */
    val imageFocusX: Float = 0.5f,
    val imageFocusY: Float = 0.5f
) {
    val isImage: Boolean get() = contentType == "image"
    val isText: Boolean get() = contentType == "text"

    /**
     * Whether the card's own window contains [nowMs] — the rule the server applies.
     *
     * Needed on the CLIENT only for the cached copy: the server filters a live
     * read by the clock, but a card saved an hour ago can have expired since, and
     * a cached advertisement that has run out must not be shown. Applying the same
     * rule here is what keeps the cache honest without a second endpoint.
     */
    fun isActiveAt(nowMs: Long): Boolean {
        val start = parseIsoMillis(startsAt)
        if (start != null && start > nowMs) return false
        val end = parseIsoMillis(expiresAt)
        if (end != null && end <= nowMs) return false
        return true
    }
}

/** The ink a text card falls back to when it has no colour of its own. */
const val AD_DEFAULT_TEXT_COLOR = "#E9EDEF"

/** The surface a card falls back to — the app's own bar, as it was drawn before. */
const val AD_DEFAULT_BACKGROUND_COLOR = "#202C33"

/**
 * The surfaces a card can be drawn on.
 *
 * Dark-first, because the card sits on a near-black list and a light panel there
 * is a different kind of object rather than a louder one. The last two are the
 * exceptions and are deliberately muted — a card is allowed to stand out, not to
 * glare.
 */
val AD_BACKGROUND_COLORS = listOf(
    "#202C33", // the bar colour, for a card that reads as part of the app
    "#103529", // the WhatsApp deep green, for an offer
    "#3B1F2B", // wine, for an announcement
    "#1B2A4A", // navy, for something informational
    "#3A2E12", // bronze, for a deadline
    "#0B141A", // the canvas itself, for a card that is only its words
    "#FFFFFF"  // white, for a card that has to be seen from across a room
)

/**
 * The colours the card editor offers for a text card.
 *
 * A short, fixed palette rather than a colour wheel: every one of these is
 * legible on the card's dark surface, which is the only property that matters
 * and the one a free picker lets an administrator get wrong. The list is the
 * card's own set, not the app's theme — a card may deliberately be louder than
 * the rest of the tab.
 */
val AD_TEXT_COLORS = listOf(
    "#E9EDEF", // the reader's ordinary ink
    "#25D366", // the WhatsApp green, for a card that reads as an offer
    "#FFD166", // amber, for a warning or a deadline
    "#7FD4FF", // sky, for a link-like card
    "#FF8FA3", // rose, for a card that should stop the scroll
    "#FFFFFF", // plain white, for a picture behind a dark scrim
    "#000000"  // black, for a card on a light background
)

/** Where a card can appear (§12). One endpoint serves both surfaces. */
enum class GoodPostAdPlacement(val wire: String) {
    Channels("channels"),
    Explore("explore")
}

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
            verified = json.optBoolean("verified", false),
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
            fileName = json.nullableString("fileName"),
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
            views = json.optInt("views", 0).coerceAtLeast(0),
            reactions = reactionArray(json.optJSONArray("reactions"))
        )
    }

    /**
     * The reaction counts on a post.
     *
     * A zero count is dropped rather than shown: the server only ever sends
     * emoji somebody chose, and a card that drew "0" next to an emoji would be
     * describing a reaction that no longer exists.
     */
    fun reactionArray(array: JSONArray?): List<GoodPostReaction> {
        if (array == null) return emptyList()
        val parsed = ArrayList<GoodPostReaction>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val emoji = row.optString("emoji")
            val count = row.optInt("count", 0)
            if (emoji.isBlank() || count <= 0) continue
            parsed.add(GoodPostReaction(emoji = emoji, count = count))
        }
        return parsed
    }

    /**
     * A `{ reactions: { postId: emoji } }` body — the reader's own choices.
     *
     * Tolerant of an unknown post id: the map is merged into whatever page is on
     * screen, and a reaction on a post that has scrolled out of the window is
     * simply not drawn.
     */
    fun readerReactions(body: JSONObject): Map<String, String> {
        val row = body.optJSONObject("reactions") ?: return emptyMap()
        val map = HashMap<String, String>(row.length())
        row.keys().forEach { key ->
            val emoji = row.optString(key)
            if (emoji.isNotBlank()) map[key] = emoji
        }
        return map
    }

    /** A `{ reaction: { postId, emoji, count } }` body. */
    fun reactionResult(body: JSONObject): GoodPostReactionResult? {
        val row = body.optJSONObject("reaction") ?: return null
        val postId = row.optString("postId")
        if (postId.isBlank()) return null
        return GoodPostReactionResult(
            postId = postId,
            emoji = row.nullableString("emoji"),
            count = row.optInt("count", 0).coerceAtLeast(0)
        )
    }

    /** The six offered (§9), from `GET /readers/reactions`. */
    fun reactionVocabulary(body: JSONObject): List<String> {
        val array = body.optJSONArray("emoji") ?: return emptyList()
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val emoji = array.optString(i)
            if (emoji.isNotBlank()) list.add(emoji)
        }
        return list
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
            fileName = json.nullableString("fileName"),
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

    /**
     * A page of a channel's media, for the gallery and the media strip.
     *
     * Documents are dropped HERE rather than in the grid that draws them. Both
     * surfaces are a wall of PREVIEWS — a tile is the picture, or a poster frame
     * and a play badge — and a document has neither: it would be a tile with
     * nothing in it, whose tap opened a viewer for a file it cannot show. Its card
     * on the post it came from is where it is opened, so it is not in the wall.
     *
     * The server still sends it. This is a decision about a grid, not about what a
     * channel contains, so it is made at the point the grid consumes the page.
     */
    fun mediaPage(body: JSONObject): GoodPostPage<GoodPostMediaItem> =
        page(body, ::mediaItem).let { parsed ->
            parsed.copy(items = parsed.items.filterNot { it.isDocument })
        }

    /** Parse a `{ channel: {...} }` body. */
    fun singleChannel(body: JSONObject): GoodPostChannel? =
        body.optJSONObject("channel")?.let(::channel)

    /** Parse a `{ post: {...} }` body. */
    fun singlePost(body: JSONObject): GoodPostPost? =
        body.optJSONObject("post")?.let(::post)

    /**
     * One advertisement.
     *
     * `id` and `contentType` are required because a card without them cannot be
     * acted on or drawn; the rest degrades to a default so a missing schedule is
     * "none" rather than a blank screen. An unknown content type is dropped at
     * [adList] rather than shown as a card with no content.
     */
    fun ad(json: JSONObject): GoodPostAd? {
        val id = json.optString("id")
        val contentType = json.optString("contentType")
        if (id.isBlank() || contentType.isBlank()) return null
        return GoodPostAd(
            id = id,
            contentType = contentType,
            imageUrl = json.nullableString("imageUrl"),
            text = json.nullableString("text"),
            targetUrl = json.nullableString("targetUrl"),
            showInChannels = json.optBoolean("showInChannels", false),
            showInExplore = json.optBoolean("showInExplore", false),
            enabled = json.optBoolean("enabled", false),
            startsAt = json.nullableString("startsAt"),
            expiresAt = json.nullableString("expiresAt"),
            priority = json.optInt("priority", 100),
            textColor = json.optString("textColor").takeIf { it.isNotBlank() }
                ?: AD_DEFAULT_TEXT_COLOR,
            backgroundColor = json.optString("backgroundColor").takeIf { it.isNotBlank() }
                ?: AD_DEFAULT_BACKGROUND_COLOR,
            imageFit = if (json.optString("imageFit") == "contain") "contain" else "cover",
            imageFocusX = json.optDouble("imageFocusX", 0.5).toFloat().coerceIn(0f, 1f),
            imageFocusY = json.optDouble("imageFocusY", 0.5).toFloat().coerceIn(0f, 1f)
        )
    }

    /** Parse a `{ ads: [...] }` body. */
    fun adList(body: JSONObject): List<GoodPostAd> {
        val items = body.optJSONArray("ads") ?: return emptyList()
        val parsed = ArrayList<GoodPostAd>(items.length())
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            ad(row)?.let(parsed::add)
        }
        return parsed
    }

    /** Parse a `{ ad: {...} }` body. */
    fun singleAd(body: JSONObject): GoodPostAd? =
        body.optJSONObject("ad")?.let(::ad)

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
                put("verified", channel.verified)
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
     * Serialise advertisement cards for the cache (§12, §27).
     *
     * The reader's carousel is the same few cards on every open, and asking the
     * server for them each time made opening the tab wait on a read whose answer
     * had not changed. Cached like the channel list is, and for the same reason.
     *
     * [GoodPostAd.imageUrl] is deliberately NOT written, unlike a channel's
     * avatar: it is a short-lived signed URL and a card IS its picture, so a
     * cached card drawing an expired address would be a card with a hole in it.
     * A cached image card therefore renders nothing until the live read replaces
     * it, which is the same state [ad] already handles for a null URL.
     */
    fun encodeAds(ads: List<GoodPostAd>): String {
        val items = JSONArray()
        ads.forEach { ad ->
            items.put(JSONObject().apply {
                put("id", ad.id)
                put("contentType", ad.contentType)
                put("text", ad.text ?: JSONObject.NULL)
                put("targetUrl", ad.targetUrl ?: JSONObject.NULL)
                put("showInChannels", ad.showInChannels)
                put("showInExplore", ad.showInExplore)
                put("enabled", ad.enabled)
                put("startsAt", ad.startsAt ?: JSONObject.NULL)
                put("expiresAt", ad.expiresAt ?: JSONObject.NULL)
                put("priority", ad.priority)
                put("textColor", ad.textColor)
                put("backgroundColor", ad.backgroundColor)
                put("imageFit", ad.imageFit)
                put("imageFocusX", ad.imageFocusX.toDouble())
                put("imageFocusY", ad.imageFocusY.toDouble())
            })
        }
        return items.toString()
    }

    fun decodeAds(raw: String?): List<GoodPostAd> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val items = JSONArray(raw)
            val parsed = ArrayList<GoodPostAd>(items.length())
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { ad(it)?.let(parsed::add) }
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

    // ── Brain Rot repository (admin, /admin/api/brainrot) ────────────────

    /**
     * One global rule, keyword or channel.
     *
     * The SAME shape for both, with [value] carrying the keyword or the handle,
     * because the review queue, the two management lists and the rules endpoint
     * all describe one row and the only difference is which column holds the
     * string. A channel's `value` is already normalised to a leading "@" by the
     * server, so nothing here has to guess.
     */
    fun brainRotRule(json: JSONObject): GoodPostBrainRotRule? {
        val id = json.nullableString("id") ?: return null
        val keyword = json.nullableString("keyword")
        val handle = json.nullableString("handle")
        val value = keyword ?: handle ?: return null
        val isChannel = handle != null
        return GoodPostBrainRotRule(
            id = id,
            kind = if (isChannel) "channel" else "keyword",
            value = value,
            displayName = json.nullableString("displayName"),
            reason = json.nullableString("reason"),
            enabled = json.optBoolean("enabled", true),
            reports = json.optInt("reports", 0),
            createdAt = json.nullableString("createdAt")
        )
    }

    /** Parse a `{ keywords: [...] }` body. */
    fun brainRotKeywords(body: JSONObject): List<GoodPostBrainRotRule> =
        brainRotRuleList(body.optJSONArray("keywords"))

    /** Parse a `{ channels: [...] }` body. */
    fun brainRotChannels(body: JSONObject): List<GoodPostBrainRotRule> =
        brainRotRuleList(body.optJSONArray("channels"))

    private fun brainRotRuleList(items: JSONArray?): List<GoodPostBrainRotRule> {
        if (items == null) return emptyList()
        val parsed = ArrayList<GoodPostBrainRotRule>(items.length())
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { brainRotRule(it)?.let(parsed::add) }
        }
        return parsed
    }

    /** Parse a `{ submissions: [...] }` body. */
    fun brainRotSubmissions(body: JSONObject): List<GoodPostBrainRotSubmission> {
        val items = body.optJSONArray("submissions") ?: return emptyList()
        val parsed = ArrayList<GoodPostBrainRotSubmission>(items.length())
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            val id = row.nullableString("id") ?: continue
            val value = row.nullableString("value") ?: continue
            val kind = if (row.optString("kind") == "channel") "channel" else "keyword"
            parsed.add(
                GoodPostBrainRotSubmission(
                    id = id,
                    kind = kind,
                    value = value,
                    displayName = row.nullableString("displayName"),
                    note = row.nullableString("note"),
                    source = row.nullableString("source") ?: "unknown",
                    status = row.nullableString("status") ?: "pending",
                    reports = row.optInt("reports", 0),
                    requesters = row.optInt("requesters", 0),
                    createdAt = row.nullableString("createdAt")
                )
            )
        }
        return parsed
    }

    /** Parse a `{ totals: {...}, topChannels: [...], topKeywords: [...] }` body. */
    fun brainRotDashboard(body: JSONObject): GoodPostBrainRotDashboard {
        val totals = body.optJSONObject("totals")
        return GoodPostBrainRotDashboard(
            pending = totals?.optInt("pending", 0) ?: 0,
            approved = totals?.optInt("approved", 0) ?: 0,
            rejected = totals?.optInt("rejected", 0) ?: 0,
            underReview = totals?.optInt("underReview", 0) ?: 0,
            total = totals?.optInt("all", 0) ?: 0,
            topChannels = brainRotDemandList(body.optJSONArray("topChannels")),
            topKeywords = brainRotDemandList(body.optJSONArray("topKeywords"))
        )
    }

    private fun brainRotDemandList(items: JSONArray?): List<GoodPostBrainRotDemand> {
        if (items == null) return emptyList()
        val parsed = ArrayList<GoodPostBrainRotDemand>(items.length())
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            val value = row.nullableString("value") ?: continue
            parsed.add(
                GoodPostBrainRotDemand(
                    kind = if (row.optString("kind") == "channel") "channel" else "keyword",
                    value = value,
                    displayName = row.nullableString("displayName"),
                    usersBlocking = row.optInt("usersBlocking", 0),
                    globalRequests = row.optInt("globalRequests", 0),
                    status = row.nullableString("status")
                )
            )
        }
        return parsed
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
