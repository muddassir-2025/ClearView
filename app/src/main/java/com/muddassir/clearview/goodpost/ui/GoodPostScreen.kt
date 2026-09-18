package com.muddassir.clearview.goodpost.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostVideoPoster
import com.muddassir.clearview.goodpost.GoodPostScreen
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel

/**
 * Points the status bar at the Good Post surface while the tab is open.
 *
 * The app runs edge to edge, so the status bar is transparent and its icons are
 * whatever the app theme last asked for. Good Post is always dark, so leaving
 * the previous choice in place would draw dark icons on a near-black bar — which
 * is not a subtle styling difference, it is an invisible clock. The previous
 * state is put back on the way out, so leaving the tab cannot leave the status
 * bar wrong for the rest of the app.
 */
@Composable
internal fun ApplyGoodPostStatusBar(enabled: Boolean) {
    val activity = LocalActivity.current

    DisposableEffect(enabled, activity) {
        if (!enabled || activity == null) return@DisposableEffect onDispose {}

        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previous = controller.isAppearanceLightStatusBars
        // Always light icons: the bar behind them is dark in every state.
        controller.isAppearanceLightStatusBars = false

        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
}

/**
 * The Good Post tab (§1, §3).
 *
 * **There is no gate.** A reader opens the tab and lands on the channel list —
 * no signup, no login, no code, no account. That is the product, and it is why
 * this composable is a `when` over which screen is on top rather than over an
 * authentication phase.
 *
 * The only credential anywhere in the tab belongs to an administrator, and it is
 * asked for only when someone taps "Create a channel" (§16).
 *
 * There is no administrator home screen and no dashboard: signing in changes
 * which channels the tab lists and what the rows and posts offer, and nothing
 * else. The same screens serve both, which is what keeps this feeling like a
 * channel app rather than a control panel.
 */
@Composable
fun GoodPostTab(
    viewModel: GoodPostViewModel = viewModel(),
    /**
     * A channel slug carried by a share link (§6), or null.
     *
     * Owned by MainActivity and cleared through [onOpenChannelHandled] once the
     * ViewModel has it, so a rotation cannot replay the same link.
     */
    openChannelSlug: String? = null,
    onOpenChannelHandled: () -> Unit = {}
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.initialize(context) }

    // The image cache needs a directory, and this project has no Application
    // subclass to put one in. Idempotent and cheap after the first call; the
    // trim of what is already on disk happens once per process, off the main
    // thread.
    LaunchedEffect(Unit) { GoodPostImages.attach(context) }

    // The video stills' own directory, attached for the same reason: a clip's
    // tile draws a frame from the file, and that frame is worth keeping across
    // sessions rather than re-reading a clip's header on every open (§22).
    LaunchedEffect(Unit) { GoodPostVideoPoster.attach(context) }

    LaunchedEffect(openChannelSlug) {
        val slug = openChannelSlug ?: return@LaunchedEffect
        viewModel.requestOpenChannel(slug)
        onOpenChannelHandled()
    }

    val state = viewModel.uiState

    if (!state.configured) {
        NotConfigured()
        return
    }

    KeepTheListCurrent(screen = state.screen, viewModel = viewModel)

    // Back pops the in-tab stack. Handled here rather than per screen so the
    // gesture behaves the same on all of them, and returns false at the root so
    // the system can leave the tab.
    BackHandler(enabled = state.backStack.size > 1) { viewModel.back() }

    WaBackdrop {
        // §22: one transition for every move on the tab, rather than each screen
        // choosing its own. The target is the whole STACK and the content key is
        // the screen on top of it, which is what lets the transition tell a push
        // from a pop: two stacks of different depths are a navigation, and two of
        // the same depth are a swap with no direction to report. Keying on the
        // top screen is also what stops an ordinary state change — a post
        // arriving, a count ticking — from animating the screen underneath it.
        AnimatedContent(
            targetState = state.backStack,
            modifier = Modifier.fillMaxSize(),
            contentKey = { stack -> stack.last() },
            transitionSpec = {
                WaMotion.screenChange(
                    forward = targetState.size > initialState.size,
                    sameDepth = targetState.size == initialState.size
                )
            },
            label = "goodpost-screen"
        ) { stack ->
            when (val screen = stack.last()) {
                GoodPostScreen.Home -> GoodPostHome(state = state, viewModel = viewModel)

                GoodPostScreen.Explore -> GoodPostExplore(state = state, viewModel = viewModel)

                is GoodPostScreen.Channel -> GoodPostFeed(
                    state = state,
                    channelId = screen.channelId,
                    editable = false,
                    viewModel = viewModel
                )

                is GoodPostScreen.ChannelInfo -> GoodPostChannelInfo(
                    state = state,
                    channelId = screen.channelId,
                    viewModel = viewModel
                )

                is GoodPostScreen.ChannelMedia -> GoodPostMediaGallery(
                    state = state,
                    channelId = screen.channelId,
                    viewModel = viewModel
                )

                is GoodPostScreen.ChannelSearch -> GoodPostChannelSearch(
                    state = state,
                    channelId = screen.channelId,
                    viewModel = viewModel
                )

                is GoodPostScreen.Starred -> GoodPostStarredScreen(
                    state = state,
                    channelId = screen.channelId,
                    viewModel = viewModel
                )

                GoodPostScreen.AdminLogin -> AdminLoginScreen(state = state, viewModel = viewModel)

                is GoodPostScreen.AdminChannel -> GoodPostFeed(
                    state = state,
                    channelId = screen.channelId,
                    editable = true,
                    viewModel = viewModel
                )
            }
        }
    }

    // §22: the channel form comes up from the bottom and goes back down, which is
    // what says "this is a step over the tab" rather than "you have gone
    // somewhere". It is the only full-screen surface in the tab that is not a
    // destination, and the motion is the only thing that distinguishes the two.
    AnimatedVisibility(
        visible = state.channelFormOpen,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            initialOffsetY = { height -> height }
        ),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            targetOffsetY = { height -> height }
        ),
        label = "goodpost-channel-form"
    ) {
        ChannelFormScreen(state = state, viewModel = viewModel)
    }

    // §16: the longest wait in the product, and the only one the reader cannot
    // hurry along — the Google account exchange runs away from this screen
    // entirely. It covers the tab rather than the sign-in screen alone because
    // the tab is what is on screen while it runs; see [WaBusyOverlay].
    if (state.creatorGoogleBusy) {
        WaBusyOverlay(text = stringResource(R.string.goodpost_signing_in))
    }

    // §16: a creator whose account was already here is greeted rather than
    // dropped onto a list. Shown over whatever screen the session landed on, so
    // it is the same greeting whether Google or the password form got them in.
    state.welcomeEmail?.let { email ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_welcome_back),
            message = stringResource(R.string.goodpost_welcome_back_note, email),
            confirmLabel = stringResource(R.string.goodpost_welcome_continue),
            onConfirm = viewModel::clearWelcome,
            onDismiss = viewModel::clearWelcome,
            destructive = false,
            hideDismiss = true
        )
    }

    state.messageCode?.let { code ->
        MessageDialog(code = code, onDismiss = viewModel::clearMessage)
    }
}

