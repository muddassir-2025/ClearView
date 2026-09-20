package com.muddassir.clearview.media.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Resolves a YouTube channel reference to its stable UC… channel id. */
object ChannelIdResolver {
    private val BARE_ID = Regex("^UC[0-9A-Za-z_-]{22}$")
    private val CHANNEL_PATH = Regex(
        "(?:youtube\\.com|youtu\\.be)/channel/(UC[0-9A-Za-z_-]{22})",
        RegexOption.IGNORE_CASE
    )
    private val HANDLE = Regex("@([^/?#\\s]+)")
    private val META_CHANNEL_ID = Regex(
        "<meta\\s+[^>]*itemprop=[\\\"']channelId[\\\"'][^>]*content=[\\\"'](UC[0-9A-Za-z_-]{22})[\\\"']",
        RegexOption.IGNORE_CASE
    )
    private val META_IDENTIFIER = Regex(
        "<meta\\s+[^>]*itemprop=[\\\"']identifier[\\\"'][^>]*content=[\\\"'](UC[0-9A-Za-z_-]{22})[\\\"']",
        RegexOption.IGNORE_CASE
    )
    private val CANONICAL_CHANNEL = Regex(
        "<link\\s+[^>]*rel=[\\\"']canonical[\\\"'][^>]*href=[\\\"'][^\\\"']*/channel/(UC[0-9A-Za-z_-]{22})",
        RegexOption.IGNORE_CASE
    )
    private val CANONICAL_HANDLE = Regex(
        "<link\\s+[^>]*rel=[\\\"']canonical[\\\"'][^>]*href=[\\\"'][^\\\"']*/@([^/\\\"'?]+)",
        RegexOption.IGNORE_CASE
    )

    fun extractChannelId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        BARE_ID.find(trimmed)?.let { return it.value }
        CHANNEL_PATH.find(trimmed)?.let { return it.groupValues[1] }
        HANDLE.find(trimmed)?.let { return it.value }
        return null
    }

    fun encodeHandlePath(handle: String): String =
        if ('%' in handle) handle
        else "@" + URLEncoder.encode(handle.removePrefix("@"), "UTF-8").replace("+", "%20")

    suspend fun resolve(input: String): String? {
        val extracted = extractChannelId(input) ?: return null
        return if (BARE_ID.matches(extracted)) extracted else resolveHandle(extracted)
    }

    private suspend fun resolveHandle(handle: String): String? = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/${encodeHandlePath(handle)}"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // Never use the first channelId in the document: recommendations and
            // related channels are embedded in the same page. Page-level metadata
            // belongs to the requested channel.
            extractResolvedChannelId(html, handle)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Pure page-identity extraction, kept visible for regression tests. */
    internal fun extractResolvedChannelId(html: String, requestedHandle: String): String? {
        val canonicalId = CANONICAL_CHANNEL.find(html)?.groupValues?.get(1)
        val metaId = META_CHANNEL_ID.find(html)?.groupValues?.get(1)
            ?: META_IDENTIFIER.find(html)?.groupValues?.get(1)
        val canonicalHandle = CANONICAL_HANDLE.find(html)?.groupValues?.get(1)
        if (canonicalHandle != null &&
            !requestedHandle.removePrefix("@").equals(canonicalHandle, ignoreCase = true)
        ) return null
        return canonicalId ?: metaId ?: channelIdNearCanonicalHandle(html, canonicalHandle)
    }

    /** Fallback for page variants that expose only canonicalBaseUrl + channelId. */
    private fun channelIdNearCanonicalHandle(html: String, canonicalHandle: String?): String? {
        if (canonicalHandle.isNullOrBlank()) return null
        val marker = Regex(
            "\\\"canonicalBaseUrl\\\"\\s*:\\s*\\\"/@" +
                Regex.escape(canonicalHandle) + "\\\""
        ).find(html) ?: return null
        val end = minOf(html.length, marker.range.last + 4000)
        return Regex("\\\"channelId\\\"\\s*:\\s*\\\"(UC[0-9A-Za-z_-]{22})\\\"")
            .find(html.substring(marker.range.first, end))
            ?.groupValues?.get(1)
    }
}
