package com.muddassir.clearview.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A shortcut is a website, never an arbitrary search. */
class BrowserShortcutTest {

    @Test
    fun bareHostGetsHttps() {
        assertEquals("https://example.com", BrowserShortcutInput.normalize("example.com"))
        assertEquals(
            "https://www.instagram.com/someone",
            BrowserShortcutInput.normalize("www.instagram.com/someone")
        )
    }

    @Test
    fun explicitWebUrlIsKept() {
        assertEquals(
            "https://example.com/path?q=1",
            BrowserShortcutInput.normalize("https://example.com/path?q=1")
        )
        assertEquals(
            "http://example.com/",
            BrowserShortcutInput.normalize("http://example.com/")
        )
    }

    @Test
    fun ordinaryTextIsNotAShortcut() {
        assertNull(BrowserShortcutInput.normalize("how to cook rice"))
        assertNull(BrowserShortcutInput.normalize(""))
        assertNull(BrowserShortcutInput.normalize(null))
        assertNull(BrowserShortcutInput.normalize("javascript:alert(1)"))
        assertNull(BrowserShortcutInput.normalize("file:///sdcard/secret"))
    }

    @Test
    fun nameFallsBackToTheDomain() {
        assertEquals("Example", BrowserShortcutInput.nameFor("https://example.com"))
        assertEquals("Youtube", BrowserShortcutInput.nameFor("https://www.youtube.com/"))
        assertEquals("Shortcut", BrowserShortcutInput.nameFor("not a url"))
    }
}
