package com.muddassir.clearview.goodpost

import com.muddassir.clearview.goodpost.data.GoodPostAttachment
import com.muddassir.clearview.goodpost.data.GoodPostCodec
import com.muddassir.clearview.goodpost.data.GoodPostPost
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.goodPostKindFor
import com.muddassir.clearview.goodpost.data.parseIsoMillis
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The public Good Post contract (§24).
 *
 * These are the pure boundaries: the JSON the backend sends, the JSON the cache
 * writes, and the day grouping the feed draws. They are tested here rather than
 * through a screen because a field rename shows up as blank rows on every
 * channel at once, and the place to catch that is the parser.
 */
class GoodPostPublicTest {

    private fun channelJson(
        id: String = "c1",
        name: String = "ClearView",
        extra: JSONObject.() -> Unit = {}
    ) = JSONObject().apply {
        put("id", id)
        put("slug", "clearview")
        put("name", name)
        put("description", "Daily reminders")
        put("categorySlug", "islamic")
        put("categoryLabel", "Islamic")
        put("countryCode", JSONObject.NULL)
        put("createdAt", "2026-08-01T10:00:00.000Z")
        put("lastPostAt", "2026-09-17T12:27:00.000Z")
        put("lastPostType", "text")
        put("lastPostPreview", "New update about…")
        put("shareLink", "clearview://goodpost/channel/clearview")
        extra()
    }

    @Test
    fun `a channel parses, and carries no counter`() {
        val channel = GoodPostCodec.channel(channelJson())
        assertNotNull(channel)
        assertEquals("ClearView", channel!!.name)
        assertEquals("islamic", channel.categorySlug)
        assertEquals("clearview://goodpost/channel/clearview", channel.shareLink)

        // The whole product rule in one assertion, read off the MODEL rather
        // than off one payload: nothing a public channel carries may be a count
        // or an account (§1, §5). Adding `followerCount` to the data class — the
        // regression this product exists to prevent — fails here.
        val fields = com.muddassir.clearview.goodpost.data.GoodPostChannel::class.java
            .declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        assertEquals(GoodPostChannelFields, fields)
    }

    @Test
    fun `a channel without an id or a name is unusable`() {
        assertNull(GoodPostCodec.channel(channelJson(id = "")))
        assertNull(GoodPostCodec.channel(channelJson(name = "")))
    }

    @Test
    fun `a channel that has never posted keeps a null preview rather than an empty string`() {
        val channel = GoodPostCodec.channel(
            channelJson {
                put("lastPostAt", JSONObject.NULL)
                put("lastPostPreview", JSONObject.NULL)
                put("lastPostType", JSONObject.NULL)
            }
        )
        assertNull(channel!!.lastPostAt)
        assertNull(channel.lastPostPreview)
        assertNull(channel.lastPostType)
    }

    @Test
    fun `a page keeps its cursor and skips unparseable rows`() {
        val body = JSONObject().apply {
            put(
                "items",
                org.json.JSONArray().apply {
                    put(channelJson(id = "a", name = "A"))
                    put(JSONObject().put("id", "")) // unusable, skipped
                    put(channelJson(id = "b", name = "B"))
                }
            )
            put("nextCursor", "abc")
        }

        val page = GoodPostCodec.channelPage(body)
        assertEquals(listOf("a", "b"), page.items.map { it.id })
        assertEquals("abc", page.nextCursor)
    }

    @Test
    fun `an empty cursor is read as the end of the list, not as a cursor`() {
        val page = GoodPostCodec.channelPage(
            JSONObject().apply { put("items", org.json.JSONArray()); put("nextCursor", "") }
        )
        assertNull(page.nextCursor)
    }

    @Test
    fun `merging pages keeps the fresher copy and never repeats a row`() {
        val older = GoodPostCodec.channelPage(
            JSONObject().put("items", org.json.JSONArray().put(channelJson(name = "Old")))
        )
        val newer = GoodPostCodec.channelPage(
            JSONObject().put("items", org.json.JSONArray().put(channelJson(name = "New")))
        )

        val merged = GoodPostCodec.merge(older.items, newer.items) { it.id }
        assertEquals(1, merged.size)
        assertEquals("New", merged.first().name)
    }

