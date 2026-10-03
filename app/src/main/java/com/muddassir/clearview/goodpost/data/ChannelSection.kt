package com.muddassir.clearview.goodpost.data

/**
 * A group of channels on the discovery screen (§7).
 *
 * A section is a NAMED QUERY, not a stored list: it is the same public channel
 * catalogue read with one `sort` and, for most sections, one `category`. Nothing
 * is curated by hand and nothing is duplicated — a channel appears under its own
 * category because that is what it was created as, which is what keeps the
 * sections from drifting out of step with the catalogue.
 *
 * The screen reads in three parts. "Explore channels" is TRENDING — the whole
 * catalogue by follower count, which is what a reader wants first. The category
 * sections are the catalogue split up. "More channels" is the remainder: the
 * channels that carry no category and therefore appear nowhere above.
 *
 * Every section is drawn even when it is empty, because the empty ones are still
 * the shape of the product.
 *
 * The order is what the "See all" screen opens with, so a section cannot open
 * showing rows in a different order from the few it previewed.
 */
enum class ChannelSection(
    /** The `sort` the API is asked for. */
    val sort: String,
    /** The `category` filter, or null for a section that spans the catalogue. */
    val category: String?
) {
    /**
     * The most-followed channels anywhere — the screen's first section.
     *
     * `popular` rather than `active`: this is the one section that is not a
     * category, so its job is to answer "what is worth following" and the answer
     * to that is what other readers have already decided. "Most recently
     * published" would surface whichever channel happened to post a minute ago,
     * which is a different and much less useful question on the screen a reader
     * opens to find something new.
     */
    Explore("popular", null),

    Entertainment("active", "entertainment"),
    Sports("active", "sports"),

    /** News and current affairs. */
    News("active", "news"),

    Lifestyle("active", "lifestyle"),
    People("active", "people"),
    Business("active", "business"),
    Organizations("active", "organization"),

    /**
     * Everything that does not fit a category — the last section.
     *
     * Not "newest", which is what this used to be: a channel with no category is
     * a real state (one is created before anybody files it), and it is the one
     * group of channels no other section on this screen can show. A newest-first
     * section, by contrast, showed channels that had already appeared above it.
     */
    More("active", UNCATEGORISED)
}

/**
 * The `category` value the API reads as "has no category".
 *
 * Top-level rather than in a companion, because an enum entry's arguments are
 * evaluated before its companion exists — a companion constant referenced from an
 * entry does not compile. Mirrors `UNCATEGORISED` on the server; see
 * `listPublicChannels`.
 */
private const val UNCATEGORISED = "none"

/**
 * A sort chip on the See-all screen (§7).
 *
 * Four orders over ONE list, which is what the chips are: tapping one re-sorts
 * the same channels rather than moving to another screen. Every value maps to a
 * DISTINCT server order — a chip that re-sorted into the order already showing
 * would be a button that appears to do nothing, so the four offered are the four
 * orders the API actually has.
 *
 * The brief named an "Explore" chip and a "Most active" chip; "Explore" is
 * called Trending here because that is the question it answers and the section it
 * opens from is the trending one. `A–Z` is the fourth because it is the only
 * order the server has that the other three do not already cover.
 */
enum class ChannelSortChip(val sort: String) {
    /** What other readers have followed most — the default, and "trending". */
    Trending("popular"),

    /** Most recently active first. */
    Active("active"),

    New("new"),
    Name("name");

    companion object {
        /** The chip to show selected for a sort the API returned. */
        fun fromSort(sort: String): ChannelSortChip =
            entries.firstOrNull { it.sort == sort } ?: Trending
    }
}
