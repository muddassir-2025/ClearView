package com.muddassir.clearview.youtubetest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which nodes the YouTube blocker may touch.
 *
 * These exist because the failure they describe is invisible from the outside:
 * ClearView was supposed to pause a blocked video and instead opened YouTube's
 * Share sheet, and nothing in the code said "click Share" — the player lookup
 * simply chose it. A rule this consequential has to be checkable without a
 * device, a Chrome build and a Short that happens to contain the keyword.
 */
class YouTubeNodeRulesTest {

    // ── The regression itself ────────────────────────────────────────────

    @Test
    fun `a share button is never a player and never clickable by the blocker`() {
        // The exact node the player walk used to select: a toolbar button that
        // is clickable, visible, and labelled with the word the old rule matched.
        assertTrue(
            YouTubeNodeRules.isForbiddenActionNode(
                text = null,
                desc = "Share this video",
                cls = "android.view.View"
            )
        )
        assertFalse(
            YouTubeNodeRules.isPlayerNode(
                text = null,
                desc = "Share this video",
                cls = "android.view.View"
            )
        )
    }

    @Test
    fun `every label the share sheet is reachable through is refused`() {
        // Chrome and YouTube spell this differently across versions, which is
        // why the guard reads the substring and all four fields.
        listOf("Share", "share", "Share this video", "Share Short", "More actions")
            .forEach { label ->
                assertTrue("desc=$label", YouTubeNodeRules.isForbiddenActionNode(null, label))
                assertTrue("text=$label", YouTubeNodeRules.isForbiddenActionNode(label, null))
            }
    }

    @Test
    fun `the guard also reads the class and the view id`() {
        // A node with no label at all can still be identified by where it lives.
        assertTrue(
            YouTubeNodeRules.isForbiddenActionNode(
                text = null,
                desc = null,
                cls = null,
                viewId = "com.android.chrome:id/share_button"
            )
        )
    }

    @Test
    fun `the rest of the video toolbar is refused too`() {
        listOf("Subscribe", "Save", "Download", "Report", "Remix", "Dislike", "Comments")
            .forEach { label ->
                assertTrue(label, YouTubeNodeRules.isForbiddenActionNode(null, label))
            }
    }

    // ── The player and the transport controls still work ─────────────────

    @Test
    fun `the player is recognised by its own name and by the media surface`() {
        assertTrue(
            YouTubeNodeRules.isPlayerNode(null, "YouTube video player", "android.view.View")
        )
        // Chrome's media element, which is the player whatever it is labelled.
        assertTrue(
            YouTubeNodeRules.isPlayerNode(null, null, "android.view.SurfaceView")
        )
        assertTrue(
            YouTubeNodeRules.isPlayerNode(null, null, "android.widget.VideoView")
        )
    }

    @Test
    fun `play and pause controls are players, and only as whole labels`() {
        assertTrue(YouTubeNodeRules.isPlayerNode(null, "Play"))
        assertTrue(YouTubeNodeRules.isPlayerNode(null, "Pause video"))
        assertTrue(YouTubeNodeRules.isPlayLabel("play"))
        assertTrue(YouTubeNodeRules.isPauseLabel(" Pause "))

        // A sentence that merely CONTAINS the word is not a transport control —
        // this is the loose matching that made the toolbar eligible.
        assertFalse(YouTubeNodeRules.isPlayLabel("Playback settings"))
        assertFalse(YouTubeNodeRules.isPauseLabel("Pause subscription"))
    }

    // ── The player is found by its own container id ───────────────────────

    @Test
    fun `youtube's own player containers are recognised by resource id`() {
        // The exact ids the current Chrome tree carries for a Short. The old
        // rule read only text/desc/class, none of which name the player, so the
        // lookup returned nothing and a matched Short kept playing.
        listOf(
            "player",
            "movie_player",
            "player-container-id",
            "player-shorts-container",
            "player-cinematics-container"
        ).forEach { id ->
            assertTrue(id, YouTubeNodeRules.isPlayerContainerId(id))
            assertTrue(
                id,
                YouTubeNodeRules.isPlayerNode(null, null, "android.view.View", id)
            )
        }
    }

    @Test
    fun `a player id with a chrome package prefix still matches`() {
        assertTrue(YouTubeNodeRules.isPlayerContainerId("com.android.chrome:id/movie_player"))
    }

    @Test
    fun `a browser button id is not a player container`() {
        listOf(
            "com.android.chrome:id/menu_button",
            "com.android.chrome:id/home_button",
            "player_playback_settings"
        ).forEach { id ->
            assertFalse(id, YouTubeNodeRules.isPlayerContainerId(id))
        }
    }

    @Test
    fun `the player guard beats the forbidden guard — a share button is still refused`() {
        // Belt and braces: even if a node carried both a player id and a
        // forbidden label, the walk applies the forbidden guard first.
        assertTrue(
            YouTubeNodeRules.isForbiddenActionNode(null, "Share this video", null, "player")
        )
    }

    // ── "Don't recommend this channel" (the YouTube app offer) ──────────

    @Test
    fun `the dont-recommend menu items are recognised across spellings`() {
        listOf(
            "Don't recommend this channel",
            "Dont recommend this channel",
            "Do not recommend this channel",
            "Don't recommend this video"
        ).forEach { label ->
            assertTrue(label, YouTubeNodeRules.isDontRecommendLabel(label))
        }
    }

    @Test
    fun `only the channel item is the narrower dont-recommend action`() {
        // The channel action is the one ClearView can act on, because it names
        // a channel. The video item must not be mistaken for it — blocking a
        // channel is a much bigger decision than hiding one video.
        assertTrue(
            YouTubeNodeRules.isDontRecommendChannelLabel("Don't recommend this channel")
        )
        assertFalse(
            YouTubeNodeRules.isDontRecommendChannelLabel("Don't recommend this video")
        )
    }

    @Test
    fun `an ordinary label is not a dont-recommend action`() {
        listOf("Recommended", "Recommendations", "Subscribe", "Share", "More actions")
            .forEach { label ->
                assertFalse(label, YouTubeNodeRules.isDontRecommendLabel(label))
                assertFalse(label, YouTubeNodeRules.isDontRecommendChannelLabel(label))
            }
    }

    // ── "Not interested" (the immediate personal block) ──────────────────

    @Test
    fun `the not-interested action is recognised across spellings`() {
        listOf("Not interested", "not interested", "Not Interested").forEach { label ->
            assertTrue(label, YouTubeNodeRules.isNotInterestedLabel(label))
        }
    }

    @Test
    fun `an ordinary label is not the not-interested action`() {
        listOf("Interested", "Related videos", "Subscribe", "Save").forEach { label ->
            assertFalse(label, YouTubeNodeRules.isNotInterestedLabel(label))
        }
        assertFalse(YouTubeNodeRules.isNotInterestedLabel(null))
    }

    @Test
    fun `a plain container is neither forbidden nor a player`() {
        assertFalse(YouTubeNodeRules.isForbiddenActionNode(null, null, "android.view.View"))
        assertFalse(YouTubeNodeRules.isPlayerNode(null, null, "android.view.View"))
        assertFalse(YouTubeNodeRules.isPlayerNode("Some video title", null, "android.widget.TextView"))
    }

    @Test
    fun `a labelled container is not mistaken for a player`() {
        assertFalse(YouTubeNodeRules.isPlayerNode(null, null, "android.widget.FrameLayout"))
        assertFalse(
            YouTubeNodeRules.isPlayerNode(null, null, "android.view.View", "player_title")
        )
    }
}
