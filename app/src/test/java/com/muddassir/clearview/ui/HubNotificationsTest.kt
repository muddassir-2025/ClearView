package com.muddassir.clearview.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two rules the notification centre's list has to obey, tested without Android.
 *
 * Both are one-liners, and both are the kind of one-liner that is easy to get
 * subtly wrong in a way no compiler notices: a "newest first" that drops the
 * entries with no timestamp, or a badge that counts a reminder as news. The
 * runner-up risk is drift — the bell's number and the sheet's list are drawn from
 * the same entries and must therefore be computed by the same rules, which is why
 * they live here rather than inline at either call site.
 */
class HubNotificationsTest {

    private fun entry(id: String, at: Long, unread: Boolean, kind: HubNotificationKind) =
        HubNotification(
            id = id,
            kind = kind,
            title = id,
            body = null,
            at = at,
            unread = unread,
            onOpen = {}
        )

    @Test
    fun `newest first, and a standing reminder sorts last`() {
        val media = entry("media", at = 5_000L, unread = true, kind = HubNotificationKind.MEDIA)
        val todo = entry("todo", at = 9_000L, unread = true, kind = HubNotificationKind.TO_DO)
        val quran = entry("quran", at = 0L, unread = false, kind = HubNotificationKind.QURAN)

        val ordered = listOf(media, quran, todo).inNotificationOrder()

        assertEquals(listOf("todo", "media", "quran"), ordered.map { it.id })
    }

    @Test
    fun `the badge counts what is new, not what is listed`() {
        val entries = listOf(
            entry("a", at = 3L, unread = true, kind = HubNotificationKind.MEDIA),
            entry("b", at = 2L, unread = false, kind = HubNotificationKind.QURAN),
            entry("c", at = 1L, unread = true, kind = HubNotificationKind.TO_DO)
        )

        assertEquals(2, entries.unreadNotificationCount())
    }

    @Test
    fun `an empty centre counts nothing`() {
        assertEquals(0, emptyList<HubNotification>().unreadNotificationCount())
    }

    @Test
    fun `every kind has a heading and an icon, so no entry is filed under nothing`() {
        // The sheet groups by kind and skips the ones with no entries; a kind with
        // no icon would be a group heading with a hole in it.
        assertEquals(
            HubNotificationKind.entries.size,
            HubNotificationKind.entries.map { it.icon }.distinct().size
        )
        assertEquals(
            HubNotificationKind.entries.size,
            HubNotificationKind.entries.map { it.section }.distinct().size
        )
    }
}
