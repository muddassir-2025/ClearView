package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reactions, polls and view counts (§13, §14, §15).
 *
 * Everything in this file is AGGREGATE data plus the caller's OWN choice. The
 * API never sends a voter or a reactor, so there is no field here to leak by
 * accident — which is a stronger guarantee than remembering not to render one
 * (§38).
 *
 * The whole engagement block arrives attached to each post rather than as a
 * second request per row. A feed that fetched counts separately would either
 * make one round trip per post or render numbers that belong to a different
 * post than the one beside them; the backend builds it in four queries for a
 * whole page for exactly this reason.
 */

/** One reaction and how many people chose it. */
data class GoodPostReaction(val reaction: String, val count: Int)

/** One poll option and its vote count. */
data class GoodPostPollOption(
    val id: String,
    val position: Int,
    val label: String,
    val votes: Int
)

/**
 * A poll as a reader sees it (§14).
 *
 * [totalVotes] counts SELECTIONS, not people: in a multiple-choice poll one
 * voter contributes several. Reporting it as voters would overstate
 * participation, so the name says what it is and the UI must not call it
 * "voters".
 *
 * [viewerVotes] is the only per-viewer field, and it is the caller's own — it
 * is what lets the client show "you chose B" without ever learning who else
 * did.
 */
data class GoodPostPoll(
    val id: String,
    val question: String,
    val allowMultiple: Boolean,
    val closesAt: String?,
    val isClosed: Boolean,
    val totalVotes: Int,
    val options: List<GoodPostPollOption>,
    val viewerVotes: List<String>
) {
    val hasVoted: Boolean get() = viewerVotes.isNotEmpty()

    /** The largest option count, used to scale the bars. Zero when nobody voted. */
    val highestVotes: Int get() = options.maxOfOrNull { it.votes } ?: 0

    /** This caller's share of one option, as a 0f..1f fraction for a bar. */
    fun share(option: GoodPostPollOption): Float =
        if (totalVotes <= 0) 0f else option.votes.toFloat() / totalVotes.toFloat()
}

/**
 * A post's engagement, always present.
 *
 * Never null on a post: a post nobody has touched still renders a zero state,
 * and making the UI null-check every count is how a "--" ends up on screen.
 */
data class GoodPostEngagement(
    val reactions: List<GoodPostReaction>,
    val reactionTotal: Int,
    val viewerReaction: String?,
    /** §15's headline: distinct accounts that have opened the post. */
    val uniqueViewers: Int,
    /** Looks, capped by the server's dedupe window. Never below [uniqueViewers]. */
    val totalViews: Int,
    val viewerHasViewed: Boolean,
    val poll: GoodPostPoll?
) {
    fun countOf(reaction: String): Int =
        reactions.firstOrNull { it.reaction == reaction }?.count ?: 0

    companion object {
        /** The zero state, so no payload ever needs a null check. */
        val none = GoodPostEngagement(
            reactions = emptyList(),
            reactionTotal = 0,
            viewerReaction = null,
            uniqueViewers = 0,
            totalViews = 0,
            viewerHasViewed = false,
            poll = null
        )
    }
}

/** What a reaction write returns: the fresh counts and the caller's choice. */
data class GoodPostReactionState(
    val reaction: String?,
    val reactions: List<GoodPostReaction>
) {
    val total: Int get() = reactions.sumOf { it.count }
}

/**
 * The reactions this client offers, in the order it offers them.
 *
 * Mirrors the server's `reaction_kind` enum. A value the server does not know
 * is a `400 invalid_reaction`, so the set is stated once here rather than
 * derived from whatever a payload happened to contain.
 */
val GOODPOST_REACTIONS: List<String> = listOf("like", "love", "laugh", "wow", "sad", "angry")

/**
 * The emoji shown for each reaction, kept beside the wire name.
 *
 * Presentation data, not protocol: the label a user sees must not be what
 * travels, or changing an icon would be a breaking API change.
 */
fun goodPostReactionEmoji(reaction: String): String = when (reaction) {
    "like" -> "\uD83D\uDC4D"
    "love" -> "\u2764\uFE0F"
    "laugh" -> "\uD83D\uDE02"
    "wow" -> "\uD83D\uDE2E"
    "sad" -> "\uD83D\uDE22"
    "angry" -> "\uD83D\uDE21"
    else -> "\u2022"
}

// ── Channel analytics (§15) ─────────────────────────────────────────────

/** A channel's totals over the analytics window. */
data class GoodPostAnalyticsTotals(
    val followers: Int,
    val posts: Int,
    val uniqueViewers: Int,
    val totalViews: Int,
    val reactions: Int,
    val pollVotes: Int
)

/** One day of the series. A day with no activity is a zero, never absent. */
data class GoodPostAnalyticsPoint(val day: String, val views: Int, val newFollowers: Int)

/** One of the channel's best-performing posts. */
data class GoodPostAnalyticsPost(
    val postId: String,
    val type: String,
    val createdAt: String,
    val uniqueViewers: Int,
    val totalViews: Int,
    val reactions: Int
)