/**
 * How often the channel list refreshes itself while it is being looked at (§24).
 *
 * A quarter of a minute, and the number is a cost decision rather than an
 * animation one. Every tick is one small indexed query against the reader's own
 * follows, so this is a handful of requests per minute of active reading per
 * device, which is what keeps a free-tier deployment free as usage grows. A
 * channel's update arriving twenty seconds late is not something a reader can
 * perceive; a request every second is something the bill can.
 */
private const val LIST_REFRESH_MS = 25_000L

/**
 * Re-fetch the list while the Home screen is in front (§24).
 *
 * `repeatOnLifecycle(STARTED)` is what makes this cheap in the way that matters
 * most: the loop is cancelled when the app goes to the background, so a phone in
 * a pocket makes no requests at all. Without it a `LaunchedEffect` keeps running
 * while the composable is in the tree, which for a tab inside a pager is most of
 * the session — polling on behalf of a reader who is not there.
 *
 * Only Home. A feed, a channel's information page and a search are all fetched
 * when they are opened and are not worth re-checking on a timer: the feed's
 * update is the reader's next navigation, the information page is static, and a
 * search is a question the reader asked.
 */
@Composable
private fun KeepTheListCurrent(screen: GoodPostScreen, viewModel: GoodPostViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(screen, lifecycleOwner) {
        if (screen != GoodPostScreen.Home) return@LaunchedEffect

        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(LIST_REFRESH_MS)
                viewModel.refreshIfIdle()
            }
        }
    }
}

/**
 * Shown when this build has no backend URL (§41).
 *
 * A stated condition rather than a network error, because that is what it is: a
 * build with no configured backend cannot fail to load, it has nothing to load
 * from. Saying so names the real problem instead of blaming the reader's
 * connection.
 */
@Composable
private fun NotConfigured() {
    Column(
        modifier = Modifier.fillMaxSize().background(Wa.List).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = Wa.TextDim,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.goodpost_not_configured_title),
            color = Wa.Text,
            fontSize = 17.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.goodpost_not_configured_note),
            color = Wa.TextDim,
            fontSize = 14.sp
        )
    }
}

/** Centred progress, for the first frame before any content exists. */
@Composable
internal fun CenteredProgress(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.dp,
            color = Wa.Accent
        )
    }
}

/**
 * A one-line message over a dimmed surface.
 *
 * Used for a failed action rather than for a whole screen's failure: the screen
 * behind it is still usable, and a modal that replaces a working list would
 * overstate what went wrong. Every message is a backend code worded by
 * [goodPostErrorMessage], so no screen has to invent its own phrasing.
 */
@Composable
private fun MessageDialog(code: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Wa.Bar, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(
                // Through the shared renderer rather than the resource id, so a
                // message that has to name a number is worded on every surface
                // that shows it (§10).
                text = goodPostErrorText(code),
                color = Wa.Text,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(6.dp))
            WaTextAction(
                text = stringResource(R.string.goodpost_dismiss),
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

