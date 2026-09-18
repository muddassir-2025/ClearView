package com.muddassir.clearview.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.muddassir.clearview.R
import com.muddassir.clearview.media.data.MediaBadge
import com.muddassir.clearview.media.data.MediaRepository
import com.muddassir.clearview.media.download.AudioDownloads
import com.muddassir.clearview.media.download.DownloadItem
import com.muddassir.clearview.media.download.OfflineAudioPlayer
import com.muddassir.clearview.media.model.MediaChannelUpdate
import com.muddassir.clearview.media.model.MediaVideo
import com.muddassir.clearview.media.ui.AudioPlayerScreen
import com.muddassir.clearview.media.ui.LiveTab
import com.muddassir.clearview.media.ui.MediaTab
import com.muddassir.clearview.media.ui.VideoPlayerScreen
import com.muddassir.clearview.phonelimit.ui.PhoneLimitSheet
import com.muddassir.clearview.media.worker.MediaNotifier
import com.muddassir.clearview.media.worker.MediaWorkScheduler
import com.muddassir.clearview.quran.data.IslamicDateStore
import com.muddassir.clearview.quran.data.QuranRepository
import com.muddassir.clearview.quran.model.QuranVerse
import com.muddassir.clearview.quran.ui.DhikrCounterScreen
import com.muddassir.clearview.quran.ui.QuranTab
import com.muddassir.clearview.todo.data.TodoCodec
import com.muddassir.clearview.todo.data.TodoScheduler
import com.muddassir.clearview.todo.data.TodoStore
import com.muddassir.clearview.todo.ui.TodoScreen
import com.muddassir.clearview.quran.util.copyVerseToClipboard
import com.muddassir.clearview.quran.util.formatVerseForSharing
import com.muddassir.clearview.quran.widget.QuranReminderWidgetProvider
import com.muddassir.clearview.quran.worker.QuranWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Shared Islamic-content hub — the exact experience the Quran Reminder widget
 * opens. Both the widget activity ([QuranVerseActivity]) and the main app's
 * Quran / Media / Live tabs render this same state + content, so the two can
 * never drift apart.
 *
 * [ContentHubState] owns the tab selection, the playing video, and the Quran
 * verse state (verse, bookmark, refresh interval) plus the actions the top
 * bar can trigger (share / bookmark / copy / new verse / interval).
 */
enum class ContentTab { QURAN, MEDIA }

/**
 * The three views of the Quran screen (§1–§3).
 *
 * One surface with three tabs rather than three entry points, because they are
 * the same question asked three ways — find a verse by its words, find a surah
 * by name, or go back to one you kept — and a reader moves between them.
 */
enum class QuranSheetTab { SEARCH, SURAH, BOOKMARKS }

class ContentHubState(appContext: Context) {

    var selectedTab by mutableStateOf(ContentTab.QURAN)
    var playingVideo by mutableStateOf<MediaVideo?>(null)
    // Offline audio playback (downloaded files). Only one of playingVideo /
    // playingAudio is non-null at a time — playing audio closes the video player.
    var playingAudio by mutableStateOf<DownloadItem?>(null)
    // The playing audio's queue (a playlist, the Downloads list, or the whole
    // offline library) + the current index in it — drives Next / Previous.
    var audioQueue by mutableStateOf<List<DownloadItem>>(emptyList())
    var audioIndex by mutableStateOf(-1)
    // Vertical fullscreen (YouTube Shorts style): the video fills the whole
    // portrait screen and the bars hide. Reset when the video changes/exits.
    var playerFullscreen by mutableStateOf(false)
    // Ordered Shorts list for the vertical viewer (empty for long videos) +
    // the index of the currently playing video within it.
    var shortsQueue by mutableStateOf<List<MediaVideo>>(emptyList())
    var shortsIndex by mutableStateOf(-1)
    // ── Media tab navigation state (survives player open/close) ────
    var mediaFilterChannelId by mutableStateOf<String?>(null)
    var mediaSelectedPlaylistId by mutableStateOf<String?>(null)
    var mediaSelectedUserPlaylistId by mutableStateOf<String?>(null)
    // Previous/Next verse navigation availability (false at the very first /
    // last verse of the Quran, or before the cache is loaded).
    var canGoPrevious by mutableStateOf(false)
    var canGoNext by mutableStateOf(false)
    var verse by mutableStateOf<QuranVerse?>(null)
    var verseLoading by mutableStateOf(false)
    var refreshIntervalHours by mutableStateOf(DEFAULT_REFRESH_INTERVAL_HOURS)
    var isBookmarked by mutableStateOf(false)

    // ── The Quran reader: search, surahs and bookmarks (§1–§3) ──────

    /**
     * Which of the three tabs the Quran screen is showing.
     *
     * Held here rather than in the dialog's own `remember`, so switching tabs —
     * or opening a surah and coming back — does not re-parse anything: the screen
     * is one surface with three views, not three screens to reload.
     */
    var quranSheetTab by mutableStateOf(QuranSheetTab.SEARCH)

    /** The surah the continuous reader has open, or null while the tabs are showing. */
    var openSurahNumber by mutableStateOf<Int?>(null)

    /**
     * Every "surah:ayah" this device has bookmarked.
     *
     * One value for the whole hub, read from the same store the bookmark button
     * writes to. The surah reader draws 286 rows that each need an answer, and a
     * per-row prefs read would be 286 file reads; a per-row COPY of the answer
     * would be 286 chances to disagree with the top-bar icon. This is the single
     * source both read, and it changes the moment a bookmark does.
     */
    var bookmarkKeys by mutableStateOf<Set<String>>(emptySet())

    /**
     * Every surah this device has starred, keyed by number.
     *
     * A separate value from [bookmarkKeys] because they answer different
     * questions: that one is "is this verse saved", asked once per row of a
     * surah, this one is "is this whole surah saved", asked once per card of the
     * browse list and by the bookmarks screen's second tab. Both are read from
     * the same store the star writes to, so a card and the tab cannot disagree.
     */
    var surahBookmarkKeys by mutableStateOf<Set<String>>(emptySet())

