package com.muddassir.clearview.viewmodel

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.muddassir.clearview.brainrot.BlockAction
import com.muddassir.clearview.brainrot.BlockedItemMeta
import com.muddassir.clearview.brainrot.BrainRotClient
import com.muddassir.clearview.brainrot.BrainRotRefreshBus
import com.muddassir.clearview.brainrot.BrainRotRepository
import com.muddassir.clearview.brainrot.BrainRotStats
import com.muddassir.clearview.brainrot.BrainRotSummary
import com.muddassir.clearview.brainrot.GlobalRulesStore
import com.muddassir.clearview.brainrot.NotificationStore
import com.muddassir.clearview.repository.BlockRepository
import com.muddassir.clearview.youtubetest.YoutubeTestKeywordRepository
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    // ── UI State ───────────────────────────────────────────────────

    var isAccessibilityEnabled by mutableStateOf(false)
        private set

    var isDeviceAdminEnabled by mutableStateOf(false)
        private set

    var newKeywordText by mutableStateOf("")
        private set

    var newDomainText by mutableStateOf("")
        private set

    val userKeywords = mutableStateListOf<String>()
    val blockedDomains = mutableStateListOf<String>()

    // ── Repo ───────────────────────────────────────────────────────

    private var repository: BlockRepository? = null
    private var youtubeTestKeywordRepository: YoutubeTestKeywordRepository? = null
    private var brainRotRepository: BrainRotRepository? = null
    private var globalRulesStore: GlobalRulesStore? = null
    private var notificationStore: NotificationStore? = null
    private var blockedItemMeta: BlockedItemMeta? = null
    private var blockAction: BlockAction? = null
    private var brainRotRefreshListener: (() -> Unit)? = null
    private var viewModelScopeRef: kotlinx.coroutines.CoroutineScope? = null

    fun initialize(context: Context) {
        if (repository != null) return
        repository = BlockRepository(context.applicationContext)
        youtubeTestKeywordRepository = YoutubeTestKeywordRepository(context.applicationContext)
        brainRotRepository = BrainRotRepository(context.applicationContext)
        globalRulesStore = GlobalRulesStore(context.applicationContext)
        notificationStore = NotificationStore(context.applicationContext)
        blockedItemMeta = BlockedItemMeta(context.applicationContext)
        blockAction = BlockAction(context.applicationContext)
        globalRulesAvailable = globalRulesStore?.let { store ->
            com.muddassir.clearview.brainrot.BrainRotClient(context.applicationContext).isConfigured()
        } ?: false
        refreshBrainRot()
        refreshGlobalRules()
        // A background refresh so the global rules are current shortly after the
        // app opens. Failure is a no-op: the previous snapshot (or the empty
        // first-run state) stays in force, and local protection is unaffected.
        // MAIN, not IO. Every network call already hops to Dispatchers.IO
        // internally (BrainRotClient/GlobalRulesStore), but the completion and
        // the callbacks run on THIS dispatcher: a submit's onResult shows a
        // Toast and mutates Compose state, and doing that from an IO thread
        // crashes (Toast needs a Looper) and corrupts the UI snapshot.
        viewModelScopeRef = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob()
        )
        viewModelScopeRef?.launch {
            runCatching { globalRulesStore?.sync() }
            refreshGlobalRules()
        }
        refreshKeywords()
        refreshDomains()
        checkHasPassword()
        refreshStrictMode()
        refreshBlockShorts()
        refreshYouTubeChromeTest()
        refreshYoutubeTestKeywords()
        refreshNotifications()
        refreshMySubmissions()
        // A block made by the accessibility service (YouTube "Not interested")
        // happens in this process but not through this ViewModel, so the lists
        // are re-read when it says something changed — otherwise a channel
        // blocked from YouTube would not show until the tab was rebuilt.
        brainRotRefreshListener = {
            refreshBrainRot()
            refreshYoutubeTestKeywords()
            refreshKeywords()
            refreshNotifications()
        }
        brainRotRefreshListener?.let { BrainRotRefreshBus.addListener(it) }
        ensureLauncherEnabled(context)   // cleanup stale disabled state first
        checkDeviceAdminStatus(context)  // then apply correct hide/show based on admin status
        // Auto-lock if password is set (app was restarted)
        if (hasPassword) {
            isAppLocked = true
        }
    }

    // ── App Password / Lock ────────────────────────────────────────

    var isAppLocked by mutableStateOf(false)
        private set

    var hasPassword by mutableStateOf(false)
        private set

    /** When set to true, triggers the lock screen in setup mode (no password yet). */
    var appLockTriggered by mutableStateOf(false)

    /** Check whether a password is configured. */
    fun checkHasPassword() {
        val has = repository?.hasPassword() ?: false
        hasPassword = has
        // If no password is set, unlock; otherwise keep current lock state
        if (!has) {
            appLockTriggered = false
        }
    }

    /** Set a new password. */
    fun setAppPassword(password: String) {
        repository?.setPassword(password)
        hasPassword = true
        isAppLocked = true
        appLockTriggered = false
    }

    /** Verify password attempt. Returns true if correct, unlocks if so. */
    fun verifyAppPassword(password: String): Boolean {
        val correct = repository?.verifyPassword(password) ?: false
        if (correct) {
            isAppLocked = false
            appLockTriggered = false
        }
        return correct
    }

    /** Lock the app (hides protected content). */
    fun lockApp() {
        if (hasPassword) {
            isAppLocked = true
        }
        // NOTE: When no password is set, backgrounding the app must NOT trigger
        // the password-setup screen. Otherwise every trip to another app/screen
        // (e.g., Accessibility Settings to toggle protection) would hijack the
        // dashboard with the "Set App Password" lock screen. Setup is triggered
        // only explicitly from the App Lock card's "Set" button.
    }

    /** Clear the password and unlock. */
    fun clearAppPassword() {
        repository?.clearPassword()
        hasPassword = false
        isAppLocked = false
        appLockTriggered = false
    }

    /** Should the lock screen be shown (either locked with password or for setup). */
    fun shouldShowLockScreen(): Boolean {
        return isAppLocked || appLockTriggered
    }

    // ── Strict Mode (broad keyword blocking) ───────────────────────

    var isStrictMode by mutableStateOf(false)
        private set

    fun toggleStrictMode(context: Context) {
        val newValue = !isStrictMode
        repository?.isStrictMode = newValue
        isStrictMode = newValue
        android.util.Log.i("MainViewModel", "Strict Mode ${if (newValue) "enabled" else "disabled"}")
    }

    fun refreshStrictMode() {
        isStrictMode = repository?.isStrictMode ?: false
    }

    // ── Block Shorts (YouTube Shorts) ────────────────────────────────

    var blockShorts by mutableStateOf(false)
        private set

    fun toggleBlockShorts() {
        val newValue = !blockShorts
        repository?.blockShorts = newValue
        blockShorts = newValue
        android.util.Log.i("MainViewModel", "Block Shorts ${if (newValue) "enabled" else "disabled"}")
    }

    fun refreshBlockShorts() {
        blockShorts = repository?.blockShorts ?: false
    }

    // ── YouTube Chrome Test (Stage 1 feasibility experiment) ────────

    var youTubeChromeTest by mutableStateOf(false)
        private set

    fun toggleYouTubeChromeTest() {
        val newValue = !youTubeChromeTest
        repository?.youTubeChromeTest = newValue
        youTubeChromeTest = newValue
        android.util.Log.i("MainViewModel", "YouTube Chrome Test ${if (newValue) "enabled" else "disabled"}")
    }

    fun refreshYouTubeChromeTest() {
        youTubeChromeTest = repository?.youTubeChromeTest ?: false
    }

    // ── YouTube Chrome Test Keywords (separate test-only list) ──────

    var newYoutubeTestKeywordText by mutableStateOf("")
        private set

    val youtubeTestKeywords = mutableStateListOf<String>()

    fun updateNewYoutubeTestKeyword(text: String) {
        newYoutubeTestKeywordText = text
    }

    /**
     * Add a keyword to the YOUTUBE list.
     *
     * Goes through [BlockAction] so the reason and source are recorded with it —
     * every block must be able to explain itself, and a keyword added here is
     * the one a user is most likely to come back and ask "why is this blocked".
     */
    fun addYoutubeTestKeyword() {
        val keyword = newYoutubeTestKeywordText.trim()
        if (keyword.isEmpty()) return
        blockAction?.blockKeyword(
            keyword = keyword,
            scope = BlockAction.Scope.YOUTUBE,
            source = BlockedItemMeta.Source.USER,
            notify = false
        ) ?: youtubeTestKeywordRepository?.addKeyword(keyword)
        newYoutubeTestKeywordText = ""
        refreshYoutubeTestKeywords()
    }

    fun removeYoutubeTestKeyword(keyword: String) {
        youtubeTestKeywordRepository?.removeKeyword(keyword)
        blockedItemMeta?.forget(keyword)
        refreshYoutubeTestKeywords()
    }

    private fun refreshYoutubeTestKeywords() {
        youtubeTestKeywords.clear()
        youtubeTestKeywords.addAll((youtubeTestKeywordRepository?.getKeywords() ?: emptySet()).sorted())
    }

    // ── Brain Rot Protection (dashboard + blocked channels) ─────────
    //
    // The rename the product spec asks for: "YouTube Chrome Test" was an
    // experiment name, and the feature is no longer an experiment — it is the
    // global content-blocking system. The stored flag keeps its old key so an
    // existing install keeps its setting; only the label changes.

    /** The user's own blocked channels (handle-canonical). */
    val brainRotChannels = mutableStateListOf<BrainRotRepository.BlockedChannel>()

    var newChannelHandleText by mutableStateOf("")
        private set

    /** Activity numbers for the dashboard, recomputed from stored events. */
    var brainRotSummary by mutableStateOf(BrainRotSummary())
        private set

    /** Filter text for the channel manager's search box. */
    var channelSearchText by mutableStateOf("")
        private set

    fun updateNewChannelHandle(text: String) {
        newChannelHandleText = text
    }

    fun updateChannelSearch(text: String) {
        channelSearchText = text
    }

    /**
     * Add a channel by handle; returns false when the handle is not valid.
     *
     * Routed through [BlockAction] so the block is recorded with a reason and a
     * source — the channel list is what the long-video blocker enforces, and a
     * block that cannot say why is a block the user cannot correct.
     */
    fun addBrainRotChannel(handle: String = newChannelHandleText, name: String? = null, reason: String? = null): Boolean {
        val added = blockAction?.blockChannel(
            handle = handle,
            name = name,
            reason = reason,
            source = BlockedItemMeta.Source.USER,
            notify = false
        ) ?: (brainRotRepository?.addBlockedChannel(handle, name, reason) ?: false)
        if (added) {
            newChannelHandleText = ""
            refreshBrainRot()
        }
        return added
    }

    fun removeBrainRotChannel(handle: String) {
        brainRotRepository?.removeBlockedChannel(handle)
        blockedItemMeta?.forget(handle)
        refreshBrainRot()
    }

    /** Channels matching the current search text (empty search = all). */
    fun filteredBrainRotChannels(): List<BrainRotRepository.BlockedChannel> {
        val query = channelSearchText.trim().lowercase()
        if (query.isEmpty()) return brainRotChannels
        return brainRotChannels.filter {
            it.handle.contains(query) || (it.name?.lowercase()?.contains(query) == true)
        }
    }

    /** Recompute the dashboard numbers from the stored events. */
    fun refreshBrainRot() {
        brainRotChannels.clear()
        brainRotChannels.addAll(brainRotRepository?.getBlockedChannels() ?: emptyList())
        val events = brainRotRepository?.getEvents() ?: emptyList()
        brainRotSummary = BrainRotStats.summarise(events, System.currentTimeMillis())
    }

    /** Reset the recorded activity (the dashboard's clear action). */
    fun clearBrainRotActivity() {
        brainRotRepository?.clearEvents()
        refreshBrainRot()
    }

    /**
     * Record one protection event. Called by the accessibility service (the
     * enforcement path) so the Activity section reflects real blocks. Writes
     * are cheap and exception-safe; a failure must never disturb protection.
     */
    fun recordBrainRotBlock(category: String, keyword: String? = null, channel: String? = null) {
        brainRotRepository?.recordBlock(category, keyword, channel)
    }

    // ── Global rules ──────────────────────────────────────────────

    /** The number of GLOBAL keywords currently cached on the device. */
    var globalKeywordCount by mutableStateOf(0)
        private set

    /** The number of GLOBAL channels currently cached on the device. */
    var globalChannelCount by mutableStateOf(0)
        private set

    /**
     * The actual global rules cached on this device, so the repository card can
     * show WHAT is enforced rather than only how many there are. An approved
     * channel that never appears here is exactly the "it says 0 channels"
     * confusion this list removes.
     */
    val globalKeywords = mutableStateListOf<String>()
    val globalChannels = mutableStateListOf<String>()

    /** True when a global-rules sync is in flight, for a progress indicator. */
    var globalRulesSyncing by mutableStateOf(false)
        private set

    /**
     * True when this build has a backend configured at all.
     *
     * An unconfigured build is a supported state, not an error: the app runs on
     * its local rules and the UI simply does not offer the global actions.
     */
    var globalRulesAvailable by mutableStateOf(false)
        private set

    private fun refreshGlobalRules() {
        val store = globalRulesStore ?: return
        globalKeywordCount = store.keywords.size
        globalChannelCount = store.channelHandles.size
        globalKeywords.clear()
        globalKeywords.addAll(store.keywords.sorted())
        globalChannels.clear()
        globalChannels.addAll(store.channelHandles.sorted())
    }

    /** Force a refresh of the global rules from the server. */
    fun syncGlobalRules() {
        val scope = viewModelScopeRef ?: return
        if (globalRulesSyncing) return
        globalRulesSyncing = true
        scope.launch {
            runCatching { globalRulesStore?.sync(force = true) }
            refreshGlobalRules()
            globalRulesSyncing = false
        }
    }

    /** Suggest a keyword for the global repository. Returns true when queued. */
    fun suggestGlobalKeyword(keyword: String, onResult: (Boolean) -> Unit) {
        val scope = viewModelScopeRef
        val store = globalRulesStore
        if (scope == null || store == null) { onResult(false); return }
        scope.launch {
            val ok = runCatching { store.suggestKeyword(keyword) }.getOrDefault(false)
            onResult(ok)
        }
    }

    /** Suggest a channel for the global repository. Returns true when queued. */
    fun suggestGlobalChannel(handle: String, name: String?, onResult: (Boolean) -> Unit) {
        val scope = viewModelScopeRef
        val store = globalRulesStore
        if (scope == null || store == null) { onResult(false); return }
        scope.launch {
            val ok = runCatching { store.suggestChannel(handle, name) }.getOrDefault(false)
            onResult(ok)
        }
    }

    /** Report a keyword. Returns the resulting count, or null on failure. */
    fun reportGlobalKeyword(keyword: String, detail: String?, onResult: (Int?) -> Unit) {
        val scope = viewModelScopeRef
        val store = globalRulesStore
        if (scope == null || store == null) { onResult(null); return }
        scope.launch {
            onResult(runCatching { store.reportKeyword(keyword, detail) }.getOrNull())
        }
    }

    /** Report a channel. Returns the resulting count, or null on failure. */
    fun reportGlobalChannel(handle: String, detail: String?, onResult: (Int?) -> Unit) {
        val scope = viewModelScopeRef
        val store = globalRulesStore
        if (scope == null || store == null) { onResult(null); return }
        scope.launch {
            onResult(runCatching { store.reportChannel(handle, detail) }.getOrNull())
        }
    }

    // ── Notification centre ────────────────────────────────────────
    //
    // Two things land here, and they are the same thing to the person reading
    // them: a block ClearView made on their behalf (the "Not interested"
    // receipt, written the moment it happens), and the fate of a request they
    // sent to the global repository.

    val notifications = mutableStateListOf<NotificationStore.Item>()

    var unreadNotificationCount by mutableStateOf(0)
        private set

    fun refreshNotifications() {
        notifications.clear()
        notifications.addAll(notificationStore?.getAll() ?: emptyList())
        unreadNotificationCount = notificationStore?.unreadCount() ?: 0
    }

    fun markNotificationRead(id: String) {
        notificationStore?.markRead(id)
        refreshNotifications()
    }

    fun markAllNotificationsRead() {
        notificationStore?.markAllRead()
        refreshNotifications()
    }

    fun clearNotifications() {
        notificationStore?.clear()
        refreshNotifications()
    }

    // ── My submissions to the global repository ────────────────────

    /** This device's own suggestions, newest first, with their current status. */
    val mySubmissions = mutableStateListOf<BrainRotClient.SubmissionStatus>()

    var mySubmissionsLoading by mutableStateOf(false)
        private set

    /**
     * Read this device's submissions, and turn any decision we have not already
     * seen into a notification.
     *
     * The notification is written only when the status CHANGED since the last
     * poll: the store is idempotent on the submission id, so re-reading an
     * unchanged "approved" is a no-op rather than a second notification.
     */
    fun refreshMySubmissions(notifyOnChange: Boolean = true) {
        val scope = viewModelScopeRef ?: return
        val store = globalRulesStore ?: return
        scope.launch {
            val result = runCatching { store.fetchSubmissions() }.getOrNull()
            if (result == null) return@launch
            val previous = mySubmissions.associate { it.id to it.status }
            mySubmissions.clear()
            mySubmissions.addAll(result)
            mySubmissionsLoading = false
            if (!notifyOnChange) return@launch
            result.forEach { submission ->
                val before = previous[submission.id]
                // Only a CHANGE is worth a notification, and only a final
                // decision. A submission seen as pending the first time is not
                // news — the user was there when they sent it.
                if (before == submission.status) return@forEach
                if (before == null) return@forEach
                when (submission.status) {
                    "approved" -> notificationStore?.add(
                        id = "approved:${submission.id}",
                        kind = NotificationStore.Kind.GLOBAL_APPROVED,
                        value = submission.value,
                        displayName = submission.displayName,
                        message = "Block Request Approved — ${submission.value} was added to the " +
                            "global block repository."
                    )
                    "rejected" -> notificationStore?.add(
                        id = "rejected:${submission.id}",
                        kind = NotificationStore.Kind.GLOBAL_REJECTED,
                        value = submission.value,
                        displayName = submission.displayName,
                        message = "Block Request Rejected — your request to globally block " +
                            "${submission.value} was rejected by an administrator."
                    )
                }
            }
            refreshNotifications()
        }
    }

    /**
     * This device's submissions still waiting on a decision, for one kind.
     *
     * Shown as its own "Queued for review" group above the user's own list: a
     * request that was sent is not a block yet, and mixing the two made a
     * pending request look like a rule that was already in force.
     */
    fun pendingSubmissions(kind: String): List<BrainRotClient.SubmissionStatus> =
        mySubmissions.filter {
            it.kind == kind && (it.status == "pending" || it.status == "under_review")
        }

    /** This device's APPROVED submissions for one kind — now global rules. */
    fun approvedSubmissions(kind: String): List<BrainRotClient.SubmissionStatus> =
        mySubmissions.filter { it.kind == kind && it.status == "approved" }

    /**
     * Where this device's request for one value stands, or null when it was
     * never sent.
     *
     * This is what stops the Send button being offered twice: once a value is
     * queued it shows as queued, and once it is approved it shows as approved —
     * the button is not drawn again, so the same request cannot be re-submitted
     * from the list it came from. A REJECTED request returns null, deliberately:
     * a rejection is not a reason to stop somebody asking again.
     */
    fun submissionStatusFor(kind: String, value: String): String? =
        mySubmissions
            .firstOrNull { it.kind == kind && it.value.equals(value.trim(), ignoreCase = true) }
            ?.status

    /** True while this value is still waiting on a decision. */
    fun isQueuedForReview(kind: String, value: String): Boolean =
        submissionStatusFor(kind, value).let { it == "pending" || it == "under_review" }

    /** True once this value became a global rule. */
    fun isApprovedGlobally(kind: String, value: String): Boolean =
        submissionStatusFor(kind, value) == "approved"

    /** Suggest a channel globally and record the queued state in the centre. */
    fun submitChannelToGlobal(handle: String, name: String?, onResult: (Boolean) -> Unit) {
        val scope = viewModelScopeRef
        val store = globalRulesStore
        if (scope == null || store == null) { onResult(false); return }
        scope.launch {
            val ok = runCatching {
                store.suggestChannel(handle, name, source = "app")
            }.getOrDefault(false)
            if (ok) {
                notificationStore?.add(
                    id = "submitted:channel:$handle",
                    kind = NotificationStore.Kind.GLOBAL_SUBMITTED,
                    value = handle,
                    displayName = name,
                    message = "$handle was submitted to the global repository and is pending " +
                        "admin review."
                )
                refreshNotifications()
                refreshMySubmissions(notifyOnChange = false)
            }
            onResult(ok)
        }
    }

    /** Suggest a keyword globally and record the queued state in the centre. */
    fun submitKeywordToGlobal(keyword: String, onResult: (Boolean) -> Unit) {
        val scope = viewModelScopeRef
        val store = globalRulesStore
        if (scope == null || store == null) { onResult(false); return }
        scope.launch {
            val ok = runCatching {
                store.suggestKeyword(keyword, source = "app")
            }.getOrDefault(false)
            if (ok) {
                notificationStore?.add(
                    id = "submitted:keyword:$keyword",
                    kind = NotificationStore.Kind.GLOBAL_SUBMITTED,
                    value = keyword,
                    displayName = null,
                    message = "\"$keyword\" was submitted to the global repository and is " +
                        "pending admin review."
                )
                refreshNotifications()
                refreshMySubmissions(notifyOnChange = false)
            }
            onResult(ok)
        }
    }

    // ── Why an item is blocked ─────────────────────────────────────

    /** The recorded reason for a blocked item, or null when none was recorded. */
    fun reasonFor(item: String): BlockedItemMeta.Meta? = blockedItemMeta?.get(item)

    /** The provenance behind every blocked item, keyed by canonical identity. */
    fun allItemMeta(): Map<String, BlockedItemMeta.Meta> {
        val out = mutableMapOf<String, BlockedItemMeta.Meta>()
        (userKeywords + youtubeTestKeywords).forEach { keyword ->
            blockedItemMeta?.get(keyword)?.let { out[keyword] = it }
        }
        brainRotChannels.forEach { channel ->
            blockedItemMeta?.get(channel.handle)?.let { out[channel.handle] = it }
        }
        return out
    }

    // ── Private DNS (network-level filtering) ───────────────────────

    /**
     * Cloudflare Family DNS hostname (1.1.1.3) — blocks malware AND adult
     * content at the network level. Using the DoT hostname form so it works
     * as an Android Private DNS provider on all networks.
     */
    fun cloudflareFamilyHostname(): String = "family.cloudflare-dns.com"

    /**
     * CleanBrowsing Family Filter hostname — blocks adult content + malware
     * at the network level (185.228.168.168 / 185.228.169.168). The DoT
     * hostname form works as an Android Private DNS provider.
     */
    fun cleanBrowsingFamilyHostname(): String = "family-filter-dns.cleanbrowsing.org"

    fun setPrivateDnsProvider(context: Context, hostname: String): Boolean {
        if (hostname != cloudflareFamilyHostname() && hostname != cleanBrowsingFamilyHostname()) {
            android.util.Log.e("MainViewModel", "Rejected unauthorized DNS hostname: $hostname")
            return false
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val component = ComponentName(context, com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java)
                
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    val result = dpm.setGlobalPrivateDnsModeSpecifiedHost(component, hostname)
                    if (result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                        dpm.addUserRestriction(component, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                        return true
                    } else {
                        android.util.Log.e("MainViewModel", "Failed to set Private DNS: DPM error code $result")
                        return false
                    }
                } else {
                    android.util.Log.e("MainViewModel", "Cannot set Private DNS: Not Device Owner")
                    return false
                }
            } else {
                android.util.Log.e("MainViewModel", "Private DNS requires API 29")
                return false
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Exception setting Private DNS", e)
            return false
        }
    }

    fun getPrivateDnsProvider(context: Context): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val component = ComponentName(context, com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    val mode = dpm.getGlobalPrivateDnsMode(component)
                    if (mode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
                        return dpm.getGlobalPrivateDnsHost(component)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Exception getting Private DNS", e)
        }
        return null
    }

    // ── Accessibility ──────────────────────────────────────────────

    fun checkAccessibilityStatus(context: Context) {
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ── Device Admin (uninstall friction) / Device Owner (true uninstall block) ──

    var isDeviceOwner by mutableStateOf(false)
        private set

    fun checkDeviceAdminStatus(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(context, com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java)
        isDeviceAdminEnabled = dpm.isAdminActive(component)
        checkDeviceOwnerStatus(context)
    }

    /**
     * Check if this app is a Device Owner (set via ADB).
     * Device Owner status allows us to truly block uninstall.
     */
    private fun checkDeviceOwnerStatus(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
            } else {
                isDeviceOwner = false
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to check Device Owner: ${e.message}")
            isDeviceOwner = false
        }
    }

    /**
     * Show ADB setup instructions for Device Owner.
     * Returns the ADB command to run.
     */
    fun getDeviceOwnerAdbCommand(): String {
        return "adb shell dpm set-device-owner com.muddassir.clearview/.receiver.DeviceAdminReceiver"
    }

    /**
     * Lifts the Device Owner lock (set via ADB) so the app can be updated or
     * uninstalled normally again. Only works while this app IS the device
     * owner. Order matters: unblock uninstall FIRST (requires owner powers),
     * then clear the owner flag. All app data is kept — this only drops the
     * lock. The device-admin status (if any) is left intact and can be turned
     * off from Settings > Device admin apps if desired.
     */
    @Suppress("DEPRECATION")
    fun removeUninstallProtection(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                android.util.Log.i("MainViewModel", "Not device owner — nothing to remove")
                return
            }
            val component = ComponentName(
                context,
                com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java
            )
            // 1) Lift the hard uninstall block (set by DeviceAdminReceiver) while
            //    we still hold owner powers.
            dpm.setUninstallBlocked(component, context.packageName, false)
            // 2) Clear the Device Owner flag. This API may only be called by the
            //    device owner app itself.
            dpm.clearDeviceOwnerApp(context.packageName)
            isDeviceOwner = false
            checkDeviceAdminStatus(context)
            android.util.Log.i("MainViewModel", "Device Owner removed — uninstall/updates allowed again")
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to remove device owner: ${e.message}")
            checkDeviceAdminStatus(context)
        }
    }

    /**
     * Safety net: re-enable LauncherActivity if it was disabled by a
     * previous version's icon-hiding code. Called once on initialize().
     */
    private fun ensureLauncherEnabled(context: Context) {
        try {
            val componentName = ComponentName(context, com.muddassir.clearview.LauncherActivity::class.java)
            val currentState = context.packageManager.getComponentEnabledSetting(componentName)
            if (currentState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                currentState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                android.util.Log.i("MainViewModel", "Re-enabled LauncherActivity (safety net)")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to ensure launcher enabled: ${e.message}")
        }
    }

    // ── Keywords ───────────────────────────────────────────────────

    fun updateNewKeyword(text: String) {
        newKeywordText = text
    }

    /**
     * Add a keyword to the GLOBAL list (every website in Chrome).
     *
     * Recorded through [BlockAction] so it carries a reason, and so the same
     * code path is used wherever a keyword is blocked rather than each screen
     * writing to the list directly and only some of them explaining why.
     */
    fun addKeyword() {
        val keyword = newKeywordText.trim()
        if (keyword.isEmpty()) return
        blockAction?.blockKeyword(
            keyword = keyword,
            scope = BlockAction.Scope.EVERYWHERE,
            source = BlockedItemMeta.Source.USER,
            notify = false
        ) ?: repository?.addUserKeyword(keyword)
        newKeywordText = ""
        refreshKeywords()
    }

    fun removeKeyword(keyword: String) {
        repository?.removeUserKeyword(keyword)
        blockedItemMeta?.forget(keyword)
        refreshKeywords()
    }

    /**
     * Re-read the keywords the user THEMSELVES added.
     *
     * Deliberately not the merged enforcement list: a global rule must never
     * appear in the editable list, or it looks like the user's own entry and its
     * Remove button silently does nothing.
     */
    private fun refreshKeywords() {
        userKeywords.clear()
        userKeywords.addAll((repository?.getUserOwnKeywords() ?: emptySet()).sorted())
    }

    // ── Domains ────────────────────────────────────────────────────

    fun updateNewDomain(text: String) {
        newDomainText = text
    }

    fun addDomain() {
        val domain = newDomainText.trim()
        if (domain.isEmpty()) return
        repository?.addBlockedDomain(domain)
        newDomainText = ""
        refreshDomains()
    }

    fun removeDomain(domain: String) {
        repository?.removeBlockedDomain(domain)
        refreshDomains()
    }

    private fun refreshDomains() {
        blockedDomains.clear()
        blockedDomains.addAll((repository?.getBlockedDomains() ?: emptySet()).sorted())
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // The settings value stores the FULLY-QUALIFIED component name
        // ("pkg/com.pkg.Cls"), not the shorthand form ("pkg/.Cls") — comparing
        // against the shorthand always failed, so the toggle showed OFF even
        // when the service was enabled in Settings. flattenToString() produces
        // the exact form the system stores.
        val serviceName = ComponentName(
            context,
            com.muddassir.clearview.service.UrlBlockerService::class.java
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }
}
