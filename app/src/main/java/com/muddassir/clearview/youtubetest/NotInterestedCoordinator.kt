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
 * YouTube, and the tree walk is bounded. It is triggered by a click and a window
 * transition only — never by the content-changed storm, which would walk the
 * tree on every frame of a scrolling feed.
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

        private const val NODE_BUDGET = 900
        private const val MAX_DEPTH = 70
        private const val MAX_HANDLE_TEXT_LEN = 60

        /** Throttle between label scans. */
        private const val SCAN_MIN_INTERVAL_MS = 400L

        /** Do not offer the same channel twice within this window. */
        private const val OFFER_COOLDOWN_MS = 60_000L

        private val STANDALONE_HANDLE_REGEX = Regex("^@[A-Za-z0-9._-]{2,100}$")
        private val CHANNEL_HANDLE_REGEX = Regex("@(?=[A-Za-z0-9._-]*[A-Za-z0-9])[A-Za-z0-9._-]{2,100}")
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lastScanAt = 0L
    private var lastOfferedHandle: String? = null
    private var lastOfferedAt = 0L
    private var lastHandledAt = 0L

    /** Called from the service for every accessibility event. Cheap. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in WATCHED_PACKAGES) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_MIN_INTERVAL_MS) return
        lastScanAt = now
        scan(packageName)
    }

    /** Called from the service on destroy / interrupt. */
    fun stop() {
        scope.cancel()
        lastOfferedHandle = null
        lastOfferedAt = 0L
        Log.i(TAG, "STOPPED")
    }

    private fun scan(packageName: String) {
        val root = try { service.rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            val rootPkg = try { root.packageName?.toString() } catch (e: Exception) { null }
            if (rootPkg != null && rootPkg !in WATCHED_PACKAGES) return
            if (!hasNotInterestedAction(root)) return
            Log.i(TAG, "NOT_INTERESTED_FOUND package=$packageName")
            val handle = findChannelHandle(root)
            if (handle.isNullOrBlank()) {
                // The action was taken but the tree does not name a channel. We
                // cannot block what we cannot name, so nothing is offered rather
                // than a toast whose button would do nothing.
                Log.i(TAG, "NOT_INTERESTED_NO_HANDLE — nothing to block")
                return
            }
            handleNotInterested(handle)
        } catch (e: Exception) {
            Log.e(TAG, "scan error: ${e.message}")
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    /** True when a visible "Not interested" / "Don't recommend" item is present. */
    private fun hasNotInterestedAction(root: AccessibilityNodeInfo): Boolean {
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
