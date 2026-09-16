package com.muddassir.clearview.goodpost.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire/storage contract for a channel (§5, §6).
 *
 * These are the failures that would otherwise reach a user instead of CI: a
 * renamed field that blanks every row, a JSON null arriving as the empty string
 * and rendering as a channel called "", a page whose cursor is deleted and
 * re-sent as an error, or a merge that shows the same channel twice.
 */
class GoodPostChannelCodecTest {

    /**
     * A payload builder whose id is derived from the name.
     *
     * That default matters: several tests build more than one channel, and a
     * shared id would be deduplicated by `mergePage` and replaced in pairs by
     * `replace` — making those tests fail for a reason that has nothing to do
     * with the behaviour under test.
     */
    private fun json(
        name: String = "Karachi Runners",
        id: String = "id-$name",
        extra: JSONObject.() -> Unit = {}
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("slug", "karachi-runners")
        put("description", "Weekly runs along the coast")
        put("categorySlug", "sports")
        put("categoryLabel", "Sports")
        put("countryCode", "PK")
        put("followerCount", 120)
        put("postCount", 7)
        put("allowFollowerMessages", true)
        put("shareLink", "clearview://goodpost/channel/karachi-runners")
        put("isFollowing", true)
        put("notificationsEnabled", false)
        put("isBlocked", false)
        put("viewerRole", "owner")
        put("hasUnread", true)
        put("status", "active")
        extra()
    }

    // ── Parsing ─────────────────────────────────────────────────────────

    @Test
    fun `parses a full channel payload`() {
        val channel = GoodPostChannelCodec.channel(json())

        assertNotNull(channel)
        requireNotNull(channel)
        assertEquals("id-Karachi Runners", channel.id)
        assertEquals("Karachi Runners", channel.name)
        assertEquals("sports", channel.categorySlug)
        assertEquals("Sports", channel.categoryLabel)
        assertEquals(120, channel.followerCount)
        assertEquals(7, channel.postCount)
        assertTrue(channel.allowFollowerMessages)
        assertTrue(channel.isFollowing)
        assertFalse(channel.notificationsEnabled)
        assertTrue(channel.hasUnread)
        assertEquals("active", channel.status)
    }

    @Test
    fun `refuses a channel without an id or a name`() {
        // A row that cannot be identified or labelled is not renderable and
        // cannot be acted on, so it is dropped rather than shown as a blank.
        assertNull(GoodPostChannelCodec.channel(json().apply { remove("id") }))
        assertNull(GoodPostChannelCodec.channel(json().apply { remove("name") }))
        assertNull(GoodPostChannelCodec.channel(json(name = "  ", id = "")))
    }

    @Test
    fun `reads a JSON null as null, not as an empty string`() {
        // The backend returns real nulls for these. `optString` would turn them
        // into "" and the UI would render an empty description as though the
        // channel had one.
        val channel = GoodPostChannelCodec.channel(
            json {
                put("description", JSONObject.NULL)
                put("categorySlug", JSONObject.NULL)
                put("categoryLabel", JSONObject.NULL)
                put("countryCode", JSONObject.NULL)
                put("viewerRole", JSONObject.NULL)
                put("status", JSONObject.NULL)
            }
        )

        requireNotNull(channel)
        assertNull(channel.description)
        assertNull(channel.categorySlug)
        assertNull(channel.countryCode)
        assertNull(channel.viewerRole)
        assertNull(channel.status)
    }

    @Test
    fun `defaults the optional numbers and flags when they are absent`() {
        val channel = GoodPostChannelCodec.channel(
            JSONObject().apply {
                put("id", "abc")
                put("name", "Bare Minimum")
            }
        )

        requireNotNull(channel)
        assertEquals(0, channel.followerCount)
        assertEquals(0, channel.postCount)
        assertFalse(channel.isFollowing)
        assertFalse(channel.isBlocked)
        assertFalse(channel.hasUnread)
    }

