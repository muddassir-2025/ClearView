package com.muddassir.clearview.brainrot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The canonical key an item's provenance is filed under.
 *
 * Pure, and worth pinning: the reason for a block is looked up by an item's
 * IDENTITY, and if this key disagreed with the identity the lists use, a blocked
 * item would show no reason at all — which is exactly the state the reason store
 * exists to prevent. A channel is keyed by its normalised handle (so a rename
 * never loses the reason), a keyword by its lowercased text.
 */
class BlockedItemMetaTest {

    @Test
    fun `a channel is keyed by its normalised handle`() {
        // Channels are always recorded with their leading "@" (BlockAction
        // normalises before recording), which is what marks an item as a
        // handle rather than a keyword.
        assertEquals("@example", BlockedItemMeta.keyOf("@Example"))
        assertEquals("@a.b-c_d", BlockedItemMeta.keyOf("@A.B-C_D"))
    }

    @Test
    fun `a bare name is a keyword, not a channel`() {
        // No leading "@", so it is keyword text — keyed as a keyword. This is
        // the distinction the two lists are built on, so it is pinned here.
        assertEquals("example", BlockedItemMeta.keyOf("example"))
    }

    @Test
    fun `a keyword is keyed by its lowercased text`() {
        assertEquals("brainrot", BlockedItemMeta.keyOf("BrainRot"))
        assertEquals("brain rot", BlockedItemMeta.keyOf("  Brain Rot  "))
    }

    @Test
    fun `a blank item has no key rather than an empty one`() {
        assertNull(BlockedItemMeta.keyOf(null))
        assertNull(BlockedItemMeta.keyOf(""))
        assertNull(BlockedItemMeta.keyOf("   "))
    }

    @Test
    fun `an unusable handle is not keyed as a channel`() {
        // "@" and "@-" are not handles, so they must not be filed as one — the
        // lookup would then never be asked for them, and the reason would be
        // lost while looking present.
        assertNull(BlockedItemMeta.keyOf("@"))
        assertNull(BlockedItemMeta.keyOf("@-"))
    }

    @Test
    fun `the source wire strings are stable`() {
        // These travel to the backend and are grouped by the dashboard, so a
        // rename here is a data migration, not a refactor.
        assertEquals("youtube_not_interested", BlockedItemMeta.Source.YOUTUBE_NOT_INTERESTED.wire)
        assertEquals("user", BlockedItemMeta.Source.USER.wire)
        assertEquals(BlockedItemMeta.Source.UNKNOWN, BlockedItemMeta.Source.fromWire("nonsense"))
        assertEquals(BlockedItemMeta.Source.GLOBAL, BlockedItemMeta.Source.fromWire("global"))
    }
}
