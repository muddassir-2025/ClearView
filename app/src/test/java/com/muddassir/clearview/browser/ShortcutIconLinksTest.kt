package com.muddassir.clearview.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A shortcut tile must be able to reach the site's own icon, not a letter. */
class ShortcutIconLinksTest {

    @Test
    fun declaredTouchIconComesFirst() {
        val html = """
            <html><head>
            <link rel="shortcut icon" href="/favicon.ico">
            <link rel="apple-touch-icon" sizes="180x180" href="/touch.png">
            </head></html>
        """.trimIndent()
        val icons = ShortcutIconLinks.candidates(html, "www.example.com")
        assertEquals("https://www.example.com/touch.png", icons.first())
        assertTrue(icons.contains("https://www.example.com/favicon.ico"))
    }

    @Test
    fun relativeAndProtocolRelativeHrefsAreResolved() {
        val html = """
            <link rel="icon" href="assets/logo.png">
            <link rel="icon" href="//cdn.example.net/mark.png">
        """.trimIndent()
        val icons = ShortcutIconLinks.candidates(html, "example.com")
        assertTrue(icons.contains("https://example.com/assets/logo.png"))
        assertTrue(icons.contains("https://cdn.example.net/mark.png"))
    }

    @Test
    fun unquotedAttributesAndSingleQuotesAreRead() {
        val html = "<link rel=icon href='/one.png'><link rel='icon' href=\"/two.png\">"
        val icons = ShortcutIconLinks.candidates(html, "example.com")
        assertTrue(icons.contains("https://example.com/one.png"))
        assertTrue(icons.contains("https://example.com/two.png"))
    }

    @Test
    fun svgAndDataIconsAreSkipped() {
        // Android's decoder cannot read either, so a tile must not wait on them.
        val html = """
            <link rel="icon" href="/logo.svg">
            <link rel="icon" href="data:image/png;base64,AAAA">
            <link rel="mask-icon" href="/mask.svg">
        """.trimIndent()
        val icons = ShortcutIconLinks.candidates(html, "example.com")
        assertTrue(icons.none { it.contains("logo.svg") || it.contains("mask.svg") })
        assertTrue(icons.none { it.startsWith("data:") })
    }

    @Test
    fun aSiteWithoutIconsStillGetsTheFallbacks() {
        val icons = ShortcutIconLinks.candidates("<html><head></head></html>", "noicon.example")
        assertEquals(
            listOf(
                "https://noicon.example/favicon.ico",
                "https://www.google.com/s2/favicons?sz=128&domain=noicon.example"
            ),
            icons
        )
    }

    @Test
    fun aFullUrlIsAcceptedAsTheHost() {
        val icons = ShortcutIconLinks.candidates(null, "https://example.com/some/page")
        assertEquals("https://example.com/favicon.ico", icons.first())
    }
}
