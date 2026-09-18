package com.muddassir.clearview

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.playback.AudioPlayback
import com.muddassir.clearview.media.worker.AudioWorkScheduler
import com.muddassir.clearview.media.worker.MediaWorkScheduler
import com.muddassir.clearview.phonelimit.PhoneLimitCoordinator
import com.muddassir.clearview.quran.worker.QuranWorkScheduler
import com.muddassir.clearview.todo.data.TodoNotifier
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.goodpost.ui.GoodPostTab
import com.muddassir.clearview.goodpost.ui.Wa
import com.muddassir.clearview.goodpost.ui.ApplyGoodPostStatusBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.muddassir.clearview.ui.BlockTab
import com.muddassir.clearview.ui.ContentHubOverlays
import com.muddassir.clearview.ui.MoreTab
import com.muddassir.clearview.ui.ContentHubTabContent
import com.muddassir.clearview.ui.ContentHubTopBar
import com.muddassir.clearview.ui.ContentTab
import com.muddassir.clearview.ui.ApplyImmersiveIfNeeded
import com.muddassir.clearview.ui.contentHubNavItems
import com.muddassir.clearview.ui.isLandscape
import com.muddassir.clearview.ui.rememberContentHubState
import com.muddassir.clearview.ui.theme.UrlblockerTheme
import com.muddassir.clearview.viewmodel.MainViewModel

open class MainActivity : ComponentActivity() {

    /**
     * Warm-start request for the Todo screen: a Todo-reminder notification tap
     * while the app is already running delivers a new intent via [onNewIntent];
     * this snapshot state lets MainScreen react. The cold-start path reads the
     * same flag straight from the launcher intent instead.
     */
    private val todoRequestState = mutableStateOf(false)
    val todoScreenRequested: Boolean get() = todoRequestState.value

    /**
     * Channel share link (§6): `clearview://goodpost/channel/<slug>`.
     *
     * A slug rather than an id because a slug is what a link carries, and the
     * backend resolves it. Held as state for the same reason the Todo flag is:
     * a warm start delivers the link through [onNewIntent], while the cold start
     * reads it straight from the launch intent in MainScreen.
     */
    private val channelSlugRequestState = mutableStateOf<String?>(null)
    val requestedChannelSlug: String? get() = channelSlugRequestState.value

    /** Clears the request once MainScreen has handed it to the Good Post tab. */
    fun consumeChannelSlugRequest() {
        channelSlugRequestState.value = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        channelSlugFrom(intent.data)?.let { channelSlugRequestState.value = it }
        if (intent.getBooleanExtra(TodoNotifier.EXTRA_OPEN_TODO, false)) {
            intent.removeExtra(TodoNotifier.EXTRA_OPEN_TODO)
            todoRequestState.value = true
        }
        // Phone Limit deep link (widget START fallback / expiry notification).
        if (intent.getBooleanExtra(PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT, false)) {
            intent.removeExtra(PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT)
            phoneLimitRequestState.value = true
        }
    }

    /** Clears the warm-start request after MainScreen has handled it. */
    fun consumeTodoScreenRequest() {
        todoRequestState.value = false
    }

    // ── Phone Limit deep link (widget fallback / expiry notification) ──

    /** Warm-start request for the Phone Limit sheet (same pattern as Todo). */
    private val phoneLimitRequestState = mutableStateOf(false)
    val phoneLimitScreenRequested: Boolean get() = phoneLimitRequestState.value

    /** Clears the warm-start request after MainScreen has handled it. */
    fun consumePhoneLimitScreenRequest() {
        phoneLimitRequestState.value = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android notification channels are permanent once created — they
        // persist across app updates and never clean up on their own. Older
        // builds registered channels that are no longer created (or duplicated
        // the "Channel updates" name), so remove them on every startup:
        //   - "media_badge": a second channel that shared the "Channel
        //     updates" name (the launcher-badge notification now reuses
        //     media_updates); deleting it collapses the duplicate entries in
        //     system notification settings.
        //   - "protection_monitor": retired from the code long ago.
        // Both calls are idempotent no-ops on devices that never had them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.deleteNotificationChannel("media_badge")
            nm.deleteNotificationChannel("protection_monitor")
            // Todo reminders: keep both channels (reminders + the silent alarm
            // channel) in their correct state from the very first launch — an
            // older build's alarm channel without the silent tone must be
            // rebuilt here, not only when the first alarm happens to fire.
            TodoNotifier.ensureChannel(this)
        }

        // Quran Reminder: ensure the initial download + 6-hour refresh work is
        // scheduled the first time the app opens (idempotent). The widget also
        // schedules this on add, so the first verse is ready either way.
        QuranWorkScheduler.ensureScheduled(this)