    // ── Media notifications (channel updates) ──────────────────────
    var mediaNotificationsEnabled by mutableStateOf(true)
    var mediaUpdates by mutableStateOf<List<MediaChannelUpdate>>(emptyList())
    var mediaUpdatesLoading by mutableStateOf(false)
    // Number of updates the user hasn't seen yet (drives the Media-tab badge).
    var unreadMediaUpdates by mutableStateOf(0)
    // Ids of the updates that were unread at the last refresh — drives the
    // unread indicator in the notifications sheet (which may be opened after
    // the badge was already cleared).
    var unreadUpdateIds by mutableStateOf<Set<String>>(emptySet())

    /**
     * What the bell's badge counts (§5): everything that wants attention, from
     * every feature, not just the channel updates.
     *
     * Media updates clear when they are seen (they are news). A todo clears when
     * it is done — so its number stays until the work does, which is the point of
     * putting it on a badge. The Quran leg is a cadence rather than an event and
     * never counts.
     */
    val unreadNotificationCount: Int
        get() = unreadMediaUpdates + dueTodoNotifications.unreadNotificationCount()

    /**
     * Every notification the app has, newest first — one list, for the one bell.
     *
     * Ordering is by moment, and the entries with no moment (the Quran cadence)
     * sort last because 0 is the oldest value there is. Within a kind the section
     * heading in the sheet is what separates them, so a todo and a channel update
     * from the same minute never have to be told apart by their wording.
     */
    fun notificationEntries(): List<HubNotification> =
        (mediaNotificationEntries() + dueTodoNotifications + quranNotificationEntries())
            .inNotificationOrder()

    // ── Quran notifications (new verse) ────────────────────────────
    var quranNotificationsEnabled by mutableStateOf(true)

    // ── Todo reminders (alarm notifications) ───────────────────────
    var todoNotificationsEnabled by mutableStateOf(true)

    /**
     * The To Do leg of the notification centre (§5), rebuilt from the todo store.
     *
     * Cached rather than derived on every read because building it means reading
     * the store — every todo, its schedule, its completion history — which is a
     * file read, and a property that does that would do it once per frame.
     * Rebuilt when the Quran tab opens, when the toggle changes, and when the
     * Todo screen closes (completing a todo is what makes its entry go away).
     */
    var dueTodoNotifications by mutableStateOf<List<HubNotification>>(emptyList())

    // Haramayn Live (Makkah & Madinah) opened from the Media tab's shortcut.
    // Rendered as a full-screen overlay (its own embedded player) so it never
    // interferes with the Media feed's composition slot.
    var showHaramaynLive by mutableStateOf(false)

    // ── Top-bar sheets on the Quran tab (search / settings / notifications) ──
    var showSearchSheet by mutableStateOf(false)
    var showSettingsSheet by mutableStateOf(false)
    var showNotificationsSheet by mutableStateOf(false)
    // Dhikr Counter screen opened from the settings sheet's Dhikr card.
    var showDhikrCounter by mutableStateOf(false)
    // Todo screen opened from the settings sheet's Todo card.
    var showTodoScreen by mutableStateOf(false)
    // Phone Limit sheet opened from the settings sheet's "Set Phone Limit" card.
    var showPhoneLimitSheet by mutableStateOf(false)

    // ── Islamic date (Umm al-Qura) on the Quran tab ────────────────
    // The user's ±1 day adjustment (0 = the default calculated date),
    // persisted via IslamicDateStore so it survives restarts.
    var islamicDateAdjustment by mutableStateOf(0)
    var showIslamicDateSheet by mutableStateOf(false)

    private val appContext: Context = appContext
    private val quranRepository = QuranRepository(appContext)

