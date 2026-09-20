package com.muddassir.clearview.media.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helpers that decide where a feed item is rendered: Instagram Reels and
 * videos must behave like YouTube LONG videos (feed row, resume, Continue
 * Watching, offline audio) while Instagram photos/carousels are stills, and
 * only YouTube Shorts may enter the Shorts row / vertical viewer.
 */
class MediaVideoInstagramTest {

    private fun video(
        videoId: String,
        platform: MediaPlatform = MediaPlatform.YOUTUBE,
        type: InstagramMediaType? = null,
        isShort: Boolean = false,
        mediaUrl: String? = null,
        instagramUrl: String? = null
    ) = MediaVideo(
        videoId = videoId,
        title = "t",
        channelId = "c",
        channelName = "c",
        publishedAtEpochMillis = 0L,
        thumbnailUrl = "",
        isShort = isShort,
        platform = platform,
        instagramType = type,
        mediaUrl = mediaUrl,
        instagramUrl = instagramUrl
    )

    @Test
    fun `an instagram reel is a video, never a short`() {
        // The parsers store `isShort = true` for Reels (Instagram's own word
        // for a short clip) — the app must still treat it as a normal video.
        val reel = video(
            videoId = "ig_CxYz123",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.REEL,
            isShort = true,
            instagramUrl = "https://www.instagram.com/reel/CxYz123/"
        )
        assertTrue(reel.isInstagram)
        assertTrue(reel.isInstagramVideo)
        assertFalse(reel.isInstagramImage)
        assertFalse(reel.isShortsEntry)
    }

    @Test
    fun `an instagram video post with a direct mp4 is a video`() {
        val post = video(
            videoId = "ig_Vid42",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.VIDEO,
            isShort = true,
            mediaUrl = "https://scontent.cdninstagram.com/v/t50/vid42.mp4"
        )
        assertTrue(post.isInstagramVideo)
        assertFalse(post.isInstagramImage)
    }

    @Test
    fun `an instagram photo is a still, even without a type`() {
        val photo = video(
            videoId = "ig_Photo7",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.IMAGE
        )
        val carousel = video(
            videoId = "ig_Car9",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.CAROUSEL
        )
        assertTrue(photo.isInstagramImage)
        assertTrue(carousel.isInstagramImage)
        assertFalse(photo.isInstagramVideo)
        assertFalse(carousel.isShortsEntry)
    }

    @Test
    fun `a carousel with a video slide is still a post, not a video`() {
        // The Videos section is for Reels and videos ONLY. A carousel that
        // happens to contain a video clip belongs with the posts — the type
        // decides, not the presence of an mp4.
        val carousel = video(
            videoId = "ig_Car42",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.CAROUSEL,
            mediaUrl = "https://scontent.cdninstagram.com/v/clip.mp4"
        )
        assertFalse(carousel.isInstagramVideo)
        assertTrue(carousel.isInstagramImage)
    }

    @Test
    fun `an untyped instagram item falls back to its media url`() {
        // Older caches and manually added posts carry no type at all: a real
        // progressive video URL is then the only evidence available.
        val untypedVideo = video(
            videoId = "ig_Unknown1",
            platform = MediaPlatform.INSTAGRAM,
            mediaUrl = "https://scontent.cdninstagram.com/v/clip.mp4"
        )
        val untypedStill = video(videoId = "ig_Unknown2", platform = MediaPlatform.INSTAGRAM)
        assertTrue(untypedVideo.isInstagramVideo)
        assertTrue(untypedStill.isInstagramImage)
    }

    @Test
    fun `an untagged ig_ id is still recognised as instagram`() {
        // Older caches / manually added posts only carry the id prefix.
        val legacy = video(videoId = "ig_Legacy1", type = InstagramMediaType.REEL)
        assertTrue(legacy.isInstagram)
        assertTrue(legacy.isInstagramVideo)
        assertFalse(legacy.isShortsEntry)
    }

    @Test
    fun `youtube shorts stay shorts and youtube videos stay videos`() {
        val short = video(videoId = "abc", isShort = true)
        val long = video(videoId = "def")
        assertTrue(short.isShortsEntry)
        assertFalse(short.isInstagram)
        assertFalse(short.isInstagramImage)
        assertFalse(long.isShortsEntry)
    }

    // ── Posts vs playable media (the feed renders each differently) ──

    @Test
    fun `an x tweet without a video is a post`() {
        val textOnly = video(videoId = "x_1", platform = MediaPlatform.X)
        val withPhoto = video(
            videoId = "x_2",
            platform = MediaPlatform.X,
            mediaUrl = null
        ).copy(thumbnailUrl = "https://pbs.twimg.com/media/x.jpg")
        assertTrue(textOnly.isPost)
        assertTrue(withPhoto.isPost)
        assertFalse(textOnly.hasPlayableVideo)
    }

    @Test
    fun `an x video attachment is playable media, not a post`() {
        val clip = video(
            videoId = "x_3",
            platform = MediaPlatform.X,
            mediaUrl = "https://video.twimg.com/ext_tw_video/1/pu/vid/640x360/a.mp4?tag=12"
        )
        assertTrue(clip.hasPlayableVideo)
        assertFalse(clip.isPost)
    }

    @Test
    fun `instagram photos are posts and reels are not`() {
        val photo = video(
            videoId = "ig_P1",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.IMAGE
        )
        val reel = video(
            videoId = "ig_R1",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.REEL
        )
        assertTrue(photo.isPost)
        assertFalse(reel.isPost)
    }

    @Test
    fun `a youtube video is never a post`() {
        assertFalse(video(videoId = "yt1").isPost)
        assertFalse(video(videoId = "yt2", isShort = true).isPost)
    }

    @Test
    fun `post body text is carried separately from the card label`() {
        val long = "x".repeat(500)
        val post = video(videoId = "x_9", platform = MediaPlatform.X)
            .copy(title = long.take(160), bodyText = long)
        assertTrue(post.title.length < post.bodyText.length)
        assertTrue(post.bodyText.endsWith("xxxx"))
    }
}
