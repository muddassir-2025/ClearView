package com.muddassir.clearview.browser

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muddassir.clearview.BuildConfig
import com.muddassir.clearview.R
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** A short browser-chrome message shown instead of a full-screen overlay. */
data class BrowserNotice(
    val message: String,
    val atMs: Long = System.currentTimeMillis()
)

/**
 * State + WebView owner for the Browser tab.
 *
 * The session lives as long as MainScreen does, so switching to the Quran or
 * Media tab and back keeps the page and its history. The WebView itself is
 * created lazily (the first time the Browser tab is shown) and is destroyed
 * with the screen.
 *
 * All protection decisions are delegated to [BrowserRules], which in turn uses
 * the existing matcher and channel repositories. This class only:
 *  - owns the custom start page state (the WebView is hidden, not destroyed),
 *  - filters navigations (safe schemes, blocked URLs, Shorts/Reels/search),
 *  - injects the page script (assets/browser/clearview_browser.js),
 *  - refuses YouTube ad requests when the ad switch is on,
 *  - exposes a deliberately tiny, host-gated JavaScript bridge,
 *  - tracks the address bar / progress / notice/overlay state.
 *
 * The extension's behaviour is the reference for searches and feeds: a blocked
 * Google/YouTube search does not replace the page with an overlay — it is
 * stopped before it runs and the reader stays where they are. Overlays remain
 * for content that has already been opened (a blocked watch page) because
 * there is nothing else to leave visible.
 */
class BrowserSession(private val context: Context) {

    companion object {
        /** YouTube's home page — where blocked video/search actions land. */
        const val YOUTUBE_HOME = "https://www.youtube.com/"

        /** Google's home page — where a blocked Google search stays. */
        const val GOOGLE_HOME = "https://www.google.com/"

        /** Instagram's home page — where a blocked Reels link stays. */
        const val INSTAGRAM_HOME = "https://www.instagram.com/"

        /**
         * Name of the JS object. Every method is gated on the CURRENT page
         * being a protected host (YouTube, Google or Instagram), so a website
         * that somehow retains the object after a navigation cannot call it.
         */
        private const val BRIDGE_NAME = "ClearViewBridge"

        private const val SCRIPT_ASSET = "browser/clearview_browser.js"
        private const val MAX_PAYLOAD_CHARS = 20_000
        private const val NOTICE_DEBOUNCE_MS = 1_500L

        /** Ad networks the ClearView extension refuses for YouTube requests. */
        private val AD_HOSTS = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "googletagservices.com",
            "2mdn.net",
            "adservice.google.com"
        )