    /** Surah verses parsed once per process, keyed by surah number (§2). */
    private val surahVerseCache = mutableMapOf<Int, List<QuranVerse>>()
    private val mediaRepository = MediaRepository(appContext)
    private val islamicDateStore = IslamicDateStore(appContext)
    private val todoStore = TodoStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // Only the current interval options (1/3/6/12/24 hr) are offered; a
        // stored value from an older build (2/4/8 hr) is coerced to 6 hr and
        // persisted so the scheduler and the UI never disagree.
        val stored = quranRepository.getRefreshIntervalHours()
        refreshIntervalHours = if (stored in VERSE_INTERVAL_OPTIONS) stored else {
            quranRepository.setRefreshIntervalHours(DEFAULT_REFRESH_INTERVAL_HOURS)
            // The periodic verse work was scheduled at the old interval (KEEP
            // policy never re-reads it) — reschedule so the UI and the actual
            // refresh cadence agree again.
            QuranWorkScheduler.reschedule(appContext)
            DEFAULT_REFRESH_INTERVAL_HOURS
        }
        mediaNotificationsEnabled = mediaRepository.isMediaNotificationsEnabled()
        quranNotificationsEnabled = quranRepository.getQuranNotificationsEnabled()
        todoNotificationsEnabled = todoStore.getTodoNotificationsEnabled()
        islamicDateAdjustment = islamicDateStore.adjustmentDays()
        bookmarkKeys = quranRepository.getBookmarks()
        surahBookmarkKeys = quranRepository.getSurahBookmarks()
        verseLoading = true
    }

    /**
     * Loads the verse off the main thread and shows it. On every open the
     * PERSISTED current verse is shown (the one the widget displays) so the
     * app and the home-screen widget can never drift apart; a random verse is
     * only picked on the very first run, when nothing is persisted yet. The
     * widget is re-rendered afterwards so both surfaces stay in lock-step even
     * across a first-run race with the background worker.
     */
    fun start() {
        scope.launch {
            if (verse == null) {
                verseLoading = true
                var loaded = withContext(Dispatchers.IO) {
                    // The persisted current verse is preferred so the app and the
                    // home-screen widget never drift apart. backfill() supplies
                    // the surah ayah count for verses saved by older builds
                    // (derived from the cached edition, never hardcoded).
                    (quranRepository.getCurrentVerse() ?: quranRepository.pickRandomVerse())
                        ?.let { quranRepository.backfillCurrentVerseTotals() ?: it }
                }
                if (loaded == null) {
                    // Very first launch: nothing is cached yet, so the persisted
                    // verse doesn't exist and pickRandomVerse() has no data. The
                    // background worker downloads the translation separately, but
                    // this in-process download makes the result observable by
                    // the UI — when it completes, the verse is set and the tab
                    // recomposes immediately, with NO app restart required.
                    val downloaded = withContext(Dispatchers.IO) {
                        quranRepository.ensureEnglishAndArabic()
                    }
                    if (downloaded) {
                        loaded = withContext(Dispatchers.IO) {
                            quranRepository.pickRandomVerse()
                        }
                    }
                }
                verse = loaded
                verseLoading = false
                // Refresh on every open is cheap (one RemoteViews update) and
                // guarantees the widget reflects any in-app change immediately.
                if (loaded != null) QuranReminderWidgetProvider.refreshAllWidgets(appContext)
            }
            refreshNavAvailability()
        }
        refreshMediaUpdates()
        refreshTodoReminders()
    }

    // ── Media notifications (channel updates) ──────────────────────

    /**
     * Rebuilds the home-page "Latest Updates" feed from the persisted update
     * history (the entries the background worker recorded when it detected
     * uploads — each one matches a notification). Never network: reads the
     * newest [MediaUpdates.MAX] entries. Runs whenever the Quran (home) tab is
     * shown.
     */
    fun refreshMediaUpdates() {
        scope.launch {
            mediaUpdatesLoading = true
            val (updates, unreadIds) = withContext(Dispatchers.IO) {
                // Fresh installs start empty until the first background check;
                // the one-time seed pre-fills from the cached feeds (never
                // re-runs after the history has been written, so dismissals
                // are never resurrected).
                mediaRepository.ensureUpdatesHistorySeeded(mediaRepository.getSavedChannels())
                val list = mediaRepository.getUpdatesHistory()
                list to mediaRepository.unreadUpdateIds(list)
            }
            mediaUpdates = updates
            unreadUpdateIds = unreadIds
            unreadMediaUpdates = unreadIds.size
            mediaUpdatesLoading = false
            // Keep the launcher app-icon badge in sync with the unread count.
            MediaBadge.setBadge(appContext, unreadIds.size)
        }
    }

    /** Marks every currently listed update as seen (the Media tab was opened). */
    fun markMediaUpdatesSeen() {
        scope.launch {
            withContext(Dispatchers.IO) {
                mediaRepository.markUpdatesSeen(mediaUpdates.map { it.latestVideoId })
            }
            unreadMediaUpdates = 0
            MediaBadge.setBadge(appContext, 0)
        }
    }

    /**
     * Persists the media-notifications toggle and keeps the background check
     * running. Turning it ON also triggers an immediate check so channels that
     * already uploaded get notified right away. (The OS notification
     * permission is handled by the UI before this is called.)
     */
    fun setMediaNotifications(enabled: Boolean, context: Context) {
        mediaNotificationsEnabled = enabled
        mediaRepository.setMediaNotificationsEnabled(enabled)
        MediaWorkScheduler.ensureScheduled(context)
        if (enabled) {
            MediaWorkScheduler.checkNow(context)
            refreshMediaUpdates()
        }
    }

    /** Persists the Quran-verse notification toggle (OS notification on new verse). */
    fun setQuranNotifications(enabled: Boolean) {
        quranNotificationsEnabled = enabled
        quranRepository.setQuranNotificationsEnabled(enabled)
    }

    /**
     * Persists the global Todo-reminders toggle. Turning it ON re-registers
     * every pending todo alarm so existing todos get their reminders again
     * (turning it OFF leaves alarms unset — [TodoScheduler] skips them too).
     */
    fun setTodoNotifications(enabled: Boolean) {
        todoNotificationsEnabled = enabled
        todoStore.setTodoNotificationsEnabled(enabled)
        TodoScheduler.rescheduleAll(appContext)
        // The To Do leg of the bell follows the toggle immediately, in both
        // directions: turning reminders off should empty that section now, not
        // at the next launch.
        refreshTodoReminders()
    }

    /** Persists the ±1 day Islamic-date adjustment (0 = the default Umm al-Qura date). */
    fun changeIslamicDateAdjustment(days: Int) {
        islamicDateAdjustment = days.coerceIn(-1, 1)
        islamicDateStore.setAdjustmentDays(islamicDateAdjustment)
    }

    /**
     * Opens the notifications panel; viewing it marks everything it lists as
     * seen (clears the bell / tab / launcher badges), like opening the Media tab
     * does.
     *
     * The To Do leg is acknowledged too, and that is the whole point of opening
     * the panel: a badge that survived being read would be a badge that means
     * "something is due", which is what the todo list and the widget are for. The
     * ENTRY stays (the todo is still due — pretending otherwise would be a lie);
     * only its weight on the bell is spent.
     */
    fun openNotificationsPanel() {
        markMediaUpdatesSeen()
        markTodoRemindersSeen()
        showNotificationsSheet = true
    }

    /**
     * Records that the reader has seen the reminders currently in the list.
     *
     * Stamped here, on the open, rather than in the sheet's composition: a sheet
     * that could not be scrolled is still a sheet that was read, and the write is
     * one prefs edit either way.
     */
    private fun markTodoRemindersSeen() {
        // The same key [buildTodoNotifications] looked up: the entry's own id
        // (which already carries the "todo:" prefix) and the moment it spoke.
        val keys = dueTodoNotifications
            .filter { it.unread }
            .map { entry -> "${entry.id}#${entry.at}" }
        if (keys.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { todoStore.markRemindersSeen(keys) }
            // Recompute, so the number falls immediately rather than at the next
            // launch — and so a todo that is genuinely still unread (one whose
            // moment arrives while the panel is open) is not silently swallowed.
            dueTodoNotifications = withContext(Dispatchers.IO) { buildTodoNotifications() }
        }
    }

    /**
     * Removes EVERY update from the "Latest Updates" feed and the notification
     * shade (the notifications sheet's "Clear all" action).
     */
    fun clearAllUpdates() {
        scope.launch {
            // Remove the OS notifications too (deterministic per-channel ids),
            // so the shade and the launcher bubble follow the in-app feed.
            mediaUpdates.forEach { MediaNotifier.cancelChannelNotification(appContext, it.channelId) }
            withContext(Dispatchers.IO) { mediaRepository.clearAllUpdates() }
            mediaUpdates = emptyList()
            unreadUpdateIds = emptySet()
            unreadMediaUpdates = 0
            MediaNotifier.cancelSummary(appContext)
            MediaBadge.setBadge(appContext, 0)
        }
    }

    /**
     * The channel updates as notifications (§5).
     *
     * Their words and their actions are the ones this sheet always had — what
     * changed is that they are now one kind among several rather than the only
     * thing a reader can be told about.
     */
    private fun mediaNotificationEntries(): List<HubNotification> =
        mediaUpdates.map { update ->
            HubNotification(
                id = "media:${update.latestVideoId}",
                kind = HubNotificationKind.MEDIA,
                title = update.channelName,
                body = update.latestVideoTitle,
                at = update.publishedAtEpochMillis,
                unread = update.latestVideoId in unreadUpdateIds,
                onOpen = {
                    showNotificationsSheet = false
                    playMediaUpdate(update)
                },
                onDismiss = { dismissUpdate(update.latestVideoId) }
            )
        }

    /**
     * The Quran reminder as a notification (§5).
     *
     * Deliberately the only QURAN entry and deliberately never unread: the verse
     * reminder is a standing arrangement, not something that happened. A reader
     * who opens the bell should be able to see what the app is set to tell them
     * about and where it has got to, without that inflating a badge about news.
     * Tapping it closes the sheet — the verse is the Quran tab, which the sheet
     * is covering.
     */
    private fun quranNotificationEntries(): List<HubNotification> {
        if (!quranNotificationsEnabled) return emptyList()
        val v = verse ?: return emptyList()
        val cadence = appContext.resources.getQuantityString(
            R.plurals.quran_verse_refresh_note_hours,
            refreshIntervalHours,
            refreshIntervalHours
        )
        return listOf(
            HubNotification(
                // The heading above this entry already says "Quran reminders", so
                // the card itself says WHICH verse rather than repeating it.
                id = "quran:reminder",
                kind = HubNotificationKind.QURAN,
                title = "${v.surahName} ${v.surahNumber}:${v.ayahNumber}",
                body = cadence,
                at = 0,
                unread = false,
                onOpen = { showNotificationsSheet = false }
            )
        )
    }

    /**
     * Rebuilds the To Do leg of the notification centre (§5) from the store.
     *
     * Only REMINDED todos appear. A todo without a reminder is a note to the
     * reader, not a promise by the app to interrupt them, and listing it here
     * would make the bell's number mean "things I wrote down once".
     */
    fun refreshTodoReminders() {
        scope.launch {
            dueTodoNotifications = withContext(Dispatchers.IO) { buildTodoNotifications() }
        }
    }

    /**
     * The todos that are due today, uncompleted, and set to remind.
     *
     * An entry is unread once its reminder moment has arrived AND the reader has
     * not already been shown that moment; before either, it is listed but does not
     * count — a todo due at five should not be in a badge at nine in the morning,
     * and one the reader has already read about should not be in it at all. A todo
     * with a reminder but no time of its own is due from the start of its day.
     */
    private fun buildTodoNotifications(): List<HubNotification> {
        if (!todoNotificationsEnabled) return emptyList()
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        return todoStore.getItems()
            .filterNot { TodoCodec.isArchived(it, today) }
            .filterNot { TodoCodec.completedOn(it, today) }
            // The moment a reminder first speaks also decides whether there is a
            // reminder to speak at all, so the "no reminder / switched off / not
            // active today" cases are one rule rather than three filters that
            // have to stay in agreement.
            .mapNotNull { item ->
                TodoCodec.reminderMomentMillis(item, today)?.let { at -> item to at }
            }
            // Read once, so the same set answers every row (§ the store's note on
            // why the key is the reminder's own moment).
            .let { pairs ->
                val seen = todoStore.getSeenReminders()
                pairs.map { (item, at) -> Triple(item, at, "todo:${item.id}#$at" in seen) }
            }
            .map { (item, at, acknowledged) ->
                HubNotification(
                    id = "todo:${item.id}",
                    kind = HubNotificationKind.TO_DO,
                    title = item.title,
                    // Details when the todo has them, otherwise when it is due —
                    // which is the thing a reader opening this list wants to know
                    // about a reminder.
                    body = item.details.ifBlank {
                        when {
                            item.timeStartMinutes != null && item.timeEndMinutes != null ->
                                timeFormat.format(
                                    LocalTime.of(
                                        item.timeStartMinutes / 60,
                                        item.timeStartMinutes % 60
                                    )
                                ) + " – " + timeFormat.format(
                                    LocalTime.of(
                                        item.timeEndMinutes / 60,
                                        item.timeEndMinutes % 60
                                    )
                                )
                            item.timeMinutes != null ->
                                timeFormat.format(
                                    LocalTime.of(item.timeMinutes / 60, item.timeMinutes % 60)
                                )
                            else -> appContext.getString(R.string.todo_notifications_note)
                        }
                    },
                    at = at,
                    unread = now >= at && !acknowledged,
                    // Opening it opens the To Do list, which is where a todo can
                    // actually be dealt with. There is no ✕: the way to make this
                    // entry go away is to do the todo, and a dismiss that merely
                    // hid it would be a lie about that.
                    onOpen = {
                        showNotificationsSheet = false
                        showTodoScreen = true
                    }
                )
            }
    }

    /**
     * Removes one update from the "Latest Updates" feed (persisted — it won't
     * come back on the next refresh).
     */
    fun dismissUpdate(latestVideoId: String) {
        scope.launch {
            // Remove its OS notification too (deterministic per-channel id), so
            // the shade and the launcher bubble follow the in-app feed.
            mediaUpdates.firstOrNull { it.latestVideoId == latestVideoId }
                ?.let { MediaNotifier.cancelChannelNotification(appContext, it.channelId) }
            withContext(Dispatchers.IO) { mediaRepository.dismissUpdate(latestVideoId) }
            val updated = mediaUpdates.filterNot { it.latestVideoId == latestVideoId }
            mediaUpdates = updated
            unreadUpdateIds = unreadUpdateIds - latestVideoId
            // All updates gone → the group summary in the shade must go too,
            // otherwise the launcher bubble lingers after clearing the feed.
            if (updated.isEmpty()) MediaNotifier.cancelSummary(appContext)
            val unread = withContext(Dispatchers.IO) {
                mediaRepository.countUnreadUpdates(updated)
            }
            unreadMediaUpdates = unread
            MediaBadge.setBadge(appContext, unread)
        }
    }

    /** Starts a video from the Media tab; [queue]/[index] enable Shorts paging. */
    fun playVideo(video: MediaVideo, queue: List<MediaVideo> = emptyList(), index: Int = -1) {
        shortsQueue = queue
        shortsIndex = index
        playingAudio = null
        playingVideo = video
    }

    /**
     * Switches the hub's tab, dismissing any full-screen overlay that belongs
     * to the PREVIOUS section (currently Haramayn Live).
     *
     * Without this, opening Haramayn and then tapping another tab left the live
     * player composed on top of the new section — the user had to dismiss it
     * with its own back arrow before reaching the section they had tapped.
     */
    fun selectTab(tab: ContentTab) {
        selectedTab = tab
        showHaramaynLive = false
    }

    /**
     * Plays the downloaded audio for [video] immediately (podcast-style),
     * closing the WebView video player if it was open. No-op when the video
     * isn't downloaded yet.
     */
    fun playAudio(video: MediaVideo) {
        AudioDownloads.itemFor(video.videoId)?.let { playAudioItem(it) }
    }

    /**
     * Plays [item] (its local audio file) immediately, inside [queue] when one
     * is given so the player's Next / Previous buttons walk the SAME context the
     * user started from (a playlist, the Downloads list, …). Without a queue the
     * whole offline library becomes the queue, newest first — the same order the
     * Downloads list shows — so the controls are never dead.
     */
    fun playAudioItem(
        item: DownloadItem,
        queue: List<DownloadItem> = emptyList()
    ) {
        playingVideo = null
        shortsQueue = emptyList()
        shortsIndex = -1
        val resolved = queue.ifEmpty { AudioDownloads.items.value }
        audioQueue = if (resolved.any { it.videoId == item.videoId }) resolved else resolved + item
        audioIndex = audioQueue.indexOfFirst { it.videoId == item.videoId }
        playingAudio = item
    }

    /**
     * Moves within the audio queue: +1 next, -1 previous. No-op at the ends
     * (the buttons disable there), and a no-op before playback ever started.
     */
    fun navigateAudio(delta: Int) {
        val next = audioIndex + delta
        if (next in audioQueue.indices) {
            audioIndex = next
            playingAudio = audioQueue[next]
        }
    }

    /** True when a previous / next track exists in the current audio queue. */
    val canGoPreviousAudio: Boolean
        get() = audioIndex > 0 && audioIndex < audioQueue.size

    val canGoNextAudio: Boolean
        get() = audioIndex >= 0 && audioIndex < audioQueue.size - 1

    /** Closes the audio player screen. Playback deliberately CONTINUES in the
     *  background (foreground service + media notification) — like any music
     *  app, leaving the player must not stop the audio.
     */
    fun exitAudio() {
        playingAudio = null
    }

    /** Vertical Shorts paging: +1 next, -1 previous (no-op at the ends). */
    fun navigateShorts(delta: Int) {
        val next = shortsIndex + delta
        if (next in shortsQueue.indices) {
            shortsIndex = next
            playingVideo = shortsQueue[next]
        }
    }

    /**
     * Plays the latest video of an update from the home feed. Prefers the
     * cached copy (instant); if it isn't cached yet, fetches that channel's
     * feed once so the player always has a valid id.
     */
    fun playMediaUpdate(update: MediaChannelUpdate) {
        scope.launch {
            val video = withContext(Dispatchers.IO) {
                mediaRepository.getCachedVideos(update.channelId)
                    ?.first?.firstOrNull { it.videoId == update.latestVideoId }
                    ?: mediaRepository.refreshVideos(update.channelId)
                        ?.firstOrNull { it.videoId == update.latestVideoId }
            }
            if (video != null) playingVideo = video
        }
    }

    /** Re-read the bookmark state for the current verse. */
    fun refreshBookmarkState() {
        isBookmarked = verse?.let { quranRepository.isBookmarked(it.surahNumber, it.ayahNumber) } == true
    }

    fun pickNewVerse() {
        verseLoading = true
        scope.launch {
            val next = withContext(Dispatchers.IO) { quranRepository.pickRandomVerse() }
            if (next != null) {
                verse = next
                // Keep the home-screen widget in sync with the in-app verse.
                QuranReminderWidgetProvider.refreshAllWidgets(appContext)
                // The top-bar bookmark icon must reflect the NEW verse instantly.
                refreshBookmarkState()
            }
            verseLoading = false
            refreshNavAvailability()
        }
    }

    // ── Verse navigation (previous / next) ─────────────────────────

    /**
     * Loads the adjacent verse (step = -1 previous / +1 next). Wraps across
     * surah boundaries automatically; no-op at the very first/last verse of
     * the Quran (the buttons are disabled there). Loads without the full-screen
     * spinner so the Crossfade in the UI can animate the swap smoothly.
     */
    fun goToAdjacentVerse(step: Int) {
        val v = verse ?: return
        scope.launch {
            val (next, prevAvailable, nextAvailable) = withContext(Dispatchers.IO) {
                val n = quranRepository.getAdjacentVerse(v.surahNumber, v.ayahNumber, step)
                if (n == null) {
                    Triple<QuranVerse?, Boolean, Boolean>(null, false, false)
                } else {
                    Triple(
                        n,
                        quranRepository.hasAdjacentVerse(n.surahNumber, n.ayahNumber, -1),
                        quranRepository.hasAdjacentVerse(n.surahNumber, n.ayahNumber, +1)
                    )
                }
            }
            if (next != null) {
                verse = next
                canGoPrevious = prevAvailable
                canGoNext = nextAvailable
                // Keep the home-screen widget in sync with the in-app verse, and
                // the top-bar bookmark icon with the NEW verse.
                QuranReminderWidgetProvider.refreshAllWidgets(appContext)
                refreshBookmarkState()
            }
        }
    }

    /** Re-evaluates whether Previous/Next have a verse to go to. */
    private fun refreshNavAvailability() {
        val v = verse ?: return
        scope.launch {
            val (prev, next) = withContext(Dispatchers.IO) {
                Pair(
                    quranRepository.hasAdjacentVerse(v.surahNumber, v.ayahNumber, -1),
                    quranRepository.hasAdjacentVerse(v.surahNumber, v.ayahNumber, +1)
                )
            }
            canGoPrevious = prev
            canGoNext = next
        }
    }

    fun copyVerse(context: Context) {
        val v = verse ?: return
        copyVerseToClipboard(context, v)
        Toast.makeText(context, context.getString(R.string.quran_verse_copied), Toast.LENGTH_SHORT).show()
    }

    fun shareVerse(context: Context) {
        val v = verse ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, formatVerseForSharing(context, v))
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.quran_share_via)))
    }

    fun toggleBookmark(context: Context) {
        val v = verse ?: return
        val added = quranRepository.toggleBookmark(v.surahNumber, v.ayahNumber)
        isBookmarked = added
        Toast.makeText(
            context,
            context.getString(if (added) R.string.quran_bookmarked else R.string.quran_bookmark_removed),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun changeInterval(context: Context, hours: Int) {
        refreshIntervalHours = hours
        quranRepository.setRefreshIntervalHours(hours)
        QuranWorkScheduler.reschedule(context)
        Toast.makeText(
            context,
            context.resources.getQuantityString(R.plurals.quran_verse_interval_set, hours, hours),
            Toast.LENGTH_SHORT
        ).show()
    }

    // ── Quran search / bookmarks manager ─────────────────────────────

    /**
     * Searches the cached translation; see [QuranRepository.searchVerses].
     * Empty when nothing is cached yet (or no matches).
     */
    suspend fun searchQuran(query: String): List<QuranVerse> =
        quranRepository.searchVerses(query)

    /**
     * Opens [v] as the current verse (persisted, widget synced, nav flags +
     * bookmark state refreshed) without the full-screen loading spinner — used
     * by search results and the bookmarks manager.
     */
    fun goToVerse(v: QuranVerse) {
        verse = v
        scope.launch {
            withContext(Dispatchers.IO) {
                // Persist as the current verse so the widget follows instantly.
                quranRepository.saveCurrentVerse(v)
            }
            QuranReminderWidgetProvider.refreshAllWidgets(appContext)
            refreshNavAvailability()
            refreshBookmarkState()
        }
    }

    /** Resolves every saved bookmark into its full verse (enriched with Arabic). */
    suspend fun bookmarkedVerses(): List<QuranVerse> = quranRepository.getBookmarkedVerses()

    // ── The surah reader (§2) ───────────────────────────────────────

    /** Verses of one surah, so the reader can be one scrollable list. */
    suspend fun surahVerses(surahNumber: Int): List<QuranVerse> {
        // Cached per surah for the process: a 286-verse surah is re-parsed on
        // every open otherwise, and stepping between two surahs would do it
        // repeatedly. The edition itself is stamped by the repository, so a new
        // download is still picked up on the next launch.
        surahVerseCache[surahNumber]?.let { return it }
        val verses = quranRepository.getSurahVerses(surahNumber)
        if (verses.isNotEmpty()) surahVerseCache[surahNumber] = verses
        return verses
    }

    /** Opens the continuous reader for one surah. */
    fun openSurah(surahNumber: Int) {
        openSurahNumber = surahNumber
    }

    /** Back out of the reader, to the tabs it was opened from (§10). */
    fun closeSurah() {
        openSurahNumber = null
    }

    /**
     * Bookmark or unbookmark one verse, from anywhere (§2, §3).
     *
     * The single write path: the surah reader's rows, the bookmarks tab and the
     * top bar all come through here, and all of them read [bookmarkKeys]
     * afterwards, so the three can never show different answers. Returns the new
     * state, which is what a row needs to draw itself.
     */
    fun toggleBookmarkAt(surahNumber: Int, ayahNumber: Int): Boolean {
        val added = quranRepository.toggleBookmark(surahNumber, ayahNumber)
        bookmarkKeys = quranRepository.getBookmarks()
        // Keep the top-bar icon honest when the verse on the home tab is the one
        // that was just toggled from a list.
        verse?.let { current ->
            if (current.surahNumber == surahNumber && current.ayahNumber == ayahNumber) {
                isBookmarked = added
            }
        }
        return added
    }

    /**
     * Star or unstar one surah (§2).
     *
     * The single write path for a surah star: the browse list's cards and the
     * bookmarks screen's surah tab both come through here and both read
     * [surahBookmarkKeys] afterwards, so the card the reader just tapped fills in
     * the same frame the list behind it changes. Returns the new state, which is
     * what the card needs to draw itself.
     */
    fun toggleSurahBookmarkAt(surahNumber: Int): Boolean {
        val added = quranRepository.toggleSurahBookmark(surahNumber)
        surahBookmarkKeys = quranRepository.getSurahBookmarks()
        return added
    }

    /** Removes a surah star (no-op when not starred). */
    fun removeSurahBookmark(surahNumber: Int) {
        quranRepository.removeSurahBookmark(surahNumber)
        surahBookmarkKeys = quranRepository.getSurahBookmarks()
    }

    /** Whether one surah is starred, by the same key the store uses. */
    fun isSurahStarred(surahNumber: Int): Boolean =
        surahNumber.toString() in surahBookmarkKeys

    /** Every starred surah, in Quran order. */
    val starredSurahs: List<Int>
        get() = surahBookmarkKeys.mapNotNull { it.toIntOrNull() }.sorted()

    /** Removes a bookmark (no-op when not bookmarked); updates the top-bar icon. */
    fun removeBookmark(surahNumber: Int, ayahNumber: Int) {
        quranRepository.removeBookmark(surahNumber, ayahNumber)
        bookmarkKeys = quranRepository.getBookmarks()
        refreshBookmarkState()
    }

    /** "surah:ayah", the form the bookmark set is keyed on. */
    private fun keyOf(surahNumber: Int, ayahNumber: Int) = "$surahNumber:$ayahNumber"

    /** Whether one verse is bookmarked, by the same key the store uses. */
    fun isVerseBookmarked(surahNumber: Int, ayahNumber: Int): Boolean =
        keyOf(surahNumber, ayahNumber) in bookmarkKeys

    fun cancel() {
        scope.cancel()
    }
}

@Composable
fun rememberContentHubState(): ContentHubState {
    val context = LocalContext.current
    val state = remember { ContentHubState(context.applicationContext) }
    LaunchedEffect(state.verse) {
        state.refreshBookmarkState()
    }
    DisposableEffect(Unit) {
        state.start()
        onDispose { state.cancel() }
    }
    return state
}

/** True when the current orientation is landscape. */
@Composable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * The hub's tab content: the playing video (full screen), or the Quran /
 * Media / Live tab. The video player replaces the tab so playback keeps its
 * full composition slot.
 */
@Composable
fun ContentHubTabContent(
    state: ContentHubState,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        // Refresh the home-page updates feed whenever the Quran (home) tab is
        // shown — the worker and Media tab keep the caches fresh, so this is a
        // cheap local read that picks up any new uploads.
        LaunchedEffect(state.selectedTab) {
            if (state.selectedTab == ContentTab.QURAN) state.refreshMediaUpdates()
            // A section switch always dismisses the Haramayn live overlay, so it
            // can never sit on top of the section the user just opened.
            state.showHaramaynLive = false
        }
        // Leaving the player (or switching to a different video) always exits
        // vertical fullscreen — EXCEPT when navigating within the Shorts queue
        // (swipe up/down), where fullscreen must stay put.
        LaunchedEffect(state.playingVideo?.videoId) {
            val current = state.playingVideo
            val inShortsQueue = current != null &&
                state.shortsIndex in state.shortsQueue.indices &&
                state.shortsQueue[state.shortsIndex].videoId == current.videoId
            if (!inShortsQueue) state.playerFullscreen = false
        }
        when {
            state.playingVideo != null -> VideoPlayerScreen(
                video = state.playingVideo!!,
                isLandscape = isLandscape,
                fullscreenVertical = state.playerFullscreen,
                onToggleFullscreen = { state.playerFullscreen = !state.playerFullscreen },
                onExit = { state.playingVideo = null },
                onPlayOffline = { state.playAudio(state.playingVideo!!) },
                shortsQueue = state.shortsQueue,
                shortsIndex = state.shortsIndex,
                onNavigateShorts = { state.navigateShorts(it) }
            )

            state.playingAudio != null -> AudioPlayerScreen(
                item = state.playingAudio!!,
                hasPrevious = state.canGoPreviousAudio,
                hasNext = state.canGoNextAudio,
                onPrevious = { state.navigateAudio(-1) },
                onNext = { state.navigateAudio(1) },
                onExit = { state.exitAudio() }
            )

            // Haramayn Live (Makkah & Madinah) — opened from the Media tab's
            // shortcut; replaces the tab content with the in-app live player.
            state.showHaramaynLive -> LiveTab(
                isLandscape = isLandscape,
                onExit = { state.showHaramaynLive = false }
            )

            state.selectedTab == ContentTab.QURAN -> QuranTab(
                verse = state.verse,
                isLoading = state.verseLoading,
                onNewVerse = { state.pickNewVerse() },
                onCopyVerse = { state.copyVerse(context) },
                canGoPrevious = state.canGoPrevious,
                canGoNext = state.canGoNext,
                onPrevious = { state.goToAdjacentVerse(-1) },
                onNext = { state.goToAdjacentVerse(+1) },
                islamicDateAdjustment = state.islamicDateAdjustment,
                onAdjustDate = { state.showIslamicDateSheet = true }
            )

            state.selectedTab == ContentTab.MEDIA -> MediaTab(
                hubState = state,
                onPlayVideo = { video, queue, index ->
                    state.playVideo(video, queue, index)
                    if (index < 0) state.markMediaUpdatesSeen()
                },
                onPlayOffline = { video -> state.playAudio(video) },
                onPlayAudio = { item, queue -> state.playAudioItem(item, queue) },
                onMediaOpened = { state.markMediaUpdatesSeen() },
                onOpenHaramaynLive = { state.showHaramaynLive = true }
            )
        }
    }
}

