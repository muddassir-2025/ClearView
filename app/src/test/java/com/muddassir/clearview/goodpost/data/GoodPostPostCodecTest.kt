package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post and media codec (M3).
 *
 * This is the contract boundary with the server, so the cases that carry the
 * weight are the malformed ones: a field rename or a shape change must degrade
 * into a shorter list or a missing value, never into a crash and never into a
 * confidently wrong render.
 */
class GoodPostPostCodecTest {

    private fun post(
        id: String = "post-1",
        channelId: String = "channel-1",
        extra: JSONObject.() -> Unit = {}
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("channelId", channelId)
        put("type", "text")
        put("createdAt", "2026-09-16T10:00:00.000Z")
        extra()
    }

    @Test
    fun `parses a post with its media in position order`() {
        val json = post {
            put("body", "hello")
            put("isEdited", true)
            put("viewerCanManage", true)
            put(
                "media",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "m2")
                        put("kind", "image")
                        put("contentType", "image/png")
                        put("byteSize", 20)
                        put("position", 1)
                        put("url", "https://bucket.test/b")
                    })
                    put(JSONObject().apply {
                        put("id", "m1")
                        put("kind", "image")
                        put("contentType", "image/jpeg")
                        put("byteSize", 10)
                        put("position", 0)
                        put("url", "https://bucket.test/a")
                    })
                }
            )
        }

        val parsed = GoodPostPostCodec.post(json)!!

        assertEquals("hello", parsed.body)
        assertTrue(parsed.isEdited)
        assertTrue(parsed.viewerCanManage)
        // Sorted by position, so the client renders the publisher's order even
        // if the server ever returned them differently.
        assertEquals(listOf("m1", "m2"), parsed.media.map { it.id })
        assertEquals(0, parsed.media.first().position)
    }

    @Test
    fun `skips a row that cannot be acted on`() {
        // Without an id there is nothing to edit, delete or key a list by, and
        // without a channel id there is no path to act on. A row missing either
        // is unusable rather than partially usable.
        assertNull(GoodPostPostCodec.post(JSONObject().put("type", "text")))
        assertNull(GoodPostPostCodec.post(post(channelId = "")))
    }

    @Test
    fun `treats a missing or null media url as no url`() {
        val json = post {
            put(
                "media",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "m1")
                        put("kind", "image")
                        put("contentType", "image/jpeg")
                        put("url", JSONObject.NULL)
                    })
                }
            )
        }

        // Null rather than absent: the UI has one shape to read, and "the
        // server has no bucket" must render as a placeholder rather than as a
        // broken image.
        assertNull(GoodPostPostCodec.post(json)!!.media.first().url)
    }

    @Test
    fun `parses a page and skips unparseable rows`() {
        val body = JSONObject().apply {
            put("items", JSONArray().apply {
                put(post(id = "a"))
                put(JSONObject().put("type", "text"))
                put(post(id = "b"))
            })
            put("nextCursor", "abc")
        }

        val page = GoodPostPostCodec.page(body)

        assertEquals(listOf("a", "b"), page.items.map { it.id })
        assertEquals("abc", page.nextCursor)
    }

    @Test
    fun `reads an empty cursor as the end of the list`() {
        val body = JSONObject().apply {
            put("items", JSONArray())
            put("nextCursor", "")
        }

        // An empty string sent back as a cursor is rejected by the server as
        // invalid, which would turn the end of a list into an error.
        assertNull(GoodPostPostCodec.page(body).nextCursor)
    }

    @Test
    fun `merges pages without repeating a post`() {
        val first = GoodPostPostCodec.post(post(id = "a"))!!
        val second = GoodPostPostCodec.post(post(id = "b"))!!
        val updatedFirst = GoodPostPostCodec.post(post(id = "a") { put("body", "newer") })!!

        val merged = GoodPostPostCodec.mergePage(listOf(first, second), listOf(updatedFirst))

        // A post can legitimately appear on two pages when new posts arrive
        // between requests, and the fresher copy is the one to keep.
        assertEquals(2, merged.size)
        assertEquals("newer", merged.first { it.id == "a" }.body)
    }

    @Test
    fun `round-trips a page through the cache without keeping a URL`() {
        val original = GoodPostPostCodec.page(
            JSONObject().apply {
                put("items", JSONArray().apply {
                    put(post {
                        put("body", "cached")
                        put(
                            "media",
                            JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", "m1")
                                    put("kind", "image")
                                    put("contentType", "image/jpeg")
                                    put("byteSize", 42)
                                    put("position", 0)
                                    put("url", "https://bucket.test/signed?X-Amz-Signature=secret")
                                })
                            }
                        )
                    })
                })
                put("nextCursor", "cursor-1")
            }
        )

        val raw = GoodPostPostCodec.encodePage(original)
        // A presigned URL is a short-lived capability. Writing one to disk
        // would leave the app presenting an expired link as a saved asset,
        // which is exactly the claim §36 forbids.
        assertTrue(!raw.contains("X-Amz-Signature"))

        val restored = GoodPostPostCodec.decodePage(raw)!!

        assertEquals("cached", restored.items.first().body)
        assertEquals(42L, restored.items.first().media.first().byteSize)
        assertNull(restored.items.first().media.first().url)
        assertEquals("2026-09-16T10:00:00.000Z", restored.items.first().createdAt)
        // The cursor is dropped: a cached cursor points into an ordering that
        // has moved, and offering "load more" from it would fail offline.
        assertNull(restored.nextCursor)
    }

    @Test
    fun `refuses a damaged cache entry instead of reporting an empty feed`() {
        // An empty page would tell someone whose cache held a feed a minute ago
        // that there is nothing to read.
        assertNull(GoodPostPostCodec.decodePage("not json"))
        assertNull(GoodPostPostCodec.decodePage(null))
        assertNull(GoodPostPostCodec.decodePage("""{"items":"nope"}"""))
    }

    @Test
    fun `parses the media body an upload returns`() {
        val body = JSONObject().apply {
            put("media", JSONObject().apply {
                put("id", "m1")
                put("kind", "video")
                put("contentType", "video/mp4")
                put("byteSize", 1024)
                put("width", 640)
                put("height", 480)
                put("durationMs", 3000)
                put("position", 0)
                put("url", "https://bucket.test/v")
            })
        }

        val media = GoodPostPostCodec.media(body)!!

        assertEquals("video", media.kind)
        assertEquals(640, media.width)
        assertEquals(3000, media.durationMs)
        assertTrue(media.isVideo)
        assertEquals("mp4", media.extension)
    }

    @Test
    fun `parses the channel a feed row belongs to`() {
        val json = post {
            put("channel", JSONObject().apply {
                put("id", "c1")
                put("slug", "some-channel")
                put("name", "Some Channel")
            })
        }

        val parsed = GoodPostPostCodec.post(json)!!

        assertEquals("c1", parsed.channel?.id)
        assertEquals("some-channel", parsed.channel?.slug)
        assertEquals("Some Channel", parsed.channel?.name)
    }

    @Test
    fun `derives a file extension from the content type`() {
        // From the content type rather than the URL: a presigned URL's path is
        // the server's object key, which is a policy the client should not be
        // parsing.
        assertEquals("jpg", extensionFor("image/jpeg", "image"))
        assertEquals("png", extensionFor("image/png; charset=utf-8", "image"))
        assertEquals("m4a", extensionFor("audio/mp4", "audio"))
        assertEquals("bin", extensionFor("", ""))
    }
}
