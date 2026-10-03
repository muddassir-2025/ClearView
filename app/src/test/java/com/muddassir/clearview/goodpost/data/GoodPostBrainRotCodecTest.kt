package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Brain Rot admin payload parsing.
 *
 * Pure, because this is the boundary the whole global repository crosses on its
 * way to a reviewer: the server sends keywords and channels in the two different
 * shapes their tables have, and the app manages both as one row. A field rename
 * here would not crash anything — it would quietly show an empty queue, which
 * reads as "nobody has suggested anything" rather than as a bug.
 */
class GoodPostBrainRotCodecTest {

    private fun keyword(id: String = "k1", keyword: String = "brainrot"): JSONObject =
        JSONObject()
            .put("id", id)
            .put("keyword", keyword)
            .put("reason", "short-form noise")
            .put("enabled", true)
            .put("reports", 7)
            .put("createdAt", "2026-01-01T00:00:00.000Z")

    private fun channel(id: String = "c1", handle: String = "@examplechannel"): JSONObject =
        JSONObject()
            .put("id", id)
            .put("handle", handle)
            .put("displayName", "Example")
            .put("reason", "clickbait")
            .put("enabled", false)
            .put("reports", 3)

    // ── Rules ────────────────────────────────────────────────────────────

    @Test
    fun `a keyword rule reads its value from the keyword field`() {
        val rule = GoodPostCodec.brainRotRule(keyword())!!
        assertEquals("keyword", rule.kind)
        assertFalse(rule.isChannel)
        assertEquals("brainrot", rule.value)
        assertEquals(7, rule.reports)
        assertTrue(rule.enabled)
    }

    @Test
    fun `a channel rule reads its value from the handle field`() {
        val rule = GoodPostCodec.brainRotRule(channel())!!
        assertEquals("channel", rule.kind)
        assertTrue(rule.isChannel)
        assertEquals("@examplechannel", rule.value)
        assertEquals("Example", rule.displayName)
        assertFalse(rule.enabled)
    }

    @Test
    fun `a rule with no id or no value is dropped`() {
        assertNull(GoodPostCodec.brainRotRule(JSONObject().put("keyword", "x")))
        assertNull(GoodPostCodec.brainRotRule(JSONObject().put("id", "x")))
    }

    @Test
    fun `the keyword and channel lists read their own arrays`() {
        val keywords = GoodPostCodec.brainRotKeywords(
            JSONObject().put("keywords", JSONArray().put(keyword()).put(JSONObject()))
        )
        assertEquals(1, keywords.size)
        assertEquals("brainrot", keywords.first().value)

        val channels = GoodPostCodec.brainRotChannels(
            JSONObject().put("channels", JSONArray().put(channel()))
        )
        assertEquals(1, channels.size)
        assertEquals("@examplechannel", channels.first().value)
    }

    @Test
    fun `an absent rule array is empty rather than a failure`() {
        assertTrue(GoodPostCodec.brainRotKeywords(JSONObject()).isEmpty())
        assertTrue(GoodPostCodec.brainRotChannels(JSONObject()).isEmpty())
    }

    // ── Submissions ──────────────────────────────────────────────────────

    @Test
    fun `a submission carries its kind, status and report count`() {
        val body = JSONObject().put(
            "submissions",
            JSONArray().put(
                JSONObject()
                    .put("id", "s1")
                    .put("kind", "channel")
                    .put("value", "@suggested")
                    .put("note", "please")
                    .put("status", "pending")
                    .put("reports", 12)
            )
        )
        val parsed = GoodPostCodec.brainRotSubmissions(body)
        assertEquals(1, parsed.size)
        val submission = parsed.first()
        assertTrue(submission.isChannel)
        assertTrue(submission.isPending)
        assertEquals("@suggested", submission.value)
        assertEquals("please", submission.note)
        assertEquals(12, submission.reports)
    }

    @Test
    fun `a decided submission is not pending`() {
        val body = JSONObject().put(
            "submissions",
            JSONArray().put(
                JSONObject()
                    .put("id", "s1")
                    .put("kind", "keyword")
                    .put("value", "noise")
                    .put("status", "approved")
            )
        )
        val submission = GoodPostCodec.brainRotSubmissions(body).first()
        assertFalse(submission.isPending)
        assertFalse(submission.isChannel)
        // A missing report count is zero, not a crash.
        assertEquals(0, submission.reports)
    }

    @Test
    fun `a submission without an id or a value is dropped`() {
        val body = JSONObject().put(
            "submissions",
            JSONArray()
                .put(JSONObject().put("kind", "keyword").put("value", "x"))
                .put(JSONObject().put("id", "s2").put("kind", "keyword"))
                .put(JSONObject().put("id", "s3").put("value", "kept"))
        )
        val parsed = GoodPostCodec.brainRotSubmissions(body)
        assertEquals(1, parsed.size)
        assertEquals("s3", parsed.first().id)
    }

    @Test
    fun `an absent submissions array is empty rather than a failure`() {
        assertTrue(GoodPostCodec.brainRotSubmissions(JSONObject()).isEmpty())
    }
}