/**
 * The hub's top bar: while a video plays it shows the video title (plus the
 * optional back action); on the Quran tab it shows the verse header with
 * share / bookmark / copy actions; Media and Live get their titles. Pass
 * [onBack] to show a back arrow (the widget passes an activity-finish; the
 * main app passes player-exit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentHubTopBar(
    state: ContentHubState,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    when {
        // While a video plays, the back arrow ALWAYS returns to the Media tab
        // (clears the player) — regardless of [onBack]. Both the widget and the
        // main app want this; [onBack] only applies to the tab top bars.
        state.playingVideo != null -> TopAppBar(
            title = {
                Text(
                    text = state.playingVideo!!.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { state.playingVideo = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        // Offline audio: back arrow closes the podcast-style player; the bar
        // shows "Now Playing" (the player body itself shows the track title).
        state.playingAudio != null -> TopAppBar(
            title = {
                Text(
                    text = "Now Playing",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { state.exitAudio() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        state.selectedTab == ContentTab.QURAN -> TopAppBar(
            title = { Text(stringResource(R.string.quran_verse_header)) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                // Search: by verse number or English translation text.
                IconButton(
                    onClick = { state.showSearchSheet = true },
                    enabled = state.verse != null && !state.verseLoading
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.quran_search)
                    )
                }
                // Settings: the verse refresh interval and the notification
                // toggles — everything this sheet decides. The To Do, Dhikr,
                // Bookmarks and Phone Limit cards that used to sit under them are
                // no longer features of the Quran tab; they live in More, and a
                // gear is the honest icon for what is left (§4).
                IconButton(
                    onClick = { state.showSettingsSheet = true },
                    enabled = state.verse != null && !state.verseLoading
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.quran_settings_title)
                    )
                }
                IconButton(
                    onClick = { state.toggleBookmark(context) },
                    enabled = state.verse != null && !state.verseLoading
                ) {
                    // Filled + primary-tinted when bookmarked, outlined + muted
                    // otherwise — the state change is unmistakable at a glance.
                    Icon(
                        imageVector = if (state.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                        contentDescription = stringResource(R.string.quran_bookmark),
                        tint = if (state.isBookmarked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Notifications: everything the app has to say (§5) — channel
                // updates, To Do reminders, the Quran cadence — behind one bell
                // with one count, rather than a Media-only one.
                IconButton(
                    onClick = { state.openNotificationsPanel() },
                    enabled = state.verse != null && !state.verseLoading
                ) {
                    if (state.unreadNotificationCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(state.unreadNotificationCount.coerceAtMost(99).toString())
                                }
                            }
                        ) {
                            Icon(
                                Icons.Filled.Notifications,
                                contentDescription = stringResource(R.string.quran_notifications_title),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = stringResource(R.string.quran_notifications_title)
                        )
                    }
                }
            }
        )

        // The Media tab, and only the Media tab: `ContentTab` has two members,
        // and Quran is handled above. The third branch that used to sit here was
        // the Clear Feed tab's, and it is gone with the tab itself.
        else -> TopAppBar(
            title = { Text(stringResource(R.string.media_tab)) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            }
        )
    }

    ContentHubOverlays(state)
}

/**
 * The full-screen surfaces the hub can open over whatever is behind them: the
 * Quran reader's search / settings / notification sheets, and the To Do, Dhikr
 * and Phone Limit screens.
 *
 * Separate from [ContentHubTopBar] because they are no longer reachable from the
 * Quran tab alone (§4, §7): More opens the same three utilities, and a utility
 * that could only be reached by first switching to the Quran tab would leave the
 * More tab looking like a second, emptier copy of that menu.
 */
