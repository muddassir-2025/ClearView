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
import java.time.LocalDate
import java.time.ZoneId

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
    fun `a channel parses, and carries exactly the two numbers §9 asks for`() {
        val channel = GoodPostCodec.channel(
            channelJson {
                put("followerCount", 1204)
                put("lastPostViews", 37)
            }
        )
        assertNotNull(channel)
        assertEquals("ClearView", channel!!.name)
        assertEquals("islamic", channel.categorySlug)
        assertEquals("clearview://goodpost/channel/clearview", channel.shareLink)
        assertEquals(1204, channel.followerCount)
        assertEquals(37, channel.lastPostViews)

        // The product rule in one assertion, read off the MODEL rather than off
        // one payload. §9 added exactly two numbers — how many readers follow a
        // channel, and how many have opened its newest post — and both are
        // counts of real rows. Everything else a social app would keep beside a
        // name stays off this shape: the set below is exhaustive, so adding a
        // like count, a reaction total or a `postCount` fails here.
        val fields = com.muddassir.clearview.goodpost.data.GoodPostChannel::class.java
            .declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        assertEquals(GoodPostChannelFields, fields)

        // §4–§6's reader-owned fields default rather than being required, so a
        // row from the PUBLIC list — which carries none of them — still parses
        // and renders with no badge and no toggle.
        assertEquals(0, channel.unreadCount)
        assertFalse(channel.following)
        assertFalse(channel.notificationsMuted)
        assertNull(channel.followedAt)
    }

    @Test
    fun `a followed channel carries the reader's own state, not the channel's popularity`() {
        // The distinction §4 forces, stated where it can be checked: `unreadCount`
        // is how many posts THIS reader has not opened, and it would be a
        // different number for every reader. A follower count would be the same
        // number for all of them, and is the thing §1 excludes.
        val followed = GoodPostCodec.channel(
            channelJson {
                put("following", true)
                put("notificationsMuted", true)
                put("unreadCount", 3)
                put("followedAt", "2026-09-10T08:00:00.000Z")
            }
        )

        assertTrue(followed!!.following)
        assertTrue(followed.notificationsMuted)
        assertEquals(3, followed.unreadCount)
        assertEquals("2026-09-10T08:00:00.000Z", followed.followedAt)
    }

    @Test
    fun `a negative unread count is read as none rather than as a badge`() {
        // Defensive: the count comes from a server query, and a row that somehow
        // arrived negative must not render as a badge saying -1.
        val channel = GoodPostCodec.channel(channelJson { put("unreadCount", -4) })
        assertEquals(0, channel!!.unreadCount)
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
        // Built from LOCAL MIDNIGHT, not from offsets to `Instant.now()`.
        //
        // This fixture used to be `now`, `now - 2h`, `now - 1d`, `now - 1d - 3h`,
        // which reads as "two days" and is two days for most of the clock — but
        // between midnight and 02:00 the first subtraction lands on yesterday,
        // producing three separators and a failure that has nothing to do with
        // the code. It did exactly that in CI at 00:08. Times of day are now
        // stated, so the grouping is the only thing the test can be measuring.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant()

        val entries = withDateSeparators(
            listOf(
                postAt("a", at(today, 10)),
                postAt("b", at(today, 8)),
                postAt("c", at(today.minusDays(1), 12)),
                postAt("d", at(today.minusDays(1), 9))
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

    @Test
    fun `every way a sign-in can be over is worded as one, never as something went wrong`() {
        // Each of these is a credential the server refused for its own reason.
        // They all used to fall through to "Something went wrong", which named
        // nothing, and left the reader retrying a token that could not work.
        // The creator flow also keys off this exact mapping to decide that the
        // token it holds is dead and the sign-in step has to come back.
        listOf("invalid_token", "missing_token", "session_expired", "session_revoked", "invalid_refresh_token")
            .forEach { code ->
                assertEquals(code, GoodPostError.SessionExpired, goodPostErrorFor(code))
            }

        // And the deployment-side counterpart, which is NOT retryable and must
        // not read as an expired session: nobody's sign-in ended, the server
        // simply cannot verify one.
        assertEquals(GoodPostError.SignInUnavailable, goodPostErrorFor("auth_unavailable"))
    }

    @Test
    fun `the password floor is enforced at the boundary the form states`() {
        // The rule the channel form puts in its own label and checks before it
        // submits. One character either side of the floor, because that is where
        // the bug was reported from: a password that looked long enough being
        // refused by a form that never said how long was needed.
        assertTrue(adminPasswordTooShort("Password123", 12))
        assertFalse(adminPasswordTooShort("Password1234", 12))
        assertTrue(adminPasswordTooShort("", 12))

        // The floor is a parameter, so a deployment that raises it gets the same
        // rule applied from its own value rather than from a constant buried
        // here.
        assertTrue(adminPasswordTooShort("Password1234", 16))
        assertFalse(adminPasswordTooShort("Password12345678", 16))
    }

    @Test
    fun `the remaining refusals a creator can meet are each named`() {
        assertEquals(GoodPostError.NameTaken, goodPostErrorFor("slug_unavailable"))
        assertEquals(GoodPostError.WeakPassword, goodPostErrorFor("weak_password"))
        assertEquals(GoodPostError.CannotDisableYourself, goodPostErrorFor("cannot_disable_self"))
        assertEquals(GoodPostError.InvalidCredentials, goodPostErrorFor("admin_credentials_incomplete"))
        assertEquals(GoodPostError.InvalidCredentials, goodPostErrorFor("creator_required"))
        assertEquals(GoodPostError.Forbidden, goodPostErrorFor("admin_forbidden"))
        assertEquals(GoodPostError.Forbidden, goodPostErrorFor("invalid_role"))
        assertEquals(GoodPostError.NotFound, goodPostErrorFor("admin_not_found"))
        assertEquals(GoodPostError.NotFound, goodPostErrorFor("channel_unavailable"))
        assertEquals(GoodPostError.InvalidInput, goodPostErrorFor("channel_required"))
        assertEquals(GoodPostError.InvalidInput, goodPostErrorFor("not_following"))
        assertEquals(GoodPostError.AttachmentFailed, goodPostErrorFor("media_not_found"))

        // A status with no code in the body — a proxy's HTML 502. A server-side
        // condition however it is described, so it is reported as one rather
        // than as a mystery.
        assertEquals(GoodPostError.ServerFault, goodPostErrorFor("http_error"))
    }

    @Test
    fun `only a refusal that Firebase cannot answer stops the single sign-in form`() {
        // One form serves a server-side administrator and a Firebase creator, so
        // the app tries the server first and falls through for everyone else.
        // These two refusals must NOT fall through.
        assertTrue(adminRefusalIsFinal(GoodPostError.AdminLocked))
        assertTrue(adminRefusalIsFinal(GoodPostError.SignInUnavailable))

        // And the one that must: a wrong password for THIS kind of account is
        // exactly the signal that the credentials may belong to the other kind,
        // which is what makes "Continue" work for a creator without a second
        // button.
        assertFalse(adminRefusalIsFinal(GoodPostError.InvalidCredentials))
        assertFalse(adminRefusalIsFinal(GoodPostError.AdminUnavailable))
        assertFalse(adminRefusalIsFinal(GoodPostError.SessionExpired))
        assertFalse(adminRefusalIsFinal(GoodPostError.ServerFault))
        assertFalse(adminRefusalIsFinal(GoodPostError.Forbidden))
    }

    @Test
    fun `a sign-in refused by a different contract is named as one, not as a bad password`() {
        // The previous deployment's login also demanded a phone number, so it
        // refused a well-formed request from this app as malformed — and the
        // person typing a CORRECT password was told their credentials were
        // wrong, which is the report this rule exists to answer.
        assertTrue(signInHitAnotherContract(400, "invalid_request"))
        assertTrue(signInHitAnotherContract(404, "not_found"))
        assertTrue(signInHitAnotherContract(405, "http_error"))

        // Everything a server that DOES implement this contract may answer is
        // left alone: a wrong password must keep reading as a wrong password,
        // and a locked account must keep saying so.
        assertFalse(signInHitAnotherContract(401, "invalid_credentials"))
        assertFalse(signInHitAnotherContract(401, "unauthorized"))
        assertFalse(signInHitAnotherContract(403, "admin_disabled"))
        assertFalse(signInHitAnotherContract(429, "rate_limited"))
        assertFalse(signInHitAnotherContract(500, "server_error"))
    }

    // ── Attaching a file (§21, §22) ───────────────────────────────────────

    @Test
    fun `the accepted file types are the ones the server stores`() {
        assertEquals("image", goodPostKindFor("image/jpeg"))
        assertEquals("image", goodPostKindFor("image/png"))
        assertEquals("video", goodPostKindFor("video/mp4"))
        assertEquals("video", goodPostKindFor("video/quicktime"))

        // An audio file is no longer a post this product can carry: the server
        // refuses to store one, so accepting it here would mean a pick that
        // fails an upload later instead of a pick that is refused now.
        assertNull(goodPostKindFor("audio/mpeg"))

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
    "countryCode", "iconUrl", "createdAt", "lastPostAt", "lastPostType",
    "lastPostPreview", "shareLink",
    // `status` is not a counter and not an account: it is `active`/`suspended`,
    // present only on a payload an administrator received, and it exists so a
    // channel they run can say it is not currently public.
    "status",
    // The two the product now shows on a row and a channel's page (§9). Both are
    // counts of rows somebody's action created — a follow, or a read that was
    // reported — rather than a score kept about a channel.
    "followerCount", "lastPostViews",
    // §4–§6's four, and every one of them is the READER's own state rather than
    // a fact about the channel. `unreadCount` and `followedAt` differ per
    // reader; `following` and `notificationsMuted` are that reader's settings.
    "unreadCount", "following", "notificationsMuted", "followedAt"
)