        /** Paths that serve an ad even from a host that also serves content. */
        private val AD_PATH_MARKERS = listOf(
            "/pagead/",
            "/api/stats/ads",
            "/get_midroll_info",
            "/ptracking",
            "/pcs/activeview",
            "/pagead/id"
        )
    }

    /**
     * Per-session secret embedded only in the injected script. A cross-origin
     * iframe on YouTube can see the bridge object but not this token, so it
     * cannot ask the bridge to evaluate content or dismiss an overlay.
     */
    private val bridgeToken = java.util.UUID.randomUUID().toString()

    /** The start page and its protection toggles. */
    val settings = BrowserSettings(context)

    private val rules = BrowserRules(context, settings)

    // ── Observable UI state ────────────────────────────────────────

    /** True while the ClearView start page is showing instead of a website. */
    var showHome by mutableStateOf(true)
        private set

    /**
     * Whether the app's bottom navigation is showing over the browser.
     *
     * The Browser tab is the one surface that gives its whole height to the
     * page; the footer is revealed by tapping the page (or by the system Back
     * while it is up) so switching tabs never costs permanent screen space.
     */
    var footerVisible by mutableStateOf(false)
        private set

    /** Current page URL, kept in sync by the WebView callbacks. Empty on Home. */
    var url by mutableStateOf("")
        private set

    /** The page is loading (drives the stop button and progress bar). */
    var isLoading by mutableStateOf(false)
        private set

    /** 0..100 page progress, for the thin loading bar. */
    var progress by mutableIntStateOf(0)
        private set

    var canGoBack by mutableStateOf(false)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    /** Non-null while ClearView is covering the page with its overlay. */
    var block by mutableStateOf<BrowserBlock?>(null)
        private set

    /** A transient message shown in browser chrome (blocked search/Reels). */
    var notice by mutableStateOf<BrowserNotice?>(null)
        private set

    /** Main-frame load failure, shown as a small retry banner. */
    var loadError by mutableStateOf<String?>(null)
        private set

    /**
     * Incremented when the page reports a zero-height layout viewport.
     *
     * The tab watches this and gives the WebView a one-pixel resize, which is
     * the one operation that makes Chromium rebuild the CSS viewport (and with
     * it `100vh` and every height media query) for a document that was created
     * before the view had its final size.
     */
    var viewportRepairTick by mutableIntStateOf(0)
        private set

    // ── Internal ───────────────────────────────────────────────────

    /** The current host, read off the UI thread by the JS bridge. */
    @Volatile private var currentUrl: String? = null

    /** True while the page is one the in-page script may run on. */
    @Volatile private var protectedPage = false

    private var scriptCache: String? = null
    private var hasLoadedPage = false
    private var viewportRepairs = 0
    private var startRequested = false
    private var destroyed = false

    /**
     * True while [closePages] is tearing the current page down. The WebView
     * callbacks for that internal `about:blank` navigation must not be mistaken
     * for the reader opening a page (which would un-show the start page).
     */
    @Volatile private var closingPage = false

    /** HTML5 fullscreen video: WebView hands us the player view. */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customViewDialog: Dialog? = null

    private val webViewLazy = lazy { createWebView() }

    /**
     * Starts the system file picker for a page that asked for a file.
     *
     * The WebView cannot open a picker itself: `onShowFileChooser` has to be
     * answered by an Activity for result. The UI installs this launcher, and the
     * pending callback is held here so the result lands on the same request.
     */
    var fileChooserLauncher: ((android.content.Intent) -> Unit)? = null
    private var pendingFileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    /** Give the page its file (or the cancellation) and release the request. */
    fun onFileChooserResult(resultCode: Int, data: android.content.Intent?) {
        val callback = pendingFileCallback ?: return
        pendingFileCallback = null
        val uris = runCatching {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }.getOrNull()
        callback.onReceiveValue(uris)
    }

    /** Answer an outstanding request when the tab goes away. */
    private fun cancelFileChooser() {
        val callback = pendingFileCallback ?: return
        pendingFileCallback = null
        callback.onReceiveValue(null)
    }

    /** The WebView; creating it on first access keeps the tab cheap when unused. */
    val webView: WebView
        get() = webViewLazy.value

    // ── Navigation API ────────────────────────────────────────────

    /** Load whatever the reader typed (URL or search). */
    fun navigate(input: String) {
        val target = BrowserAddress.toUrl(input) ?: return
        navigateTo(target)
    }

    /** Load [target] unless the existing rules block it. */
    fun navigateTo(target: String) {
        if (destroyed) return
        // A hidden tab must not be reachable by typing its address either. This
        // is checked before the content rules because it is about the SURFACE
        // (a discovery feed), not about one item in it.
        val hiddenTab = hiddenTabBlock(target)
        if (hiddenTab != null) {
            showBlock(hiddenTab)
            return
        }
        val blockedProfile = blockedProfileBlock(target)
        if (blockedProfile != null) {
            showBlock(blockedProfile)
            return
        }
        val hiddenSurface = hiddenSurfaceRedirect(target)
        if (hiddenSurface != null) {
            redirectBlockedFeed(target)
            return
        }
        val blocked = rules.blockForUrl(target)
        if (blocked != null) {
            when (blocked.type) {
                BrowserBlock.Type.SEARCH -> {
                    handleBlockedSearch(target)
                    return
                }
                // A Short/Reel is a surface, not a single video: covering one
                // item of an endless queue is not blocking it (the reader can
                // swipe to the next). The URL is never loaded; the reader is
                // left on the feed home instead.
                BrowserBlock.Type.SHORTS, BrowserBlock.Type.REELS -> {
                    redirectBlockedFeed(target)
                    return
                }
                else -> {
                    showBlock(blocked)
                    return
                }
            }
        }
        block = null
        loadError = null
        notice = null
        showHome = false
        // A real navigation always ends any page-teardown state, even if the
        // blank document's onPageFinished never arrived.
        closingPage = false
        hideFooter()
        val safeTarget = BrowserAddress.withSafeSearch(target) ?: target
        if (webViewLazy.isInitialized()) {
            webView.visibility = View.VISIBLE
            webView.onResume()
        }
        webView.loadUrl(safeTarget)
    }

    /**
     * Show the ClearView start page and CLOSE every open webpage.
     *
     * Home is a clean slate, not a page kept warm behind it: the loaded
     * document is replaced with `about:blank`, its timers and any playing media
     * are stopped, its fullscreen surface is removed and its history is
     * cleared. So nothing that was open is still running behind the start page,
     * and Back cannot resurrect it. Shortcuts and protection settings are
     * preferences and are untouched.
     *
     * The WebView OBJECT is kept (it is attached to the tab's layout, and was
     * laid out before this call, so pausing it is safe): only the page it was
     * showing is disposed. It is paused here so a closed page cannot keep
     * running scripts, and [navigateTo] resumes it for the next page.
     */
    fun goHome() {
        if (destroyed) return
        showHome = true
        hideFooter()
        block = null
        loadError = null
        notice = null
        url = ""
        currentUrl = null
        protectedPage = false
        isLoading = false
        progress = 0
        closePages()
    }

    /**
     * Disposes the CURRENT page: no document, no history, no media, no
     * fullscreen surface. Idempotent, and safe before the WebView exists.
     *
     * Deliberately does not clear cookies, localStorage or the download cache:
     * closing a page is not "reset the browser", and signing the reader out of
     * the sites they use would be a side effect nobody asked for.
     */
    private fun closePages() {
        if (!webViewLazy.isInitialized()) return
        val view = webView
        closingPage = true
        hasLoadedPage = false
        viewportRepairs = 0
        cancelFileChooser()
        block = null
        loadError = null
        notice = null
        url = ""
        currentUrl = null
        protectedPage = false
        canGoBack = false
        canGoForward = false
        isLoading = false
        progress = 0
        runCatching {
            // A fullscreen video is a separate surface (and window): it has no
            // page to be closed with, so it is dismissed first.
            exitCustomView(notify = true)
            pausePlayback()
            view.stopLoading()
            // Replace the document: this is what actually ends the page's
            // scripts, timers, audio and video. `about:blank` is a blank
            // document with no web URL, and [updateLocation] ignores it, so the
            // address bar and protection state stay cleared.
            view.loadUrl(BrowserAddress.ABOUT_BLANK)
            view.clearHistory()
        }
    }

    fun reload() {
        if (destroyed || showHome) return
        val safe = BrowserAddress.withSafeSearch(currentUrl)
        if (safe != null && safe != currentUrl) {
            webView.loadUrl(safe)
            return
        }
        val blocked = rules.blockForUrl(currentUrl)
        if (blocked != null) {
            when (blocked.type) {
                BrowserBlock.Type.SEARCH -> {
                    handleBlockedSearch(currentUrl.orEmpty())
                    return
                }
                BrowserBlock.Type.SHORTS, BrowserBlock.Type.REELS -> {
                    redirectBlockedFeed(currentUrl.orEmpty())
                    return
                }
                else -> {
                    showBlock(blocked)
                    return
                }
            }
        }
        block = null
        loadError = null
        webView.reload()
    }

    fun goBack() {
        if (!webView.canGoBack()) return
        // Walking history leaves the blocked page, so the overlay goes too.
        block = null
        showHome = false
        webView.visibility = View.VISIBLE
        webView.onResume()
        webView.goBack()
    }

    fun goForward() {
        if (!webView.canGoForward()) return
        block = null
        showHome = false
        webView.visibility = View.VISIBLE
        webView.onResume()
        webView.goForward()
    }

    /**
     * System back inside the Browser tab.
     *
     * A visible overlay is dismissed FIRST (and, for a blocked video, sends the
     * reader home rather than revealing the video). Otherwise normal WebView
     * history is used. Returns false when there is nothing left to consume, so
     * the caller can let Android leave the app.
     */
    fun handleBack(): Boolean {
        if (block != null) {
            dismissBlock()
            return true
        }
        if (webView.canGoBack()) {
            block = null
            showHome = false
            webView.onResume()
            webView.goBack()
            return true
        }
        return false
    }

    /**
     * Leave a blocked page. A content block returns to YouTube Home (never
     * revealing the blocked video); a URL block was cancelled before it loaded,
     * so the previous page (or Home) can be shown again.
     */
    fun dismissBlock() {
        val current = block ?: return
        block = null
        if (current.isContent) {
            navigateTo(YOUTUBE_HOME)
        } else if (!hasLoadedPage) {
            goHome()
        }
    }

    fun dismissNotice() {
        notice = null
    }

    /** Reveal or hide the app's bottom navigation over the browser. */
    fun toggleFooter() {
        footerVisible = !footerVisible
    }

    fun hideFooter() {
        if (footerVisible) footerVisible = false
    }

    /** Called once when the tab is first composed. */
    fun ensureStarted() {
        if (startRequested || destroyed) return
        startRequested = true
        goHome()
    }

    /**
     * A switch in the browser's Protection Settings changed. Re-read the policy
     * in the page and, where the new value makes the current page invalid,
     * leave it immediately.
     */
    fun onProtectionSettingsChanged() {
        if (destroyed) return
        // Nothing navigates here any more: the page applies the new policy to
        // the item the reader is on. Turning "Block Shorts" on covers the
        // current Short (and the next swipe's Short) instead of throwing the
        // reader back to the YouTube home feed.
        if (webViewLazy.isInitialized()) {
            applyDarkPages(webView.settings, reload = true)
            webView.evaluateJavascript(
                "window.__clearviewRefresh && window.__clearviewRefresh();",
                null
            )
        }
    }

    /** The last value of [BrowserSettings.darkPages] pushed into the WebView. */
    private var appliedDarkPages: Boolean? = null

    /**
     * Ask the WebView to render pages that ship no dark theme in dark colours.
     *
     * Android 13+ calls the switch "algorithmic darkening": the page still
     * decides, so a site with a real `prefers-color-scheme: dark` design keeps
     * it instead of being inverted into something unreadable. Android 10-12
     * has the same feature under its first name, force-dark. Below Android 10
     * the WebView has no such switch, and those devices keep each site's own
     * colours rather than getting a half-inverted page.
     *
     * The flag is read when a document is created, so flipping the switch
     * reloads the page the reader is on: that is the visible proof it did
     * something. [reload] is false on construction, where there is no page yet.
     */
    @Suppress("DEPRECATION")
    private fun applyDarkPages(webSettings: android.webkit.WebSettings, reload: Boolean) {
        val enabled = settings.darkPages
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webSettings.isAlgorithmicDarkeningAllowed = enabled
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webSettings.forceDark =
                if (enabled) android.webkit.WebSettings.FORCE_DARK_ON
                else android.webkit.WebSettings.FORCE_DARK_OFF
        }
        val changed = appliedDarkPages != null && appliedDarkPages != enabled
        appliedDarkPages = enabled
        if (changed && reload && !showHome && !currentUrl.isNullOrBlank()) webView.reload()
    }

    /** Remove the WebView from any previous Compose host before re-attaching. */
    fun detachFromParent() {
        if (!webViewLazy.isInitialized()) return
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    /** The tab (or the app) is going away for now: stop media and rendering. */
    fun onHidden() {
        hideFooter()
        if (!webViewLazy.isInitialized()) return
        // Nothing has been laid out yet: pausing here would poison the
        // viewport before Chromium has measured it (see goHome).
        if (!hasLoadedPage) return
        pausePlayback()
        webView.onPause()
        // The last rendered frame must not become the app's background / recent
        // apps thumbnail.
        webView.visibility = View.INVISIBLE
    }

    /** The tab (or the app) is visible again. */
    fun onShown() {
        if (!webViewLazy.isInitialized()) return
        if (!showHome) {
            webView.visibility = View.VISIBLE
            webView.onResume()
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        cancelFileChooser()
        if (webViewLazy.isInitialized()) {
            val view = webView
            try {
                exitCustomView(notify = true)
                view.removeJavascriptInterface(BRIDGE_NAME)
                view.stopLoading()
                view.loadUrl("about:blank")
                view.clearHistory()
                view.removeAllViews()
                view.destroy()
            } catch (_: Exception) {
                // A destroyed WebView must never take the app down.
            }
        }
    }

    // ── WebView construction ──────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(context)
        view.setBackgroundColor(Color.BLACK)
        view.isVerticalScrollBarEnabled = true
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isScrollbarFadingEnabled = true

        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Instagram/Meta pages and many modern sites branch on the `wv`
            // marker and serve their WebView-incompatible shell (a blank white
            // screen) instead of the real page. Presenting the same modern
            // Chrome UA the installed WebView actually is avoids that without
            // pretending to be a desktop browser.
            val defaultUa = userAgentString
            if (!defaultUa.isNullOrBlank()) {
                userAgentString = defaultUa
                    .replace(Regex(";\\s*wv\\)"), ")")
                    .replace("Version/4.0 ", "")
            }
            // Modern Instagram keeps a chunk of its session in third-party
            // cookies; refusing them leaves an authenticated page blank.
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            // Normal websites (including YouTube) work with these defaults;
            // user gesture is disabled so inline playback can start when the
            // page itself allows it.
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            // Never upgrade/downgrade silently: mixed content is refused.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // Instagram and other modern web apps keep part of their session
            // in IndexedDB; leaving database storage off makes a signed-in
            // page look empty even though its HTML loaded.
            databaseEnabled = true
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        // Dark pages from the very first document: the reader should never see
        // a flash of white before the preference is applied.
        applyDarkPages(view.settings, reload = false)

        CookieManager.getInstance().setAcceptCookie(true)

        // The bridge is tiny and every method validates that the loaded page is
        // a protected host. No Context, file access or arbitrary method
        // dispatch is exposed to page JavaScript.
        view.addJavascriptInterface(Bridge(), BRIDGE_NAME)

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val original = request.url.toString()
                // SafeSearch is a browser policy: rewrite a search URL before
                // it is loaded, so a link tapped inside Google/Bing gets the
                // same strict flag as one typed into the address bar.
                val safe = BrowserAddress.withSafeSearch(original) ?: original
                if (safe != original) {
                    if (handleNavigation(safe)) return true
                    view.loadUrl(safe)
                    return true
                }
                return handleNavigation(original)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // The internal teardown navigation (goHome -> about:blank) is
                // not the reader opening a page: the start page stays up and the
                // scratch document paints behind it.
                if (closingPage) return
                isLoading = true
                progress = 5
                showHome = false
                if (webViewLazy.isInitialized()) {
                    webView.visibility = View.VISIBLE
                    webView.onResume()
                }
                updateLocation(url)
                // Early injection hides Shorts/Reels/category tabs as soon as
                // the new document exists; onPageFinished injects again for
                // SPA pages and anything the early pass missed. A null URL means
                // the new document's identity is not known yet — never inject on
                // a stale host flag in that case.
                if (!url.isNullOrBlank()) {
                    if (protectedPage) injectScript() else injectTapScript()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (closingPage) {
                    // The blank document has replaced the closed page: finish
                    // the teardown (the history still held the old entries when
                    // goHome asked), stop the view's timers, and drop the flag.
                    closingPage = false
                    runCatching {
                        view.clearHistory()
                        view.onPause()
                    }
                    canGoBack = false
                    canGoForward = false
                    isLoading = false
                    progress = 0
                    return
                }
                isLoading = false
                progress = 100
                updateLocation(url)
                refreshHistory()
                if (!url.isNullOrBlank()) {
                    if (protectedPage) injectScript() else injectTapScript()
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                // SPA/history navigations can bypass shouldOverrideUrlLoading.
                // Enforce SafeSearch here too, before the page is treated as
                // the current, unprotected search.
                val safe = BrowserAddress.withSafeSearch(url)
                if (safe != null && safe != url) {
                    view.loadUrl(safe)
                    return
                }
                updateLocation(url)
                refreshHistory()
                // SPA navigations (YouTube/Google/Instagram pushState) do not
                // necessarily go through shouldOverrideUrlLoading. Check the new
                // URL here too; the extension handles these before the new
                // surface is acted on.
                val blocked = rules.blockForUrl(url) ?: return
                when (blocked.type) {
                    BrowserBlock.Type.SEARCH -> {
                        showNotice(context.getString(R.string.browser_search_blocked))
                        redirectToSafeHome(
                            when {
                                BrowserAddress.isGoogleSearchUrl(url) -> GOOGLE_HOME
                                BrowserAddress.isYouTubeSearchUrl(url) -> YOUTUBE_HOME
                                else -> null
                            }
                        )
                    }
                    // SPA navigation into a Short/Reel. There is no page to
                    // cover any more: leave the feed instead.
                    BrowserBlock.Type.SHORTS, BrowserBlock.Type.REELS ->
                        redirectToSafeHome(feedHomeFor(url))
                    else -> showBlock(blocked)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                isLoading = false
                // ERROR_UNKNOWN is also reported for cancelled loads; only show
                // a retry banner for a real failure.
                if (error.errorCode != WebViewClient.ERROR_UNKNOWN) {
                    loadError = error.description?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: "This page could not be loaded."
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                // Never proceed past an invalid certificate.
                handler.cancel()
            }

            /**
             * YouTube ads: the same network layer the extension gets from
             * declarativeNetRequest. Only subresources on a YouTube page are
             * refused, so the same ad network is untouched on other sites.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (isBlockedAdRequest(request)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        204,
                        "No Content",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress.coerceIn(0, 100)
                if (newProgress >= 100) isLoading = false
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Websites may not reach the camera/microphone through the tab.
                request.deny()
            }

            /**
             * `<input type="file">`: Instagram's "+" (create post, add to
             * story) and YouTube's upload/avatar buttons all open one. Without
             * this override the tap was swallowed and nothing happened at all —
             * the site asked, the WebView had no answer, and no picker appeared.
             */
            override fun onShowFileChooser(
                view: WebView,
                callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                params: FileChooserParams
            ): Boolean {
                val launcher = fileChooserLauncher
                if (launcher == null) {
                    callback.onReceiveValue(null)
                    return false
                }
                cancelFileChooser()
                pendingFileCallback = callback
                val intent = runCatching { params.createIntent() }.getOrNull()
                    ?: android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = params.acceptTypes?.firstOrNull { it.contains('/') } ?: "*/*"
                    }
                val started = runCatching { launcher(intent) }.isSuccess
                if (!started) {
                    // No picker on this device: release the page instead of
                    // leaving it waiting on a request that will never answer.
                    onFileChooserResult(android.app.Activity.RESULT_CANCELED, null)
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, false, false)
            }

            /**
             * HTML5 fullscreen video. YouTube and most video sites call this
             * when the reader taps the fullscreen control; without it the
             * request is simply ignored. The player view is hosted in a
             * fullscreen dialog, and back/dismiss exits the same way the site's
             * own control does.
             */
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                val dialog = Dialog(
                    context,
                    android.R.style.Theme_Black_NoTitleBar_Fullscreen
                )
                val container = FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                dialog.setContentView(container)
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog.setOnDismissListener { exitCustomView(notify = true) }
                customView = view
                customViewCallback = callback
                customViewDialog = dialog
                dialog.show()
            }

            override fun onHideCustomView() {
                exitCustomView(notify = true)
            }
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        return view
    }

    // ── Navigation filtering ──────────────────────────────────────

    /**
     * True when the navigation is consumed (blocked or unsupported). Only
     * http(s) is ever loaded: intent://, market://, file:// and friends are
     * refused rather than handed to other apps, which keeps web content from
     * launching arbitrary intents.
     */
    private fun handleNavigation(target: String): Boolean {
        if (!BrowserAddress.isWebUrl(target)) return true
        val hiddenTab = hiddenTabBlock(target)
        if (hiddenTab != null) {
            showBlock(hiddenTab)
            return true
        }
        val blockedProfile = blockedProfileBlock(target)
        if (blockedProfile != null) {
            showBlock(blockedProfile)
            return true
        }
        if (hiddenSurfaceRedirect(target) != null) {
            webView.post { if (!destroyed) redirectBlockedFeed(target) }
            return true
        }
        val blocked = rules.blockForUrl(target)
        if (blocked != null) {
            when (blocked.type) {
                BrowserBlock.Type.SEARCH -> handleBlockedSearch(target)
                // Never load a blocked vertical feed. The load is posted
                // because a WebView must not be asked to load from inside
                // shouldOverrideUrlLoading.
                BrowserBlock.Type.SHORTS, BrowserBlock.Type.REELS -> {
                    webView.post { if (!destroyed) redirectBlockedFeed(target) }
                }
                else -> showBlock(blocked)
            }
            return true
        }
        return false
    }

    /**
     * A hidden tab, entered directly by URL.
     *
     * Hiding a tab in the nav bar only removes the door, not the room: the
     * address bar, a pasted link and the back stack all still reached it. A tab
     * whose toggle is on is refused here, before the page is loaded, so there is
     * nothing to filter once it opens.
     */
    private fun hiddenTabBlock(target: String): BrowserBlock? {
        if (!BrowserAddress.isInstagram(target)) return null
        if (settings.hideInstagramSearch && BrowserAddress.isInstagramExploreUrl(target)) {
            return BrowserBlock("Instagram Search", BrowserBlock.Type.SURFACE)
        }
        if (settings.hideInstagramHome && BrowserAddress.isInstagramHomeUrl(target)) {
            return BrowserBlock("Instagram Home", BrowserBlock.Type.SURFACE)
        }
        return null
    }

    /**
     * A profile or channel page whose owner the reader blocked.
     *
     * Blocked posts and cards already leave the feed, but the channel page was a
     * separate door: typing `instagram.com/<handle>/` showed the whole grid.
     */
    private fun blockedProfileBlock(target: String): BrowserBlock? {
        val instagramHandle = BrowserAddress.instagramProfileHandle(target)
        if (instagramHandle != null && rules.isChannelBlocked(null, instagramHandle)) {
            return BrowserBlock(instagramHandle, BrowserBlock.Type.CHANNEL)
        }
        val youtubeHandle = BrowserAddress.youtubeChannelHandle(target)
        if (youtubeHandle != null && rules.isChannelBlocked(null, youtubeHandle)) {
            return BrowserBlock(youtubeHandle, BrowserBlock.Type.CHANNEL)
        }
        return null
    }

    /**
     * A discovery feed the reader did not ask for, entered directly by URL.
     *
     * Returns the feed home the reader is sent to, or null when this URL is a
     * normal page.
     */
    private fun hiddenSurfaceRedirect(target: String): String? {
        val path = runCatching {
            java.net.URI(target).path.orEmpty()
        }.getOrDefault("")
        // YouTube's own discovery feeds are the same class of surface as
        // Shorts: an endless list the reader never asked for.
        return when {
            BrowserRules.isYouTubeSurface(target) &&
                (path.startsWith("/feed/explore") || path.startsWith("/feed/trending")) ->
                YOUTUBE_HOME
            else -> null
        }
    }

    /** The feed home a blocked Short/Reel URL belongs to. */
    private fun feedHomeFor(url: String?): String? = when {
        BrowserRules.isYouTubeSurface(url) -> YOUTUBE_HOME
        BrowserAddress.isInstagram(url) -> INSTAGRAM_HOME
        else -> null
    }

    /**
     * Leave a blocked Shorts/Reels URL without loading it.
     *
     * The page script performs the same redirect for SPA navigations; this is
     * the native path for a typed URL, a link tap and a history entry. The home
     * page is only used when the rules do not block it too, so a blocklist can
     * never create a redirect loop.
     */
    private fun redirectBlockedFeed(target: String) {
        if (destroyed || !webViewLazy.isInitialized()) return
        val home = feedHomeFor(target) ?: return
        if (rules.blockForUrl(home) != null) return
        // A Short tapped on the feed home would otherwise reload the page the
        // reader is already on.
        if (currentUrl?.trimEnd('/') == home.trimEnd('/')) return
        block = null
        loadError = null
        showHome = false
        hideFooter()
        webView.visibility = View.VISIBLE
        webView.onResume()
        webView.loadUrl(home)
    }

    /**
     * A blocked search never loads. Where the reader searched decides where
     * they end up: already on Google/YouTube, they stay exactly there (the
     * extension's behaviour); anywhere else, they are taken to that engine's
     * home page rather than being shown a full-page block screen.
     */
    private fun handleBlockedSearch(target: String) {
        showNotice(context.getString(R.string.browser_search_blocked))
        if (showHome) return

        val home = when {
            BrowserAddress.isGoogleSearchUrl(target) -> GOOGLE_HOME
            BrowserAddress.isYouTubeSearchUrl(target) -> YOUTUBE_HOME
            else -> null
        } ?: return
        val onTargetSite = if (home == GOOGLE_HOME) {
            BrowserRules.isGoogleSurface(currentUrl)
        } else {
            BrowserRules.isYouTubeSurface(currentUrl)
        }
        if (!onTargetSite) {
            if (rules.blockForUrl(home) == null) {
                showHome = false
                webView.onResume()
                webView.loadUrl(home)
            }
        }
    }

    private fun updateLocation(newUrl: String?) {
        if (newUrl.isNullOrBlank()) return
        if (!BrowserAddress.isWebUrl(newUrl)) return
        currentUrl = newUrl
        url = newUrl
        hasLoadedPage = true
        protectedPage = BrowserRules.isProtectedSurface(newUrl)
    }

    private fun refreshHistory() {
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    private fun showBlock(newBlock: BrowserBlock) {
        if (block == newBlock) return
        block = newBlock
        pausePlayback()
    }

    private fun showNotice(message: String) {
        val current = notice
        if (current != null && current.message == message &&
            System.currentTimeMillis() - current.atMs < NOTICE_DEBOUNCE_MS
        ) {
            return
        }
        notice = BrowserNotice(message)
    }

    /**
     * Leave a blocked SPA destination without drawing an overlay. The injected
     * script normally performs the same redirect; this is the native backstop
     * for a page whose script did not run.
     */
    private fun redirectToSafeHome(home: String?) {
        if (home == null || destroyed) return
        if (rules.blockForUrl(home) != null) return
        if (!webViewLazy.isInitialized()) return
        if (BrowserRules.isProtectedSurface(currentUrl)) {
            webView.evaluateJavascript(
                "window.location.replace(" + JSONObject.quote(home) + ");",
                null
            )
        } else {
            webView.post { if (!destroyed) webView.loadUrl(home) }
        }
    }

    /**
     * Remove the fullscreen player and tell the page it has exited. Idempotent:
     * the dialog's dismiss listener and the page's own exit can both arrive.
     */
    private fun exitCustomView(notify: Boolean) {
        val view = customView ?: return
        val callback = customViewCallback
        val dialog = customViewDialog
        customView = null
        customViewCallback = null
        customViewDialog = null
        (view.parent as? ViewGroup)?.removeView(view)
        dialog?.setOnDismissListener(null)
        try { dialog?.dismiss() } catch (_: Exception) { /* already gone */ }
        if (notify) {
            try { callback?.onCustomViewHidden() } catch (_: Exception) { /* ignore */ }
        }
    }

    /**
     * Rebuild the WebView's viewport metrics after the page reported that its
     * layout viewport has no height.
     *
     * The repair is a metric rebuild plus a real one-pixel relayout (driven by
     * [viewportRepairTick]) — NEVER a reload.
     *
     * This used to reload the current page on the second report, on the theory
     * that the new document would be laid out against the repaired metrics. It
     * did — but a 2.5 s viewport probe deciding to reload the reader's page is
     * an unexpected reload in the middle of normal browsing: on YouTube the
     * second report landed a few seconds after opening, right after the reader
     * started scrolling, and it happened again on every visit. Reloading also
     * cannot fix a viewport that is broken for the WebView itself. The page
     * script's own height shim ([clearview_browser.js]) is what actually makes
     * a zero-height layout viewport usable, and the relayout re-applies it; so
     * the relayout is the whole repair.
     */
    private fun repairViewport() {
        if (destroyed || !webViewLazy.isInitialized()) return
        viewportRepairs++
        if (viewportRepairs > 2) return
        webView.requestLayout()
        // A tick the tab turns into an actual size change (a 1 dp nudge):
        // Chromium only rebuilds the CSS viewport on a real resize.
        viewportRepairTick = viewportRepairs
    }

    /** Pause/mute any playing video before the overlay covers it. */
    private fun pausePlayback() {
        if (!webViewLazy.isInitialized()) return
        webView.evaluateJavascript(
            "window.__clearviewPause && window.__clearviewPause();",
            null
        )
    }

    private fun injectScript() {
        if (!webViewLazy.isInitialized()) return
        val script = scriptCache ?: try {
            context.assets.open(SCRIPT_ASSET)
                .bufferedReader()
                .use { it.readText() }
                .also { scriptCache = it }
        } catch (_: Exception) {
            return
        }
        // The token prefix is injected with the script, so only the main-frame
        // page script holds it.
        webView.evaluateJavascript(
            "window.__clearviewToken=" + JSONObject.quote(bridgeToken) + ";\n" + script,
            null
        )
    }

    /**
     * The tap-to-reveal footer listener for ordinary websites.
     *
     * The full page script is deliberately limited to YouTube, Google and
     * Instagram. The footer is not: it is the only way back to the app's tabs
     * while the Browser tab hides it by default, so every page needs the same
     * tap listener. This is the smallest script that provides it, with no DOM
     * scanning or observers of its own.
     */
    private fun injectTapScript() {
        if (!webViewLazy.isInitialized()) return
        val source = "window.__clearviewToken=" + JSONObject.quote(bridgeToken) + ";\n" +
            "(function(){if(window.__clearviewTapLoaded)return;" +
            "window.__clearviewTapLoaded=true;" +
            "var token=window.__clearviewToken||'';" +
            "var sx=0,sy=0,at=0,started=false;" +
            "function interactive(el){return !!(el&&el.closest&&el.closest(" +
            "'a,button,input,textarea,select,[role=\"button\"],[role=\"link\"]," +
            "[role=\"menuitem\"],[role=\"tab\"],[contenteditable=\"true\"],[contenteditable=\"\"]'));}" +
            "function tap(){try{ClearViewBridge.onTap(token);}catch(e){}}" +
            "document.addEventListener('touchstart',function(e){" +
            "if(!e.touches||e.touches.length!==1){started=false;return;}" +
            "started=true;sx=e.touches[0].clientX;sy=e.touches[0].clientY;at=Date.now();" +
            "},true);" +
            "document.addEventListener('touchend',function(e){" +
            "if(!started)return;started=false;if(Date.now()-at>450)return;" +
            "var t=e.changedTouches&&e.changedTouches[0];" +
            "var dx=t?t.clientX-sx:0,dy=t?t.clientY-sy:0;" +
            "if(Math.sqrt(dx*dx+dy*dy)>12)return;" +
            "if(interactive(e.target))return;tap();" +
            "},true);" +
            "if(!('ontouchstart' in window)){document.addEventListener('click'," +
            "function(e){if(!interactive(e.target))tap();},true);}" +
            "})();"
        webView.evaluateJavascript(source, null)
    }

    /**
     * Browser-owned Instagram sign-in fallback.
     *
     * Instagram's sign-in screen sometimes refuses to render inside a WebView.
     * The provider's own browser is offered instead, so the sign-in stays in an
     * app that Instagram already trusts; ClearView's cookies are untouched and
     * the in-page filters deliberately do not apply to that window.
     */
    fun openInstagramAuthentication() {
        val target = currentUrl?.takeIf { BrowserAddress.isInstagramAuthUrl(it) } ?: return
        if (rules.blockForUrl(target) != null) return
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(target))
        } catch (_: ActivityNotFoundException) {
            showNotice(context.getString(R.string.browser_auth_no_browser))
        }
    }

    // ── Ads ───────────────────────────────────────────────────────

    /**
     * True for a YouTube ad subresource. The host list and path markers mirror
     * the extension's declarativeNetRequest ruleset; the page script removes
     * the slots that still render and chases a video ad that still starts.
     */
    private fun isBlockedAdRequest(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return false
        if (!settings.blockYouTubeAds) return false
        if (!BrowserRules.isYouTubeSurface(currentUrl)) return false
        val requestUrl = request.url
        val host = requestUrl.host?.lowercase() ?: return false
        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val full = requestUrl.toString().lowercase()
        return AD_PATH_MARKERS.any { full.contains(it) }
    }

    // ── JavaScript bridge ─────────────────────────────────────────

    /**
     * The only surface exposed to page JavaScript.
     *
     *  - every call re-checks that the CURRENT page is a protected host;
     *  - the content-specific methods additionally require YouTube;
     *  - inputs are plain strings, outputs are JSON or Unit;
     *  - nothing here exposes a Context, a file, a URL loader or reflection.
     *
     * Calls arrive on a WebView worker thread, so UI state is updated through
     * [WebView.post].
     */
    private inner class Bridge {

        private fun authorized(token: String?): Boolean =
            token == bridgeToken && protectedPage && !destroyed

        private fun onYouTube(): Boolean = BrowserRules.isYouTubeSurface(currentUrl)

        /**
         * A payload field, or null when it is MISSING or a JSON `null`.
         *
         * `JSONObject.optString` returns the literal string "null" for a JSON
         * null (org.json stringifies JSONObject.NULL). The page sends
         * `channelName: null` for every card that has no channel name, so the
         * stored block carried the name "null": the reader's block of @Beluga1
         * then had no real name to match, and the Home card that only shows
         * "Beluga" stayed on the feed while the watch page blocked it.
         */
        private fun field(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null
            else json.optString(key)
                .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

        /**
         * The page measured `100vh` as 0 while `window.innerHeight` is fine:
         * its layout viewport is broken, which collapses the YouTube player and
         * blanks Instagram's sign-in screen. See [repairViewport].
         */
        @JavascriptInterface
        fun onViewportBroken(token: String?) {
            if (token != bridgeToken || destroyed) return
            webView.post { repairViewport() }
        }

        @JavascriptInterface
        fun policy(token: String?): String {
            if (!authorized(token)) return "{}"
            return JSONObject().apply {
                put("hideShorts", settings.blockShorts)
                put("hideReels", settings.hideInstagramReels)
                put("hideInstagramSearch", settings.hideInstagramSearch)
                put("hideInstagramSuggestions", settings.hideInstagramSuggestions)
                put("hideHome", settings.hideInstagramHome)
                put("hideGoogleCategories", settings.hideGoogleCategories)
                put("removeBlocked", settings.removeBlockedFromFeed)
                put("blockAds", settings.blockYouTubeAds)
                put("youtube", onYouTube())
                put("google", BrowserRules.isGoogleSurface(currentUrl))
                put("instagram", BrowserRules.isInstagramSurface(currentUrl))
            }.toString()
        }

        @JavascriptInterface
        fun evaluateVideo(token: String?, payload: String?): String = safe {
            if (!authorized(token) || !onYouTube()) return@safe "{\"blocked\":false}"
            val json = JSONObject(payload.orEmpty())
            val verdict = rules.blockForVideo(
                title = field(json, "title"),
                description = field(json, "description"),
                channelId = field(json, "channelId"),
                channelHandle = field(json, "channelHandle"),
                channelName = field(json, "channelName")
            )
            verdict?.toJson() ?: "{\"blocked\":false}"
        }

        @JavascriptInterface
        fun evaluateCard(token: String?, payload: String?): String = safe {
            if (!authorized(token) || !onYouTube()) return@safe "{\"blocked\":false}"
            val json = JSONObject(payload.orEmpty())
            val verdict = rules.blockForCard(
                title = field(json, "title"),
                thumbnailDescription = field(json, "thumbnailDescription"),
                channelName = field(json, "channelName"),
                channelId = field(json, "channelId"),
                channelHandle = field(json, "channelHandle")
            )
            verdict?.toJson() ?: "{\"blocked\":false}"
        }

        /**
         * One Instagram post/reel. Gated on the Instagram host and delegated to
         * the same content policy YouTube uses (channels, then keywords), so a
         * Brain Rot rule applies identically on both surfaces.
         */
        @JavascriptInterface
        fun evaluateInstagram(token: String?, payload: String?): String = safe {
            if (!authorized(token) || !BrowserRules.isInstagramSurface(currentUrl)) {
                return@safe "{\"blocked\":false}"
            }
            val json = JSONObject(payload.orEmpty())
            val verdict = rules.blockForInstagram(
                caption = field(json, "caption"),
                channelHandle = field(json, "channelHandle"),
                channelName = field(json, "channelName")
            )
            verdict?.toJson() ?: "{\"blocked\":false}"
        }

        /**
         * A tap on page whitespace. It toggles the app footer, the same way a
         * tap reveals browser chrome on mobile — without a full-screen
         * transparent overlay that would swallow the page's own gestures.
         */
        @JavascriptInterface
        fun onTap(token: String?) {
            // Deliberately not limited to protected hosts: the footer must be
            // reachable on every page (see injectTapScript).
            if (token != bridgeToken || destroyed) return
            webView.post { toggleFooter() }
        }

        /**
         * The page surface the action came from, or null when the current page
         * cannot host it. A request can only ever act on the surface it says it
         * is on, so an Instagram page cannot block through the YouTube path.
         */
        private fun surfaceOf(json: JSONObject): String? {
            val surface = field(json, "surface")
            if (surface == BrowserRules.SURFACE_INSTAGRAM) {
                return if (BrowserRules.isInstagramSurface(currentUrl)) {
                    BrowserRules.SURFACE_INSTAGRAM
                } else null
            }
            return if (onYouTube()) BrowserRules.SURFACE_YOUTUBE else null
        }

        /**
         * The reader chose "Not interested" (or the ClearView action) on a
         * YouTube card/Short, or blocked the author of an Instagram post/reel.
         * Native side stores the channel block, then the page removes the card
         * and re-evaluates the current video.
         */
        @JavascriptInterface
        fun blockChannel(token: String?, payload: String?): String = safe {
            if (!authorized(token)) return@safe "{\"blocked\":false}"
            val json = JSONObject(payload.orEmpty())
            val surface = surfaceOf(json) ?: return@safe "{\"blocked\":false}"
            val handle = field(json, "channelHandle")
            val name = field(json, "channelName")
            val added = rules.blockChannel(handle, name, surface = surface)
            if (!added) return@safe "{\"blocked\":false}"
            webView.post { showNotice(context.getString(R.string.browser_channel_blocked, handle ?: name.orEmpty())) }
            JSONObject().apply {
                put("blocked", true)
                put("item", handle ?: name.orEmpty())
            }.toString()
        }

        /** Whether the injected menu should offer Block or Unblock. */
        @JavascriptInterface
        fun channelState(token: String?, payload: String?): String = safe {
            if (!authorized(token)) return@safe "{\"blocked\":false}"
            val json = JSONObject(payload.orEmpty())
            if (surfaceOf(json) == null) return@safe "{\"blocked\":false}"
            val handle = field(json, "channelHandle")
            val id = field(json, "channelId")
            JSONObject().apply {
                put("blocked", rules.channelBlocked(handle, id))
                if (handle != null) put("item", handle)
            }.toString()
        }

        /** The reader chose "Unblock channel with ClearView" in the page menu. */
        @JavascriptInterface
        fun unblockChannel(token: String?, payload: String?): String = safe {
            if (!authorized(token)) return@safe "{\"unblocked\":false}"
            val json = JSONObject(payload.orEmpty())
            if (surfaceOf(json) == null) return@safe "{\"unblocked\":false}"
            val handle = field(json, "channelHandle")
            val id = field(json, "channelId")
            val removed = rules.unblockChannel(handle, id)
            if (!removed) return@safe "{\"unblocked\":false}"
            webView.post {
                showNotice(context.getString(R.string.browser_channel_unblocked, handle.orEmpty()))
            }
            JSONObject().apply {
                put("unblocked", true)
                put("item", handle.orEmpty())
            }.toString()
        }

        @JavascriptInterface
        fun evaluateSearch(token: String?, query: String?): String = safe {
            if (!authorized(token)) return@safe "{\"blocked\":false}"
            val safeQuery = query.orEmpty().take(MAX_PAYLOAD_CHARS)
            val verdict = when {
                BrowserRules.isGoogleSurface(currentUrl) ->
                    rules.blockForGoogleSearch(safeQuery)
                BrowserRules.isYouTubeSurface(currentUrl) ->
                    rules.blockForYouTubeSearch(safeQuery)
                else -> null
            }
            verdict?.toJson() ?: "{\"blocked\":false}"
        }

        /**
         * The page reports an SPA navigation. Returns whether the URL is
         * blocked and, for a search/Shorts/Reels block, where the page should
         * go instead of showing the blocked surface.
         */
        @JavascriptInterface
        fun onNavigated(token: String?, target: String?): String = safe {
            if (!authorized(token)) return@safe "{\"blocked\":false}"
            val safeUrl = target.orEmpty().take(MAX_PAYLOAD_CHARS)
            if (!BrowserAddress.isWebUrl(safeUrl)) return@safe "{\"blocked\":false}"
            webView.post { updateLocation(safeUrl) }
            val verdict = rules.blockForUrl(safeUrl)
            if (verdict == null) {
                webView.post {
                    // A new unblocked page means an old CONTENT overlay is stale.
                    if (block?.isContent == true) block = null
                }
                return@safe "{\"blocked\":false}"
            }

            val redirectTo: String? = when (verdict.type) {
                BrowserBlock.Type.SEARCH -> {
                    webView.post { showNotice(context.getString(R.string.browser_search_blocked)) }
                    when {
                        BrowserAddress.isGoogleSearchUrl(safeUrl) -> GOOGLE_HOME
                        BrowserAddress.isYouTubeSearchUrl(safeUrl) -> YOUTUBE_HOME
                        else -> null
                    }
                }
                // A vertical feed is left, not covered: the page script
                // navigates to this URL on the next tick.
                BrowserBlock.Type.SHORTS -> feedHomeFor(safeUrl)
                BrowserBlock.Type.REELS -> feedHomeFor(safeUrl)
                else -> {
                    webView.post { showBlock(verdict) }
                    null
                }
            }
            return JSONObject().apply {
                put("blocked", true)
                put("item", verdict.item)
                put("type", verdict.type.name)
                if (verdict.type == BrowserBlock.Type.SHORTS ||
                    verdict.type == BrowserBlock.Type.REELS
                ) {
                    put("inline", true)
                }
                if (redirectTo != null) put("redirectTo", redirectTo)
            }.toString()
        }

        /** The page found blocked content (video/channel/keyword). */
        @JavascriptInterface
        fun onBlocked(token: String?, payload: String?) {
            if (!authorized(token) || !onYouTube()) return
            val json = safeParse(payload) ?: return
            val item = json.optString("item").takeIf { it.isNotBlank() } ?: "Blocked content"
            val type = runCatching {
                BrowserBlock.Type.valueOf(json.optString("type"))
            }.getOrDefault(BrowserBlock.Type.KEYWORD)
            webView.post { showBlock(BrowserBlock(item, type, isContent = true)) }
        }

        /** The page is now on allowed content; drop a stale content overlay. */
        @JavascriptInterface
        fun onContentAllowed(token: String?) {
            if (!authorized(token)) return
            webView.post {
                if (block?.isContent == true) block = null
            }
        }

        private inline fun safe(block: () -> String): String = try {
            block()
        } catch (_: Exception) {
            "{\"blocked\":false}"
        }

        private fun safeParse(payload: String?): JSONObject? = try {
            JSONObject(payload.orEmpty())
        } catch (_: Exception) {
            null
        }
    }
}