@Composable
fun ContentHubOverlays(state: ContentHubState) {
    // Sheets opened from the Quran tab top bar: search, settings, notifications
    // and the bookmarks manager (the latter is opened from the settings sheet).
    if (state.showSearchSheet) {
        QuranSearchScreen(state = state, onDismiss = { state.showSearchSheet = false })
    }
    if (state.showSettingsSheet) {
        QuranSettingsSheet(state = state, onDismiss = { state.showSettingsSheet = false })
    }
    if (state.showNotificationsSheet) {
        NotificationsSheet(state = state, onDismiss = { state.showNotificationsSheet = false })
    }
    if (state.showDhikrCounter) {
        DhikrCounterScreen(onDismiss = { state.showDhikrCounter = false })
    }
    if (state.showTodoScreen) {
        TodoScreen(onDismiss = {
            state.showTodoScreen = false
            // Completing a todo is the way its notification goes away, and the
            // To Do screen is where that happens — so the list is rebuilt the
            // moment it closes rather than at the next launch.
            state.refreshTodoReminders()
        })
    }
    if (state.showIslamicDateSheet) {
        IslamicDateAdjustmentSheet(
            adjustment = state.islamicDateAdjustment,
            onSelect = { state.changeIslamicDateAdjustment(it) },
            onDismiss = { state.showIslamicDateSheet = false }
        )
    }
    // Phone Limit: countdown that locks the phone when it expires. Opened from
    // the More tab's card (and from its widget's deep link).
    if (state.showPhoneLimitSheet) {
        PhoneLimitSheet(onDismiss = { state.showPhoneLimitSheet = false })
    }
}

