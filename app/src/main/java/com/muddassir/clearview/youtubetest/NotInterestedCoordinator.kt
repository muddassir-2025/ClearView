package com.muddassir.clearview.youtubetest

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.muddassir.clearview.R
import com.muddassir.clearview.brainrot.BlockAction
import com.muddassir.clearview.brainrot.BlockedItemMeta
import com.muddassir.clearview.brainrot.BrainRotRepository
import com.muddassir.clearview.brainrot.GlobalRulesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Turns YouTube's "Not interested" into a block ClearView keeps.
 *
 * ## The idea
 *
 * When somebody taps three dots → "Not interested" (or "Don't recommend this
 * channel"), they have just made a decision about what they do not want to see.
 * YouTube forgets it for that one video. ClearView's whole purpose is that a
 * decision like that should survive the app it was made in, so this coordinator
 * treats the tap as intent and acts on it — immediately, locally, with no
 * administrator in the loop.
 *
 * ## What it does, in order
 *
 *  1. Sees the action in the accessibility tree (Chrome's YouTube pages and the
 *     native YouTube app alike).
 *  2. Works out WHICH channel it was, from the handle on screen.
 *  3. Blocks that channel for this user **right now** — through [BlockAction],
 *     so the reason and the source are recorded with it — and notifies them.
 *  4. Offers, once, to submit the same channel to the global repository, where
 *     an administrator can decide whether everybody should be protected from it.
 *
 * ## Two things it deliberately does NOT do
 *
 *  * **It never blocks a keyword automatically.** The channel is a precise,
 *    reversible identity that "Not interested" unambiguously refers to. A word
 *    scraped from a title is neither: auto-blocking the tokens of one video's
 *    title would block every future video containing those words, which is a
 *    much larger decision than the user made. The keyword path exists and is
 *    offered, but a person has to choose it.
 *  * **It never blocks silently.** Every block it makes produces a notification
 *    in the centre, so the user can see what ClearView did on their behalf and
 *    undo it from the same list.
 *
 * ## Cost
 *
 * The handler returns immediately for any package that is neither Chrome nor
 * YouTube, and the tree walk is bounded. It watches clicks and window/content
 * transitions — the last because YouTube renders the bottom sheet and its
 * confirmation inside the SAME window — but throttles the scan so the per-frame
 * content-changed storm of a scrolling feed never walks the tree continuously.
 *
 * The action is taken on YouTube's POST-ACTION confirmation ("You'll see fewer
 * videos like this"), never on the menu merely being open: an open menu is not a
 * decision, and Chrome exposes no readable label on the clicked menu node.
 *
 * Log tag: `ClearViewNotInterested`.
 */
class NotInterestedCoordinator(
    private val service: AccessibilityService,
    private val blockAction: BlockAction,
    private val brainRotRepository: BrainRotRepository,
    private val globalRulesStore: GlobalRulesStore
) {

    companion object {
        private const val TAG = "ClearViewNotInterested"
        private const val CHROME_PACKAGE = "com.android.chrome"

        private val WATCHED_PACKAGES = setOf(
            CHROME_PACKAGE,
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music"
        )

        // Deep enough to reach the channel row on a Shorts page: the row sits
        // below the player and the whole surrounding chrome, and a shallower
        // walk (70) missed it — observed live.
        private const val NODE_BUDGET = 1500
        private const val MAX_DEPTH = 150

        /** Throttle between label scans. */
        private const val SCAN_MIN_INTERVAL_MS = 400L

        /** Do not offer the same channel twice within this window. */
        private const val OFFER_COOLDOWN_MS = 60_000L

        /**
         * How long the confirmation snackbar is allowed to drive a retry. The
         * snackbar lingers for many seconds after the tap; acting on it for that
         * whole time would risk blocking whatever channel is on screen NEXT (the
         * user has usually swiped on by then). The handle is on the page
         * immediately, so a short window is plenty.
         */
        private const val CONFIRMATION_WINDOW_MS = 1_500L

        /**
         * How long a channel captured from an open menu stays usable for the
         * confirmation that follows it. Long enough for a slow reader to pick a
         * reason in the "Tell us why" submenu, short enough that a stale menu
         * from minutes ago cannot mis-attribute an unrelated action.
         */
        private const val PENDING_HANDLE_TTL_MS = 30_000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lastScanAt = 0L
    private var lastOfferedHandle: String? = null
    private var lastOfferedAt = 0L
    private var lastHandledAt = 0L
    /** When the current confirmation snackbar was first seen. */
    private var confirmationFirstSeenAt = 0L
    /** True once this confirmation has produced (at most) its one action. */
    private var confirmationHandled = false
    /** Channel captured from the open "Not interested" menu, pending confirmation. */
    private var pendingActionHandle: String? = null
    private var pendingActionAt = 0L

    /**
     * Called from the service for every accessibility event. Cheap: the first
     * check rejects every package that is neither Chrome nor YouTube.
     *
     * Two triggers, deliberately different:
     *
     *  * **A CLICK** is the authoritative signal. The clicked node carries the
     *    "Not interested"/"Don't recommend channel" label itself, so we read it
     *    straight off [AccessibilityEvent.source] — even when the popup menu is
     *    its own window and has already vanished from `rootInActiveWindow` by
     *    the time we look. Relying on a tree scan here was the bug: the tap
     *    registered (YouTube showed "You'll see fewer videos like this") but no
     *    label was left to find.
     *  * **A WINDOW transition** keeps the old tree scan as a backstop for a
     *    menu that is left open, throttled so the content-changed storm of a
     *    scrolling feed can never walk the tree per frame.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in WATCHED_PACKAGES) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (clickIsNotInterested(event)) {
                    Log.i(TAG, "NOT_INTERESTED_CLICK package=$packageName")
                    handleAction(packageName)
                }
            }
            // Content changes are watched because YouTube renders the
            // bottom sheet AND its confirmation inside the SAME window — no
            // window-state event fires for either. Throttled, so the
            // per-frame storm of a scrolling feed cannot walk the tree
            // continuously; the tree walk is bounded as well.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastScanAt < SCAN_MIN_INTERVAL_MS) return
                lastScanAt = now
                scan(packageName)
            }
            else -> return
        }
    }

    /**
     * True when the node that was just CLICKED is YouTube's "Not interested" or
     * "Don't recommend this channel" item. The label is read off the clicked
     * node before the event is recycled — this is the signal that survives the
     * popup closing.
     */
    private fun clickIsNotInterested(event: AccessibilityEvent): Boolean {
        val src = try { event.source } catch (e: Exception) { null } ?: return false
        return try {
            val text = try { src.text?.toString() } catch (e: Exception) { null }
            val desc = try { src.contentDescription?.toString() } catch (e: Exception) { null }
            if (YouTubeNodeRules.isNotInterestedLabel(text) ||
                YouTubeNodeRules.isNotInterestedLabel(desc) ||
                YouTubeNodeRules.isDontRecommendChannelLabel(text) ||
                YouTubeNodeRules.isDontRecommendChannelLabel(desc)
            ) {
                true
            } else {
                Log.d(TAG, "CLICK_OTHER text=${text?.take(60)} desc=${desc?.take(60)}")
                false
            }
        } finally {
            try { src.recycle() } catch (e: Exception) {}
        }
    }

    /**
     * The click was a real "Not interested". Find the channel and act.
     *
     * The handle is NOT read from the clicked node (a menu item never names the
     * channel) — it is read from the Chrome page behind the menu, which is a
     * different window while the popup is up. Hence the cross-window search.
     */
    private fun handleAction(packageName: String) {
        // Prefer the channel captured when the menu was open: the native YouTube
        // app can advance to the next video on the same tap, and the capture is
        // the channel the action was actually about.
        val handle = pendingActionHandle
            ?.takeIf { System.currentTimeMillis() - pendingActionAt <= PENDING_HANDLE_TTL_MS }
            ?: findChannelHandleAcrossWindows()
        if (handle.isNullOrBlank()) {
            Log.i(TAG, "NOT_INTERESTED_NO_HANDLE — nothing to block")
            return
        }
        Log.i(TAG, "NOT_INTERESTED_FOUND package=$packageName handle=$handle")
        handleNotInterested(handle)
    }

    /**
     * Search every window for a channel @handle, preferring the Chrome page (the
     * popup that owns the clicked item never carries one).
     */
    private fun findChannelHandleAcrossWindows(): String? {
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        try {
            for (window in service.windows) {
                val root = try { window.root } catch (e: Exception) { null } ?: continue
                roots.add(root)
            }
        } catch (e: Exception) {
            // `service.windows` can throw when the service is tearing down.
        }
        // Chrome first (the page is where the handle lives), then anything else.
        val ordered = roots.sortedByDescending { r ->
            try { r.packageName?.toString() == CHROME_PACKAGE } catch (e: Exception) { false }
        }
        try {
            for (root in ordered) {
                val handle = try { findChannelHandle(root) } catch (e: Exception) { null }
                if (!handle.isNullOrBlank()) return handle
            }
        } finally {
            for (root in roots) {
                try { root.recycle() } catch (e: Exception) {}
            }
        }
        return null
    }

    /** Called from the service on destroy / interrupt. */
    fun stop() {
        scope.cancel()
        lastOfferedHandle = null
        lastOfferedAt = 0L
        lastHandledAt = 0L
        confirmationFirstSeenAt = 0L
        confirmationHandled = false
        pendingActionHandle = null
        pendingActionAt = 0L
        Log.i(TAG, "STOPPED")
    }

    private fun scan(packageName: String) {
        val root = try { service.rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            val rootPkg = try { root.packageName?.toString() } catch (e: Exception) { null }
            if (rootPkg != null && rootPkg !in WATCHED_PACKAGES) return

            val menuOpen = scanForActionMenu(root)
            val confirmed = scanForConfirmation(root)
            val now = System.currentTimeMillis()

            // No confirmation on screen: re-arm for the NEXT one, FIRST and
            // unconditionally. This used to happen only in the not-menu branch,
            // so re-opening the menu before it reset left `confirmationHandled`
            // stuck true and silently dropped the next "Not interested" — the
            // intermittency. Re-arming here covers every path.
            if (!confirmed) {
                confirmationFirstSeenAt = 0L
                confirmationHandled = false
                // The captured handle is deliberately NOT cleared here: between
                // picking a menu option and the confirmation appearing there is a
                // gap where nothing is on screen, and clearing would lose the
                // channel and fall back to whatever Short is on screen next.
            }

            // The menu names the video the ACTION is about. Capture its channel
            // NOW, because choosing an option often ADVANCES to the next Short
            // before the confirmation appears — and acting on whatever channel
            // is on screen then blocks the WRONG one. An open menu is still not
            // acted on; it only records which channel the coming action means.
            if (menuOpen && !confirmed) {
                val h = findChannelHandle(root) ?: findChannelHandleAcrossWindows()
                if (!h.isNullOrBlank()) {
                    pendingActionHandle = h
                    pendingActionAt = now
                    Log.i(TAG, "NOT_INTERESTED_MENU_OPEN handle=$h")
                }
                return
            }

            if (!confirmed) return

            if (confirmationHandled) return
            if (confirmationFirstSeenAt == 0L) {
                confirmationFirstSeenAt = now
                Log.i(TAG, "NOT_INTERESTED_CONFIRMED package=$packageName")
            }
            // One tap, one block: only retry within a short window, so a
            // lingering snackbar can never act on the NEXT channel the user
            // swipes to.
            if (now - confirmationFirstSeenAt > CONFIRMATION_WINDOW_MS) {
                confirmationHandled = true
                return
            }
            // Prefer the channel captured when the menu was open — that is the
            // one the action was actually about — and only fall back to the
            // current tree when nothing was captured.
            val handle = pendingActionHandle
                ?.takeIf { now - pendingActionAt <= PENDING_HANDLE_TTL_MS }
                ?: findChannelHandle(root)
                ?: findChannelHandleAcrossWindows()
            if (handle.isNullOrBlank()) {
                // The action was taken but no window names a channel yet. We
                // cannot block what we cannot name; retry within the window in
                // case the handle renders a moment later. Log what WAS visible
                // so a miss is diagnosable from logcat rather than guessed at.
                Log.i(TAG, "NOT_INTERESTED_NO_HANDLE — visible=${describeVisible(root)}")
                return
            }
            confirmationHandled = true
            pendingActionHandle = null
            handleNotInterested(handle)
        } catch (e: Exception) {
            Log.e(TAG, "scan error: ${e.message}")
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    /** A short, bounded description of visible labels, for diagnosis only. */
    private fun describeVisible(root: AccessibilityNodeInfo): String {
        val out = ArrayList<String>(16)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400 && out.size < 50) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (visible) {
                val text = try { node.text?.toString() } catch (e: Exception) { null }
                val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
                text?.take(40)?.let { out.add(it) }
                desc?.take(40)?.let { out.add(it) }
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
        }
        return out.joinToString(" | ")
    }

    /**
     * True when YouTube's "Not interested" / "Don't recommend channel" MENU is
     * open. Used only to RECORD which channel the coming action is about — never
     * to act by itself.
     */
    private fun scanForActionMenu(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        var found = false
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET && !found) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (visible) {
                val text = try { node.text?.toString() } catch (e: Exception) { null }
                val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
                if (YouTubeNodeRules.isNotInterestedLabel(text) ||
                    YouTubeNodeRules.isNotInterestedLabel(desc) ||
                    YouTubeNodeRules.isDontRecommendChannelLabel(text) ||
                    YouTubeNodeRules.isDontRecommendChannelLabel(desc)
                ) {
                    found = true
                }
            }
            if (!found) {
                val count = try { node.childCount } catch (e: Exception) { 0 }
                for (i in 0 until count) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    queue.add(child)
                }
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        return found
    }

    /** True when a visible post-action confirmation is present. */
    private fun scanForConfirmation(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        var found = false
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET && !found) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (visible) {
                val text = try { node.text?.toString() } catch (e: Exception) { null }
                val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
                if (YouTubeNodeRules.isNotInterestedConfirmation(text) ||
                    YouTubeNodeRules.isNotInterestedConfirmation(desc)
                ) {
                    found = true
                }
            }
            if (!found) {
                val count = try { node.childCount } catch (e: Exception) { 0 }
                for (i in 0 until count) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    queue.add(child)
                }
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        return found
    }

    /**
     * Extract the channel @handle the action refers to, if the tree shows one.
     *
     * VISIBLE nodes are preferred, but a non-visible fallback is kept: when the
     * confirmation snackbar is up it overlays the bottom of the page, and the
     * channel row underneath can report `isVisibleToUser == false` even though
     * its handle is still in the tree. A miss here means the block silently does
     * not happen, which is worse than an occasional broad read of a bounded tree
     * that is already not allowed to match anything but a genuine @handle.
     */
    private fun findChannelHandle(root: AccessibilityNodeInfo): String? {
        val visible = ArrayList<String>(24)
        val all = ArrayList<String>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val isVisible = try { node.isVisibleToUser } catch (e: Exception) { false }
            val text = try { node.text?.toString() } catch (e: Exception) { null }
            val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
            if (text != null) {
                all.add(text)
                if (isVisible) visible.add(text)
            }
            if (desc != null) {
                all.add(desc)
                if (isVisible) visible.add(desc)
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        return YouTubeNodeRules.channelHandleFrom(visible)
            ?: YouTubeNodeRules.channelHandleFrom(all)
    }

    /**
     * Block the channel now, tell the user, and offer to make it global.
     *
     * The personal block happens FIRST and unconditionally. The global offer is a
     * separate, optional step, and nothing about it can prevent or delay the
     * block the user already asked for.
     */
    private fun handleNotInterested(rawHandle: String) {
        val normalized = BrainRotRepository.normalizeHandle(rawHandle) ?: return
        val now = System.currentTimeMillis()
        // One action, one block: the tree can carry the label for a few hundred
        // milliseconds across several events.
        if (now - lastHandledAt < OFFER_COOLDOWN_MS && normalized == lastOfferedHandle) {
            Log.i(TAG, "NOT_INTERESTED_SUPPRESSED handle=$normalized (recent)")
            return
        }
        lastHandledAt = now
        lastOfferedHandle = normalized

        val alreadyBlocked = brainRotRepository.isChannelBlocked(normalized)
        if (!alreadyBlocked) {
            val reason = "Blocked from YouTube \"Not interested\" action"
            blockAction.blockChannel(
                handle = normalized,
                reason = reason,
                source = BlockedItemMeta.Source.YOUTUBE_NOT_INTERESTED,
                notificationMessage = "Blocked $normalized — you chose \"Not interested\" in YouTube.",
                notificationId = "ni:$normalized"
            )
            Log.i(TAG, "NOT_INTERESTED_BLOCKED handle=$normalized")
        } else {
            Log.i(TAG, "NOT_INTERESTED_ALREADY_BLOCKED handle=$normalized")
        }

        offerGlobal(normalized)
    }

    /**
     * Offer, once, to submit the channel to the global repository.
     *
     * A toast with its own action rather than an overlay: this is an offer, not a
     * block, and it auto-dismisses if the user does nothing. The block they asked
     * for has already happened either way.
     */
    private fun offerGlobal(normalized: String) {
        // No separate cooldown: [handleNotInterested] has already established
        // that this is a new action for this channel, so reaching here once is
        // what "once" means.
        lastOfferedAt = System.currentTimeMillis()

        val message = service.getString(R.string.brainrot_not_interested_blocked, normalized)
        val toast = Toast(service)
        toast.duration = Toast.LENGTH_LONG
        toast.view = buildOfferView(message, normalized) { toast.cancel() }
        toast.show()
        Log.i(TAG, "NOT_INTERESTED_OFFER handle=$normalized")
    }

    /**
     * The toast's view: the confirmation, and a button to submit the same
     * channel globally. Built in code so the feature adds no layout resource.
     */
    private fun buildOfferView(
        message: String,
        normalized: String,
        dismiss: () -> Unit
    ): LinearLayout {
        val pad = dp(16)
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(12), dp(8), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#EE1F1F1F"))
            }
        }
        root.addView(TextView(service).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(Button(service).apply {
            text = service.getString(R.string.brainrot_submit_global_action)
            setTextColor(Color.parseColor("#FF8AB4F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            background = null
            setOnClickListener {
                submitGlobal(normalized)
                dismiss()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return root
    }

    private fun submitGlobal(normalized: String) {
        scope.launch {
            if (!isActive) return@launch
            val ok = runCatching {
                globalRulesStore.suggestChannel(
                    normalized,
                    name = null,
                    source = "youtube_not_interested"
                )
            }.getOrDefault(false)
            Log.i(TAG, "NOT_INTERESTED_GLOBAL_SUBMIT handle=$normalized ok=$ok")
            Toast.makeText(
                service,
                service.getString(
                    if (ok) R.string.brainrot_submit_global_queued
                    else R.string.block_global_suggest_failed
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}
