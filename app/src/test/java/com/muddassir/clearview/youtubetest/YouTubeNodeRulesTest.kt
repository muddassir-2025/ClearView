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

    @Test
    fun `a plain container is neither forbidden nor a player`() {
        assertFalse(YouTubeNodeRules.isForbiddenActionNode(null, null, "android.view.View"))
        assertFalse(YouTubeNodeRules.isPlayerNode(null, null, "android.view.View"))
        assertFalse(YouTubeNodeRules.isPlayerNode("Some video title", null, "android.widget.TextView"))
    }
}
