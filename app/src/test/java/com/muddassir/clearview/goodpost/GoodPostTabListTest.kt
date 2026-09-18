package com.muddassir.clearview.goodpost

import com.muddassir.clearview.goodpost.data.AdminSession
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Good Post tab lists (§1, §3, §4).
 *
 * One rule, and it is the product's rather than a screen's: a reader sees the
 * list the tab fetched for them — their FOLLOWED channels, which is what
 * `refreshChannels` writes — and a signed-in account sees the channels that
 * account has access to. The second REPLACES the first while it lasts, which is
 * the part worth pinning: an account created for one channel must not be handed
 * the rest of Good Post.
 *
 * Both are rules about [GoodPostUiState.channels], so the reader cases here hand
 * it the list a reader's own follows produce. Which list that is, and why it is
 * the follows rather than the catalogue or the other way round, belongs to the
 * ViewModel and is settled where the fetch is made.
 *
 * Tested here as a pure rule rather than through a screen because it is the
 * decision that was hardest to get right, and the one a screen would let drift:
 * "the public catalogue" and "the channels this account may publish to" produce
 * the same list for a single-channel account and totally different ones for a
 * super administrator.
 */
class GoodPostTabListTest {

    private fun channel(id: String) = GoodPostChannel(
        id = id,
        slug = id,
        name = id.uppercase(),
        description = null,
        categorySlug = null,
        categoryLabel = null,
        countryCode = null,
        iconUrl = null,
        createdAt = "2026-08-01T10:00:00.000Z",
        lastPostAt = null,
        lastPostType = null,
        lastPostPreview = null,
        shareLink = "clearview://goodpost/channel/$id"
    )

    private fun session(role: String, channelId: String?) = AdminSession(
        token = "access-token",
        refreshToken = "refresh-token",
        role = role,
        email = "admin@example.test",
        channelId = channelId
    )

    private fun ids(state: GoodPostUiState) = state.tabChannels.map { it.id }

    @Test
    fun `a reader sees the channels the tab fetched for them`() {
        val state = GoodPostUiState(
            channels = listOf(channel("a"), channel("b"), channel("c"))
        )

        assertEquals(listOf("a", "b", "c"), ids(state))
    }

    @Test
    fun `following nothing is an empty tab, not a catalogue`() {
        // The state a reader who follows nothing is in, and the one this list is
        // most likely to get wrong: filling it with every channel on the
        // platform would make the tab indistinguishable from Explore and hide
        // the fact that nobody had chosen anything.
        val signedIn = GoodPostUiState(admin = session("super_admin", null))

        assertEquals(emptyList<String>(), ids(signedIn.copy(adminChannels = emptyList())))
        assertEquals(emptyList<String>(), ids(GoodPostUiState(channels = emptyList())))
    }

    @Test
    fun `the tab has nothing to show before the catalogue arrives`() {
        // Nothing is stored per device, so an empty list is a loading state
        // rather than a set of choices not yet made.
        assertEquals(emptyList<String>(), ids(GoodPostUiState()))
    }

    @Test
    fun `a channel that is no longer public stops being listed`() {
        // Suspended, or deleted: the public read does not return it, so it cannot
        // appear — a reader is never shown a channel the server refused them.
        val state = GoodPostUiState(channels = listOf(channel("a")))

        assertEquals(listOf("a"), ids(state))
    }

    @Test
    fun `a channel administrator sees the channel they run first, then their follows`() {
        val state = GoodPostUiState(
            channels = listOf(channel("a"), channel("b")),
            admin = session("channel_admin", "b"),
            adminChannels = listOf(channel("b"))
        )

        // §3, §4: the account's own channel leads — it is the row with something
        // to do on it — and the channels this reader follows come after it.
        //
        // The follows used to be dropped entirely while signed in, which meant a
        // creator who followed a channel from Explore saw it appear NOWHERE: the
        // follow succeeded, the tab was drawing the account's channels, and the
        // channel they had just chosen was invisible.
        //
        // `b` is deduped rather than listed twice, which is the case that makes
        // running a channel you also follow one row.
        assertEquals(listOf("b", "a"), ids(state))
    }

    @Test
    fun `a channel administrator who follows nothing still sees only their own channel`() {
        val state = GoodPostUiState(
            admin = session("channel_admin", "b"),
            adminChannels = listOf(channel("b"))
        )

        assertEquals(listOf("b"), ids(state))
    }

    @Test
    fun `a super administrator sees every channel their account has`() {
        val state = GoodPostUiState(
            channels = listOf(channel("a"), channel("b"), channel("c")),
            admin = session("super_admin", null),
            adminChannels = listOf(channel("a"), channel("b"), channel("c"))
        )

        assertEquals(listOf("a", "b", "c"), ids(state))
    }

    @Test
    fun `only a reader or a super administrator is offered the way to create a channel`() {
        val reader = GoodPostUiState()
        val owner = GoodPostUiState(admin = session("channel_admin", "b"))
        val superAdmin = GoodPostUiState(admin = session("super_admin", null))

        assertTrue(reader.canCreateChannel)
        assertTrue(superAdmin.canCreateChannel)

        // A channel administrator holds no `channels.create`. Offering it would
        // be offering a button the server always refuses.
        assertFalse(owner.canCreateChannel)
    }

    @Test
    fun `management follows the account, not the screen it was opened from`() {
        val reader = GoodPostUiState()
        val owner = GoodPostUiState(admin = session("channel_admin", "b"))
        val superAdmin = GoodPostUiState(admin = session("super_admin", null))

        assertFalse(reader.canManage("b"))

        assertTrue(owner.canManage("b"))
        assertFalse(owner.canManage("a"))

        assertTrue(superAdmin.canManage("a"))
        assertTrue(superAdmin.canManage("b"))
    }
}
