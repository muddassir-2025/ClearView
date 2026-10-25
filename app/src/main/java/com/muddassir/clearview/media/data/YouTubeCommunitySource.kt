package com.muddassir.clearview.media.data

import com.muddassir.clearview.media.model.MediaVideo
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a YouTube channel's COMMUNITY posts.
 *
 * The Data API is not used because it does not expose community posts at all;
 * the channel's own Posts tab is the source, and [YouTubeCommunityParser] turns
 * its server-rendered data into posts. The page is requested with a DESKTOP user
 * agent on purpose: the mobile page serves a reduced shell whose initial data
 * does not include the post list.
 *
 * Best-effort by design. A channel with no community tab, a bot wall, a
 * consent interstitial or no connection all end as an empty result rather than
 * an error the feed would have to explain: posts are an addition to the feed,
 * and a channel that only publishes videos must keep working exactly as before.
 */
object YouTubeCommunitySource {

    /** When this channel's posts could not be fetched at all (vs. just none). */
    data class Result(val posts: List<MediaVideo>, val ok: Boolean)

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    /**
     * The tabs a channel's posts live on, newest URL first.
     *
     * `/community` was the tab's address for years, but YouTube moved posts to
     * `/posts` and retired the old tab: for a channel that still posts, the old
     * URL now answers 200 with "This Community isn't available" and no posts at
     * all, while `/posts` on the SAME channel id returns them. Text that a
     * channel has no posts must therefore come from the new tab, which is why
     * it is asked first and the legacy path only backs it up.
     */
    private val POST_PATHS = listOf("posts", "community")

    /**
     * The newest community posts of [channelId], or an empty result when the
     * channel has none / the page cannot be read.
     */
    fun fetch(channelId: String, channelName: String): Result {
        if (channelId.isBlank()) return Result(emptyList(), ok = false)
        for (path in POST_PATHS) {
            val result = fetchTab(channelId, channelName, path)
            // A readable page is the whole truth: it either has the posts or the
            // channel has none. Trying the next path could only re-read the same
            // answer.
            if (result.ok) return result
        }
        return Result(emptyList(), ok = false)
    }

    /** One tab of the channel, read the same way regardless of its address. */
    private fun fetchTab(channelId: String, channelName: String, path: String): Result {
        val url = "https://www.youtube.com/channel/$channelId/$path"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return Result(emptyList(), ok = false)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // A 200 is not proof of a channel page: a consent interstitial or a
            // bot wall also answers 200. Only a page that actually carries
            // YouTube's own data counts as a successful (possibly empty) read —
            // otherwise the caller keeps the cached posts instead of wiping a
            // populated rail because of a redirect.
            val realPage = body.contains("ytInitialData")
            Result(
                posts = if (realPage) {
                    YouTubeCommunityParser.parse(body, channelId, channelName)
                } else {
                    emptyList()
                },
                ok = realPage
            )
        } catch (e: Exception) {
            Result(emptyList(), ok = false)
        } finally {
            connection?.disconnect()
        }
    }
}
