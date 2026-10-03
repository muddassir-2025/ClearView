package com.muddassir.clearview.youtubetest

import java.util.Locale

/**
 * Which accessibility nodes the YouTube blocker may touch.
 *
 * Extracted from the two coordinators so the rules are one thing rather than
 * two, and so they can be tested directly: this is the decision that put
 * YouTube's Share sheet on screen, and a decision that can only be checked by
 * driving Chrome on a device is a decision that gets checked once.
 *
 * The bug it exists to close: the player was found by walking the tree for
 * anything that looked player-ish, and nodes labelled "more actions" and "share
 * this video" were in that set. When the current Chrome tree has no node
 * carrying "YouTube video player", the old code fell back to the first
 * CLICKABLE node it had collected — the Share button — and then clicked it to
 * "reveal the controls" and tapped its centre to "pause". Both gestures landed
 * on Share.
 */
internal object YouTubeNodeRules {

    /**
     * True for a node the blocker must never click or tap.
     *
     * Every one of these is a button in the same toolbar as the transport
     * controls, and not one of them pauses anything. Checked against the text,
     * the content description, the class name AND the view id, because Chrome
     * labels these inconsistently across versions and a rule that only reads one
     * of them is a rule that stops working quietly.
     */
    fun isForbiddenActionNode(
        text: String?,
        desc: String?,
        cls: String? = null,
        viewId: String? = null
    ): Boolean {
        val hay = haystack(text, desc, cls, viewId)
        return FORBIDDEN_ANYWHERE.any { hay.contains(it) }
    }

    /**
     * True for a node that is a PLAYER or a PLAYBACK CONTROL — never one of the
     * video's action buttons.
     *
     * The class is the last resort and it matters: Chrome exposes its media
     * element as a `SurfaceView` / `VideoView`, which is the player whatever it
     * happens to be labelled, and the label is exactly what changed.
     */
    fun isPlayerNode(
        text: String?,
        desc: String?,
        cls: String? = null,
        viewId: String? = null
    ): Boolean {
        val hay = haystack(text, desc, cls, viewId)
        if (hay.contains("youtube video player")) return true
        if (isPlayerContainerId(viewId)) return true
        if (isPlayLabel(text) || isPlayLabel(desc)) return true
        if (isPauseLabel(text) || isPauseLabel(desc)) return true
        val clsLower = (cls ?: "").lowercase(Locale.ROOT)
        if (clsLower.contains("surfaceview") || clsLower.contains("videoview")) return true
        return false
    }

    /**
     * True for YouTube's OWN player containers, recognised by the resource id
     * rather than a label.
     *
     * This is the rule that was missing. Chrome exposes the web player as a
     * plain `android.view.View` whose only identity is its DOM id — `player`,
     * `movie_player`, `player-container-id`, `player-shorts-container` — and
     * those ids are exactly what the current tree carries. The label the old
     * rule waited for ("YouTube video player") is not in it at all, and neither
     * is a SurfaceView, so the player lookup returned nothing and the block
     * silently did nothing while the Short kept playing.
     *
     * Matched as whole, lowercased id parts so `movie_player` counts and an
     * unrelated `player_playback_settings` does not. The forbidden-action guard
     * still runs first, so a toolbar button can never win this match.
     */
    fun isPlayerContainerId(viewId: String?): Boolean {
        val id = viewId?.substringAfterLast('/')?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (id.isEmpty()) return false
        return id in PLAYER_CONTAINER_IDS
    }

    /** True for labels like "Pause" / "Pause video" (case-insensitive). */
    fun isPauseLabel(s: String?): Boolean {
        val lower = s?.trim()?.lowercase(Locale.ROOT) ?: return false
        return lower == "pause" || lower == "pause video"
    }

    /** True for labels like "Play" / "Play video" (case-insensitive). */
    fun isPlayLabel(s: String?): Boolean {
        val lower = s?.trim()?.lowercase(Locale.ROOT) ?: return false
        return lower == "play" || lower == "play video"
    }

    private fun haystack(vararg parts: String?): String =
        parts.joinToString(" ") { it ?: "" }.lowercase(Locale.ROOT)

    /**
     * Substrings that disqualify a node outright.
     *
     * "share" is the one that matters — it is what opened the share sheet — but
     * the rest of the toolbar is here for the same reason: none of them is the
     * player, and each would be an equally wrong target if the tree shifted
     * again. Matched on the substring rather than the whole label, so
     * "Share this video", "share" and "Share Short" are all caught.
     */
    /**
     * YouTube's player containers, by their DOM id. `movie_player` is the
     * actual `<video>` wrapper; the rest are the shells the Shorts page nests it
     * in, any of which is a correct thing to cover.
     */
    private val PLAYER_CONTAINER_IDS = setOf(
        "player",
        "movie_player",
        "player-container-id",
        "player-shorts-container",
        "player-cinematics-container"
    )

    private val FORBIDDEN_ANYWHERE = listOf(
        "share",
        "more actions",
        "subscribe",
        "save",
        "download",
        "report",
        "remix",
        "dislike",
        "comment",
        "clip"
    )
}
