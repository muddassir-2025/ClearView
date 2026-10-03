package com.muddassir.clearview.media.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.muddassir.clearview.media.data.MediaBadge
import com.muddassir.clearview.media.data.MediaRepository
import com.muddassir.clearview.media.data.WatchProgressStore

/**
 * Background job that checks every saved channel for new uploads and posts a
 * media notification ("… has an update") for each channel with something new.
 *
 * No-ops fast when the media-notifications toggle is off or no channels are
 * saved. A channel the user muted (per channel, per source — see
 * [com.muddassir.clearview.media.model.SavedChannel.notificationsMuted]) is
 * refreshed but never notified about, so silence is real. A video is only ever
 * notified once, guarded by TWO independent conditions:
 *
 *  1. The persisted notified-id set — a video id is baselined when its channel
 *     is added (and after every notification), so re-fetches of the same RSS
 *     can never re-notify, across restarts and worker retries.
 *  2. The channel's subscription timestamp — a video is only even eligible if
 *     it was published at/after the channel was added. This is the safety net
 *     for the add-time baseline: if that first fetch failed (offline etc.) and
 *     no ids were baselined, the channel's pre-existing backlog still never
 *     notifies. Legacy channels (addedAt == 0) are unaffected.
 */
class MediaUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = MediaRepository(applicationContext)

        // Toggle off (or system notifications disabled) → nothing to do.
        if (!repository.isMediaNotificationsEnabled()) return Result.success()

        val channels = repository.getSavedChannels()
        if (channels.isEmpty()) return Result.success()

        // A channel the user silenced in the View all channels list produces
        // NOTHING here: no notification, no entry in the in-app updates list
        // and no launcher badge. It is still refreshed below, so its feed and
        // its cache stay current — muting is about being pinged, not about
        // dropping the subscription. Per channel and per source: the flag lives
        // on the SavedChannel, so a muted X profile does not touch Instagram.
        val notifyChannels = channels.filterNot { it.notificationsMuted }
        if (notifyChannels.isEmpty()) {
            // Still worth one refresh so a muted channel's feed is not stale the
            // moment the user un-mutes it.
            repository.refreshAllVideos(channels)
            return Result.success()
        }

        // A profile can be saved before its provider is reachable. On the first
        // successful refresh of an uncached Instagram/X channel, treat the
        // currently visible backlog as the subscription baseline. Otherwise the
        // first worker run would notify every old post as if it were new.
        val unbaselinedChannelIds = channels
            .filterNot { repository.isNotificationBaselineComplete(it.channelId) }
            .map { it.channelId }
            .toSet()

        // Refresh every feed. null = every channel failed (offline etc.) — the
        // next periodic run retries; a partial failure keeps the successes.
        val fresh = repository.refreshAllVideos(channels) ?: return Result.success()

        // The device's watch state: a video the reader has already played is
        // CONSUMED and must never be announced afterwards (§5).
        val watchProgress = WatchProgressStore(applicationContext)

        // §5: everything from READING the notified set to WRITING it back is one
        // critical section. A periodic run and a "check now" run are separate
        // WorkManager unique names, so they can overlap; without this, both could
        // read the same stale set, both decide the same video is new, and both
        // post it. The lock makes that impossible within the process, and the
        // persisted set covers process death.
        synchronized(RUN_LOCK) {
            val alreadyNotified = repository.getNotifiedVideoIds()
            val firstRefreshBaseline = fresh
                .filter { it.channelId in unbaselinedChannelIds }
                .map { it.videoId }
                .toSet()
            val baselineIds = alreadyNotified + firstRefreshBaseline
            fresh.map { it.channelId }
                .filter { it in unbaselinedChannelIds }
                .distinct()
                .forEach(repository::markNotificationBaselineComplete)
            if (firstRefreshBaseline.isNotEmpty()) {
                repository.markVideosNotified(baselineIds)
            }
            // Videos only count as new when they were published at/after the
            // moment the channel was subscribed, their id was never seen before,
            // and this device has not already played them.
            val addedAtByChannel = channels.associate { it.channelId to it.addedAtEpochMillis }
            val notifiedChannelIds = notificationChannelIds(channels)
            val newVideos = fresh
                // Deduped by content id, so a feed merged from several sources
                // can never contribute the same video twice.
                .distinctBy { it.videoId }
                .filter { v ->
                    v.channelId in notifiedChannelIds &&
                        isNotificationEligible(
                            videoId = v.videoId,
                            publishedAtEpochMillis = v.publishedAtEpochMillis,
                            addedAtEpochMillis = addedAtByChannel[v.channelId] ?: 0L,
                            alreadyNotified = baselineIds,
                            viewed = watchProgress.get(v.videoId) != null
                        )
                }
            if (newVideos.isEmpty()) return@synchronized

            // One notification per new post, across YouTube, Instagram and X.
            // Do not group by channel here: a channel can publish several posts
            // between checks and the user asked to receive every one.
            val updates = newVideos
                .sortedByDescending { it.publishedAtEpochMillis }
                .map { video ->
                    com.muddassir.clearview.media.model.MediaChannelUpdate(
                        channelId = video.channelId,
                        channelName = video.channelName.ifBlank { video.channelId },
                        latestVideoId = video.videoId,
                        latestVideoTitle = video.title,
                        publishedAtEpochMillis = video.publishedAtEpochMillis
                    )
                }
            val postedCount = MediaNotifier.notifyUpdates(applicationContext, updates)

            // Store what was detected in the in-app "Latest Updates" feed (home
            // tab) so every notification also appears there — even when the OS
            // blocks the notification itself, the update is still recorded.
            repository.recordChannelUpdates(updates)

            // If Android notification permission is denied, do not consume the
            // ids: after the user grants permission, the next check must still be
            // able to deliver these updates. A notification that was actually
            // posted is safe to deduplicate.
            if (postedCount > 0) {
                repository.markVideosNotified(baselineIds + newVideos.map { it.videoId })
            }

            // New uploads detected → the launcher badge should show them (the
            // in-app unread count is recomputed from the same persisted history).
            MediaBadge.setBadge(
                applicationContext,
                repository.countUnreadUpdates(repository.getUpdatesHistory())
            )
        }
        return Result.success()
    }

    private companion object {
        /**
         * Serializes overlapping worker runs in this process (§5). WorkManager
         * runs its workers in the app process, so a monitor here is enough to
         * stop a periodic tick and a "check now" request racing each other.
         */
        val RUN_LOCK = Any()
    }
}

