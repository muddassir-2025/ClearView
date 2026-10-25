package com.muddassir.clearview.browser

/**
 * The icon a website declares for itself, in the order a tile should try it.
 *
 * A shortcut tile has to show the site's OWN mark. Android cannot decode the
 * `favicon.ico` most sites serve (it is an ICO container), so asking for
 * `/favicon.ico` alone left the tile on its letter — the "no logo" case. A
 * browser instead reads the `<link rel="apple-touch-icon">` / `<link rel="icon">`
 * the page declares, which is a PNG the platform can decode and is usually the
 * full-size mark rather than the 16px tab icon.
 *
 * Pure string handling: no Android, no network, so it is unit tested directly.
 */
object ShortcutIconLinks {

    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex(
        "([A-Za-z:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
    )

    /**
     * Icon URLs for [host], best first: the icons the page declares (largest
     * first), then `/favicon.ico`, then the favicon service.
     *
     * Every entry is an absolute https URL. `data:` and SVG icons are skipped:
     * `data:` cannot be fetched and Android's decoder does not read SVG, so
     * neither can become a tile image.
     */
    fun candidates(html: String?, host: String): List<String> {
        val cleanHost = host.trim().removePrefix("http://").removePrefix("https://")
            .trimEnd('/').substringBefore('/')
        if (cleanHost.isEmpty()) return emptyList()
        val root = "https://$cleanHost"

        val declared = declaredIcons(html)
            .mapNotNull { icon -> resolve(icon.href, root)?.let { it to icon.rank } }
            .sortedBy { it.second }
            .map { it.first }

        return (declared + "$root/favicon.ico" +
            "https://www.google.com/s2/favicons?sz=128&domain=$cleanHost")
            .distinct()
    }

    private data class DeclaredIcon(val href: String, val rank: Int)

    private fun declaredIcons(html: String?): List<DeclaredIcon> {
        if (html.isNullOrBlank()) return emptyList()
        val icons = mutableListOf<DeclaredIcon>()
        for (tag in LINK_TAG.findAll(html)) {
            val attributes = mutableMapOf<String, String>()
            for (match in ATTRIBUTE.findAll(tag.value)) {
                val name = match.groupValues[1].lowercase()
                val value = match.groupValues[2]
                    .ifEmpty { match.groupValues[3] }
                    .ifEmpty { match.groupValues[4] }
                if (name.isNotEmpty() && value.isNotEmpty()) attributes[name] = value
            }
            val rel = attributes["rel"]?.lowercase().orEmpty()
            // `mask-icon` is a monochrome SVG for the browser chrome, not a mark.
            if (!rel.contains("icon") || rel.contains("mask-icon")) continue
            val href = attributes["href"]?.trim().orEmpty()
            if (href.isEmpty() || href.startsWith("data:")) continue
            if (href.substringBefore('?').endsWith(".svg", ignoreCase = true)) continue
            icons += DeclaredIcon(href, rankOf(rel, attributes["sizes"]))
        }
        return icons
    }

    /** Apple's touch icon is the highest-resolution mark a site ships. */
    private fun rankOf(rel: String, sizes: String?): Int {
        if (rel.contains("apple-touch-icon")) return 0
        val largest = sizes.orEmpty()
            .split(' ')
            .mapNotNull { size -> size.trim().substringBefore('x').toIntOrNull() }
            .maxOrNull() ?: 0
        return when {
            largest >= 96 -> 0
            largest >= 32 -> 1
            else -> 2
        }
    }

    private fun resolve(href: String, root: String): String? {
        val value = href.trim().replace("&amp;", "&")
        if (value.isEmpty()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("https://") -> value
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            value.startsWith("/") -> root + value
            else -> "$root/" + value.removePrefix("./")
        }
    }
}
