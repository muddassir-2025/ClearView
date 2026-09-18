package com.muddassir.clearview.media.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Resolves a PUBLIC Instagram post's playable media by RENDERING Meta's own
 * public embed page in a hidden WebView and reading the real `src` of the
 * `<video>` element it builds.
 *
 * WHY A WEBVIEW (the previous approach is dead)
 * ---------------------------------------------
 * The old resolver HTTP-GET the embed page and regex-scraped `"video_url"` out
 * of the HTML. That no longer works: `instagram.com/p/<code>/embed/` is now a
 * client-rendered app shell — the server response is ~600 KB of framework
 * JavaScript containing NO media JSON at all (verified: zero `video_url`,
 * zero `og:video`, zero `<video>` tags, zero `.mp4` links, for every UA tried,
 * including desktop Chrome, iPhone Safari and the Facebook crawler). The media
 * is fetched by the page's own JavaScript AFTER it loads.
 *
 * The embed page IS still the supported, login-free public surface — it just
 * has to actually RUN. Rendering it in a WebView (the same platform the app
 * already uses for the YouTube IFrame player) produces a `<video>` element
 * whose `src` is a plain progressive `.mp4` on Meta's CDN that anybody can
 * fetch (verified: HTTP 206, `content-type: video/mp4`, no cookies and no
 * Referer required). That URL is handed to the app's NATIVE MediaPlayer, which
 * is what actually paints the video — the WebView here is used ONLY to resolve
 * the URL and is destroyed immediately afterwards, so no Instagram UI (and no
 * second decoder) is ever kept alive behind the player.
 *
 * No login, no cookies, no account session: the embed page is Meta's public
 * widget. Nothing is scraped from a private surface and no authentication is
 * bypassed.
 *
 * PLATFORM LIMITS (deliberate, documented):
 *  - Private / deleted / age-restricted posts render no `<video>` → the
 *    resolver times out (bounded, ~12 s) and returns null, so the player can
 *    show a real error state with Retry instead of loading forever.
 *  - Image / carousel posts have no `<video>` either; the resolver returns the
 *    poster only, and the player keeps rendering the still image.
 *  - Nothing here touches the cross-origin iframe's internals: the page is
 *    loaded as a normal top-level page and only its own DOM is read.
 */
/** A resolved embed: the playable mp4 (+ the post's poster image, if any). */
typealias EmbeddedMedia = InstagramEmbedPayload.ParsedVideo

object InstagramEmbedResolver {

    private const val TAG = "InstagramEmbedResolver"

    /** Overall budget for one resolution (page load + JS render + probe). */
    private const val TIMEOUT_MS = 12_000L

    /** Probe cadence once the page has finished loading. */
    private const val POLL_MS = 250L

    /** Max probes per candidate URL before moving on / giving up. */
    private const val MAX_PROBES = 28

    /**
     * The candidate embed URLs, in order. `/reel/` and `/p/` serve the same
     * post and Meta has swapped which one works over time, so both are tried.
     * `captioned` is the richer variant; the plain one is the fallback.
     */
    private fun candidateUrls(shortcode: String): List<String> = listOf(
        "https://www.instagram.com/reel/$shortcode/embed/captioned/",
        "https://www.instagram.com/p/$shortcode/embed/captioned/"
    )



    /**
     * Results are cached briefly: a signed CDN URL stays valid for hours, but
     * re-rendering a WebView costs ~1 s, so switching back and forth between
     * two Reels should not re-render each time.
     */
    private val cache = object : LruCache<String, CachedResolution>(6) {}

    private class CachedResolution(val at: Long, val media: EmbeddedMedia?)

    private const val CACHE_TTL_MS = 20 * 60 * 1000L

    /**
     * Renders the embed page for [shortcodeOrUrl] and returns the playable
     * progressive mp4 URL, or null when the post exposes none (private,
     * deleted, still image, or the render took too long).
     */
    suspend fun resolve(
        context: Context,
        shortcodeOrUrl: String,
        /**
         * Bypass this cache and this cache only — the Retry path, where the
         * remembered URL is the thing that failed. The render still runs.
         */
        fresh: Boolean = false
    ): EmbeddedMedia? {
        val shortcode = InstagramStreamResolver.extractShortcode(shortcodeOrUrl)
        if (shortcode.isBlank()) return null
        if (!fresh) {
            synchronized(cache) { cache.get(shortcode) }?.let { hit ->
                if (System.currentTimeMillis() - hit.at < CACHE_TTL_MS) return hit.media
            }
        }
        val media = withTimeoutOrNull(TIMEOUT_MS) { render(context.applicationContext, shortcode) }
        // Only cache a POSITIVE result (a transient failure must be retryable).
        if (media != null) {
            synchronized(cache) { cache.put(shortcode, CachedResolution(System.currentTimeMillis(), media)) }
        }
        return media
    }

