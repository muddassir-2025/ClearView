package com.muddassir.clearview.media.data

import com.muddassir.clearview.media.model.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The community tab is the only place YouTube exposes a channel's posts (the
 * Data API has no endpoint for them and the RSS feed carries uploads only), so
 * the parser reads the page's own server-rendered data. These tests pin the two
 * renderer shapes YouTube serves, the id prefix that keeps a post out of the
 * player, and the balanced-brace extraction that a regex would truncate.
 */
class YouTubeCommunityParserTest {

    private val now = 1_700_000_000_000L

    private fun page(data: String): String =
        "<html><head></head><body><script>var ytInitialData = $data;</script></body></html>"

    @Test
    fun `parses a classic backstage post with an image`() {
        val html = page(
            """
            {"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"backstagePostRenderer":{"postId":"UgxClassic123","authorText":{"runs":[{"text":"Safina Society"}]},"contentText":{"runs":[{"text":"New class "},{"text":"starts tonight."}]},"publishedTimeText":{"runs":[{"text":"2 days ago"}]},"backstageAttachment":{"backstageImageRenderer":{"image":{"thumbnails":[{"url":"https://i.ytimg.com/small.jpg","width":120,"height":120},{"url":"https://i.ytimg.com/large.jpg","width":640,"height":640}]}}}}}]}}]}}}
            """.trimIndent()
        )

        val posts = YouTubeCommunityParser.parse(html, "UC_saved", "Saved Channel", now)

        assertEquals(1, posts.size)
        val post = posts.single()
        assertEquals("ytc_UgxClassic123", post.videoId)
        assertEquals(MediaPlatform.YOUTUBE, post.platform)
        assertEquals("UC_saved", post.channelId)
        assertEquals("Safina Society", post.channelName)
        assertEquals("New class starts tonight.", post.bodyText)
        assertEquals("https://i.ytimg.com/large.jpg", post.thumbnailUrl)
        assertEquals("https://www.youtube.com/post/UgxClassic123", post.sourceUrl)
        // A post is a POST: the feed's Posts rail owns it and no player may
        // receive its id.
        assertTrue(post.isPost)
        assertTrue(post.isCommunityPost)
        assertFalse(post.isShortsEntry)
    }

    @Test
    fun `parses the newer postRenderer shape without a picture`() {
        val html = page(
            """
            {"contents":[
              {"postRenderer":{
                "postId":"UgxNew456",
                "content":{"runs":[{"text":"A text-only update"}]},
                "publishedTimeText":{"runs":[{"text":"5 hours ago"}]}
              }}
            ]}
            """.trimIndent()
        )

        val post = YouTubeCommunityParser.parse(html, "UC_x", "Channel", now).single()

        assertEquals("ytc_UgxNew456", post.videoId)
        assertEquals("A text-only update", post.bodyText)
        assertEquals("A text-only update", post.title)
        assertTrue(post.thumbnailUrl.isBlank())
        assertEquals(now - 5L * 3_600_000L, post.publishedAtEpochMillis)
        assertTrue(post.isPost)
    }

    @Test
    fun `initial data extraction survives braces and semicolons inside post text`() {
        val text = "Use } and { and }); in a post — they are just words"
        val html = page("""{"contents":[{"postRenderer":{"postId":"p1","content":{"runs":[{"text":"$text"}]}}}]}""")

        val extracted = YouTubeCommunityParser.extractInitialData(html)
        assertNotNull(extracted)
        val posts = YouTubeCommunityParser.parse(html, "UC_x", "Channel", now)
        assertEquals(text, posts.single().bodyText)
    }

    @Test
    fun `a page with no initial data yields no posts`() {
        assertTrue(YouTubeCommunityParser.parse("<html></html>", "UC_x", "Channel", now).isEmpty())
    }

    @Test
    fun `a channel with no community tab yields no posts`() {
        val html = page("""{"contents":{"twoColumnBrowseResultsRenderer":{}}}""")
        assertTrue(YouTubeCommunityParser.parse(html, "UC_x", "Channel", now).isEmpty())
    }

    @Test
    fun `relative labels become approximate instants`() {
        assertEquals(now - 86_400_000L, YouTubeCommunityParser.relativeTimeToEpochMillis("1 day ago", now))
        assertEquals(now - 7 * 86_400_000L, YouTubeCommunityParser.relativeTimeToEpochMillis("7 days ago", now))
        assertEquals(now - 60_000L, YouTubeCommunityParser.relativeTimeToEpochMillis("1 minute ago", now))
        assertEquals(now - 2 * 3_600_000L, YouTubeCommunityParser.relativeTimeToEpochMillis("2 hours ago", now))
        assertEquals(null, YouTubeCommunityParser.relativeTimeToEpochMillis("recently", now))
    }
}