data class GoodPostAnalytics(
    val channelId: String,
    val windowDays: Int,
    val totals: GoodPostAnalyticsTotals,
    val series: List<GoodPostAnalyticsPoint>,
    val topPosts: List<GoodPostAnalyticsPost>
) {
    val peakViews: Int get() = series.maxOfOrNull { it.views } ?: 0
}

/**
 * Parsing engagement JSON (§13, §14, §15).
 *
 * Pure and unit-tested directly, like every other codec here: this is where a
 * server-side rename would otherwise turn every count on screen into a zero,
 * and a zero is indistinguishable from "nobody reacted" — a wrong answer that
 * looks exactly like a right one. Anything unrecognised degrades to the zero
 * state rather than throwing, so a contract break shows up as missing numbers
 * instead of a crash.
 */
internal object GoodPostEngagementCodec {

    /** Parse the `engagement` block of a post, or the zero state. */
    fun engagement(json: JSONObject?): GoodPostEngagement {
        if (json == null) return GoodPostEngagement.none

        return GoodPostEngagement(
            reactions = reactions(json.optJSONArray("reactions")),
            reactionTotal = json.optInt("reactionTotal", 0),
            viewerReaction = json.nullableString("viewerReaction"),
            uniqueViewers = json.optInt("uniqueViewers", 0),
            totalViews = json.optInt("totalViews", 0),
            viewerHasViewed = json.optBoolean("viewerHasViewed", false),
            poll = json.optJSONObject("poll")?.let(::poll)
        )
    }

    private fun reactions(array: JSONArray?): List<GoodPostReaction> {
        if (array == null) return emptyList()
        val parsed = ArrayList<GoodPostReaction>(array.length())
        for (i in 0 until array.length()) {
            val element = array.optJSONObject(i) ?: continue
            val reaction = element.optString("reaction")
            // A count with no name cannot be rendered or tapped, so it is
            // dropped rather than shown as an anonymous bar.
            if (reaction.isBlank()) continue
            parsed.add(GoodPostReaction(reaction, element.optInt("count", 0)))
        }
        return parsed
    }

    private fun poll(json: JSONObject): GoodPostPoll? {
        val id = json.optString("id")
        val question = json.optString("question")
        if (id.isBlank()) return null

        return GoodPostPoll(
            id = id,
            question = question,
            allowMultiple = json.optBoolean("allowMultiple", false),
            closesAt = json.nullableString("closesAt"),
            // Read from the server rather than derived from `closesAt` here:
            // the client's clock can be wrong, and a poll that looked open
            // while the server refused every vote would be a dead end.
            isClosed = json.optBoolean("isClosed", false),
            totalVotes = json.optInt("totalVotes", 0),
            options = pollOptions(json.optJSONArray("options")),
            viewerVotes = stringArray(json.optJSONArray("viewerVotes"))
        )
    }

    private fun pollOptions(array: JSONArray?): List<GoodPostPollOption> {
        if (array == null) return emptyList()
        val parsed = ArrayList<GoodPostPollOption>(array.length())
        for (i in 0 until array.length()) {
            val element = array.optJSONObject(i) ?: continue
            val id = element.optString("id")
            if (id.isBlank()) continue
            parsed.add(
                GoodPostPollOption(
                    id = id,
                    position = element.optInt("position", i),
                    label = element.optString("label"),
                    votes = element.optInt("votes", 0)
                )
            )
        }
        return parsed.sortedBy { it.position }
    }

