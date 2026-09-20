package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.XFeedParser
import com.muddassir.clearview.media.data.XSource
import com.muddassir.clearview.media.model.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XSourceTest {
    @Test
    fun extractsHandlesAndProfileUrls() {
        assertEquals("ClearView", XSource.extractUsername("@ClearView"))
        assertEquals("ClearView", XSource.extractUsername("https://x.com/ClearView"))
        assertEquals("ClearView", XSource.extractUsername("https://twitter.com/ClearView/status/1"))
    }

    @Test
    fun parsesTextImageAndVideoAttachments() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>https://x.com/OpenAI/status/456</id>
                <title>New multimodal model</title>
                <link rel="alternate" href="https://x.com/OpenAI/status/456"/>
                <published>2026-09-20T10:00:00Z</published>
                <content type="html"><![CDATA[<p>Text</p><img src="https://cdn.example/image.jpg"/><video><source src="https://cdn.example/video.mp4"/></video>]]></content>
              </entry>
            </feed>
        """.trimIndent()

        val item = XFeedParser.parse(xml, "OpenAI").single()
        assertEquals("https://cdn.example/image.jpg", item.thumbnailUrl)
        assertEquals("https://cdn.example/video.mp4", item.mediaUrl)
        assertEquals("New multimodal model", item.title)
    }

    @Test
    fun parsesAtomPostsAsXItems() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>https://x.com/ClearView/status/123</id>
                <title>Useful update</title>
                <link rel="alternate" href="https://x.com/ClearView/status/123"/>
                <published>2026-09-20T10:00:00Z</published>
              </entry>
            </feed>
        """.trimIndent()

        val item = XFeedParser.parse(xml, "ClearView").single()
        assertEquals("x_123", item.videoId)
        assertEquals("x_clearview", item.channelId)
        assertEquals(MediaPlatform.X, item.platform)
        assertEquals("https://x.com/ClearView/status/123", item.sourceUrl)
        assertNotNull(item.publishedAtEpochMillis)
    }

    // ── Syndication page (the source that still answers without a key) ──

    /** A page shaped like X's embedded-timeline response: posts as embedded JSON. */
    private val syndicationPage = """
        <!DOCTYPE html><html><head><title>@OpenAI / X</title></head><body>
        <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"timeline":{"entries":[{"type":"tweet","content":{"tweet":{"created_at":"Sat Sep 20 10:00:00 +0000 2026","id_str":"1836000000000000001","full_text":"Introducing something new https:\/\/t.co\/abc","favorite_count":120,"conversation_id_str":"1836000000000000001","entities":{"media":[{"media_url_https":"https:\/\/pbs.twimg.com\/media\/ABC123.jpg","type":"photo"}]}}}},{"type":"tweet","content":{"tweet":{"created_at":"Sat Sep 20 09:00:00 +0000 2026","id_str":"1836000000000000002","full_text":"A clip from today","extended_entities":{"media":[{"type":"video","video_info":{"variants":[{"content_type":"video\/mp4","url":"https:\/\/video.twimg.com\/ext_tw_video\/123\/pu\/vid\/640x360\/abc.mp4?tag=12"}]}}]}}}}]}}}}</script>
        <script>window.__INITIAL_STATE__={"user":{"id_str":"4398626122","name":"OpenAI","screen_name":"OpenAI","followers_count":5353839,"profile_image_url_https":"https:\/\/pbs.twimg.com\/profile_images\/123\/x_normal.jpg"}}</script>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesSyndicationPagePosts() {
        val posts = XFeedParser.parseSyndication(syndicationPage, "OpenAI")
        assertEquals(2, posts.size)

        val first = posts.first()
        assertEquals("x_1836000000000000001", first.videoId)
        assertEquals("x_openai", first.channelId)
        assertEquals("@OpenAI", first.channelName)
        assertEquals(MediaPlatform.X, first.platform)
        assertEquals("Introducing something new https://t.co/abc", first.title)
        assertEquals("https://pbs.twimg.com/media/ABC123.jpg", first.thumbnailUrl)
        assertEquals("https://x.com/OpenAI/status/1836000000000000001", first.sourceUrl)
        assertTrue(first.publishedAtEpochMillis > 0L)

        val video = posts[1]
        assertEquals(
            "https://video.twimg.com/ext_tw_video/123/pu/vid/640x360/abc.mp4?tag=12",
            video.mediaUrl
        )
    }

    /** The profile's own user object carries an id_str too — it is not a post. */
    @Test
    fun ignoresTheProfileUserObject() {
        val ids = XFeedParser.parseSyndication(syndicationPage, "OpenAI").map { it.videoId }
        assertTrue(ids.none { it == "x_4398626122" })
    }

    @Test
    fun parsesServerRenderedTimelineMarkup() {
        val html = """
            <div class="timeline-Tweet" data-tweet-id="1836000000000000003">
              <p class="timeline-Tweet-text" lang="en">Hello from the older markup</p>
              <div class="timeline-Tweet-media"><img src="https://pbs.twimg.com/media/XYZ789.png" /></div>
            </div>
        """.trimIndent()

        val item = XFeedParser.parseSyndication(html, "ClearView").single()
        assertEquals("x_1836000000000000003", item.videoId)
        assertEquals("Hello from the older markup", item.title)
        assertEquals("https://pbs.twimg.com/media/XYZ789.png", item.thumbnailUrl)
    }

    /** A rate-limit notice is not a timeline: it must never fabricate posts. */
    @Test
    fun ignoresRateLimitBody() {
        assertTrue(XFeedParser.parseSyndication("Rate limit exceeded", "OpenAI").isEmpty())
    }

    /**
     * The card shows a 160-char label, but the whole tweet rides along in
     * `bodyText` — that is what the post reader shows, so a long post is no
     * longer cut off after the first lines.
     */
    @Test
    fun keepsTheWholeTweetText() {
        val longText = "Start " + "x".repeat(400) + " end"
        val page = """
            <html><body><script>{"contextJSON":"{\"context\":{\"username\":\"OpenAI\",\"graphql_media\":[{\"shortcode_media\":{}}]},\"timeline\":{\"entries\":[{\"content\":{\"tweet\":{\"id_str\":\"1836000000000000009\",\"full_text\":\"$longText\",\"favorite_count\":1}}}]}}"}</script></body></html>
        """.trimIndent()
        val post = XFeedParser.parseSyndication(page, "OpenAI").firstOrNull { it.videoId == "x_1836000000000000009" }
            ?: error("expected the tweet to parse")
        assertEquals(longText, post.bodyText)
        assertEquals(160, post.title.length)
        assertTrue(post.isPost)
    }

    /**
     * A text-only tweet must NOT borrow the next tweet's media: scanning a
     * fixed window past the id used to hand it the following mp4, which is how
     * a plain post ended up rendered as a video card with someone else's clip.
     */
    @Test
    fun doesNotBorrowTheNextPostsMedia() {
        val page = """
            <html><body><script>{"timeline":{"entries":[
            {"content":{"tweet":{"id_str":"1836000000000000001","full_text":"Just talking, no picture","favorite_count":1}}},
            {"content":{"tweet":{"id_str":"1836000000000000002","full_text":"A clip","extended_entities":{"media":[{"type":"video","video_info":{"variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/640x360/clip.mp4"}]}}]}}}
            ]}}</script></body></html>
        """.trimIndent()

        val posts = XFeedParser.parseSyndication(page, "OpenAI")
        val textOnly = posts.first { it.videoId == "x_1836000000000000001" }
        val videoPost = posts.first { it.videoId == "x_1836000000000000002" }

        assertEquals("Just talking, no picture", textOnly.bodyText)
        assertNull(textOnly.mediaUrl)
        assertTrue(textOnly.isPost)
        assertEquals(
            "https://video.twimg.com/ext_tw_video/1/pu/vid/640x360/clip.mp4",
            videoPost.mediaUrl
        )
        assertTrue(!videoPost.isPost)
    }

    /**
     * X lists the HLS playlist BEFORE the progressive files and the in-app
     * player is a MediaPlayer, which needs the mp4 — handing it the playlist is
     * how an X clip ended up playing nothing. The mp4 must win.
     */
    @Test
    fun prefersTheProgressiveMp4OverTheHlsPlaylist() {
        val page = """
            <html><body><script>{"timeline":{"entries":[
            {"content":{"tweet":{"id_str":"1836000000000000010","full_text":"A clip","extended_entities":{"media":[{"type":"video","video_info":{"variants":[{"content_type":"application/x-mpegURL","url":"https://video.twimg.com/ext_tw_video/9/pu/pl/playlist.m3u8"},{"content_type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/9/pu/vid/640x360/clip.mp4"}]}}]}}}
            ]}}</script></body></html>
        """.trimIndent()

        val post = XFeedParser.parseSyndication(page, "OpenAI").single()
        assertEquals(
            "https://video.twimg.com/ext_tw_video/9/pu/vid/640x360/clip.mp4",
            post.mediaUrl
        )
        assertTrue(!post.isPost)
    }

    /** With no progressive variant, the playlist is still better than nothing. */
    @Test
    fun fallsBackToThePlaylistWhenThereIsNoMp4() {
        val page = """
            <html><body><script>{"timeline":{"entries":[
            {"content":{"tweet":{"id_str":"1836000000000000011","full_text":"A clip","extended_entities":{"media":[{"type":"video","video_info":{"variants":[{"content_type":"application/x-mpegURL","url":"https://video.twimg.com/ext_tw_video/9/pu/pl/playlist.m3u8"}]}}]}}}
            ]}}</script></body></html>
        """.trimIndent()

        val post = XFeedParser.parseSyndication(page, "OpenAI").single()
        assertEquals(
            "https://video.twimg.com/ext_tw_video/9/pu/pl/playlist.m3u8",
            post.mediaUrl
        )
        assertTrue(!post.isPost)
    }

    // ── Clip length (feed cards show a duration badge) ──────────────

    /**
     * X publishes the clip's length as `duration_millis` inside the media's
     * `video_info`, just before the variant list. It was being dropped, so an X
     * card had no duration at all while YouTube and Instagram cards both had
     * one — even though the number travels with the URL the parser already
     * reads.
     */
    @Test
    fun readsTheClipDurationFromVideoInfo() {
        val page = """
            <html><body><script>{"timeline":{"entries":[
            {"content":{"tweet":{"id_str":"1836000000000000020","full_text":"A clip","extended_entities":{"media":[{"type":"video","video_info":{"aspect_ratio":[9,16],"duration_millis":9709,"variants":[{"content_type":"application/x-mpegURL","url":"https://video.twimg.com/ext_tw_video/20/pu/pl/playlist.m3u8"},{"content_type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/20/pu/vid/avc1/720x1280/clip.mp4"}]}}]}}}
            ]}}</script></body></html>
        """.trimIndent()

        val post = XFeedParser.parseSyndication(page, "OpenAI").single()
        assertEquals(
            "https://video.twimg.com/ext_tw_video/20/pu/vid/avc1/720x1280/clip.mp4",
            post.mediaUrl
        )
        assertEquals(9L, post.durationSeconds)
    }

    /** A post with no clip has no length — the badge must stay off. */
    @Test
    fun textOnlyPostHasNoDuration() {
        val page = """
            <html><body><script>{"timeline":{"entries":[
            {"content":{"tweet":{"id_str":"1836000000000000021","full_text":"Just words","favorite_count":3}}}
            ]}}</script></body></html>
        """.trimIndent()

        val post = XFeedParser.parseSyndication(page, "OpenAI").single()
        assertNull(post.mediaUrl)
        assertEquals(0L, post.durationSeconds)
    }

    // ── Channel avatars ─────────────────────────────────────────────

    @Test
    fun readsTheAvatarFromTheProfileApis() {
        val fxtwitter = """
            {"code":200,"user":{"screen_name":"OpenAI","avatar_url":"https://pbs.twimg.com/profile_images/1885410181409820672/ztsaR0JW_normal.jpg"}}
        """.trimIndent()
        assertEquals(
            "https://pbs.twimg.com/profile_images/1885410181409820672/ztsaR0JW_normal.jpg",
            XSource.extractAvatarUrl(fxtwitter, "OpenAI")
        )

        val vxtwitter = """
            {"screen_name":"OpenAI","profile_image_url":"https://pbs.twimg.com/profile_images/1/abc_normal.jpg"}
        """.trimIndent()
        assertEquals(
            "https://pbs.twimg.com/profile_images/1/abc_normal.jpg",
            XSource.extractAvatarUrl(vxtwitter, "OpenAI")
        )
    }

    /** A default placeholder (or anything off-host) is not an avatar. */
    @Test
    fun rejectsPlaceholderAvatars() {
        val placeholder = """
            {"user":{"avatar_url":"https://abs.twimg.com/sticky/default_profile_images/default_profile_normal.png"}}
        """.trimIndent()
        assertNull(XSource.extractAvatarUrl(placeholder, "OpenAI"))
        assertNull(XSource.extractAvatarUrl("not json", "OpenAI"))
        assertNull(XSource.extractAvatarUrl("{\"user\":{\"avatar_url\":\"https://example.com/a.jpg\"}}", "OpenAI"))
    }
}