    @Test
    fun `parses a page and skips rows that are not usable`() {
        val page = GoodPostChannelCodec.page(
            JSONObject().apply {
                put(
                    "items",
                    org.json.JSONArray().apply {
                        put(json(name = "First"))
                        put(JSONObject().apply { put("name", "no id at all") })
                        put(json(name = "Second"))
                    }
                )
                put("nextCursor", "abc123")
            }
        )

        assertEquals(listOf("First", "Second"), page.items.map { it.name })
        assertEquals("abc123", page.nextCursor)
    }

    @Test
    fun `treats a blank or absent cursor as the end of the list`() {
        // An empty cursor sent back to the server is rejected as
        // `invalid_cursor`, which would turn "no more pages" into an error.
        val absent = GoodPostChannelCodec.page(JSONObject())
        assertNull(absent.nextCursor)

        val blank = GoodPostChannelCodec.page(
            JSONObject().apply {
                put("items", org.json.JSONArray())
                put("nextCursor", "")
                put("nextCursorNull", JSONObject.NULL)
            }
        )
        assertNull(blank.nextCursor)
    }

    @Test
    fun `parses single, many and category bodies`() {
        val single = GoodPostChannelCodec.single(
            JSONObject().apply { put("channel", json(name = "Only One")) }
        )
        assertEquals("Only One", single?.name)

        val many = GoodPostChannelCodec.many(
            JSONObject().apply {
                put(
                    "channels",
                    org.json.JSONArray().apply {
                        put(json(name = "A"))
                        put(json(name = "B"))
                    }
                )
            }
        )
        assertEquals(listOf("A", "B"), many.map { it.name })

        val categories = GoodPostChannelCodec.categories(
            JSONObject().apply {
                put(
                    "categories",
                    org.json.JSONArray().apply {
                        put(JSONObject().apply { put("slug", "news"); put("label", "News") })
                        // A label is cosmetic; a missing one must not drop the
                        // category, so it falls back to the slug.
                        put(JSONObject().apply { put("slug", "sports") })
                        put(JSONObject().apply { put("label", "no slug") })
                    }
                )
            }
        )
        assertEquals(listOf("News", "sports"), categories.map { it.label })
        assertEquals(2, categories.size)
    }

    // ── Derived flags ───────────────────────────────────────────────────

    @Test
    fun `derives owner, mute and availability from the flags`() {
        val owned = GoodPostChannelCodec.channel(json())!!
        assertTrue(owned.isOwner)
        assertTrue(owned.isAvailable)

        val muted = GoodPostChannelCodec.channel(
            json {
                put("viewerRole", JSONObject.NULL)
                put("isFollowing", true)
                put("notificationsEnabled", false)
            }
        )!!
        assertTrue(muted.isMuted)
        assertFalse(muted.isOwner)

        val suspended = GoodPostChannelCodec.channel(json { put("status", "suspended") })!!
        assertFalse(suspended.isAvailable)
    }

    @Test
    fun `does not call an unfollowed channel muted`() {
        // Mute only means something while following; without the follow it is
        // just a channel the user does not follow.
        val channel = GoodPostChannelCodec.channel(
            json {
                put("isFollowing", false)
                put("notificationsEnabled", false)
            }
        )!!
        assertFalse(channel.isMuted)
    }

    // ── Encoding and the offline cache (§36) ────────────────────────────

    @Test
    fun `round-trips every field through the cache encoding`() {
        val original = GoodPostChannelCodec.channel(json())!!
        val restored = GoodPostChannelCodec.channel(GoodPostChannelCodec.encode(original))!!

        assertEquals(original, restored)
    }

    @Test
    fun `round-trips nulls through the cache encoding`() {
        val original = GoodPostChannelCodec.channel(
            json {
                put("description", JSONObject.NULL)
                put("categorySlug", JSONObject.NULL)
                put("categoryLabel", JSONObject.NULL)
                put("countryCode", JSONObject.NULL)
                put("viewerRole", JSONObject.NULL)
                put("status", JSONObject.NULL)
            }
        )!!

        val restored = GoodPostChannelCodec.channel(GoodPostChannelCodec.encode(original))!!
        assertNull(restored.description)
        assertNull(restored.categorySlug)
        assertEquals(original, restored)
    }