    @Test
    fun `cached posts round-trip and lose their signed media urls`() {
        val post = GoodPostPost(
            id = "p1",
            channelId = "c1",
            type = "image",
            body = "Salam",
            linkUrl = null,
            linkTitle = null,
            media = listOf(
                com.muddassir.clearview.goodpost.data.GoodPostMedia(
                    id = "m1",
                    kind = "image",
                    contentType = "image/jpeg",
                    byteSize = 2048,
                    width = 800,
                    height = 600,
                    durationMs = null,
                    position = 0,
                    url = "https://bucket.test/signed?signature=expiring"
                )
            ),
            createdAt = "2026-09-17T12:27:00.000Z"
        )

        val decoded = GoodPostCodec.decodePosts(GoodPostCodec.encodePosts(listOf(post)))
        assertEquals(1, decoded.size)
        assertEquals("Salam", decoded.first().body)
        // A presigned URL is a short-lived capability. Writing one to disk would
        // present an expired address as a saved asset, so it is dropped.
        assertNull(decoded.first().media.first().url)
        assertEquals(800, decoded.first().media.first().width)
    }

    @Test
    fun `damaged cache blobs read as empty rather than throwing`() {
        assertEquals(0, GoodPostCodec.decodeChannels("{ not json").size)
        assertEquals(0, GoodPostCodec.decodeChannels(null).size)
        assertEquals(0, GoodPostCodec.decodePosts("[1,2,3").size)
    }

    @Test
    fun `a post names its channel only when it was fetched on its own`() {
        val body = JSONObject().put(
            "post",
            JSONObject().apply {
                put("id", "p1")
                put("channelId", "c1")
                put("type", "text")
                put("body", "Hello")
                put("createdAt", "2026-09-17T12:27:00.000Z")
                put("channel", JSONObject().apply {
                    put("id", "c1")
                    put("slug", "clearview")
                    put("name", "ClearView")
                })
            }
        )

        val post = GoodPostCodec.singlePost(body)!!
        assertEquals("ClearView", post.channel?.name)
        assertFalse(post.hasImage)
        assertFalse(post.hasVideo)
        assertFalse(post.isLink)
    }

    // ── Day grouping (§10) ───────────────────────────────────────────────

    private fun postAt(id: String, at: Instant) = GoodPostPost(
        id = id,
        channelId = "c1",
        type = "text",
        body = id,
        linkUrl = null,
        linkTitle = null,
        media = emptyList(),
        createdAt = at.toString()
    )

    @Test
    fun `a separator appears only when the day changes`() {
        val now = Instant.now()
        val entries = withDateSeparators(
            listOf(
                postAt("a", now),
                postAt("b", now.minus(2, ChronoUnit.HOURS)),
                postAt("c", now.minus(1, ChronoUnit.DAYS)),
                postAt("d", now.minus(1, ChronoUnit.DAYS).minus(3, ChronoUnit.HOURS))
            )
        )

        // Two days of posts, so two separators — not four, and not one per post.
        assertEquals(2, entries.count { it is FeedEntry.Separator })
        val labels = entries.filterIsInstance<FeedEntry.Separator>().map { it.label }
        assertEquals(listOf("Today", "Yesterday"), labels)
    }

    @Test
    fun `an empty feed has no separators`() {
        assertTrue(withDateSeparators(emptyList()).isEmpty())
    }

    @Test
    fun `a post with an unreadable timestamp still renders, just without a separator`() {
        val entries = withDateSeparators(
            listOf(
                GoodPostPost(
                    id = "p",
                    channelId = "c1",
                    type = "text",
                    body = "body",
                    linkUrl = null,
                    linkTitle = null,
                    media = emptyList(),
                    createdAt = "not-a-date"
                )
            )
        )
        assertEquals(1, entries.size)
        assertTrue(entries.first() is FeedEntry.Post)
    }

    @Test
    fun `an unparseable instant reads as null, never as 1970`() {
        assertNull(parseIsoMillis("not-a-date"))
        assertNull(parseIsoMillis(null))
        assertNotNull(parseIsoMillis("2026-09-17T12:27:00.000Z"))
    }

    // ── Errors (§24) ─────────────────────────────────────────────────────

