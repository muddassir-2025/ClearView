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

    /**
     * True for YouTube's "Not interested" item, in any of its spellings.
     *
     * This is the action a user takes on a video or a Short they do not want,
     * and ClearView treats it as an intent to block the channel behind it. The
     * label is matched on the substring because YouTube renders it as "Not
     * interested", "Not Interested" and occasionally with trailing guidance in
     * the same node.
     */
    fun isNotInterestedLabel(s: String?): Boolean {
        val lower = s?.trim()?.lowercase(Locale.ROOT) ?: return false
        return lower.contains("not interested")
    }

    /**
     * True for YouTube's POST-ACTION confirmation, e.g. "You'll see fewer
     * videos like this".
     *
     * This is the reliable signal that the user actually TOOK the "Not
     * interested" / "Don't recommend" action. It matters because, in Chrome's
     * web content, (a) the menu merely being OPEN is not an action, and (b) the
     * menu item's click event carries no readable label (`text`/`content-desc`
     * are empty on the clicked node). The confirmation toast, by contrast, only
     * appears AFTER a real tap, and the channel handle is still on the page at
     * that moment. Observed live on m.youtube.com/shorts in Chrome.
     */
    fun isNotInterestedConfirmation(s: String?): Boolean {
        val lower = s?.trim()?.lowercase(Locale.ROOT) ?: return false
        return lower.contains("fewer videos like this") ||
            lower.contains("fewer videos from this channel") ||
            lower.contains("you'll see fewer")
    }

    /**
     * True for YouTube's "Don't recommend this video" menu item.
     *
     * This is the item that OPENS the submenu; the channel action lives inside
     * it. Recognised by the substring because YouTube spells it "Don't
     * recommend", "Dont recommend" and "Do not recommend" across builds, and
     * because the label carries a typographic apostrophe on some of them.
     */
    fun isDontRecommendLabel(s: String?): Boolean {
        val lower = s?.trim()?.lowercase(Locale.ROOT) ?: return false
        return lower.contains("recommend") &&
            (lower.contains("don't") || lower.contains("dont") || lower.contains("do not"))
    }

    /**
     * True for YouTube's "Don't recommend this channel" menu item — the
     * narrower one inside the submenu, which is the action that names a
     * channel and is therefore the one ClearView can act on.
     */
    fun isDontRecommendChannelLabel(s: String?): Boolean {
        val lower = s?.trim()?.lowercase(Locale.ROOT) ?: return false
        return isDontRecommendLabel(lower) && lower.contains("channel")
    }

    /**
     * The first channel @handle found in a set of labels, or null.
     *
     * Used to identify WHICH channel a surface is showing (Shorts, watch pages,
     * the "Not interested" flow) so a blocked channel can be enforced even when
     * no keyword matched. Preference order, most to least ambiguous:
     *  1. an explicit "Go to channel @handle" description;
     *  2. a label that is EXACTLY one @handle;
     *  3. a @handle inside a short label ("Subscribe to @name").
     * Long labels are ignored so a handle mentioned in a description or comment
     * cannot be mistaken for the channel being watched.
     */
    fun channelHandleFrom(labels: List<String>): String? {
        for (t in labels) {
            if (t.trim().lowercase(Locale.ROOT).startsWith("go to channel @")) {
                return ("@" + t.trim().substringAfter('@')).trimEnd('.', '_', '-')
            }
        }
        for (t in labels) {
            val trimmed = t.trim()
            if (trimmed.length <= MAX_HANDLE_LABEL_LEN && STANDALONE_HANDLE_REGEX.matches(trimmed)) {
                return trimmed
            }
        }
        for (t in labels) {
            if (t.length > MAX_HANDLE_LABEL_LEN) continue
            val m = CHANNEL_HANDLE_REGEX.find(t) ?: continue
            // YouTube writes "Subscribe to @name." — the handle character class
            // includes '.', so a trailing sentence period is matched too. Strip
            // trailing punctuation that is never the end of a real handle.
            return m.value.trimEnd('.', '_', '-')
        }
        return null
    }

    private const val MAX_HANDLE_LABEL_LEN = 60
    private val STANDALONE_HANDLE_REGEX = Regex("^@[A-Za-z0-9._-]{2,100}$")
    private val CHANNEL_HANDLE_REGEX =
        Regex("@(?=[A-Za-z0-9._-]*[A-Za-z0-9])[A-Za-z0-9._-]{2,100}")

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