    @Test
    fun `never persists a field it does not model`() {
        // Forward compatibility with a guard rail: if the server later adds an
        // owner or contact field, the client must not start caching it just
        // because it appeared in a response (§38).
        val channel = GoodPostChannelCodec.channel(
            json {
                put("ownerEmail", "owner@example.test")
                put("phoneNumber", "+923001234567")
            }
        )!!

        val encoded = GoodPostChannelCodec.encode(channel).toString()
        assertFalse(encoded.contains("owner@example.test"))
        assertFalse(encoded.contains("phoneNumber"))
        assertFalse(encoded.contains("923001234567"))
    }

    @Test
    fun `round-trips a page but drops its cursor when cached`() {
        val page = GoodPostChannelPage(
            items = listOf(GoodPostChannelCodec.channel(json())!!),
            nextCursor = "cursor-1"
        )

        val decoded = GoodPostChannelCodec.decodePage(GoodPostChannelCodec.encodePage(page))!!
        assertEquals(page.items, decoded.items)
        // A cached cursor points into a ranking that has moved on; offline
        // content is for reading, not for fetching "the next page" of.
        assertNull(decoded.nextCursor)
    }

    @Test
    fun `reads an unreadable cache entry as nothing rather than crashing`() {
        assertNull(GoodPostChannelCodec.decodePage(null))
        assertNull(GoodPostChannelCodec.decodePage(""))
        assertNull(GoodPostChannelCodec.decodePage("not json"))
        assertNull(GoodPostChannelCodec.decodePage("{\"items\": \"not an array\"}"))
        assertTrue(GoodPostChannelCodec.decodeCategories("{{{").isEmpty())
    }

    // ── Merging pages (§5) ──────────────────────────────────────────────

    @Test
    fun `appends a page, letting the newer copy of a channel win`() {
        val a = GoodPostChannelCodec.channel(json(name = "A"))!!.copy(followerCount = 1)
        val b = GoodPostChannelCodec.channel(json(name = "B"))!!
        val aUpdated = a.copy(followerCount = 99)

        val merged = GoodPostChannelCodec.mergePage(listOf(a, b), listOf(aUpdated))

        assertEquals(listOf("A", "B"), merged.map { it.name })
        assertEquals(99, merged.first { it.name == "A" }.followerCount)
    }

    @Test
    fun `does not show the same channel twice when a page overlaps`() {
        // Ranking moves as followers arrive, so page two can legitimately
        // repeat a channel from page one.
        val a = GoodPostChannelCodec.channel(json(name = "A"))!!
        val b = GoodPostChannelCodec.channel(json(name = "B"))!!

        val merged = GoodPostChannelCodec.mergePage(listOf(a, b), listOf(b, a))

        assertEquals(2, merged.size)
        assertEquals(listOf("A", "B"), merged.map { it.name })
    }

    @Test
    fun `merging with nothing changes nothing`() {
        val a = GoodPostChannelCodec.channel(json(name = "A"))!!
        assertEquals(listOf(a), GoodPostChannelCodec.mergePage(listOf(a), emptyList()))
        assertEquals(emptyList<GoodPostChannel>(), GoodPostChannelCodec.mergePage(emptyList(), emptyList()))
    }

    @Test
    fun `replaces and removes a channel by id`() {
        val a = GoodPostChannelCodec.channel(json(name = "A"))!!
        val b = GoodPostChannelCodec.channel(json(name = "B"))!!
        val list = listOf(a, b)

        val renamed = GoodPostChannelCodec.replace(list, b.copy(name = "B2"))
        assertEquals(listOf("A", "B2"), renamed.map { it.name })

        assertEquals(listOf("B"), GoodPostChannelCodec.remove(list, a.id).map { it.name })
        assertEquals(listOf("A", "B"), GoodPostChannelCodec.remove(list, "unknown-id").map { it.name })
    }

    @Test
    fun `category sort wire values match the backend enum`() {
        // These strings are a contract with the server's `sort` enum; a typo
        // here is a 400 on every Discover open.
        assertEquals("popular", ChannelSort.Popular.wire)
        assertEquals("active", ChannelSort.Active.wire)
        assertEquals("new", ChannelSort.New.wire)
    }
}