    @Test
    fun `backend codes map onto the words a reader can act on`() {
        assertEquals(GoodPostError.NotFound, goodPostErrorFor("channel_not_found"))
        assertEquals(GoodPostError.NotFound, goodPostErrorFor("post_not_found"))
        assertEquals(GoodPostError.InvalidCredentials, goodPostErrorFor("invalid_credentials"))
        assertEquals(GoodPostError.Forbidden, goodPostErrorFor("channel_forbidden"))
        assertEquals(GoodPostError.Offline, goodPostErrorFor("unreachable"))
        assertEquals(GoodPostError.MediaUnavailable, goodPostErrorFor("media_unavailable"))
    }

    @Test
    fun `an unknown code is reported as unknown rather than guessed at`() {
        assertEquals(GoodPostError.Unknown, goodPostErrorFor("something_new"))
    }

    // ── Attaching a file (§21, §22) ───────────────────────────────────────

    @Test
    fun `the accepted file types are the ones the server stores`() {
        assertEquals("image", goodPostKindFor("image/jpeg"))
        assertEquals("image", goodPostKindFor("image/png"))
        assertEquals("video", goodPostKindFor("video/mp4"))
        assertEquals("video", goodPostKindFor("video/quicktime"))
        assertEquals("audio", goodPostKindFor("audio/mpeg"))

        // A content type arrives with parameters and mixed case from the picker,
        // and a file whose type is refused for being spelled differently would be
        // a bug the user cannot see or work around.
        assertEquals("image", goodPostKindFor("IMAGE/JPEG; charset=binary"))
    }

    @Test
    fun `a type the bucket must never serve is refused before anything is sent`() {
        // An SVG is the one that matters: stored under a URL anyone can fetch, it
        // is a script the browser runs from our own domain.
        assertNull(goodPostKindFor("image/svg+xml"))
        assertNull(goodPostKindFor("application/pdf"))
        assertNull(goodPostKindFor("application/zip"))
        assertNull(goodPostKindFor("text/html"))
        assertNull(goodPostKindFor(null))
        assertNull(goodPostKindFor(""))
    }

    @Test
    fun `a file only counts once the server has confirmed it`() {
        val attachment = GoodPostAttachment(
            uri = "content://picked/1",
            kind = "image",
            contentType = "image/jpeg",
            byteSize = 2048
        )

        // Uploading: no id, so nothing may be attached to a post yet. This is
        // what the composer's publish guard reads.
        assertNull(attachment.mediaId)

        val ready = attachment.copy(state = GoodPostUploadState.Ready("media-1"))
        assertEquals("media-1", ready.mediaId)

        val failed = attachment.copy(state = GoodPostUploadState.Failed("media_unavailable"))
        assertNull(failed.mediaId)
    }

    @Test
    fun `a failed attachment is worded by what went wrong, not as one generic failure`() {
        assertEquals(GoodPostError.MediaTooLarge, goodPostErrorFor("media_too_large"))
        assertEquals(GoodPostError.UnsupportedMedia, goodPostErrorFor("unsupported_media_type"))
        assertEquals(GoodPostError.AttachmentUploading, goodPostErrorFor("media_not_ready"))
        assertEquals(GoodPostError.AttachmentFailed, goodPostErrorFor("media_already_used"))
        assertEquals(GoodPostError.AttachmentFailed, goodPostErrorFor("unknown_media"))
        assertEquals(GoodPostError.AttachmentUploading, goodPostErrorFor("attachment_uploading"))
        assertEquals(GoodPostError.AdminLocked, goodPostErrorFor("admin_locked"))
    }

    @Test
    fun `an address that could not exist locally is refused before it is sent`() {
        assertNull(normalizeEmailInput("not-an-address"))
        assertNull(normalizeEmailInput(""))
        assertEquals("admin@example.com", normalizeEmailInput("  Admin@Example.com "))
    }
}

/**
 * The exact field set of a public channel payload.
 *
 * Written out rather than derived from the data class, so ADDING a field to the
 * model is a failing test. A counter or an owner id arriving here later is
 * exactly the regression §1 forbids, and this is the assertion that catches it.
 */
private val GoodPostChannelFields = setOf(
    "id", "slug", "name", "description", "categorySlug", "categoryLabel",
    "countryCode", "createdAt", "lastPostAt", "lastPostType", "lastPostPreview",
    "shareLink"
)
