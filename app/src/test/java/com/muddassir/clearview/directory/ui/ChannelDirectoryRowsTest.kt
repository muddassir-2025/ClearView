package com.muddassir.clearview.directory.ui

import com.muddassir.clearview.goodpost.data.DirectoryCategory
import com.muddassir.clearview.goodpost.data.DirectoryChannel
import com.muddassir.clearview.goodpost.data.DirectorySnapshot
import com.muddassir.clearview.goodpost.data.DirectorySubcategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the directory list under each category tab.
 *
 * Pure, because the one non-obvious rule — that a subcategory heading is only
 * worth drawing when there is a group of channels under it — is exactly the kind
 * of thing that stops being true the moment it is only exercised by a screen.
 */
class ChannelDirectoryRowsTest {

    private fun channel(id: String, name: String = id, categoryId: String? = "cat-1") =
        DirectoryChannel(
            id = id,
            platform = "youtube",
            handle = id,
            name = name,
            iconUrl = null,
            url = "https://www.youtube.com/@$id",
            categoryId = categoryId,
            subcategoryId = null,
            sort = 0
        )

    private val lecture = DirectorySubcategory(
        id = "sub-lectures",
        categoryId = "cat-1",
        name = "Lectures",
        slug = "lectures",
        sort = 0,
        channels = listOf(channel("lecture-a", categoryId = "cat-1"))
    )

    private val category = DirectoryCategory(
        id = "cat-1",
        name = "Islamic",
        slug = "islamic",
        sort = 0,
        subcategories = listOf(lecture),
        channels = listOf(channel("direct-a", categoryId = "cat-1"))
    )

    private val snapshot = DirectorySnapshot(
        categories = listOf(category),
        unfiled = listOf(channel("loose", categoryId = null))
    )

    @Test
    fun `the all tab lists every channel, categorised or not`() {
        val rows = buildDirectoryRows(snapshot, category = null)
        val ids = rows.mapNotNull { (it as? DirectoryRowItem.Channel)?.channel?.id }
        assertEquals(listOf("direct-a", "lecture-a", "loose"), ids)
    }

    @Test
    fun `one category draws its own channels first, then each subcategory under a heading`() {
        val rows = buildDirectoryRows(snapshot, category)

        assertEquals(3, rows.size)
        assertEquals("direct-a", (rows[0] as DirectoryRowItem.Channel).channel.id)

        val header = rows[1] as DirectoryRowItem.Header
        assertEquals("Lectures", header.label)
        assertEquals("lecture-a", (rows[2] as DirectoryRowItem.Channel).channel.id)
    }

    @Test
    fun `a subcategory with no channels is not worth a heading`() {
        val empty = DirectoryCategory(
            id = "cat-2",
            name = "Tech",
            slug = "tech",
            sort = 0,
            subcategories = listOf(
                DirectorySubcategory("sub-empty", "cat-2", "Empty", "empty", 0, emptyList())
            ),
            channels = listOf(channel("tech-a", categoryId = "cat-2"))
        )

        val rows = buildDirectoryRows(snapshot, empty)
        assertEquals(1, rows.size)
        assertTrue(rows.single() is DirectoryRowItem.Channel)
    }

    @Test
    fun `an empty category draws nothing`() {
        val empty = DirectoryCategory("cat-3", "News", "news", 0, emptyList(), emptyList())
        assertTrue(buildDirectoryRows(snapshot, empty).isEmpty())
    }

    @Test
    fun `a card's platform is named in its own words`() {
        assertEquals("YouTube", platformLabel("youtube"))
        assertEquals("Instagram", platformLabel("instagram"))
        assertEquals("X", platformLabel("x"))
        assertEquals("other", platformLabel("other"))
    }
}
