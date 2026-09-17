package com.muddassir.clearview.goodpost.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muddassir.clearview.R
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

    // Back pops the in-tab stack. Handled here rather than per screen so the
    // gesture behaves the same on all of them, and returns false at the root so
    // the system can leave the tab.
    BackHandler(enabled = state.backStack.size > 1) { viewModel.back() }

    WaBackdrop {
        when (val screen = state.screen) {
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

            GoodPostScreen.AdminLogin -> AdminLoginScreen(state = state, viewModel = viewModel)

            is GoodPostScreen.AdminChannel -> GoodPostFeed(
                state = state,
                channelId = screen.channelId,
                editable = true,
                viewModel = viewModel
            )
        }
    }

    if (state.channelFormOpen) {
        ChannelFormScreen(state = state, viewModel = viewModel)
    }

    state.messageCode?.let { code ->
        MessageDialog(code = code, onDismiss = viewModel::clearMessage)
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
                text = stringResource(
                    goodPostErrorMessage(com.muddassir.clearview.goodpost.goodPostErrorFor(code))
                ),
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

/** A colour used by the read-only surfaces, kept here so they never import grey. */
internal val WaTransparent: Color = Color.Transparent


