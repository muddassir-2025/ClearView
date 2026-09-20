package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.ChannelIdResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelIdResolverIdentityTest {
    @Test
    fun `uses canonical page channel instead of first related channel id`() {
        val html = """
            <link rel="canonical" href="https://www.youtube.com/@Maherain">
            <meta itemprop="channelId" content="UC2cX3SmsdWsrRS8t_5zvzEw">
            <script>var related = {"channelId":"UCROKYPep-UuODNwyipe6JMw"};</script>
        """.trimIndent()

        assertEquals(
            "UC2cX3SmsdWsrRS8t_5zvzEw",
            ChannelIdResolver.extractResolvedChannelId(html, "@Maherain")
        )
    }

    @Test
    fun `rejects a page whose canonical handle differs from requested handle`() {
        val html = """
            <link rel="canonical" href="https://www.youtube.com/@awakeningrecords">
            <meta itemprop="channelId" content="UC2cX3SmsdWsrRS8t_5zvzEw">
        """.trimIndent()

        assertNull(ChannelIdResolver.extractResolvedChannelId(html, "@Maherain"))
    }
}
