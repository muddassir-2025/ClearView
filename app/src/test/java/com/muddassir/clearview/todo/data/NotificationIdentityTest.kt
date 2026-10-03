package com.muddassir.clearview.todo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The identity of a reminder's buttons and its notification card.
 *
 * These are the two keys that decide whether tapping Complete on a reminder
 * answers for THAT reminder or silently for another one. Both were wrong once:
 *  - the Complete/Dismiss PendingIntent was keyed on (action, todoId) only, so
 *    scheduling a later occurrence rewrote an older notification's extras
 *    (`FLAG_UPDATE_CURRENT`) and Complete answered for the wrong day — the
 *    "Complete does nothing" report; and
 *  - the notification id folded the epoch day through `% 31`, so two
 *    occurrences exactly 31 days apart aliased onto one card.
 */
class NotificationIdentityTest {

    private val todoId = "11111111-2222-3333-4444-555555555555"

    @Test
    fun `complete actions for different days never share a request code`() {
        val day = 20_300L
        val a = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_COMPLETE, todoId, 0, day)
        val b = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_COMPLETE, todoId, 0, day + 1)
        assertNotEquals(a, b)
    }

    @Test
    fun `reminder indices on the same day never share a request code`() {
        val day = 20_300L
        val a = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_COMPLETE, todoId, 0, day)
        val b = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_COMPLETE, todoId, 1, day)
        assertNotEquals(a, b)
    }

    @Test
    fun `complete and dismiss never share a request code`() {
        val a = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_COMPLETE, todoId, 0, 20_300L)
        val b = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_DISMISS, todoId, 0, 20_300L)
        assertNotEquals(a, b)
    }

    @Test
    fun `a request code is stable for the same occurrence`() {
        val a = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_COMPLETE, todoId, 2, 20_305L)
        val b = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_COMPLETE, todoId, 2, 20_305L)
        assertEquals(a, b)
    }

    @Test
    fun `a request code is never negative`() {
        listOf(0L, 1L, 20_300L, -1L).forEach { day ->
            for (index in 0..2) {
                val code = TodoNotifier.actionRequestCode(TodoNotifier.ACTION_COMPLETE, todoId, index, day)
                assertEquals(code, code and 0x7FFFFFFF)
            }
        }
    }

    @Test
    fun `notification ids for days 31 apart do not alias`() {
        val day = 20_300L
        assertNotEquals(TodoNotifier.notificationId(todoId, day), TodoNotifier.notificationId(todoId, day + 31))
    }

    @Test
    fun `notification id is stable and non-negative`() {
        val a = TodoNotifier.notificationId(todoId, 20_300L)
        val b = TodoNotifier.notificationId(todoId, 20_300L)
        assertEquals(a, b)
        assertEquals(a, a and 0x7FFFFFFF)
    }
}
