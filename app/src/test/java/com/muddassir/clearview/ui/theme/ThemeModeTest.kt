package com.muddassir.clearview.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appearance setting's whole user-visible rule: an explicit choice beats the
 * phone's own light/dark setting, and SYSTEM defers to it. Every tab draws
 * through this, so it is worth pinning down on its own.
 */
class ThemeModeTest {

    @Test
    fun `dark is dark whatever the phone says`() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = true))
    }

    @Test
    fun `light is light whatever the phone says`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = false))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
    }

    @Test
    fun `system follows the phone`() {
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
    }
}
