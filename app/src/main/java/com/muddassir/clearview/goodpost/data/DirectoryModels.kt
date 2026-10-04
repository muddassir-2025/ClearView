package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The channel directory (§ new): a curated list of EXTERNAL channels.
 *
 * A directory row is not a ClearView channel. It cannot post, it has no
 * followers, and nothing about it is stored on this device beyond a copy of the
 * list itself. It is a platform, a handle and the two things worth drawing — a
 * name and a picture — and a tap opens the platform's own page.
 *
 * The whole list arrives as one [DirectorySnapshot] rather than one call per
 * category: the screen wants the list and its shape together, and a tab that
 * fetched itself would flicker as each answer landed. The set is curated, so it
 * stays small enough for that to be the cheap design.
 */

/** One external channel. */
data class DirectoryChannel(
    val id: String,
    /** `youtube`, `instagram` or `x`. The server decides; the app words it. */
    val platform: String,
    /** The handle as stored: no leading `@`, lowercased. */
    val handle: String,
    val name: String?,
    val iconUrl: String?,
    /** The platform page a tap opens, built by the server. */
    val url: String,
    val categoryId: String?,
    val subcategoryId: String?,
    val sort: Int
) {
    /**
     * What a card shows when the platform gave us no name.
     *
     * The handle is identity, so a channel with no name is a working row rather
     * than a broken one — it just shows the thing a person would type to find it.
     */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "@$handle"
}

/** A grouping inside one category. */
data class DirectorySubcategory(
    val id: String,
    val categoryId: String,
    val name: String,
    val slug: String,
    val sort: Int,
    val channels: List<DirectoryChannel>
)

/** A top-level grouping. */
data class DirectoryCategory(
    val id: String,
    val name: String,
    val slug: String,
    val sort: Int,
    val subcategories: List<DirectorySubcategory>,
    /** Channels filed directly under the category, with no subcategory. */
    val channels: List<DirectoryChannel>
) {
    /** Every channel under this category, subcategories included. */
    val channelCount: Int get() = channels.size + subcategories.sumOf { it.channels.size }
}

/**
 * The whole directory, as one object.
 *
 * [unfiled] is the channels whose category was never chosen, or was deleted
 * afterwards. It is not an error state — the read still returns them — and the
 * screen shows them under an "All" tab alongside the categorised ones.
 */
data class DirectorySnapshot(
    val categories: List<DirectoryCategory> = emptyList(),
    val unfiled: List<DirectoryChannel> = emptyList()
) {
    val isEmpty: Boolean get() = categories.isEmpty() && unfiled.isEmpty()

    /**
     * Every channel, categorised or not, in server order.
     *
     * What the "All" tab draws. Built here rather than in a screen so the one
     * idea of "the whole list" is defined once.
     */
    val allChannels: List<DirectoryChannel> get() = buildList {
        categories.forEach { category ->
            addAll(category.channels)
            category.subcategories.forEach { addAll(it.channels) }
        }
        addAll(unfiled)
    }
}

/**
 * Parsing and serialising the directory.
 *
 * The wire shape and the cache shape are deliberately the same object, so one
 * parser serves the live read and a stored copy — there is no second shape to
 * drift out of step.
 */
internal object DirectoryCodec {

    /** Parse the `{ categories: [...], unfiled: [...] }` body in full. */
    fun snapshot(body: JSONObject): DirectorySnapshot {
        val categories = ArrayList<DirectoryCategory>()
        val categoryArray = body.optJSONArray("categories")
        if (categoryArray != null) {
            for (i in 0 until categoryArray.length()) {
                val row = categoryArray.optJSONObject(i) ?: continue
                category(row)?.let(categories::add)
            }
        }
        return DirectorySnapshot(
            categories = categories,
            unfiled = channelArray(body.optJSONArray("unfiled"))
        )
    }

    fun category(json: JSONObject): DirectoryCategory? {
        val id = json.optString("id")
        val name = json.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        return DirectoryCategory(
            id = id,
            name = name,
            slug = json.optString("slug"),
            sort = json.optInt("sort", 0),
            subcategories = subcategoryArray(json.optJSONArray("subcategories")),
            channels = channelArray(json.optJSONArray("channels"))
        )
    }

