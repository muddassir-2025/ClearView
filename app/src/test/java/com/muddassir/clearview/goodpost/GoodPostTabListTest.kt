package com.muddassir.clearview.goodpost

import com.muddassir.clearview.goodpost.data.AdminSession
import com.muddassir.clearview.goodpost.data.GoodPostChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Good Post tab lists (§1, §3).
 *
 * One rule, and it is the product's rather than a screen's: a reader sees the
 * public channel list, and a signed-in account sees the channels that account has
 * access to. The second replaces the first while it lasts, which is the part
 * worth pinning: an account created for one channel must not be handed the rest
 * of Good Post.
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
    fun `a reader sees the public channels`() {
        val state = GoodPostUiState(
            channels = listOf(channel("a"), channel("b"), channel("c"))
        )

        assertEquals(listOf("a", "b", "c"), ids(state))
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
    fun `a channel administrator sees the channel they run, not the catalogue`() {
        val state = GoodPostUiState(
            channels = listOf(channel("a"), channel("b")),
            admin = session("channel_admin", "b"),
            adminChannels = listOf(channel("b"))
        )

        // §3: an account created for one channel gets that channel.
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