        // Media updates: periodic hourly check for new channel uploads
        // (the worker no-ops while the toggle is off).
        MediaWorkScheduler.ensureScheduled(this)

        // Offline audio downloads: app-wide init (idempotent) + the daily
        // cleanup worker (expired / orphans / stale .part files).
        AudioDownloads.initialize(this)
        AudioWorkScheduler.ensureScheduled(this)

        // Background audio: binds the facade to the process so its transport
        // commands (pause/seek/stop) work before anything has been played in
        // this process — otherwise a resumed session shows a dead pause button
        // on a player that is audibly running.
        AudioPlayback.initialize(this)

        // Use ViewModelProvider (not @Composable viewModel()) since we're in onCreate
        val viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Re-lock the Block tab when the app leaves the foreground: the
                // password is needed again the next time the Block tab is opened.
                // Quran / Media / Live stay freely accessible (like the widget).
                viewModel.lockApp()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkHasPassword()
                // Immediately re-read protection status when the user returns to
                // the app (e.g., after toggling the Accessibility Service in
                // Settings).
                viewModel.checkAccessibilityStatus(this)
                viewModel.checkDeviceAdminStatus(this)
                
                // The user may have just returned from granting the exact-alarm
                // permission (SCHEDULE_EXACT_ALARM). Re-sync every todo alarm:
                // the ones scheduled BEFORE the grant were inexact, and a
                // re-arm now upgrades them to exact so reminders ring on time.
                TodoScheduler.rescheduleAll(this)
            }
        })

        setContent {
            UrlblockerTheme {
                MainScreen()
            }
        }
    }
}

private enum class MainTab { QURAN, MEDIA, GOODPOST, MORE }

/**
 * The channel slug carried by a `clearview://goodpost/channel/<slug>` link, or
 * null when the intent is not one of ours.
 *
 * The Uri overload is a two-line adapter over [channelSlugFrom], which is where
 * the decision actually lives.
 */
internal fun channelSlugFrom(data: Uri?): String? =
    channelSlugFrom(data?.scheme, data?.host, data?.path)

/**
 * The same decision, taken from the three parts of a URI.
 *
 * Separated from the Uri form because `android.net.Uri` is stubbed to return
 * defaults in JVM unit tests (`unitTests.isReturnDefaultValues`), so a
 * Uri-only parser could not be tested at all — and a link that opens the wrong
 * screen, or refuses to open, is not something to discover on a device.
 * Nothing here touches Android.
 *
 * Empty segments are dropped so a trailing or doubled slash cannot change the
 * outcome, and the shape is matched exactly — scheme, host, then
 * `/channel/<slug>` — so the app's other `clearview://` links are never mistaken
 * for a channel. A blank slug counts as no link at all.
 */