    fun subcategory(json: JSONObject): DirectorySubcategory? {
        val id = json.optString("id")
        val name = json.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        return DirectorySubcategory(
            id = id,
            categoryId = json.optString("categoryId"),
            name = name,
            slug = json.optString("slug"),
            sort = json.optInt("sort", 0),
            channels = channelArray(json.optJSONArray("channels"))
        )
    }

    /**
     * One channel.
     *
     * `id`, `platform`, `handle` and `url` are required: a row missing any of
     * them cannot be drawn, copied or opened, so dropping it is better than
     * showing a card whose tap goes nowhere.
     */
    fun channel(json: JSONObject): DirectoryChannel? {
        val id = json.optString("id")
        val platform = json.optString("platform")
        val handle = json.optString("handle")
        val url = json.optString("url")
        if (id.isBlank() || platform.isBlank() || handle.isBlank() || url.isBlank()) return null
        return DirectoryChannel(
            id = id,
            platform = platform,
            handle = handle,
            name = json.nullableString("name"),
            iconUrl = json.nullableString("iconUrl"),
            url = url,
            categoryId = json.nullableString("categoryId"),
            subcategoryId = json.nullableString("subcategoryId"),
            sort = json.optInt("sort", 0)
        )
    }

    private fun channelArray(items: JSONArray?): List<DirectoryChannel> {
        if (items == null) return emptyList()
        val parsed = ArrayList<DirectoryChannel>(items.length())
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { channel(it)?.let(parsed::add) }
        }
        return parsed
    }

    private fun subcategoryArray(items: JSONArray?): List<DirectorySubcategory> {
        if (items == null) return emptyList()
        val parsed = ArrayList<DirectorySubcategory>(items.length())
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { subcategory(it)?.let(parsed::add) }
        }
        return parsed
    }

    // ── Cache (§27) ──────────────────────────────────────────────────────

    /**
     * Serialise the whole snapshot for the offline cache.
     *
     * Kept whole — categories, subcategories and channels — because the screen
     * draws a nested shape and a stored copy that had flattened it would have to
     * be reassembled from a shape that no longer knows what belonged to what.
     */
    fun encode(snapshot: DirectorySnapshot): String =
        JSONObject().apply {
            put("categories", JSONArray().apply {
                snapshot.categories.forEach { category ->
                    put(JSONObject().apply {
                        put("id", category.id)
                        put("name", category.name)
                        put("slug", category.slug)
                        put("sort", category.sort)
                        put("channels", channelArrayOf(category.channels))
                        put("subcategories", JSONArray().apply {
                            category.subcategories.forEach { sub ->
                                put(JSONObject().apply {
                                    put("id", sub.id)
                                    put("categoryId", sub.categoryId)
                                    put("name", sub.name)
                                    put("slug", sub.slug)
                                    put("sort", sub.sort)
                                    put("channels", channelArrayOf(sub.channels))
                                })
                            }
                        })
                    })
                }
            })
            put("unfiled", channelArrayOf(snapshot.unfiled))
        }.toString()

    fun decode(raw: String?): DirectorySnapshot? {
        if (raw.isNullOrBlank()) return null
        return try {
            snapshot(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    private fun channelArrayOf(channels: List<DirectoryChannel>): JSONArray =
        JSONArray().apply {
            channels.forEach { channel ->
                put(JSONObject().apply {
                    put("id", channel.id)
                    put("platform", channel.platform)
                    put("handle", channel.handle)
                    put("name", channel.name ?: JSONObject.NULL)
                    put("iconUrl", channel.iconUrl ?: JSONObject.NULL)
                    put("url", channel.url)
                    put("categoryId", channel.categoryId ?: JSONObject.NULL)
                    put("subcategoryId", channel.subcategoryId ?: JSONObject.NULL)
                    put("sort", channel.sort)
                })
            }
        }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
