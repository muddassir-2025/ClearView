package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engagement codec (M4): reactions, polls, views and analytics.
 *
 * The cases that carry the weight are the ones where a wrong answer looks
 * exactly like a right one. A renamed count decays to zero, and zero is what a
 * post nobody has reacted to looks like — so the parse of a real payload is
 * pinned field by field, and the absent-payload case is pinned to the zero
 * state rather than to null.
 */
class GoodPostEngagementCodecTest {

    private fun engagement(extra: JSONObject.() -> Unit = {}): JSONObject = JSONObject().apply {
        put("reactionTotal", 3)
        put("viewerReaction", "love")
        put("uniqueViewers", 12)
        put("totalViews", 30)
        put("viewerHasViewed", true)
        put(
            "reactions",
            JSONArray().apply {
                put(JSONObject().apply { put("reaction", "like"); put("count", 2) })
                put(JSONObject().apply { put("reaction", "love"); put("count", 1) })
            }
        )
        extra()
    }

    @Test
    fun `parses aggregates and the viewer's own reaction`() {
        val parsed = GoodPostEngagementCodec.engagement(engagement())

        assertEquals(3, parsed.reactionTotal)
        assertEquals("love", parsed.viewerReaction)
        assertEquals(2, parsed.countOf("like"))
        assertEquals(1, parsed.countOf("love"))
        // A reaction nobody used is zero, not an error: the UI offers the full
        // set and needs a number for each.
        assertEquals(0, parsed.countOf("angry"))
        assertEquals(12, parsed.uniqueViewers)
        assertEquals(30, parsed.totalViews)
        assertTrue(parsed.viewerHasViewed)
    }

    @Test
    fun `an absent engagement block is the zero state, never null`() {
        val parsed = GoodPostEngagementCodec.engagement(null)

        // Never null: a post with no engagement still has to render, and a
        // nullable count is how "--" reaches a screen.
        assertEquals(0, parsed.reactionTotal)
        assertNull(parsed.viewerReaction)
        assertFalse(parsed.viewerHasViewed)
        assertNull(parsed.poll)
        assertTrue(parsed.reactions.isEmpty())
    }

    @Test
    fun `skips a reaction that cannot be named or tapped`() {
        val json = engagement {
            put(
                "reactions",
                JSONArray().apply {
                    put(JSONObject().apply { put("reaction", ""); put("count", 5) })
                    put(JSONObject().apply { put("reaction", "like"); put("count", 1) })
                }
            )
        }

        // A count with no name can be neither rendered nor tapped, so it is
        // dropped rather than shown as an anonymous bar.
        assertEquals(listOf("like"), GoodPostEngagementCodec.engagement(json).reactions.map { it.reaction })
    }

    @Test
    fun `parses a poll with its options in order and the viewer's votes`() {
        val json = engagement {
            put(
                "poll",
                JSONObject().apply {
                    put("id", "poll-1")
                    put("question", "Which one?")
                    put("allowMultiple", true)
                    put("isClosed", false)
                    put("totalVotes", 7)
                    put(
                        "options",
                        JSONArray().apply {
                            put(option("o2", 1, "Second", 4))
                            put(option("o1", 0, "First", 3))
                        }
                    )
                    put("viewerVotes", JSONArray(listOf("o2")))
                }
            )
        }

        val poll = GoodPostEngagementCodec.engagement(json).poll!!

        assertEquals("Which one?", poll.question)
        assertTrue(poll.allowMultiple)
        assertFalse(poll.isClosed)
        assertEquals(7, poll.totalVotes)
        // Sorted by position, so the client shows the publisher's order however
        // the server happened to return the rows.
        assertEquals(listOf("o1", "o2"), poll.options.map { it.id })
        assertEquals(listOf("o2"), poll.viewerVotes)
        assertTrue(poll.hasVoted)
        assertEquals(4, poll.highestVotes)
    }

    @Test
    fun `a poll without an id is not a poll`() {
        val json = engagement {
            put("poll", JSONObject().apply { put("question", "No id") })
        }

        // Without an id there is nothing to vote on, so the block is dropped
        // rather than rendered as an unanswerable question.
        assertNull(GoodPostEngagementCodec.engagement(json).poll)
    }

    @Test
    fun `parses a reaction write response`() {
        val body = JSONObject().apply {
            put("reaction", "wow")
            put(
                "reactions",
                JSONArray().apply {
                    put(JSONObject().apply { put("reaction", "wow"); put("count", 4) })
                }
            )
        }

        val state = GoodPostEngagementCodec.reactionState(body)

        assertEquals("wow", state.reaction)
        assertEquals(4, state.total)
    }