// ── Constants shared with QuranTab's interval picker ──────────────

/** The only verse-refresh intervals offered (1/3/6/12/24 hr). */
val VERSE_INTERVAL_OPTIONS = listOf(1, 3, 6, 12, 24)
private const val DEFAULT_REFRESH_INTERVAL_HOURS = 6

/** Icon + label pair used by the shared bottom navigation. */
data class ContentHubNavItem(
    val tab: ContentTab,
    val icon: @Composable () -> Unit,
    val label: String
)

/** Standard hub navigation items (Quran, Media, Live) with resource labels. */
@Composable
fun contentHubNavItems(): List<ContentHubNavItem> = listOf(
    ContentHubNavItem(
        ContentTab.QURAN,
        { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
        stringResource(R.string.quran_tab)
    ),
    ContentHubNavItem(
        ContentTab.MEDIA,
        { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
        stringResource(R.string.media_tab)
    )
)

/**
 * Hides the system bars for immersive playback when [isFullscreen] (landscape
 * with a playing video or the live tab); restores them otherwise. No-op when
 * the current context isn't an [Activity].
 */
@Composable
fun ApplyImmersiveIfNeeded(isFullscreen: Boolean) {
    val activity = LocalActivity.current
    LaunchedEffect(isFullscreen) {
        if (activity != null) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
