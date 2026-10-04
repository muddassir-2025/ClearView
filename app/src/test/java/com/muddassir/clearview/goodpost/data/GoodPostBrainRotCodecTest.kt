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
        assertTrue(submission.isOpen)
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
        assertFalse(submission.isOpen)
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

    @Test
    fun `a submission carries its source and its two demand counts`() {
        val body = JSONObject().put(
            "submissions",
            JSONArray().put(
                JSONObject()
                    .put("id", "s1")
                    .put("kind", "channel")
                    .put("value", "@example")
                    .put("displayName", "Example")
                    .put("source", "youtube_not_interested")
                    .put("status", "under_review")
                    .put("reports", 247)
                    .put("requesters", 182)
            )
        )
        val submission = GoodPostCodec.brainRotSubmissions(body).first()
        assertEquals("@example", submission.value)
        assertEquals("Example", submission.displayName)
        assertEquals("youtube_not_interested", submission.source)
        assertEquals(247, submission.reports)
        assertEquals(182, submission.requesters)
        // "Under review" is still an open decision, so the actions stay available.
        assertTrue(submission.isOpen)
    }

    @Test
    fun `json null fields are absent, never the word null`() {
        // org.json's optString returns the literal "null" for a JSON null, which
        // is how an absent display name reached the UI as the word "null".
        val body = JSONObject().put(
            "submissions",
            JSONArray().put(
                JSONObject()
                    .put("id", "s1")
                    .put("kind", "channel")
                    .put("value", "@example")
                    .put("displayName", JSONObject.NULL)
                    .put("note", JSONObject.NULL)
                    .put("source", JSONObject.NULL)
                    .put("status", JSONObject.NULL)
                    .put("createdAt", JSONObject.NULL)
            )
        )
        val submission = GoodPostCodec.brainRotSubmissions(body).first()
        assertNull(submission.displayName)
        assertNull(submission.note)
        assertNull(submission.createdAt)
        // The defaults apply; neither is the string "null".
        assertEquals("unknown", submission.source)
        assertEquals("pending", submission.status)
    }

    @Test
    fun `the dashboard reads its totals and both demand lists`() {
        val body = JSONObject()
            .put(
                "totals",
                JSONObject()
                    .put("pending", 2)
                    .put("approved", 5)
                    .put("rejected", 1)
                    .put("underReview", 3)
                    .put("all", 11)
            )
            .put(
                "topChannels",
                JSONArray().put(
                    JSONObject()
                        .put("kind", "channel")
                        .put("value", "@example")
                        .put("displayName", "Example")
                        .put("usersBlocking", 247)
                        .put("globalRequests", 182)
                        .put("status", "pending")
                )
            )
            .put(
                "topKeywords",
                JSONArray().put(
                    JSONObject()
                        .put("kind", "keyword")
                        .put("value", "exkw")
                        .put("usersBlocking", 531)
                        .put("globalRequests", 410)
                        .put("status", "approved")
                )
            )

        val dashboard = GoodPostCodec.brainRotDashboard(body)
        assertEquals(2, dashboard.pending)
        assertEquals(5, dashboard.approved)
        assertEquals(1, dashboard.rejected)
        assertEquals(3, dashboard.underReview)
        assertEquals(11, dashboard.total)
        assertEquals(1, dashboard.topChannels.size)
        assertEquals(247, dashboard.topChannels.first().reports)
        assertEquals(182, dashboard.topChannels.first().globalRequests)
        assertTrue(dashboard.topChannels.first().isChannel)
        assertEquals(531, dashboard.topKeywords.first().reports)
        assertFalse(dashboard.topKeywords.first().isChannel)
    }

    @Test
    fun `an absent dashboard body is all zeroes rather than a failure`() {
        val dashboard = GoodPostCodec.brainRotDashboard(JSONObject())
        assertEquals(0, dashboard.total)
        assertTrue(dashboard.topChannels.isEmpty())
        assertTrue(dashboard.topKeywords.isEmpty())
    }

    // ── The false-positive queue ─────────────────────────────────────────

    private fun report(
        kind: String = "keyword",
        value: String = "brainrot",
        ruleId: String? = "k1",
        ruleEnabled: Boolean? = true
    ): JSONObject {
        val row = JSONObject()
            .put("kind", kind)
            .put("value", value)
            .put("reports", 4)
            .put("detail", "Blocked in error")
            .put("latestAt", "2026-01-02T00:00:00.000Z")
            .put("firstAt", "2026-01-01T00:00:00.000Z")
        if (ruleId != null) row.put("ruleId", ruleId)
        else row.put("ruleId", JSONObject.NULL)
        if (ruleEnabled != null) row.put("ruleEnabled", ruleEnabled)
        else row.put("ruleEnabled", JSONObject.NULL)
        return row
    }

    @Test
    fun `a reported target keeps its rule and its count`() {
        val body = JSONObject().put("reports", JSONArray().put(report()))
        val rows = GoodPostCodec.brainRotReports(body)
        assertEquals(1, rows.size)
        assertEquals(4, rows[0].reports)
        assertEquals("Blocked in error", rows[0].detail)
        assertEquals("k1", rows[0].ruleId)
        assertEquals(true, rows[0].ruleEnabled)
        assertTrue(rows[0].hasRule)
    }

    @Test
    fun `a report with no matching rule is not a rule`() {
        val body = JSONObject()
            .put("reports", JSONArray().put(report(value = "mine", ruleId = null, ruleEnabled = null)))
        val rows = GoodPostCodec.brainRotReports(body)
        assertEquals(1, rows.size)
        assertNull(rows[0].ruleId)
        assertNull(rows[0].ruleEnabled)
        assertFalse(rows[0].hasRule)
    }

    @Test
    fun `a channel report reads as a channel`() {
        val body = JSONObject()
            .put("reports", JSONArray().put(report(kind = "channel", value = "@examplechannel")))
        val rows = GoodPostCodec.brainRotReports(body)
        assertTrue(rows[0].isChannel)
        assertEquals("@examplechannel", rows[0].value)
    }

    @Test
    fun `an absent reports body is an empty queue rather than a failure`() {
        assertTrue(GoodPostCodec.brainRotReports(JSONObject()).isEmpty())
    }
}