    @Test
    fun `folds a confirmed reaction and vote back into engagement`() {
        val base = GoodPostEngagementCodec.engagement(engagement())

        val reacted = GoodPostEngagementCodec.withReaction(
            base,
            GoodPostReactionState(
                reaction = "laugh",
                reactions = listOf(GoodPostReaction("laugh", 9))
            )
        )

        // Counts come from the response, not from an increment: two people
        // reacting at once would otherwise leave the number wrong.
        assertEquals(9, reacted.reactionTotal)
        assertEquals("laugh", reacted.viewerReaction)
        assertEquals(0, reacted.countOf("like"))

        val voted = GoodPostEngagementCodec.withPoll(
            reacted,
            GoodPostPoll(
                id = "poll-1",
                question = "Which one?",
                allowMultiple = false,
                closesAt = null,
                isClosed = true,
                totalVotes = 2,
                options = listOf(GoodPostPollOption("o1", 0, "First", 2)),
                viewerVotes = listOf("o1")
            )
        )

        assertTrue(voted.poll!!.isClosed)
        // The fold touches only what it was told to: the reaction state
        // survives a vote.
        assertEquals("laugh", voted.viewerReaction)
    }

    @Test
    fun `a view that was not counted leaves the numbers alone`() {
        val base = GoodPostEngagementCodec.engagement(engagement())

        val result = GoodPostEngagementCodec.withView(
            base,
            GoodPostViewState(uniqueViewers = 12, totalViews = 30, counted = false, viewerHasViewed = true)
        )

        assertEquals(12, result.uniqueViewers)
        assertEquals(30, result.totalViews)
        assertTrue(result.viewerHasViewed)
    }

    @Test
    fun `parses channel analytics including zero days`() {
        val body = JSONObject().apply {
            put(
                "analytics",
                JSONObject().apply {
                    put("channelId", "c1")
                    put("windowDays", 30)
                    put(
                        "totals",
                        JSONObject().apply {
                            put("followers", 10)
                            put("posts", 4)
                            put("uniqueViewers", 100)
                            put("totalViews", 250)
                            put("reactions", 30)
                            put("pollVotes", 7)
                        }
                    )
                    put(
                        "series",
                        JSONArray().apply {
                            put(JSONObject().apply { put("day", "2026-09-01"); put("views", 5); put("newFollowers", 1) })
                            // A quiet day is a ZERO, not a missing point: a gap
                            // in a chart is a lie about what happened.
                            put(JSONObject().apply { put("day", "2026-09-02"); put("views", 0); put("newFollowers", 0) })
                        }
                    )
                    put(
                        "topPosts",
                        JSONArray().apply {
                            put(JSONObject().apply {
                                put("postId", "p1")
                                put("type", "text")
                                put("createdAt", "2026-09-01T10:00:00.000Z")
                                put("uniqueViewers", 9)
                                put("totalViews", 20)
                                put("reactions", 3)
                            })
                        }
                    )
                }
            )
        }

        val analytics = GoodPostEngagementCodec.analytics(body)!!

        assertEquals("c1", analytics.channelId)
        assertEquals(30, analytics.windowDays)
        assertEquals(10, analytics.totals.followers)
        assertEquals(250, analytics.totals.totalViews)
        assertEquals(2, analytics.series.size)
        assertEquals(0, analytics.series[1].views)
        assertEquals(5, analytics.peakViews)
        assertEquals("p1", analytics.topPosts.first().postId)
    }

    @Test
    fun `refuses an analytics body with no channel`() {
        assertNull(GoodPostEngagementCodec.analytics(JSONObject()))
        assertNull(GoodPostEngagementCodec.analytics(JSONObject().put("analytics", JSONObject())))
    }

    @Test
    fun `round-trips engagement through the cache`() {
        val original = GoodPostEngagementCodec.engagement(
            engagement {
                put(
                    "poll",
                    JSONObject().apply {
                        put("id", "poll-1")
                        put("question", "Which one?")
                        put("isClosed", false)
                        put("totalVotes", 1)
                        put("options", JSONArray().apply { put(option("o1", 0, "First", 1)) })
                        put("viewerVotes", JSONArray(listOf("o1")))
                    }
                )
            }
        )

        val restored = GoodPostEngagementCodec.engagement(GoodPostEngagementCodec.encode(original))

        assertEquals(original.reactionTotal, restored.reactionTotal)
        assertEquals(original.viewerReaction, restored.viewerReaction)
        assertEquals(original.uniqueViewers, restored.uniqueViewers)
        assertEquals(original.poll!!.id, restored.poll!!.id)
        assertEquals(original.poll!!.options.map { it.id }, restored.poll!!.options.map { it.id })
        assertEquals(original.poll!!.viewerVotes, restored.poll!!.viewerVotes)
    }

    @Test
    fun `every offered reaction has a label`() {
        // The wire values are the server's enum; a value with no icon would
        // render as a bullet and be unidentifiable.
        GOODPOST_REACTIONS.forEach { reaction ->
            assertTrue(reaction, goodPostReactionEmoji(reaction) != "\u2022")
        }
        assertEquals(6, GOODPOST_REACTIONS.size)
    }

    private fun option(id: String, position: Int, label: String, votes: Int): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("position", position)
            put("label", label)
            put("votes", votes)
        }
}
