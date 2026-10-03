package com.muddassir.clearview.goodpost.data

/**
 * A group of channels on the discovery screen (§7).
 *
 * A section is a NAMED QUERY, not a stored list: it is the same public channel
 * catalogue read with one `sort` and, for the category sections, one `category`.
 * Nothing is curated by hand and nothing is duplicated — a channel that publishes
 * appears under its own category because that is what it was created as, which is
 * what keeps the sections from drifting out of step with the catalogue.
 *
 * The category slugs are the ones the catalogue seeds. A section whose category
 * has no channels yet simply renders nothing, which is a truer answer than
 * hiding it: the reader sees that the category exists and is empty.
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
    /** Channels that are publishing, across every category — the first section. */
    Explore("active", null),

    Entertainment("active", "entertainment"),
    Sports("active", "sports"),

    /** News and current affairs. */
    News("active", "news"),

    Lifestyle("active", "lifestyle"),
    People("active", "people"),
    Business("active", "business"),
    Organizations("active", "organization"),

    /** What has just arrived, newest channel first. */
    More("new", null)
}

/**
 * A sort chip on the See-all screen (§7).
 *
 * Four orders over ONE list, which is what the chips are: tapping one re-sorts
 * the same channels rather than moving to another screen. Every value maps to a
 * DISTINCT server order — a chip that re-sorted into the order already showing
 * would be a button that appears to do nothing, so the four offered are the four
 * orders the API actually has.
 *
 * `A–Z` is here rather than a second name for "most active": the brief named an
 * "Explore" chip and a "Most active" chip, but both are the same order on the
 * server, and two chips that produce the same list is one chip too many. Alphabetical
 * is the order the server does offer that is not already covered, so it is the
 * fourth chip.
 */
enum class ChannelSortChip(val sort: String) {
    /** Most recently active first — the default, and the mixed section's order. */
    Explore("active"),
    Popular("popular"),
    New("new"),
    Name("name");

    companion object {
        /** The chip to show selected for a sort the API returned. */
        fun fromSort(sort: String): ChannelSortChip =
            entries.firstOrNull { it.sort == sort } ?: Explore
    }
}
