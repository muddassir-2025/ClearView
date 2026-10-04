package com.muddassir.clearview.brainrot

import android.content.Context
import com.muddassir.clearview.R
import com.muddassir.clearview.repository.BlockRepository
import com.muddassir.clearview.youtubetest.YoutubeTestKeywordRepository
import java.util.UUID

/**
 * The one place a personal block is performed.
 *
 * A personal block is three writes that must agree with each other: the item
 * goes into the list the matcher reads, the reason it is blocked goes into the
 * provenance store, and the user is told it happened. Doing that at each call
 * site is how one of the three gets forgotten — which is exactly the state this
 * file was written to fix: channels are enforced by `BrainRotRepository`,
 * keywords by two different stores depending on whether they are meant to apply
 * to YouTube only or everywhere, and a caller that picked the wrong store would
 * silently block nothing.
 *
 * ## Personal block vs. global submission
 *
 * These are deliberately separate acts, and this class only ever does the FIRST:
 *
 *  * **A personal block takes effect immediately.** No administrator, no
 *    network, no wait. That is the product — protection is yours and it is
 *    instant.
 *  * **A global submission changes nothing for anyone yet.** It queues a
 *    request for review ([GlobalRulesStore.suggestChannel]/`suggestKeyword`).
 *
 * Nothing here calls the network, so a personal block works on a plane.
 */
class BlockAction(context: Context) {

    private val appContext = context.applicationContext
    private val blockRepository = BlockRepository(appContext)
    private val brainRotRepository = BrainRotRepository(appContext)
    private val youtubeKeywords = YoutubeTestKeywordRepository(appContext)
    private val meta = BlockedItemMeta(appContext)
    private val notifications = NotificationStore(appContext)

    /**
     * Where a keyword applies.
     *
     * The product keeps YouTube protection and global protection separate, and
     * the difference is exactly this: a YouTube keyword is matched only against
     * YouTube content, while a global keyword is matched against every site
     * Chrome loads. They are stored in two different places for that reason, and
     * a caller has to say which one it means rather than getting a default that
     * is wrong half the time.
     */
    enum class Scope {
        /** YouTube only — Shorts and long videos. */
        YOUTUBE,

        /** Every website, in Chrome and the Google app. */
        EVERYWHERE
    }

    /**
     * Block a channel for this user, and tell the user it happened.
     *
     * Returns false when the handle is not usable, so the caller can say so
     * rather than reporting a success that changed nothing.
     */
    fun blockChannel(
        handle: String,
        name: String? = null, reason: String? = null,
        source: BlockedItemMeta.Source = BlockedItemMeta.Source.USER,
        /** The sentence shown in the notification centre. */
        notificationMessage: String? = null,
        /** Stable id so the same event cannot notify twice. */
        notificationId: String? = null,
        notify: Boolean = true
    ): Boolean {
        val normalized = BrainRotRepository.normalizeHandle(handle) ?: return false
        val resolvedReason = reason?.takeIf { it.isNotBlank() }
            ?: "Blocked by Brain Rot Protection — you blocked this channel."
        val added = brainRotRepository.addBlockedChannel(normalized, name, resolvedReason)
        if (!added) return false
        meta.record(normalized, resolvedReason, source)
        // The service records blocks the UI does not witness, so the UI is told
        // to re-read rather than waiting for a rebuild.
        BrainRotRefreshBus.notifyChanged()
        if (notify) {
            val message = notificationMessage ?: "Channel ${name ?: normalized} is now blocked."
            notifications.add(
                id = notificationId ?: "blocked:${normalized}:${System.currentTimeMillis()}",
                kind = NotificationStore.Kind.BLOCKED,
                value = normalized,
                displayName = name,
                message = message
            )
            // Also a real system notification (status bar), so a block ClearView
            // made on the user's behalf is visible outside the app.
            BlockNotifications.notifyBlocked(
                appContext,
                appContext.getString(R.string.block_notification_channel_title),
                message
            )
        }
        return true
    }

    /**
     * Block a keyword for this user, in the scope the caller names, and tell the
     * user it happened.
     */
    fun blockKeyword(
        keyword: String,
        scope: Scope,
        reason: String? = null,
        source: BlockedItemMeta.Source = BlockedItemMeta.Source.USER,
        notificationMessage: String? = null,
        notificationId: String? = null,
        notify: Boolean = true
    ): Boolean {
        val trimmed = keyword.trim().lowercase()
        if (trimmed.isEmpty()) return false
        val resolvedReason = reason?.takeIf { it.isNotBlank() } ?: defaultKeywordReason(scope)
        when (scope) {
            // YouTube keywords live in their own store, which the YouTube
            // coordinators match against and nothing else reads.
            Scope.YOUTUBE -> youtubeKeywords.addKeyword(trimmed)
            // Global keywords live in the enforcement list the matcher already
            // merges into every scan, so they apply to every site Chrome loads.
            Scope.EVERYWHERE -> blockRepository.addUserKeyword(trimmed)
        }
        meta.record(trimmed, resolvedReason, source)
        BrainRotRefreshBus.notifyChanged()
        if (notify) {
            val message = notificationMessage ?: "Keyword \"$trimmed\" is now blocked."
            notifications.add(
                id = notificationId ?: "blocked:$trimmed:${System.currentTimeMillis()}",
                kind = NotificationStore.Kind.BLOCKED,
                value = trimmed,
                displayName = null,
                message = message
            )
            BlockNotifications.notifyBlocked(
                appContext,
                appContext.getString(R.string.block_notification_keyword_title),
                message
            )
        }
        return true
    }

    /** Remove a channel block and forget why it was blocked. */
    fun unblockChannel(handle: String) {
        val normalized = BrainRotRepository.normalizeHandle(handle) ?: return
        brainRotRepository.removeBlockedChannel(normalized)
        meta.forget(normalized)
    }

    /** Why an item is blocked, or null when nothing was recorded. */
    fun reasonFor(item: String): BlockedItemMeta.Meta? = meta.get(item)

    /**
     * Keep provenance to the items that are still blocked.
     *
     * Called after the lists are read, so an item removed by another screen
     * stops being able to explain itself.
     */
    fun pruneMeta() {
        val live = buildSet {
            addAll(blockRepository.getUserKeywords())
            addAll(youtubeKeywords.getKeywords())
            brainRotRepository.getBlockedChannels().forEach { add(it.handle) }
        }
        meta.prune(live)
    }

    private fun defaultKeywordReason(scope: Scope): String = when (scope) {
        Scope.YOUTUBE ->
            "Blocked by YouTube Protection — this content matched a keyword you blocked on YouTube."
        Scope.EVERYWHERE ->
            "Blocked by Keyword Protection — this page matched a keyword you blocked everywhere."
    }

    /** A fresh notification id, for a caller that has no natural stable one. */
    fun newNotificationId(prefix: String): String = "$prefix:${UUID.randomUUID()}"
}