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
import com.muddassir.clearview.brainrot.BrainRotRepository
import com.muddassir.clearview.brainrot.GlobalRulesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the native YouTube app for the moment a user taps **"Don't recommend
 * this channel"** and offers to add that channel to ClearView's blocked list.
 *
 * ## Why this exists
 *
 * The spec's insight is that this is the one moment when a user is already
 * telling YouTube they do not want a channel. The app can either forget that
 * decision, or keep it: ClearView's whole purpose is that a decision about what
 * you do not want to see should survive the app you made it in. So the tap is
 * treated as an intent to block, and the user is asked once — never forced.
 *
 * ## Scope and safety
 *
 *  * **The YouTube app only.** Chrome is handled by
 *    [LongVideoBlockCoordinator] and [YouTubeChromeTestCoordinator]; this
 *    coordinator never touches a Chrome tree.
 *  * **Never blocks on its own.** It only OFFERS. A block is written solely
 *    when the user confirms the toast's action, and the writing goes through
 *    [BrainRotRepository.addBlockedChannel] — the same list the Blocking tab
 *    manages — plus an optional global suggestion.
 *  * **Cheap.** The event handler does a bounded tree walk for one known label;
 *    a channel already in the list is a no-op and is not offered again.
 *
 * Log tag: `ClearViewYouTubeApp`.
 */
