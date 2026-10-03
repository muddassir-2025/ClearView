package com.muddassir.clearview.brainrot

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
 * The client for the global Brain Rot repository.
 *
 * Mirrors the project's existing HTTP pattern (`HttpURLConnection` + coroutines
 * + `org.json`, no new dependency) so the accessibility hot path never waits on
 * a library it does not already have.
 *
 * ## Every call is best-effort
 *
 * The rule this class exists under: **a dead backend must never weaken or break
 * local protection.** The keyword list and the blocked channels live on the
 * device, so a failed fetch leaves the previous cached rules in place and a
 * failed report or suggestion is simply dropped. Nothing here can throw into a
 * caller, and nothing here blocks a scan.
 *
 * ## The base URL
 *
 * Taken from `BuildConfig.GOODPOST_BASE_URL` — the same deployed service the
 * Good Post tab uses, set once in `gradle.properties`. This replaces the
 * hard-coded `trycloudflare.com` tunnel the old client pointed at, which was a
 * development tunnel that had stopped answering and whose endpoints
 * (`/api/rules`, `/api/channels/check`) never existed on the deployed API at
 * all — so that integration silently did nothing for every user.
 */
class BrainRotClient(context: Context) {

    companion object {
        private const val TAG = "BrainRotClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_RESPONSE_BYTES = 1_000_000

        /**
         * The deployed API, or empty when this build has none configured.
         *
         * An empty value is a supported state rather than an error: the app then
         * runs entirely on its local rules, which is exactly the product before
         * the global repository existed.
         */
        val baseUrl: String
            get() = BuildConfig.GOODPOST_BASE_URL.trim().trimEnd('/')
    }

    private val appContext = context.applicationContext

    /** One rule from the global repository. */
    data class RuleKeyword(val keyword: String, val reason: String?, val reports: Int)

    data class RuleChannel(val handle: String, val name: String?, val reason: String?, val reports: Int)

    data class Rules(
        val version: String,
        val keywords: List<RuleKeyword>,
        val channels: List<RuleChannel>
    )

    /** True when this build can reach a backend at all. */
    fun isConfigured(): Boolean = baseUrl.isNotEmpty()

    /**
     * Fetch the active global rule set.
     *
     * Returns null on any failure — no backend configured, no network, a bad
     * status, unparseable JSON — and the caller keeps whatever it cached. A
     * partial response is never returned: a list that lost half its rules would
     * silently unblock content the previous fetch had covered.
     */
    suspend fun fetchRules(): Rules? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        val json = request("GET", "/api/v1/brainrot/rules") ?: return@withContext null
        try {
            val keywords = json.optJSONArray("keywords")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val keyword = o.optString("keyword").trim()
                    if (keyword.isEmpty()) return@mapNotNull null
                    RuleKeyword(
                        keyword = keyword,
                        reason = o.optString("reason").takeIf { it.isNotBlank() },
                        reports = o.optInt("reports", 0)
                    )
                }
            }.orEmpty()

            val channels = json.optJSONArray("channels")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val handle = o.optString("handle").trim()
                    if (handle.isEmpty()) return@mapNotNull null
                    RuleChannel(
                        handle = handle,
                        name = o.optString("name").takeIf { it.isNotBlank() },
                        reason = o.optString("reason").takeIf { it.isNotBlank() },
                        reports = o.optInt("reports", 0)
                    )
                }
            }.orEmpty()

            Rules(
                version = json.optString("version").ifBlank { "0" },
                keywords = keywords,
                channels = channels
            )
        } catch (e: Exception) {
            Log.e(TAG, "rules parse error: ${e.message}")
            null
        }
    }

    /**
     * Suggest a keyword or channel for the GLOBAL repository.
     *
     * Returns true when the server accepted it. The suggestion is inert until an
     * administrator approves it, so a success here means "queued for review",
     * not "now blocked everywhere" — the UI must word it that way.
     *
     * [source] and [displayName] travel with the request so a reviewer can tell a
     * decision made inside YouTube ("Not interested") from one typed here, and so
     * a channel is listed under the name the user recognised it by.
     */
    suspend fun submitSuggestion(
        kind: String,
        value: String,
        note: String? = null,
        source: String = "unknown",
        displayName: String? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext false
            postJson(
                "/api/v1/brainrot/suggestions",
                JSONObject().apply {
                    put("kind", kind)
                    put("value", value)
                    put("anonymousId", AnonymousId.get(appContext))
                    put("source", source)
                    note?.let { put("note", it) }
                    displayName?.let { put("displayName", it) }
                }
            )
        }

    /** One of this device's own submissions, with its current status. */
    data class SubmissionStatus(
        val id: String,
        val kind: String,
        val value: String,
        val displayName: String?,
        val status: String,
        val source: String,
        val createdAt: String?
    )

    /**
     * The submissions THIS device has made.
     *
     * The anonymous id is the only key: it is generated on the phone and nothing
     * else can read this list, which is what lets the app show somebody the fate
     * of their own request without an account. Returns null on any failure so the
     * caller keeps whatever it had — an empty list would read as "you have
     * requested nothing", which is a statement about the user rather than about
     * a failed request.
     */
    suspend fun fetchSubmissions(): List<SubmissionStatus>? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        val path = "/api/v1/brainrot/submissions?anonymousId=" + AnonymousId.get(appContext)
        val json = request("GET", path) ?: return@withContext null
        try {
            val arr = json.optJSONArray("submissions") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val value = o.optString("value")
                if (id.isBlank() || value.isBlank()) return@mapNotNull null
                SubmissionStatus(
                    id = id,
                    kind = if (o.optString("kind") == "channel") "channel" else "keyword",
                    value = value,
                    displayName = o.optString("displayName").takeIf { it.isNotBlank() },
                    status = o.optString("status").ifBlank { "pending" },
                    source = o.optString("source").ifBlank { "unknown" },
                    createdAt = o.optString("createdAt").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "submissions parse error: ${e.message}")
            null
        }
    }

    /**
     * Report a keyword or channel. Returns the resulting report count, or null
     * on failure — never a zero, which would be indistinguishable from "no one
     * has reported this" and would let a failed call look like a real answer.
     */
    suspend fun report(kind: String, value: String, detail: String? = null): Int? =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext null
            val body = JSONObject().apply {
                put("kind", kind)
                put("value", value)
                put("anonymousId", AnonymousId.get(appContext))
                detail?.let { put("detail", it) }
            }
            val json = postJsonReturning("/api/v1/brainrot/reports", body) ?: return@withContext null
            json.optInt("reports", 0)
        }

    // ── Internals ────────────────────────────────────────────────────

    private fun request(method: String, path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        try {
            conn = open(path)
            conn.requestMethod = method
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .take(MAX_RESPONSE_BYTES)
            return JSONObject(text)
        } catch (e: Exception) {
            Log.d(TAG, "$method $path failed: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun postJson(path: String, body: JSONObject): Boolean =
        postJsonReturning(path, body) != null

    private fun postJsonReturning(path: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        try {
            conn = open(path)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "POST $path answered $code")
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            Log.d(TAG, "POST $path failed: ${e.message}")
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
}
