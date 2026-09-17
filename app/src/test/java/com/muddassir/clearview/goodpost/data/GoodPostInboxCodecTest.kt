package com.muddassir.clearview.goodpost.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inbox codec (M5, M7): notifications, notices, conversations and messages.
 *
 * Two properties are pinned deliberately, because both are silent when wrong:
 * a message page arrives newest-first and must be REVERSED for display, and a
 * damaged cache entry must read as null rather than as an empty inbox.
 */
class GoodPostInboxCodecTest {

    @Test
    fun `parses a notification and its unread state`() {
        val body = JSONObject().apply {
            put(
                "items",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "n1")
                            put("kind", "channel_post")
                            put("title", "ClearView News")
                            put("body", "A new post")
                            put("channelId", "c1")
                            put("postId", "p1")
                            put("createdAt", "2026-09-16T10:00:00.000Z")
                            put("readAt", JSONObject.NULL)
                        }
                    )
                    put(JSONObject().put("title", "no id"))
                }
            )
        }

        val items = GoodPostInboxCodec.notifications(body)

        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("channel_post", item.kind)
        assertTrue(item.isUnread)
        assertEquals("c1", item.channelId)
        assertEquals("p1", item.postId)
        assertTrue(item.createdAtMs != null)
    }

    @Test
    fun `a notification with a read timestamp is not unread`() {
        val body = JSONObject().apply {
            put(
                "items",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "n1")
                            put("kind", "platform_notice")
                            put("title", "Notice")
                            put("body", "Read already")
                            put("createdAt", "2026-09-16T10:00:00.000Z")
                            put("readAt", "2026-09-16T11:00:00.000Z")
                        }
                    )
                }
            )
        }

        assertFalse(GoodPostInboxCodec.notifications(body).first().isUnread)
    }

    @Test
    fun `parses an official notice`() {
        val body = JSONObject().apply {
            put(
                "items",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "m1")
                            put("subject", "Please review")
                            put("body", "Your channel was suspended.")
                            put("createdAt", "2026-09-16T10:00:00.000Z")
                            put("readAt", JSONObject.NULL)
                            put("aboutChannelId", "c1")
                        }
                    )
                }
            )
        }

        val notice = GoodPostInboxCodec.notices(body).first()

        assertEquals("Please review", notice.subject)
        assertEquals("c1", notice.aboutChannelId)
        assertTrue(notice.isUnread)
    }

    @Test
    fun `parses the follower's public name on a conversation, never an address`() {
        val body = JSONObject().apply {
            put(
                "items",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "conv-1")
                            put("channelId", "c1")
                            put("channelName", "ClearView News")
                            put("channelSlug", "clearview-news")
                            put("lastMessageAt", "2026-09-16T10:00:00.000Z")
                            put("lastMessagePreview", "Is this thing on?")
                            put("blocked", false)
                            put("closed", false)
                            put("unreadCount", 2)
                            put(
                                "follower",
                                JSONObject().apply {
                                    put("id", "u1")
                                    put("displayName", "Muddassir")
                                    // A field the server does not send, present
                                    // only to prove the parser ignores it.
                                    put("email", "someone@example.test")
                                }
                            )
                        }
                    )
                }
            )
        }

        val conversation = GoodPostInboxCodec.conversations(body).first()

        assertEquals("ClearView News", conversation.channelName)
        assertEquals("Muddassir", conversation.followerDisplayName)
        assertEquals(2, conversation.unreadCount)
        assertTrue(conversation.hasUnread)
        assertTrue(conversation.isOpen)
        // Nothing in the model can hold an address, which is a stronger
        // guarantee than remembering not to display one (§38).
        assertFalse(conversation.toString().contains("someone@example.test"))
    }

    @Test
    fun `a conversation without both ids is unusable`() {
        val body = JSONObject().apply {
            put(
                "items",
                JSONArray().apply {
                    put(JSONObject().apply { put("id", "conv-1") })
                    put(JSONObject().apply { put("channelId", "c1") })
                }
            )
        }

        // Without both ids there is nothing to open or reply to, so the row is
        // dropped rather than rendered as a dead entry.
        assertTrue(GoodPostInboxCodec.conversations(body).isEmpty())
    }

    @Test
    fun `a thread is reversed for display`() {
        val body = JSONObject().apply {
            put(
                "items",
                JSONArray().apply {
                    put(message("m2", "second", fromAdmin = true))
                    put(message("m1", "first", fromAdmin = false))
                }
            )
            put("nextCursor", "")
        }

        val messages = GoodPostInboxCodec.messages(body)

        // The server pages newest-first; the screen shows oldest-first, and the
        // reversal belongs here rather than in a LazyColumn's own flag.
        assertEquals(listOf("m1", "m2"), messages.map { it.id })
    }

    @Test
    fun `parses a single message out of a write response`() {
        val body = JSONObject().apply { put("message", message("m3", "hello", fromAdmin = false)) }

        assertEquals("hello", GoodPostInboxCodec.singleMessage(body)?.body)
    }

    @Test
    fun `round-trips the inbox through the cache`() {
        val notification = GoodPostNotification(
            id = "n1",
            kind = "channel_post",
            title = "Title",
            body = "Body",
            channelId = "c1",
            postId = "p1",
            createdAt = "2026-09-16T10:00:00.000Z",
            readAt = null
        )

        val raw = GoodPostInboxCodec.encodeNotifications(listOf(notification))
        val restored = GoodPostInboxCodec.decodeNotifications(raw)!!

        assertEquals(1, restored.size)
        assertEquals("n1", restored.first().id)
        assertEquals("c1", restored.first().channelId)
        assertTrue(restored.first().isUnread)
        assertEquals(notification.createdAt, restored.first().createdAt)
    }

    @Test
    fun `a damaged cache entry reads as damage, not as an empty inbox`() {
        // Null rather than an empty list: an empty inbox is how "nothing new"
        // gets shown to someone whose inbox held rows a minute ago.
        assertNull(GoodPostInboxCodec.decodeNotifications("not json"))
        assertNull(GoodPostInboxCodec.decodeNotifications(null))
        assertNull(GoodPostInboxCodec.decodeNotifications("""{"items":"nope"}"""))
        assertNull(GoodPostInboxCodec.decodeConversations("""{"items":42}"""))
    }

    @Test
    fun `every report reason the picker offers is one the server accepts`() {
        // The wire values are the API's own; a client-invented reason would be a
        // request the server refuses with nothing the user can act on.
        val known = setOf("spam", "abuse", "harassment", "impersonation", "misinformation", "illegal", "other")
        assertEquals(known, GOODPOST_REPORT_REASONS.map { it.wire }.toSet())
        assertTrue(GOODPOST_REPORT_REASONS.all { it.label.isNotBlank() })
    }

    @Test
    fun `only the server's target types can be reported`() {
        assertTrue(GoodPostReportTarget.isKnown(GoodPostReportTarget.POST))
        assertTrue(GoodPostReportTarget.isKnown(GoodPostReportTarget.CHANNEL))
        assertTrue(GoodPostReportTarget.isKnown(GoodPostReportTarget.USER))
        assertTrue(GoodPostReportTarget.isKnown(GoodPostReportTarget.MESSAGE))
        assertFalse(GoodPostReportTarget.isKnown("everything"))
    }

    private fun message(id: String, body: String, fromAdmin: Boolean): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("conversationId", "conv-1")
            put("body", body)
            put("fromAdmin", fromAdmin)
            put("createdAt", "2026-09-16T10:00:00.000Z")
            put("readAt", JSONObject.NULL)
        }
}
