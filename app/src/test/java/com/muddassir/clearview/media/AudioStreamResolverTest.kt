package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.AudioStreamResolver
import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions listen mode makes before it touches the network: which
 * platform a video belongs to, and what its cached resolution is filed under.
 *
 * Both are cheap to get wrong and expensive to notice. A legacy `ig_` id routed
 * down the YouTube path asks NewPipe about a video that does not exist and
 * reports "no audio"; a cache key that collides writes an audio-only URL where
 * the Reel player looks for its video, and the result half-works — which is
 * worse than failing, because the picture appears and then does not move.
 */
class AudioStreamResolverTest {

    private fun youtube(id: String = "dQw4w9WgXcQ") = MediaVideo(
        videoId = id,
        title = "A talk",
        channelId = "UC_x",
        channelName = "Some Channel",
        publishedAtEpochMillis = 0L,
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    )

    private fun instagram(
        id: String = "ig_CxYz123",
        type: InstagramMediaType = InstagramMediaType.REEL,
        platform: MediaPlatform = MediaPlatform.INSTAGRAM
    ) = MediaVideo(
        videoId = id,
        title = "A reel",
        channelId = "somebody",
        channelName = "Somebody",
        publishedAtEpochMillis = 0L,
        thumbnailUrl = "https://scontent.cdninstagram.com/thumb.jpg",
        platform = platform,
        instagramType = type,
        instagramUrl = "https://www.instagram.com/reel/CxYz123/",
        mediaUrl = "https://scontent.cdninstagram.com/v/reel.mp4"
    )

    @Test
    fun `a YouTube video resolves as YouTube`() {
        val request = AudioStreamResolver.requestFor(youtube())
        assertFalse(request.isInstagram)
        assertEquals("dQw4w9WgXcQ", request.videoId)
        assertNull("a YouTube item carries no Instagram permalink", request.instagramUrl)
    }

    @Test
    fun `an Instagram reel resolves as Instagram and carries its permalink`() {
        val request = AudioStreamResolver.requestFor(instagram())
        assertTrue(request.isInstagram)
        assertEquals("https://www.instagram.com/reel/CxYz123/", request.instagramUrl)
        assertEquals("https://scontent.cdninstagram.com/v/reel.mp4", request.mediaUrl)
    }

    @Test
    fun `a legacy ig_ id is treated as Instagram even without the platform tag`() {
        // Older caches and manually added posts store the `ig_` prefix with the
        // default platform, and that alone has to route them down the Reel path.
        val legacy = instagram(platform = MediaPlatform.YOUTUBE)
        val request = AudioStreamResolver.requestFor(legacy)
        assertTrue(request.isInstagram)
    }

    @Test
    fun `an audio resolution is never filed under a Reel's video key`() {
        val request = AudioStreamResolver.requestFor(instagram())
        val audioKey = AudioStreamResolver.cacheKey(request)
        val shortcode = "CxYz123"

        assertNotEquals(
            "the audio cache must not collide with the Reel's own resolution",
            shortcode,
            audioKey
        )
        assertTrue("the shortcode must still identify it", audioKey.contains(shortcode))
    }

    @Test
    fun `the two platforms never share a cache key`() {
        val video = youtube("CxYz123")
        val youtubeKey = AudioStreamResolver.cacheKey(AudioStreamResolver.requestFor(video))
        val instagramKey = AudioStreamResolver.cacheKey(AudioStreamResolver.requestFor(instagram()))
        assertNotEquals(youtubeKey, instagramKey)
    }

    @Test
    fun `each video gets its own cache key`() {
        val first = AudioStreamResolver.cacheKey(AudioStreamResolver.requestFor(youtube("AAA")))
        val second = AudioStreamResolver.cacheKey(AudioStreamResolver.requestFor(youtube("BBB")))
        assertNotEquals(first, second)
    }
}