    // ── The WebView render ────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun render(context: Context, shortcode: String): EmbeddedMedia? =
        suspendCancellableCoroutine { cont ->
            val urls = candidateUrls(shortcode)
            var urlIndex = 0
            var done = false
            val handler = Handler(Looper.getMainLooper())
            val view = WebView(context)
            var probes = 0

            fun teardown() {
                runCatching {
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
            }

            fun finish(result: EmbeddedMedia?) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                teardown()
                if (cont.isActive) cont.resume(result)
            }

            // These two are declared as mutable lambdas because Kotlin local
            // functions cannot forward-reference each other, and the load ↔
            // probe pair is necessarily mutually recursive.
            var loadCandidate: () -> Unit = {}
            var scheduleProbe: (Long) -> Unit = {}

            // Probes the rendered DOM for the <video> element. The src is read
            // from the ATTRIBUTE (v.getAttribute('src')) because currentSrc can
            // be a blob: URL once Meta's player takes over — the attribute keeps
            // the real CDN address. Nothing about a cross-origin frame is
            // touched: this is the page's own DOM.
            val probeJs = """
                (function () {
                  try {
                    var v = document.querySelector('video');
                    if (!v) return '';
                    var attr = v.getAttribute('src') || '';
                    var src = v.src || '';
                    var cur = v.currentSrc || '';
                    var best = attr || src || cur;
                    if (!best || best.indexOf('blob:') === 0) {
                      var so = v.querySelector('source');
                      if (so) { best = so.getAttribute('src') || so.src || best; }
                    }
                    if (!best || best.indexOf('blob:') === 0) return '';
                    return JSON.stringify({ s: best, p: v.poster || '' });
                  } catch (e) { return ''; }
                })()
            """.trimIndent()

            /** One probe, re-arming itself until the video appears / budget ends. */
            fun probe() {
                if (done) return
                view.evaluateJavascript(probeJs) { raw ->
                    if (done) return@evaluateJavascript
                    val parsed = parseProbe(raw)
                    if (parsed != null) {
                        Log.i(
                            TAG,
                            "RESOLVED shortcode=$shortcode poster=${!parsed.posterUrl.isNullOrBlank()} " +
                                "url=${parsed.videoUrl.take(90)}"
                        )
                        finish(parsed)
                        return@evaluateJavascript
                    }
                    probes++
                    if (probes >= MAX_PROBES) {
                        // This candidate never rendered a video — try the next
                        // one (the page may be a login wall / soft 404 for this
                        // post type), then give up cleanly.
                        urlIndex++
                        if (urlIndex < urls.size) {
                            loadCandidate()
                        } else {
                            Log.w(TAG, "NO_MEDIA shortcode=$shortcode (no <video> rendered)")
                            finish(null)
                        }
                        return@evaluateJavascript
                    }
                    scheduleProbe(POLL_MS)
                }
            }

            /** Arms the next probe; a no-op once this resolution is finished. */
            scheduleProbe = { delayMs ->
                if (!done) handler.postDelayed({ probe() }, delayMs)
            }

            /**
             * Loads the next candidate embed URL and starts probing right away
             * (not at onPageFinished): the embed builds its <video> element as
             * soon as its own data resolves, which is usually BEFORE the load
             * event fires — probing only at onPageFinished added ~2 s of dead
             * time to a cold resolution.
             */
            loadCandidate = {
                if (!done) {
                    probes = 0
                    val candidate = urls.getOrNull(urlIndex)
                    if (candidate == null) {
                        Log.w(TAG, "NO_MEDIA shortcode=$shortcode (candidates exhausted)")
                        finish(null)
                    } else {
                        Log.d(TAG, "LOAD shortcode=$shortcode url=$candidate")
                        view.loadUrl(candidate)
                        scheduleProbe(600L)
                    }
                }
            }

            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Never let the hidden page start playback with sound; it is
                // only here to render the DOM. (`preload="none"` on the embed
                // already implies this — belt and braces.)
                mediaPlaybackRequiresUserGesture = true
                // The images are irrelevant (we only need the video src) and
                // skipping them makes the render noticeably faster.
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = DESKTOP_CHROME_UA
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (done) return
                    Log.d(TAG, "PAGE_FINISHED shortcode=$shortcode")
                    // The probe chain may already be running (it starts at load
                    // time); this only makes sure a slow first probe does not
                    // outlive the load event.
                    scheduleProbe(0L)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // Main-frame failures are terminal for this candidate.
                    if (request?.isForMainFrame == true && !done) {
                        Log.w(TAG, "MAIN_FRAME_ERROR shortcode=$shortcode code=${error?.errorCode}")
                        urlIndex++
                        if (urlIndex < urls.size) loadCandidate() else finish(null)
                    }
                }
            }

            cont.invokeOnCancellation {
                handler.post { finish(null) }
            }
            loadCandidate()
        }

    /** Decodes one probe result (rules live in [InstagramEmbedPayload]). */
    internal fun parseProbe(raw: String?): EmbeddedMedia? = InstagramEmbedPayload.parseProbe(raw)

    /**
     * Desktop Chrome UA: Meta serves the full embed to a desktop-class browser,
     * and it is the same identity the app's YouTube WebView already uses.
     */
    private const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}
