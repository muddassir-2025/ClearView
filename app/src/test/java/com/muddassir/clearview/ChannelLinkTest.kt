package com.muddassir.clearview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Channel share links (§6).
 *
 * Exercised through the string form rather than `android.net.Uri`, because the
 * build sets `unitTests.isReturnDefaultValues = true` and a stubbed Uri returns
 * null for everything — a Uri-based test would pass while proving nothing. The
 * Uri overload is an adapter over this same function.
 *
 * The cases that matter are the refusals. Accepting a link that is not ours
 * opens the wrong screen with someone else's identifier; refusing one that is
 * ours hands the user a dead link. Both are worse than not having the feature,
 * which is why the share button only shipped alongside these.
 */
class ChannelLinkTest {

    private fun slug(scheme: String?, host: String?, path: String?) =
        channelSlugFrom(scheme, host, path)

    @Test
    fun `reads the slug out of a channel link`() {
        assertEquals(
            "clearview-updates",
            slug("clearview", "goodpost", "/channel/clearview-updates")
        )
    }

    @Test
    fun `tolerates a trailing or doubled slash`() {
        // Links get hand-edited and re-pasted; a stray slash must not turn a
        // link that works into one that silently opens nothing.
        assertEquals("news", slug("clearview", "goodpost", "/channel/news/"))
        assertEquals("news", slug("clearview", "goodpost", "//channel//news"))
        assertEquals("news", slug("clearview", "goodpost", "/channel/news"))
    }

    @Test
    fun `keeps the slug exactly as the link spells it`() {
        // The backend lower-cases when it resolves, so the client must not
        // guess at normalisation — a slug rewritten here is one that no server
        // ever issued.
        assertEquals("Mixed-Case", slug("clearview", "goodpost", "/channel/Mixed-Case"))
    }

    @Test
    fun `refuses anything that is not a channel link`() {
        assertNull(slug(null, null, null))
        // Not our scheme: an https link is a web page, not a deep link.
        assertNull(slug("https", "goodpost", "/channel/news"))
        // Right scheme, wrong feature.
        assertNull(slug("clearview", "todo", "/reminder/42"))
        // Right scheme, host and depth, wrong first segment.
        assertNull(slug("clearview", "goodpost", "/news"))
        // A prefix of the route is not the route.
        assertNull(slug("clearview", "goodpost", "/channel"))
        assertNull(slug("clearview", "goodpost", "/channel/"))
        // Deeper than the route: /channel/<one segment> only.
        assertNull(slug("clearview", "goodpost", "/channel/a/b"))
        assertNull(slug("clearview", "goodpost", ""))
        assertNull(slug("clearview", "goodpost", null))
    }
}