/**
 * A video deserves a notification when its id was never seen before AND it was
 * published at/after the moment its channel was subscribed. The timestamp
 * guard is the safety net for the add-time baseline: if that first fetch
 * failed (offline etc.) and no ids were baselined, a channel's pre-existing
 * backlog must still never notify. Legacy channels carry [addedAtEpochMillis]
 * == 0, which admits every video — exactly their pre-feature behavior.
 */
/**
 * The channels that may be notified about: every saved channel except the ones
 * the user silenced, whichever source they came from.
 *
 * A separate function so the per-channel/per-source muting rule is testable on
 * its own — it is the one thing standing between a muted channel and a ping.
 */
internal fun notificationChannelIds(
    channels: List<com.muddassir.clearview.media.model.SavedChannel>
): Set<String> = channels
    .filterNot { it.notificationsMuted }
    .map { it.channelId }
    .toSet()

internal fun isNotificationEligible(
    videoId: String,
    publishedAtEpochMillis: Long,
    addedAtEpochMillis: Long,
    alreadyNotified: Set<String>,
    /**
     * True when this device has already PLAYED the video (§5). A consumed video
     * is never announced, even if the notified-id set lost track of it — viewing
     * is the stronger signal, and it must survive the cap on that set.
     */
    viewed: Boolean = false
): Boolean =
    videoId !in alreadyNotified &&
        !viewed &&
        publishedAtEpochMillis >= addedAtEpochMillis.coerceAtLeast(0L)