    private fun stringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val parsed = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) parsed.add(value)
        }
        return parsed
    }

    /** Parse the `{ reaction, reactions }` body a reaction write returns. */
    fun reactionState(body: JSONObject): GoodPostReactionState = GoodPostReactionState(
        reaction = body.nullableString("reaction"),
        reactions = reactions(body.optJSONArray("reactions"))
    )

    /** Parse a `{ poll: {...} }` body — what a vote returns. */
    fun pollFrom(body: JSONObject): GoodPostPoll? =
        body.optJSONObject("poll")?.let(::poll)

    /** Parse a `{ uniqueViewers, totalViews, counted, viewerHasViewed }` body. */
    fun viewState(body: JSONObject): GoodPostViewState = GoodPostViewState(
        uniqueViewers = body.optInt("uniqueViewers", 0),
        totalViews = body.optInt("totalViews", 0),
        counted = body.optBoolean("counted", false),
        viewerHasViewed = body.optBoolean("viewerHasViewed", false)
    )

    /** Parse a `{ analytics: {...} }` body. Null when the shape is unusable. */
    fun analytics(body: JSONObject): GoodPostAnalytics? {
        val json = body.optJSONObject("analytics") ?: return null
        val channelId = json.optString("channelId")
        if (channelId.isBlank()) return null

        val totals = json.optJSONObject("totals")

        val series = ArrayList<GoodPostAnalyticsPoint>()
        json.optJSONArray("series")?.let { array ->
            for (i in 0 until array.length()) {
                val element = array.optJSONObject(i) ?: continue
                val day = element.optString("day")
                if (day.isBlank()) continue
                series.add(
                    GoodPostAnalyticsPoint(
                        day = day,
                        views = element.optInt("views", 0),
                        newFollowers = element.optInt("newFollowers", 0)
                    )
                )
            }
        }

        val topPosts = ArrayList<GoodPostAnalyticsPost>()
        json.optJSONArray("topPosts")?.let { array ->
            for (i in 0 until array.length()) {
                val element = array.optJSONObject(i) ?: continue
                val postId = element.optString("postId")
                if (postId.isBlank()) continue
                topPosts.add(
                    GoodPostAnalyticsPost(
                        postId = postId,
                        type = element.optString("type"),
                        createdAt = element.optString("createdAt"),
                        uniqueViewers = element.optInt("uniqueViewers", 0),
                        totalViews = element.optInt("totalViews", 0),
                        reactions = element.optInt("reactions", 0)
                    )
                )
            }
        }

        return GoodPostAnalytics(
            channelId = channelId,
            windowDays = json.optInt("windowDays", 0),
            totals = GoodPostAnalyticsTotals(
                followers = totals?.optInt("followers", 0) ?: 0,
                posts = totals?.optInt("posts", 0) ?: 0,
                uniqueViewers = totals?.optInt("uniqueViewers", 0) ?: 0,
                totalViews = totals?.optInt("totalViews", 0) ?: 0,
                reactions = totals?.optInt("reactions", 0) ?: 0,
                pollVotes = totals?.optInt("pollVotes", 0) ?: 0
            ),
            series = series,
            topPosts = topPosts
        )
    }

    // ── Applying a confirmed write ───────────────────────────────────────

    /**
     * Fold a server-confirmed reaction into a post's engagement.
     *
     * The counts come from the response rather than being incremented locally:
     * two people reacting at the same moment would otherwise leave the number
     * wrong until the next refresh, and §36 says a number on screen is one the
     * server confirmed.
     */
    fun withReaction(
        engagement: GoodPostEngagement,
        state: GoodPostReactionState
    ): GoodPostEngagement = engagement.copy(
        reactions = state.reactions,
        reactionTotal = state.total,
        viewerReaction = state.reaction
    )

    /** Fold a confirmed vote into a post's engagement. */
    fun withPoll(engagement: GoodPostEngagement, poll: GoodPostPoll): GoodPostEngagement =
        engagement.copy(poll = poll)

    /** Fold a confirmed view ping into a post's engagement. */
    fun withView(engagement: GoodPostEngagement, state: GoodPostViewState): GoodPostEngagement =
        engagement.copy(
            uniqueViewers = state.uniqueViewers,
            totalViews = state.totalViews,
            viewerHasViewed = state.viewerHasViewed || engagement.viewerHasViewed
        )

    // ── Cache encoding (§36) ─────────────────────────────────────────────

    /**
     * Serialise engagement for the offline cache.
     *
     * Aggregates and the caller's own choice only — which is all the server
     * sends anyway. Counts are cached because they are what makes a saved post
     * look like the post it was; a cached feed whose every row said "0" would
     * be presenting a different post.
     */
    fun encode(engagement: GoodPostEngagement): JSONObject = JSONObject().apply {
        put("reactionTotal", engagement.reactionTotal)
        put("viewerReaction", engagement.viewerReaction ?: JSONObject.NULL)
        put("uniqueViewers", engagement.uniqueViewers)
        put("totalViews", engagement.totalViews)
        put("viewerHasViewed", engagement.viewerHasViewed)

        val reactions = JSONArray()
        engagement.reactions.forEach { entry ->
            reactions.put(JSONObject().apply {
                put("reaction", entry.reaction)
                put("count", entry.count)
            })
        }
        put("reactions", reactions)

        engagement.poll?.let { poll ->
            put("poll", JSONObject().apply {
                put("id", poll.id)
                put("question", poll.question)
                put("allowMultiple", poll.allowMultiple)
                put("closesAt", poll.closesAt ?: JSONObject.NULL)
                put("isClosed", poll.isClosed)
                put("totalVotes", poll.totalVotes)
                put("viewerVotes", JSONArray(poll.viewerVotes))
                val options = JSONArray()
                poll.options.forEach { option ->
                    options.put(JSONObject().apply {
                        put("id", option.id)
                        put("position", option.position)
                        put("label", option.label)
                        put("votes", option.votes)
                    })
                }
                put("options", options)
            })
        }
    }

    /** A JSON value that is absent or JSON-null reads as null, not "". */
    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

/** What a view ping returned. [counted] is the server's dedupe verdict. */
data class GoodPostViewState(
    val uniqueViewers: Int,
    val totalViews: Int,
    val counted: Boolean,
    val viewerHasViewed: Boolean
)
