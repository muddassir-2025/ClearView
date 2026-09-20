package com.muddassir.clearview.media.ui

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Starts the work a YouTube playback cannot start without, while the user is
 * still browsing the feed.
 *
 * Opening a video used to pay for everything at tap time: the WebView's
 * Chromium renderer had to be spawned, the player page loaded, and the IFrame
 * API script fetched over the network — and only then could the embed begin
 * fetching the stream the user actually asked for. That is the delay before a
 * video appears, and none of it depends on which video was tapped.
 *
 * So the same page the real player loads is loaded here instead, one screen
 * early. The renderer is alive and the API script (plus its redirect target)
 * is in the WebView's disk cache by the time the player opens, so the tap only
 * has to start the video the user asked for.
 *
 * The warm-up owns a WebView that is never attached to a view hierarchy: it
 * exists to prime the shared renderer/cache, and is destroyed the moment the
 * real player takes over ([release]), so it never competes for memory with the
 * video being played. On a low-RAM device it is skipped entirely rather than
 * spent on a video that may never be opened.
 */
internal object YoutubePlayerPrewarm {
    private const val TAG = "YoutubePlayer"

    /** The warm-up WebView, or null when it is not running. Main thread only. */
    private var warmer: WebView? = null

    /**
     * Loads the player page in a hidden WebView. Idempotent — repeated calls
     * while one is already warm do nothing.
     */
    fun warm(context: Context) {
        if (warmer != null) return
        val app = context.applicationContext
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager?.isLowRamDevice == true) return
        val html = try {
            app.assets.open("youtube_player.html")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "PREWARM_NO_PAGE: ${e.message}")
            return
        }
        try {
            val webView = WebView(app).apply {
                // The same three settings the player relies on: without JS and
                // DOM storage the page cannot fetch the API at all, and the
                // stock WebView user agent makes YouTube refuse to boot.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = YOUTUBE_PLAYER_USER_AGENT
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(1, 1)
                loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
            }
            warmer = webView
            Log.d(TAG, "PREWARM_STARTED")
        } catch (e: Exception) {
            Log.w(TAG, "PREWARM_FAILED: ${e.message}")
            warmer = null
        }
    }

    /**
     * The real player is taking over: drop the warm-up WebView. The disk cache
     * and connection it primed stay behind (they are process-wide), which is
     * the part that survives a destroyed WebView — so nothing is lost by
     * freeing the memory here instead of holding it through playback.
     */
    fun release() {
        val webView = warmer ?: return
        warmer = null
        Log.d(TAG, "PREWARM_RELEASED")
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
    }
}
