package com.muddassir.clearview.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Address-bar routing must never guess a URL from ordinary search text. */
class BrowserAddressTest {

    @Test
    fun instagramAuthenticationFallbackIsRestrictedToAccountPages() {
        assertTrue(BrowserAddress.isInstagramAuthUrl("https://www.instagram.com/accounts/login/?next=%2F"))
        assertTrue(BrowserAddress.isInstagramAuthUrl("https://www.instagram.com/accounts/emailsignup/"))
        assertFalse(BrowserAddress.isInstagramAuthUrl("https://instagram.com.attacker.example/accounts/login/"))
        assertFalse(BrowserAddress.isInstagramAuthUrl("https://www.instagram.com/reels/example/"))
        assertFalse(BrowserAddress.isInstagramAuthUrl("https://www.instagram.com/accounts-other/"))
        assertFalse(BrowserAddress.isInstagramAuthUrl(null))
    }

    @Test
    fun blankInputLoadsNothing() {
        assertNull(BrowserAddress.toUrl(null))
        assertNull(BrowserAddress.toUrl("   "))
    }

    @Test
    fun explicitHttpUrlIsUnchanged() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc123",
            BrowserAddress.toUrl("https://www.youtube.com/watch?v=abc123")
        )
        assertEquals("http://example.com/", BrowserAddress.toUrl("http://example.com/"))
    }

    @Test
    fun bareHostGetsHttps() {
        assertEquals("https://youtube.com", BrowserAddress.toUrl("youtube.com"))
        assertEquals(
            "https://m.youtube.com/results?search_query=quran",
            BrowserAddress.toUrl("m.youtube.com/results?search_query=quran")
        )
        assertEquals("https://x.com/foo", BrowserAddress.toUrl("x.com/foo"))
    }

    @Test
    fun ordinaryTextBecomesAnEncodedSearch() {
        val url = BrowserAddress.toUrl("how to cook rice")!!
        assertTrue(url.startsWith("https://www.google.com/search?q="))
        assertTrue(url.contains("how+to+cook+rice"))
    }

    @Test
    fun textWithASentenceDotIsStillASearch() {
        val url = BrowserAddress.toUrl("e.g. this is a sentence")!!
        assertTrue(url.startsWith("https://www.google.com/search?q="))
    }

    @Test
    fun hostLikeTextIsDetected() {
        assertTrue(BrowserAddress.looksLikeHost("youtube.com"))
        assertTrue(BrowserAddress.looksLikeHost("www.instagram.com/p/abc"))
        assertFalse(BrowserAddress.looksLikeHost("hello world"))
        assertFalse(BrowserAddress.looksLikeHost("localhost"))
        assertFalse(BrowserAddress.looksLikeHost("no tld.here yes"))
    }

    @Test
    fun onlyHttpSchemesAreWebUrls() {
        assertTrue(BrowserAddress.isWebUrl("https://example.com"))
        assertTrue(BrowserAddress.isWebUrl("http://example.com"))
        assertFalse(BrowserAddress.isWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(BrowserAddress.isWebUrl("javascript:alert(1)"))
        assertFalse(BrowserAddress.isWebUrl("file:///sdcard/secret"))
        assertFalse(BrowserAddress.isWebUrl(null))
    }

    @Test
    fun youtubeDetectionUsesHostBoundaries() {
        assertTrue(BrowserAddress.isYouTube("https://www.youtube.com/watch?v=1"))
        assertTrue(BrowserAddress.isYouTube("https://m.youtube.com/"))
        assertTrue(BrowserAddress.isYouTube("https://youtube.com/shorts/abc"))
        assertFalse(BrowserAddress.isYouTube("https://notyoutube.com/watch?v=1"))
        assertFalse(BrowserAddress.isYouTube("https://youtube.com.evil.example/"))
        assertFalse(BrowserAddress.isYouTube(null))
    }

    @Test
    fun hostIsLowercasedAndTrailingDotRemoved() {
        assertEquals(
            "www.youtube.com",
            BrowserAddress.host("https://WWW.YouTube.com/watch")
        )
        assertEquals("example.com", BrowserAddress.host("example.com."))
    }

    @Test
    fun googleHostsIncludeRegionalDomains() {
        assertTrue(BrowserAddress.isGoogle("https://google.com/search?q=x"))
        assertTrue(BrowserAddress.isGoogle("https://www.google.co.uk/"))
        assertTrue(BrowserAddress.isGoogle("https://google.com.au/"))
        assertFalse(BrowserAddress.isGoogle("https://notgoogle.com/"))
        assertFalse(BrowserAddress.isGoogle("https://google.com.evil.example/"))
    }

    @Test
    fun searchUrlsAreRecognisedByHostAndPath() {
        assertTrue(
            BrowserAddress.isGoogleSearchUrl("https://www.google.com/search?q=quran")
        )
        assertFalse(BrowserAddress.isGoogleSearchUrl("https://www.google.com/"))
        assertFalse(
            BrowserAddress.isGoogleSearchUrl("https://example.com/search?q=quran")
        )
        assertTrue(
            BrowserAddress.isYouTubeSearchUrl(
                "https://www.youtube.com/results?search_query=quran"
            )
        )
        assertFalse(
            BrowserAddress.isYouTubeSearchUrl("https://www.youtube.com/watch?v=1")
        )
    }

    @Test
    fun googleSearchesAlwaysGetSafeSearch() {
        assertEquals(
            "https://www.google.com/search?q=quran&safe=active",
            BrowserAddress.withSafeSearch("https://www.google.com/search?q=quran")
        )
        assertEquals(
            "https://www.google.com/search?q=quran&safe=active",
            BrowserAddress.withSafeSearch(
                "https://www.google.com/search?q=quran&safe=off"
            )
        )
        assertEquals(
            "https://www.google.co.uk/search?q=quran&safe=active#results",
            BrowserAddress.withSafeSearch(
                "https://www.google.co.uk/search?q=quran#results"
            )
        )
    }

    @Test
    fun safeSearchLeavesNonSearchPagesAlone() {
        assertEquals(
            "https://www.google.com/",
            BrowserAddress.withSafeSearch("https://www.google.com/")
        )
        assertEquals(
            "https://example.com/search?q=quran",
            BrowserAddress.withSafeSearch("https://example.com/search?q=quran")
        )
    }

    @Test
    fun bingAndDuckDuckGoGetTheirStrictFlag() {
        assertEquals(
            "https://www.bing.com/search?q=quran&adlt=strict",
            BrowserAddress.withSafeSearch("https://www.bing.com/search?q=quran")
        )
        assertEquals(
            "https://duckduckgo.com/?q=quran&kp=1",
            BrowserAddress.withSafeSearch("https://duckduckgo.com/?q=quran")
        )
    }

    @Test
    fun instagramReelsAreRecognised() {
        assertTrue(
            BrowserAddress.isInstagramReelsUrl("https://www.instagram.com/reels/abc/")
        )
        assertTrue(
            BrowserAddress.isInstagramReelsUrl("https://www.instagram.com/reel/abc/")
        )
        assertTrue(BrowserAddress.isInstagramReelsUrl("https://instagram.com/reels/"))
        assertFalse(
            BrowserAddress.isInstagramReelsUrl("https://www.instagram.com/p/abc/")
        )
        assertFalse(
            BrowserAddress.isInstagramReelsUrl("https://notinstagram.com/reels/abc/")
        )
    }

    @Test
    fun instagramHomeIsTheFeedAndNotAProfile() {
        assertTrue(BrowserAddress.isInstagramHomeUrl("https://www.instagram.com/"))
        assertTrue(BrowserAddress.isInstagramHomeUrl("https://www.instagram.com/home/"))
        assertFalse(BrowserAddress.isInstagramHomeUrl("https://www.instagram.com/explore/"))
        assertFalse(
            BrowserAddress.isInstagramHomeUrl("https://www.instagram.com/maherzainofficial/")
        )
        // A hidden Home must be recognised on a host that only looks similar.
        assertFalse(BrowserAddress.isInstagramHomeUrl("https://notinstagram.com/"))
    }

    @Test
    fun instagramExploreIsRecognised() {
        assertTrue(BrowserAddress.isInstagramExploreUrl("https://www.instagram.com/explore/"))
        assertTrue(
            BrowserAddress.isInstagramExploreUrl("https://www.instagram.com/explore/search/")
        )
        assertFalse(BrowserAddress.isInstagramExploreUrl("https://www.instagram.com/"))
        assertFalse(
            BrowserAddress.isInstagramExploreUrl("https://www.instagram.com/p/abc/")
        )
    }

    @Test
    fun instagramProfileHandleComesOnlyFromAProfileUrl() {
        assertEquals(
            "@maherzainofficial",
            BrowserAddress.instagramProfileHandle("https://www.instagram.com/maherzainofficial/")
        )
        assertEquals(
            "@someone",
            BrowserAddress.instagramProfileHandle("https://instagram.com/someone")
        )
        // Site pages are not profiles: a block must never be read out of them.
        assertNull(BrowserAddress.instagramProfileHandle("https://www.instagram.com/p/abc/"))
        assertNull(
            BrowserAddress.instagramProfileHandle("https://www.instagram.com/explore/")
        )
        assertNull(BrowserAddress.instagramProfileHandle("https://www.instagram.com/@user/"))
        assertNull(BrowserAddress.instagramProfileHandle("https://example.com/someone/"))
    }

    @Test
    fun youtubeHandleComesOnlyFromAChannelUrl() {
        assertEquals(
            "@someone",
            BrowserAddress.youtubeChannelHandle("https://www.youtube.com/@someone")
        )
        assertNull(BrowserAddress.youtubeChannelHandle("https://www.youtube.com/watch?v=abc"))
        assertNull(BrowserAddress.youtubeChannelHandle("https://example.com/@someone"))
    }
}
