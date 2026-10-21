package com.muddassir.clearview.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muddassir.clearview.R
import com.muddassir.clearview.browser.BrowserAddress
import com.muddassir.clearview.browser.BrowserNotice
import com.muddassir.clearview.browser.BrowserSession
import kotlinx.coroutines.delay

/**
 * The Browser tab — an immersive, compact surface.
 *
 * There is no app-wide title bar here: the page gets the whole height, and the
 * browser's own chrome is a single floating address pill. The app's bottom
 * navigation is hidden by default and revealed by tapping page whitespace (or
 * the home background), so switching sections costs no permanent screen space.
 *
 * Protection is delegated to [BrowserSession]; a blocked search/reel is a small
 * chrome notice, never a full-page overlay. The overlay is reserved for a page
 * whose content has already been opened and must be covered.
 *
 * [onOpenAppProtection] is the door into the password-protected main
 * Protection dashboard. It is optional so the widget surface, which only ever
 * opens the Quran tab, does not need to pretend it has one.
 */
@Composable
fun BrowserTab(
    session: BrowserSession,
    modifier: Modifier = Modifier,
    onOpenAppProtection: (() -> Unit)? = null
) {
    var address by rememberSaveable { mutableStateOf(session.url) }
    var addressFocused by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Compose measures a newly inserted child before the parent's size is
    // known. Creating WebView at that moment gives Chromium a zero-height
    // initial containing block, and it keeps reporting `100vh` = 0 (and every
    // height media query as 0) for the life of that WebView. Waiting two frames
    // means the tab already has its real size; the WebView is then measured
    // once, correctly, and pages size themselves normally.
    var webViewReady by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun submitAddress() {
        session.navigate(address)
        addressFocused = false
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun openSettings() {
        session.settings.refreshFromApp()
        session.hideFooter()
        addressFocused = false
        focusManager.clearFocus()
        keyboard?.hide()
        showSettings = true
    }

    // Keep the bar in step with real navigations, but never overwrite what the
    // reader is currently typing.
    LaunchedEffect(session.url) {
        if (!addressFocused) address = session.url
    }
    // The WebView is created lazily on first composition. onShown balances the
    // onHidden issued when this tab was last left.
    LaunchedEffect(Unit) {
        session.onShown()
        session.ensureStarted()
        withFrameNanos { }
        withFrameNanos { }
        webViewReady = true
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.onHidden()
                Lifecycle.Event.ON_START -> session.onShown()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Do not keep playing audio for a tab that is no longer on screen.
            session.onHidden()
            session.detachFromParent()
        }
    }

    // Settings replaces the page completely. Pausing the WebView stops audio
    // and stops a playing video from continuing behind an opaque screen.
    LaunchedEffect(showSettings) {
        if (showSettings) session.onHidden()
        else if (!session.showHome) session.onShown()
    }

    // A notice is a browser-chrome message, not a page overlay. It clears
    // itself so it cannot become permanent furniture.
    val notice = session.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            if (session.notice === notice) session.dismissNotice()
        }
    }

    // System back: close Settings, hide the revealed footer, dismiss the
    // overlay, or walk WebView history before leaving the app.
    BackHandler(
        enabled = showSettings || session.footerVisible ||
            session.block != null || session.canGoBack
    ) {
        when {
            showSettings -> showSettings = false
            session.footerVisible -> session.hideFooter()
            else -> session.handleBack()
        }
    }

    // Scaffold already applies the status-bar inset through its content
    // padding; adding statusBarsPadding here would reserve it twice and leave
    // the blank strip above the browser that made the start page feel empty.
    Column(modifier = modifier.fillMaxSize()) {
        if (!session.showHome && !showSettings) {
            BrowserToolbar(
                session = session,
                address = address,
                addressFocused = addressFocused,
                onAddressChange = { address = it },
                onAddressFocusChanged = { addressFocused = it },
                onSubmit = { submitAddress() },
                onOpenSettings = { openSettings() }
            )
            if (session.isLoading) {
                LinearProgressIndicator(
                    progress = { session.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
        }

        if (!session.showHome && !showSettings && BrowserAddress.isInstagramAuthUrl(session.url)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.browser_auth_help), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { session.openInstagramAuthentication() }) {
                        Text(stringResource(R.string.browser_auth_open))
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The WebView always keeps its full size, even behind the start page
            // or the settings sheet: it is hidden with View.INVISIBLE by
            // BrowserSession instead of being laid out at 0×0. A WebView that
            // is measured at zero height and then grown keeps a zero-height
            // media-query viewport (Chromium never refreshes it), which made
            // YouTube render its player as a small sticky rectangle and broke
            // height-dependent pages such as Instagram's sign-in.
            if (webViewReady) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { session.webView }
                )
            }

            if (session.showHome && !showSettings) {
                BrowserHome(
                    settings = session.settings,
                    onNavigate = { session.navigate(it) },
                    onOpenSettings = { openSettings() },
                    onBackgroundTap = { session.toggleFooter() }
                )
            }

            if (showSettings) {
                BrowserProtectionSettings(
                    settings = session.settings,
                    onBack = { showSettings = false },
                    onChanged = { session.onProtectionSettingsChanged() },
                    onOpenAppProtection = onOpenAppProtection?.let { open ->
                        {
                            showSettings = false
                            open()
                        }
                    }
                )
            }

            if (!showSettings) {
                session.block?.let { blocked ->
                    BrowserBlockOverlay(
                        block = blocked,
                        onDismiss = { session.dismissBlock() }
                    )
                }

                notice?.let { current ->
                    BrowserNoticeBanner(
                        notice = current,
                        onDismiss = { session.dismissNotice() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                    )
                }
            }

            session.loadError?.let { message ->
                if (!showSettings) {
                    LoadErrorBanner(
                        message = message,
                        onRetry = { session.reload() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * The whole browser chrome: back, one address pill, reload/stop, and a small
 * overflow menu. Deliberately one row — the old layout stacked an
 * OutlinedTextField and a second navigation row, which is not how a modern
 * mobile browser feels and left little page visible.
 */
@Composable
private fun BrowserToolbar(
    session: BrowserSession,
    address: String,
    addressFocused: Boolean,
    onAddressChange: (String) -> Unit,
    onAddressFocusChanged: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val secure = address.startsWith("https://", ignoreCase = true)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { session.goBack() },
                enabled = session.canGoBack,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.browser_back)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (secure) Icons.Filled.Lock else Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.size(7.dp))
                BasicTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onAddressFocusChanged(it.isFocused) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (address.isBlank()) {
                                Text(
                                    text = stringResource(R.string.browser_address_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                IconButton(
                    onClick = {
                        when {
                            session.isLoading -> session.webView.stopLoading()
                            addressFocused -> onSubmit()
                            else -> session.reload()
                        }
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = when {
                            session.isLoading -> Icons.Filled.Close
                            addressFocused -> Icons.AutoMirrored.Filled.ArrowForward
                            else -> Icons.Filled.Refresh
                        },
                        contentDescription = when {
                            session.isLoading -> stringResource(R.string.browser_stop)
                            addressFocused -> stringResource(R.string.browser_go)
                            else -> stringResource(R.string.browser_reload)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.browser_more)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_forward)) },
                        enabled = session.canGoForward,
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            menuOpen = false
                            session.goForward()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_home)) },
                        leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            session.goHome()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_reload)) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            session.reload()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_settings_open)) },
                        leadingIcon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserNoticeBanner(
    notice: BrowserNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.browser_notice_dismiss),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun LoadErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.browser_retry),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
