package com.muddassir.clearview.backend

import android.content.Context
import android.util.Log
import com.muddassir.clearview.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal HTTP client for the shared ClearView backend, used by the channel
 * blocker. Mirrors the project's existing pattern (HttpURLConnection +
 * coroutines + org.json) so no new dependency is introduced, and every call is
 * best-effort: each returns null on any failure and the caller falls back to
 * local protection.
 *
 * ## What changed, and why it matters
 *
 * This client used to point at a hard-coded `trycloudflare.com` development
 * tunnel and call `/api/rules`, `/api/channels/check` and `/api/blocked-channels`.
 * **None of those endpoints existed on the deployed API**, and the tunnel had
 * long stopped answering, so every call failed and the whole "global channel
 * blocking" feature silently did nothing for every user — the failure was
 * invisible because a dead backend is by design a no-op.
 *
 * It now talks to the real service (`BuildConfig.GOODPOST_BASE_URL`, the same
 * deployed host the Good Post tab uses) on the endpoints that actually exist:
 *
 *   * `GET /api/v1/brainrot/rules` — the whole active global rule set, keywords
 *     and channels, in one call.
 *
 * There is deliberately no channel-resolution call any more. The global channel
 * list is small and arrives whole, so checking a channel is a lookup in the
 * cached list rather than a network round trip on the accessibility hot path.
 * That is both faster and more reliable: a check can never fail because the
 * network was momentarily unavailable, only because the list is stale — and a
 * stale list still blocks everything it blocked last time.
 */
class ClearViewBackendClient(private val context: Context) {

    companion object {
        private const val TAG = "ClearViewBackend"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_RESPONSE_BYTES = 1_000_000

        /**
         * The deployed API. Empty when this build has none configured, which is
         * a supported state: the app then runs entirely on its local rules.
         */
        @Volatile
        var baseUrl: String = ""
            get() = field.ifEmpty { BuildConfig.GOODPOST_BASE_URL.trim().trimEnd('/') }
    }

    data class Rules(
        val keywords: List<String>,
        val domains: List<String>,
        val patterns: List<String>,
        val channels: List<BlockedChannel>,
        /** The server's fingerprint, for the caller to skip re-parsing. */
        val version: String = ""
    )

    data class BlockedChannel(
        val channelId: String?,
        val channelHandle: String?,
        val channelName: String?
    )

    /**
     * GET /api/v1/brainrot/rules — the active global rule set.
     *
     * Returns null on ANY failure so the caller keeps its cache. A partial
     * response is never returned: a list that lost half its entries would
     * silently unblock content the previous fetch had covered.
     */
    suspend fun getRules(): Rules? = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) return@withContext null
        val json = request("GET", "/api/v1/brainrot/rules") ?: return@withContext null
        try {
            Rules(
                keywords = json.optJSONArray("keywords")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("keyword")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }.orEmpty(),
                // The global repository has no domains or patterns yet; they are
                // carried as empty lists rather than removed, so the shape the
                // caller already expects does not change.
                domains = emptyList(),
                patterns = emptyList(),
                channels = json.optJSONArray("channels")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val handle = o.optString("handle").trim().takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        BlockedChannel(
                            // The global list is keyed by handle, which is the
                            // identity that survives a channel rename. There is
                            // no channelId here because the repository does not
                            // need one to block.
                            channelId = null,
                            channelHandle = handle,
                            channelName = o.optString("name").takeIf { it.isNotBlank() }
                        )
                    }
                }.orEmpty(),
                version = json.optString("version")
            )
        } catch (e: Exception) {
            Log.e(TAG, "getRules parse error: ${e.message}")
            null
        }
    }

    // ── Internals ─────────────────────────────────────────────────

    private fun request(method: String, path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        try {
            conn = open(path)
            conn.requestMethod = method
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .take(MAX_RESPONSE_BYTES)
            return JSONObject(body)
        } catch (e: Exception) {
            Log.d(TAG, "request $method $path failed: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ClearView-Android")
        }

    @Suppress("unused")
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    @Suppress("unused")
    private fun jsonArrayToStrings(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
}
