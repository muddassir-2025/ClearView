package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channel-directory payload and cache round trip (§ new).
 *
 * Pure, because this is where a field rename turns every card into a blank one:
 * a row missing its handle, platform or URL cannot be drawn, copied or opened,
 * so it is dropped rather than shown as a card whose tap goes nowhere.
 */
class DirectoryCodecTest {

    private fun channel(
        id: String,
        platform: String = "youtube",
        handle: String = "somechannel",
        name: String? = "Some Channel"
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("platform", platform)
        .put("handle", handle)
        .put("name", name)
        .put("iconUrl", "https://cdn.example/$handle.jpg")
        .put("url", "https://www.youtube.com/@$handle")
        .put("categoryId", "cat-1")
        .put("subcategoryId", JSONObject.NULL)
        .put("sort", 0)

    private fun snapshotBody(): JSONObject = JSONObject()
        .put(
            "categories",
            JSONArray().put(
                JSONObject()
                    .put("id", "cat-1")
                    .put("name", "Islamic")
                    .put("slug", "islamic")
                    .put("sort", 0)
                    .put("channels", JSONArray().put(channel("c-direct")))
                    .put(
                        "subcategories",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "sub-1")
                                .put("categoryId", "cat-1")
                                .put("name", "Lectures")
                                .put("slug", "lectures")
                                .put("sort", 0)
                                .put("channels", JSONArray().put(channel("c-sub")))
                        )
                    )
            )
        )
        .put("unfiled", JSONArray().put(channel("c-unfiled")))

    @Test
    fun `a full snapshot parses its categories, subcategories and channels`() {
        val snapshot = DirectoryCodec.snapshot(snapshotBody())
        assertEquals(1, snapshot.categories.size)

        val category = snapshot.categories.first()
        assertEquals("Islamic", category.name)
        assertEquals(1, category.channels.size)
        assertEquals("c-direct", category.channels.first().id)
        assertEquals(1, category.subcategories.size)
        assertEquals("Lectures", category.subcategories.first().name)
        assertEquals("c-sub", category.subcategories.first().channels.first().id)
        assertEquals(1, snapshot.unfiled.size)
        assertEquals("c-unfiled", snapshot.unfiled.first().id)
    }

    @Test
    fun `a channel keeps the url the server built and its filing`() {
        val parsed = DirectoryCodec.channel(
            channel("id-1", platform = "instagram", handle = "nasa")
        )!!
        assertEquals("https://www.youtube.com/@nasa", parsed.url)
        assertEquals("instagram", parsed.platform)
        assertEquals("nasa", parsed.handle)
        assertEquals("cat-1", parsed.categoryId)
        assertNull(parsed.subcategoryId)
    }

    @Test
    fun `a channel without an id, platform, handle or url is dropped`() {
        val base = channel("id-1")
        assertNull(DirectoryCodec.channel(JSONObject(base.toString()).put("id", "")))
        assertNull(DirectoryCodec.channel(JSONObject(base.toString()).put("platform", "")))
        assertNull(DirectoryCodec.channel(JSONObject(base.toString()).put("handle", "")))
        assertNull(DirectoryCodec.channel(JSONObject(base.toString()).put("url", "")))
    }

    @Test
    fun `a channel with no name is shown by its handle`() {
        val parsed = DirectoryCodec.channel(channel("id-1", handle = "nasa", name = null))!!
        assertEquals("@nasa", parsed.displayName)
        assertNull(parsed.name)
    }

    @Test
    fun `an empty body is an empty snapshot rather than a failure`() {
        val snapshot = DirectoryCodec.snapshot(JSONObject())
        assertTrue(snapshot.isEmpty)
        assertTrue(snapshot.allChannels.isEmpty())
    }

    @Test
    fun `all channels spans categories, subcategories and unfiled`() {
        val snapshot = DirectoryCodec.snapshot(snapshotBody())
        val ids = snapshot.allChannels.map { it.id }
        assertEquals(listOf("c-direct", "c-sub", "c-unfiled"), ids)
    }

    @Test
    fun `a category counts the channels it holds directly and under subcategories`() {
        val snapshot = DirectoryCodec.snapshot(snapshotBody())
        assertEquals(2, snapshot.categories.first().channelCount)
    }

    @Test
    fun `a snapshot survives the cache round trip`() {
        val original = DirectoryCodec.snapshot(snapshotBody())
        val restored = DirectoryCodec.decode(DirectoryCodec.encode(original))!!

        assertEquals(original.categories.size, restored.categories.size)
        val category = restored.categories.first()
        assertEquals("Islamic", category.name)
        assertEquals("islamic", category.slug)
        assertEquals(1, category.subcategories.size)
        assertEquals("Lectures", category.subcategories.first().name)
        assertEquals(2, category.channelCount)
        assertEquals(1, restored.unfiled.size)
        assertEquals("https://www.youtube.com/@somechannel", restored.unfiled.first().url)
    }

    @Test
    fun `an empty or corrupt cache value decodes to nothing rather than throwing`() {
        assertNull(DirectoryCodec.decode(null))
        assertNull(DirectoryCodec.decode(""))
        assertNull(DirectoryCodec.decode("not json"))
    }
}
