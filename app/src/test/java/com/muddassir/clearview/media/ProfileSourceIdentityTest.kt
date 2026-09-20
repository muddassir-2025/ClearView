package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.InstagramResolver
import com.muddassir.clearview.media.data.XSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSourceIdentityTest {
    @Test
    fun extractsInstagramHandlesAndProfileUrls() {
        assertEquals("maherzainofficial", InstagramResolver.extractUsername("@maherzainofficial"))
        assertEquals(
            "maherzainofficial",
            InstagramResolver.extractUsername("https://www.instagram.com/maherzainofficial/")
        )
        assertEquals(
            "maherzainofficial",
            InstagramResolver.extractUsername("https://instagram.com/@maherzainofficial/?hl=en")
        )
    }

    @Test
    fun doesNotTreatInstagramPostUrlsAsProfiles() {
        assertNull(InstagramResolver.extractUsername("https://www.instagram.com/reel/ABC123/"))
        assertNull(InstagramResolver.extractUsername("https://www.instagram.com/accounts/login/"))
    }

    @Test
    fun extractsXProfileWithoutRequiringBridgeContent() {
        assertEquals("ClearView", XSource.extractUsername("@ClearView"))
        assertEquals("ClearView", XSource.extractUsername("https://x.com/ClearView"))
        assertEquals("ClearView", XSource.extractUsername("https://twitter.com/ClearView/status/1"))
    }

    @Test
    fun doesNotTreatXReservedRoutesAsProfiles() {
        assertNull(XSource.extractUsername("https://x.com/explore"))
        assertNull(XSource.extractUsername("https://x.com/intent/post"))
    }
}