internal fun channelSlugFrom(scheme: String?, host: String?, path: String?): String? {
    if (scheme != "clearview") return null
    if (host != "goodpost") return null

    val segments = path.orEmpty().split('/').filter { it.isNotEmpty() }
    if (segments.size != 2) return null
    if (segments[0] != "channel") return null

    return segments[1].takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val landscape = isLandscape()

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.QURAN) }

    /**
     * Whether the More tab is showing Protection rather than its card list (§8).
     *
     * One tab, two pages, so the old Block dashboard keeps its place in the
     * navigation without a fifth item for it — a bottom bar with five entries on
     * a phone is four labels and one guess.
     */
    var moreProtection by rememberSaveable { mutableStateOf(false) }

    /**
     * A channel slug from a share link (§6), waiting to reach Good Post.
     *
     * Deliberately NOT `rememberSaveable`: the home ViewModel holds the opened
     * channel and survives a rotation, so restoring this would reopen what is
     * already open.
     */
    var pendingChannelSlug by remember { mutableStateOf<String?>(null) }
    val hub = rememberContentHubState()

    // A Todo-reminder notification tap opens straight into the Todo screen —
    // either from a cold start (the launcher intent carries EXTRA_OPEN_TODO) or
    // a warm start (onNewIntent set todoScreenRequested). Both switch to More,
    // which is where the Todo screen is opened from (§7) and which composes the
    // hub's overlays so the screen has something to draw itself in.
    val activity = LocalActivity.current as? MainActivity
    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra(TodoNotifier.EXTRA_OPEN_TODO, false) == true) {
            activity.intent.removeExtra(TodoNotifier.EXTRA_OPEN_TODO)
            selectedTab = MainTab.MORE
            hub.selectTab(ContentTab.QURAN)
            hub.showTodoScreen = true
        }
    }
    val todoRequested = activity?.todoScreenRequested == true
    LaunchedEffect(todoRequested) {
        if (todoRequested) {
            activity?.consumeTodoScreenRequest()
            selectedTab = MainTab.MORE
            hub.selectTab(ContentTab.QURAN)
            hub.showTodoScreen = true
        }
    }

    // A Phone Limit deep link (widget START fallback, expiry notification tap)
    // opens straight into the Phone Limit sheet — the "timer window", not just
    // the Quran tab. Cold start: the launcher intent carries the flag; warm
    // start: onNewIntent set phoneLimitRequested.
    LaunchedEffect(Unit) {
        if (activity?.intent?.getBooleanExtra(
                PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT, false
            ) == true
        ) {
            activity.intent.removeExtra(PhoneLimitCoordinator.EXTRA_OPEN_PHONE_LIMIT)
            selectedTab = MainTab.MORE
            hub.selectTab(ContentTab.QURAN)
            hub.showPhoneLimitSheet = true
        }
    }
    val phoneLimitRequested = activity?.phoneLimitScreenRequested == true
    LaunchedEffect(phoneLimitRequested) {
        if (phoneLimitRequested) {
            activity?.consumePhoneLimitScreenRequest()
            selectedTab = MainTab.MORE
            hub.selectTab(ContentTab.QURAN)
            hub.showPhoneLimitSheet = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
        viewModel.checkAccessibilityStatus(context)
    }

    // A shared channel link (§6) opens Good Post with that channel already
    // open. Cold start: the launch intent carries the data, consumed here so a
    // configuration change cannot open it again. Warm start: onNewIntent stored
    // the slug and the state below picks it up.
    LaunchedEffect(Unit) {
        channelSlugFrom(activity?.intent?.data)?.let { slug ->
            activity?.intent?.data = null
            pendingChannelSlug = slug
            selectedTab = MainTab.GOODPOST
        }
    }
    val requestedChannelSlug = activity?.requestedChannelSlug
    LaunchedEffect(requestedChannelSlug) {
        if (requestedChannelSlug != null) {
            activity?.consumeChannelSlugRequest()
            pendingChannelSlug = requestedChannelSlug
            selectedTab = MainTab.GOODPOST
        }
    }

    // Periodically check accessibility and device admin status while the app
    // is in the FOREGROUND (the user may leave to Settings, toggle the service,
    // and return). The loop is paused while backgrounded so it never burns
    // battery polling an invisible UI. Wrapped in try/catch so a single failure
    // can never silently kill the refresh loop.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            resumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        try {
            while (true) {
                if (resumed) {
                    try {
                        viewModel.checkAccessibilityStatus(context)
                        viewModel.checkDeviceAdminStatus(context)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Status refresh failed: ${e.message}")
                    }
                }
                kotlinx.coroutines.delay(3000)
            }
        } finally {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Device Admin launcher — hoisted to MainScreen level for stability
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            android.util.Log.i("MainActivity", "Device Admin activated via result")
        }
    }

    // System back while a video is playing exits vertical fullscreen first
    // (Shorts style), then returns to the Media tab — in landscape fullscreen
    // there is no visible top-bar back button.
    BackHandler(enabled = hub.playingVideo != null) {
        if (hub.playerFullscreen) hub.playerFullscreen = false
        else hub.playingVideo = null
    }

    // System back while the offline audio player is open closes it (stops
    // playback), like the top-bar back arrow.
    BackHandler(enabled = hub.playingAudio != null) {
        hub.exitAudio()
    }

    // System back inside Protection returns to the More list rather than leaving
    // the app — the same thing the top-bar arrow does, so the two agree.
    BackHandler(enabled = selectedTab == MainTab.MORE && moreProtection) {
        moreProtection = false
    }

    // Immersive fullscreen: landscape + (video player OR live tab), or the
    // vertical Shorts-style fullscreen, hides the system bars; portrait
    // restores them. Playback is never restarted because the activity doesn't
    // recreate on rotation (configChanges in the manifest).
    val isFullscreen =
        (landscape && (hub.playingVideo != null || hub.showHaramaynLive)) ||
            (hub.playerFullscreen && hub.playingVideo != null)
    ApplyImmersiveIfNeeded(isFullscreen)
    ApplyGoodPostStatusBar(enabled = selectedTab == MainTab.GOODPOST)

    Scaffold(
        // Good Post paints its own dark surfaces and has no light mode, so the
        // window behind it follows that rather than the app theme. Otherwise the
        // transparent status bar shows a strip of the app's background above the
        // tab's own dark bar, which reads as a rendering fault.
        containerColor = if (selectedTab == MainTab.GOODPOST) {
            Wa.Bar
        } else {
            MaterialTheme.colorScheme.background
        },
        topBar = {
            if (!isFullscreen) {
                if (selectedTab == MainTab.MORE) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = stringResource(
                                        if (moreProtection) R.string.more_protection_title
                                        else R.string.more_tab
                                    ),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(
                                        if (moreProtection) R.string.more_protection_subtitle
                                        else R.string.more_tab_subtitle
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            // Back out of Protection to the More list, rather than
                            // out of the app: Protection is a page of this tab.
                            if (moreProtection) {
                                IconButton(onClick = { moreProtection = false }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            }
                        },
                        actions = {
                            // Lock indicator — tap to re-lock Protection. Only on
                            // the Protection page: there is nothing to lock on the
                            // card list, and an open padlock beside a todo list
                            // would be a control that does nothing.
                            if (moreProtection) {
                                IconButton(onClick = {
                                    if (viewModel.hasPassword) {
                                        viewModel.lockApp()
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (viewModel.isAppLocked && viewModel.hasPassword)
                                            Icons.Filled.Lock
                                        else
                                            Icons.Outlined.LockOpen,
                                        contentDescription = stringResource(
                                            if (viewModel.hasPassword) R.string.block_lock_now_locked
                                            else R.string.block_lock_no_password
                                        ),
                                        tint = if (viewModel.hasPassword && viewModel.isAppLocked)
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else if (selectedTab == MainTab.GOODPOST) {
                    // Nothing. Good Post draws its own bar, and it has to: its
                    // bar is dark in every state, so a ClearView bar above it
                    // would be the one strip on screen that ignored that — and
                    // it duplicated the title as well. Leaving the slot empty
                    // hands the whole height to the tab.
                } else {
                    // The main app has nothing to navigate back to on the content
                    // tabs; the shared top bar's player branch handles its own
                    // back (returns to the Media tab).
                    ContentHubTopBar(state = hub, onBack = null)
                }
            }
        },
        bottomBar = {
            if (hub.playingVideo == null && hub.playingAudio == null && !isFullscreen) {
                NavigationBar {
                    contentHubNavItems().forEach { item ->
                        NavigationBarItem(
                            selected = selectedTab == tabFor(item.tab),
                            onClick = {
                                // selectTab (not a bare assignment) so the
                                // Haramayn overlay is dismissed when the user
                                // moves to another section — including tapping
                                // the tab they are already on.
                                hub.selectTab(item.tab)
                                selectedTab = tabFor(item.tab)
                            },
                            icon = {
                                // Channel-update badge: shows the number of
                                // unseen updates; clears once Media is opened.
                                if (item.tab == ContentTab.MEDIA && hub.unreadMediaUpdates > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge {
                                                Text(hub.unreadMediaUpdates.coerceAtMost(99).toString())
                                            }
                                        }
                                    ) {
                                        item.icon()
                                    }
                                } else {
                                    item.icon()
                                }
                            },
                            label = { Text(item.label) }
                        )
                    }
                    NavigationBarItem(
                        selected = selectedTab == MainTab.GOODPOST,
                        onClick = {
                            selectedTab = MainTab.GOODPOST
                            // Like the Block tab, Good Post replaces the hub
                            // content entirely — a stale Haramayn overlay must
                            // not reappear on the next content tab.
                            hub.showHaramaynLive = false
                        },
                        icon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
                        label = { Text(stringResource(R.string.goodpost_tab)) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == MainTab.MORE,
                        onClick = {
                            selectedTab = MainTab.MORE
                            // Tapping the tab you are already on returns to its
                            // list — the usual way back out of a page that a
                            // bottom bar opened.
                            moreProtection = false
                            // The More tab replaces the hub content entirely —
                            // a stale Haramayn overlay must not reappear when the
                            // user comes back to a content tab.
                            hub.showHaramaynLive = false
                        },
                        icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = null) },
                        label = { Text(stringResource(R.string.more_tab)) }
                    )
                }
            }
        }
    ) { padding ->
        // §21: Good Post owns its bottom inset while the keyboard is up.
        //
        // The Scaffold's inner padding reserves the navigation bar's height, and
        // this tab's composer then applies `imePadding()` of its own — so with
        // the keyboard open the composer was pushed up by the navigation bar's
        // height AND the IME's, leaving a strip of empty surface between the
        // input field and the keyboard. `consumeWindowInsets` records that this
        // space is already accounted for, so the composer lands at
        // `max(navigation bar, IME)` — resting on the keyboard — instead of their
        // sum.
        //
        // Deliberately not applied to the other tabs. They are not
        // scroll-to-the-composer surfaces, and at least one of them (the Block
        // tab's lock screen) stacks its own `imePadding()` on the Scaffold's
        // padding on purpose.
        val contentModifier = if (selectedTab == MainTab.GOODPOST) {
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        } else {
            Modifier.fillMaxSize().padding(padding)
        }

        Box(modifier = contentModifier) {
            when (selectedTab) {
                MainTab.MORE -> {
                    if (moreProtection) {
                        // Protection IS the old Block dashboard — same lock, same
                        // gate, same screens. Only the tab it is reached from has
                        // moved (§8), which is why nothing here was rewritten.
                        //
                        // It is the ONLY password-protected surface, and it is
                        // gated on first open too: no password set → the setup
                        // screen forces one before the dashboard is revealed;
                        // password set but locked → unlock screen; otherwise the
                        // security dashboard.
                        if (!viewModel.hasPassword || viewModel.shouldShowLockScreen()) {
                            LockScreen(
                                onUnlock = { password -> viewModel.verifyAppPassword(password) },
                                onSetupPassword = { password -> viewModel.setAppPassword(password) },
                                hasPassword = viewModel.hasPassword
                            )
                        } else {
                            BlockTab(viewModel, deviceAdminLauncher)
                        }
                    } else {
                        MoreTab(
                            onOpenTodo = { hub.showTodoScreen = true },
                            onOpenPhoneLimit = { hub.showPhoneLimitSheet = true },
                            onOpenZikr = { hub.showDhikrCounter = true },
                            onOpenProtection = { moreProtection = true }
                        )
                    }
                    // These utilities ARE the hub's screens (todo, zikr, phone
                    // limit), so the hub's overlays have to be composed on this
                    // tab too — this is where they are opened from now, and a
                    // card that set a flag nothing drew would be a dead button.
                    ContentHubOverlays(state = hub)
                }
                MainTab.GOODPOST -> GoodPostTab(
                    openChannelSlug = pendingChannelSlug,
                    onOpenChannelHandled = { pendingChannelSlug = null }
                )
                else -> ContentHubTabContent(state = hub, isLandscape = landscape)
            }
        }
    }
}

private fun tabFor(tab: ContentTab): MainTab = when (tab) {
    ContentTab.QURAN -> MainTab.QURAN
    ContentTab.MEDIA -> MainTab.MEDIA
}

@Composable
private fun LockScreen(
    onUnlock: (String) -> Boolean,
    onSetupPassword: (String) -> Unit,
    hasPassword: Boolean
) {
    var passwordInput by remember { mutableStateOf("") }
    var setupPasswordInput by remember { mutableStateOf("") }
    var setupConfirmInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSetupMode by remember { mutableStateOf(!hasPassword) }
    // Resolved once (not inside the click handlers) so the message follows the
    // current locale when the configuration changes.
    val tooShortMessage = stringResource(R.string.block_lock_too_short)
    val mismatchMessage = stringResource(R.string.block_lock_mismatch)
    val incorrectMessage = stringResource(R.string.block_lock_incorrect)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        // The setup/unlock forms are taller than a phone in landscape and the
        // soft keyboard covers the lower half — scrollable + imePadding keeps
        // the fields and the action button reachable in both cases (the button
        // used to sit behind the keyboard while typing a password).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(
                    if (isSetupMode) R.string.block_lock_set_title
                    else R.string.block_lock_title
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(
                    if (isSetupMode) R.string.block_lock_set_note
                    else R.string.block_lock_note
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isSetupMode) {
                OutlinedTextField(
                    value = setupPasswordInput,
                    onValueChange = { setupPasswordInput = it; errorMessage = null },
                    label = { Text(stringResource(R.string.block_lock_new_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = setupConfirmInput,
                    onValueChange = { setupConfirmInput = it; errorMessage = null },
                    label = { Text(stringResource(R.string.block_lock_confirm_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (setupPasswordInput.trim().length < 4) {
                            errorMessage = tooShortMessage
                        } else if (setupPasswordInput != setupConfirmInput) {
                            errorMessage = mismatchMessage
                        } else {
                            onSetupPassword(setupPasswordInput)
                            setupPasswordInput = ""
                            setupConfirmInput = ""
                            isSetupMode = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.block_lock_set_action))
                }
            } else {
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it; errorMessage = null },
                    label = { Text(stringResource(R.string.block_lock_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (onUnlock(passwordInput)) {
                            passwordInput = ""
                            errorMessage = null
                        } else {
                            errorMessage = incorrectMessage
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.block_lock_unlock_action))
                }
            }
        }
    }
}