class YouTubeAppBlockCoordinator(
    private val service: AccessibilityService,
    private val brainRotRepository: BrainRotRepository,
    private val globalRulesStore: GlobalRulesStore
) {

    companion object {
        private const val TAG = "ClearViewYouTubeApp"

        /** The native app, and its music sibling, which shares the UI. */
        private val YOUTUBE_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music"
        )

        /** A label scan touches at most this many nodes. */
        private const val NODE_BUDGET = 800
        private const val MAX_DEPTH = 60
        private const val MAX_HANDLE_TEXT_LEN = 60

        /** Throttle between label scans — the menu opens once, but trees storm. */
        private const val SCAN_MIN_INTERVAL_MS = 400L

        /** Don't offer twice for the same channel within this window. */
        private const val OFFER_COOLDOWN_MS = 60_000L

        private val STANDALONE_HANDLE_REGEX = Regex("^@[A-Za-z0-9._-]{2,100}$")
        private val CHANNEL_HANDLE_REGEX = Regex("@(?=[A-Za-z0-9._-]*[A-Za-z0-9])[A-Za-z0-9._-]{2,100}")
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lastScanAt = 0L
    private var lastOfferedHandle: String? = null
    private var lastOfferedAt = 0L

    /** Called from the service for every accessibility event. Cheap. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in YOUTUBE_PACKAGES) return
        // Only a tap opens the menu, and only a window transition renders it.
        // Deliberately NOT TYPE_WINDOW_CONTENT_CHANGED: the YouTube feed storms
        // that event, and a tree walk per storm would jank the app the user is
        // looking at. The two events below are the ones the menu actually
        // produces, so nothing is missed by leaving the storm out.
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_MIN_INTERVAL_MS) return
        lastScanAt = now
        scan()
    }

    /** Called from the service on destroy / interrupt. */
    fun stop() {
        scope.cancel()
        lastOfferedHandle = null
        lastOfferedAt = 0L
        Log.i(TAG, "STOPPED")
    }

    private fun scan() {
        val root = try { service.rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            val rootPkg = try { root.packageName?.toString() } catch (e: Exception) { null }
            if (rootPkg != null && rootPkg !in YOUTUBE_PACKAGES) return
            val action = findDontRecommendChannel(root) ?: return
            Log.i(TAG, "YOUTUBE_APP_DONT_RECOMMEND_FOUND text=${action.text} desc=${action.contentDescription}")
            action.recycle()
            val handle = findChannelHandle(root)
            if (handle.isNullOrBlank()) {
                // The action was taken, but the tree does not expose the handle
                // (the submenu closed before the scan, or the channel is shown
                // only by name). Nothing is offered: an action that cannot name
                // what it would block is not an action, and a toast with no
                // usable button is worse than silence.
                Log.i(TAG, "YOUTUBE_APP_DONT_RECOMMEND_NO_HANDLE — nothing to offer")
                return
            }
            offer(handle)
        } catch (e: Exception) {
            Log.e(TAG, "scan error: ${e.message}")
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    /**
     * Find the "Don't recommend this channel" item. Bounded BFS, visible nodes
     * only, so a hidden/offscreen menu is never matched.
     */
    private fun findDontRecommendChannel(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        var found: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (visible) {
                val text = try { node.text?.toString() } catch (e: Exception) { null }
                val desc = try { node.contentDescription?.toString() } catch (e: Exception) { null }
                if (YouTubeNodeRules.isDontRecommendChannelLabel(text) ||
                    YouTubeNodeRules.isDontRecommendChannelLabel(desc)
                ) {
                    found = AccessibilityNodeInfo.obtain(node)
                }
            }
            if (found == null) {
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

    /** Extract the channel @handle the action refers to, if the tree shows one. */
    private fun findChannelHandle(root: AccessibilityNodeInfo): String? {
        val texts = ArrayList<String>(16)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < NODE_BUDGET) {
            visited++
            val node = queue.removeFirst()
            val isRoot = node === root
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            if (visible) {
                try { node.text?.toString()?.let { texts.add(it) } } catch (e: Exception) {}
                try { node.contentDescription?.toString()?.let { texts.add(it) } } catch (e: Exception) {}
            }
            val count = try { node.childCount } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                queue.add(child)
            }
            if (!isRoot) try { node.recycle() } catch (e: Exception) {}
            depth++
        }
        // Prefer a standalone handle (the strongest signal), then a short text.
        for (t in texts) {
            val trimmed = t.trim()
            if (trimmed.length <= MAX_HANDLE_TEXT_LEN && STANDALONE_HANDLE_REGEX.matches(trimmed)) {
                return trimmed
            }
        }
        for (t in texts) {
            if (t.length > MAX_HANDLE_TEXT_LEN) continue
            val m = CHANNEL_HANDLE_REGEX.find(t) ?: continue
            return m.value
        }
        return null
    }

    /**
     * Ask the user, once, whether to keep the decision ClearView just observed.
     *
     * A toast rather than an overlay: this is not a block, it is an offer, and
     * an overlay over the YouTube app would be a block in all but name. The
     * toast carries its own action, so a block is written only when the user
     * taps it. It auto-dismisses on its own if they do nothing — the decision
     * they made in YouTube is not overwritten by ClearView assuming anything.
     */
    private fun offer(handle: String) {
        val normalized = BrainRotRepository.normalizeHandle(handle) ?: return
        val now = System.currentTimeMillis()
        if (normalized == lastOfferedHandle && now - lastOfferedAt < OFFER_COOLDOWN_MS) {
            Log.i(TAG, "YOUTUBE_APP_OFFER_SUPPRESSED handle=$normalized (recent)")
            return
        }
        // Already blocked: nothing to offer, and saying nothing is correct —
        // ClearView is already doing what the user is asking for.
        if (brainRotRepository.isChannelBlocked(normalized)) {
            Log.i(TAG, "YOUTUBE_APP_ALREADY_BLOCKED handle=$normalized")
            return
        }
        lastOfferedHandle = normalized
        lastOfferedAt = now

        val message = service.getString(R.string.brainrot_offer_block_channel, normalized)

        val toast = Toast(service)
        toast.duration = Toast.LENGTH_LONG
        toast.view = buildOfferView(message, normalized) { toast.cancel() }
        toast.show()
        Log.i(TAG, "YOUTUBE_APP_OFFER handle=$normalized")
    }

    /**
     * The offer toast's view, built in code so the feature adds no layout
     * resource. A "Block" action is present only when the tree named a
     * channel — an action that cannot name what it would block is not offered.
     */
    private fun buildOfferView(message: String, normalized: String, dismiss: () -> Unit): LinearLayout {
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
            text = service.getString(R.string.brainrot_offer_block_action)
            setTextColor(Color.parseColor("#FF8AB4F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            background = null
            setOnClickListener {
                confirmBlock(normalized)
                dismiss()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return root
    }

    /**
     * The user confirmed. Write the channel to the local list (the source of
     * truth for enforcement) and, best-effort, suggest it to the global
     * repository so the decision can help everyone.
     */
    private fun confirmBlock(handle: String) {
        val normalized = BrainRotRepository.normalizeHandle(handle) ?: return
        val added = brainRotRepository.addBlockedChannel(normalized, reason = "don't recommend")
        if (added) {
            Log.i(TAG, "YOUTUBE_APP_CHANNEL_BLOCKED handle=$normalized")
            Toast.makeText(
                service,
                service.getString(R.string.brainrot_channel_blocked, normalized),
                Toast.LENGTH_SHORT
            ).show()
        }
        scope.launch {
            if (!isActive) return@launch
            runCatching { globalRulesStore.suggestChannel(normalized) }
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}
