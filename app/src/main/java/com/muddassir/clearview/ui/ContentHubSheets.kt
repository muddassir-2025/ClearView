package com.muddassir.clearview.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostNotifications
import com.muddassir.clearview.goodpost.data.GoodPostUpdateScheduler
import com.muddassir.clearview.media.worker.MediaWorkScheduler
import com.muddassir.clearview.quran.data.QuranJsonParser
import com.muddassir.clearview.quran.data.verseReference
import com.muddassir.clearview.quran.model.QuranVerse
import com.muddassir.clearview.quran.util.copyVerseToClipboard
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings bottom sheet (opened from the Quran tab's gear icon): the verse
 * refresh interval (presets + a custom slider) and the notification toggles.
 * Permission is requested when a toggle is turned ON without the OS permission,
 * and once on first open if a toggle already defaults ON.
 *
 * ## What is deliberately NOT here (§4)
 *
 * The To Do, Dhikr, Bookmarks and Phone Limit cards used to sit under these
 * settings, which made the Quran tab's menu the app's junk drawer: a reader who
 * wanted their todo list had to open the Quran, then a sheet, then a card. They
 * are ClearView features rather than Quran settings, and they are cards in the
 * More tab now. What is left is everything this sheet actually decides — when the
 * next verse arrives, and what the app may notify about.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuranSettingsSheet(state: ContentHubState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val presets = VERSE_INTERVAL_OPTIONS
    val interval = state.refreshIntervalHours

    // Notification permission (Android 13+). The launcher remembers WHICH
    // toggle asked, so a grant applies it to the right setting.
    val deniedMessage = stringResource(R.string.media_notification_permission_denied)
    var pendingPermissionApply by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingPermissionApply(true)
        } else {
            // Turn the requesting toggle back OFF so we never re-prompt on the
            // next visit, then explain why.
            pendingPermissionApply(false)
            Toast.makeText(context, deniedMessage, Toast.LENGTH_SHORT).show()
        }
    }
    // Fresh-install courtesy: a toggle defaults ON but the OS permission may be
    // missing — ask once while the settings sheet is open. Granting from this
    // auto-prompt must ALSO kick an immediate media check, otherwise channels
    // that already uploaded wait up to an hour for the periodic worker.
    LaunchedEffect(Unit) {
        if ((state.mediaNotificationsEnabled || state.quranNotificationsEnabled ||
                state.todoNotificationsEnabled) &&
            needsNotificationPermission(context)
        ) {
            pendingPermissionApply = { granted ->
                if (granted && state.mediaNotificationsEnabled) {
                    // Same as flipping the media toggle ON: kick an immediate
                    // check AND refresh the in-app "Latest Updates" feed.
                    MediaWorkScheduler.checkNow(context)
                    state.refreshMediaUpdates()
                }
            }
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.quran_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))
            // ── 1. Quran / New Verse ──
            Text(
                text = stringResource(R.string.quran_settings_section_quran),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.quran_verse_refresh_frequency),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { hours ->
                    FilterChip(
                        selected = hours == interval,
                        onClick = { if (hours != interval) state.changeInterval(context, hours) },
                        label = { Text(stringResource(R.string.quran_verse_hour_short, hours)) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = pluralStringResource(R.plurals.quran_verse_refresh_note_hours, interval, interval),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── 2. Notifications ──
            Text(
                text = stringResource(R.string.quran_notifications_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            // ── Media notifications toggle ──
            SettingsToggleRow(
                icon = {
                    Icon(
                        imageVector = if (state.mediaNotificationsEnabled) Icons.Filled.Notifications
                        else Icons.Outlined.Notifications,
                        contentDescription = null,
                        tint = if (state.mediaNotificationsEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                },
                title = stringResource(R.string.media_notifications),
                note = stringResource(R.string.media_notifications_note),
                checked = state.mediaNotificationsEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        pendingPermissionApply = { granted ->
                            state.setMediaNotifications(granted, context)
                        }
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        state.setMediaNotifications(enabled, context)
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── Quran notifications toggle ──
            SettingsToggleRow(
                icon = {
                    Icon(
                        imageVector = if (state.quranNotificationsEnabled) Icons.Filled.Notifications
                        else Icons.Outlined.Notifications,
                        contentDescription = null,
                        tint = if (state.quranNotificationsEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                },
                title = stringResource(R.string.quran_notifications),
                note = stringResource(R.string.quran_notifications_note),
                checked = state.quranNotificationsEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        pendingPermissionApply = { granted ->
                            state.setQuranNotifications(granted)
                        }
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        state.setQuranNotifications(enabled)
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── Good Post notifications toggle ──
            //
            // This sheet is where the app keeps every "tell me when there is
            // something new" decision, so a reader should not have to open Good
            // Post to make this one. Its state is read from Good Post's own store
            // rather than through this tab's ViewModel: the value belongs to that
            // feature, and a second copy here is how the two end up disagreeing.
            var goodPostNotifications by remember {
                mutableStateOf(GoodPostNotifications.isEnabled(context))
            }
            SettingsToggleRow(
                icon = {
                    Icon(
                        imageVector = if (goodPostNotifications) Icons.Filled.Campaign
                        else Icons.Outlined.Campaign,
                        contentDescription = null,
                        tint = if (goodPostNotifications) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                },
                title = stringResource(R.string.goodpost_notification_channel),
                note = stringResource(R.string.goodpost_notifications_master_note),
                checked = goodPostNotifications,
                onCheckedChange = { enabled ->
                    val apply = { granted: Boolean ->
                        goodPostNotifications = granted
                        GoodPostNotifications.setEnabled(context, granted)
                        // The schedule always exists and the worker reads the
                        // switch; turning it on also checks once, so the first
                        // notification is about a post that arrives after now.
                        GoodPostUpdateScheduler.ensureScheduled(context)
                        if (granted) GoodPostUpdateScheduler.checkNow(context)
                    }
                    if (enabled && needsNotificationPermission(context)) {
                        pendingPermissionApply = apply
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        apply(enabled)
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── Todo reminders toggle ──
            SettingsToggleRow(
                icon = {
                    Icon(
                        imageVector = if (state.todoNotificationsEnabled) Icons.Filled.Notifications
                        else Icons.Outlined.Notifications,
                        contentDescription = null,
                        tint = if (state.todoNotificationsEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                },
                title = stringResource(R.string.todo_notifications),
                note = stringResource(R.string.todo_notifications_note),
                checked = state.todoNotificationsEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        pendingPermissionApply = { granted ->
                            state.setTodoNotifications(granted)
                        }
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        state.setTodoNotifications(enabled)
                    }
                }
            )

        }
    }
}

/**
 * One tab of the Quran screen's top row: Search / Surah / Bookmarks (§1).
 *
 * A text segment rather than a Material `FilterChip`, for two reasons that both
 * come from this being a three-way switch rather than a filter: a chip reserves
 * space for a leading check mark (so the row jitters as the selection moves and
 * the three never line up), and chips size themselves to their labels, which
 * makes "Search" narrow and "Bookmarks" wide. Equal thirds that re-colour when
 * picked read as one control, which is what it is.
 */
@Composable
private fun QuranSegment(
    selected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 4.dp)
        )
    }
}

/**
 * The Quran screen's three views (§1–§3), held in [ContentHubState.quranSheetTab]
 * so switching between them — or opening a surah and coming back — never
 * re-parses anything, and so the screen returns where the reader left it.
 *
 * Full-screen search (opened from the Quran tab's search icon): one search field
 * above three tabs.
 *
 * * **Search** — verses matching the field, debounced, matches highlighted.
 * * **Surah** — all 114 surahs, filtered by the same field; tapping one opens the
 *   *continuous* reader for that surah, where the field filters within the surah
 *   instead. The old behaviour jumped to a single verse and left the reader
 *   stepping through the surah one screen at a time; a surah is read by
 *   scrolling it (§2).
 * * **Bookmarks** — every verse this device has saved, filtered by the same
 *   field, with per-item removal.
 *
 * The single field is the point: the three tabs are three questions about the
 * same text, and a reader who types "mercy" then taps Surah expects the filter to
 * follow them rather than start over.
 *
 * Implemented as a full-screen dialog so the lists get the whole screen instead
 * of fighting a bottom sheet for space with the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuranSearchScreen(state: ContentHubState, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<QuranVerse>?>(null) }
    var bookmarks by remember { mutableStateOf<List<QuranVerse>?>(null) }
    // Verse awaiting removal confirmation (null = no dialog). Removing a bookmark
    // is destructive, so the list always asks Remove / Cancel first.
    var pendingRemove by remember { mutableStateOf<QuranVerse?>(null) }
    var searching by remember { mutableStateOf(false) }
    // The reader's field is behind its search icon, and starts closed (§2).
    //
    // The three tabs are three QUESTIONS, so a field on screen is what they are:
    // the reader arrives and types. A surah is a piece of scripture, and opening
    // it behind a keyboard with the text pushed up out of the way is not reading
    // it — so the icon is there when it is wanted, and the Quran is what is on
    // screen when it is not.
    var readerSearchOpen by remember { mutableStateOf(false) }
    val tab = state.quranSheetTab
    val openSurah = state.openSurahNumber
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    // Debounced search; the other two tabs (and a blank query) show their own
    // content and need no request.
    LaunchedEffect(query, tab) {
        if (tab != QuranSheetTab.SEARCH || query.isBlank()) {
            searching = false
            results = null
            return@LaunchedEffect
        }
        searching = true
        delay(250)
        results = withContext(Dispatchers.IO) { state.searchQuran(query) }
        searching = false
    }

    // The bookmarks, re-read whenever the tab is opened and whenever a bookmark
    // changes anywhere in the app. [ContentHubState.bookmarkKeys] is the single
    // source for "what is bookmarked", so this list and the top-bar icon cannot
    // disagree (§3, §10).
    LaunchedEffect(tab, state.bookmarkKeys) {
        if (tab == QuranSheetTab.BOOKMARKS) {
            bookmarks = withContext(Dispatchers.IO) { state.bookmarkedVerses() }
        }
    }

    // Autofocus + open the keyboard wherever there IS a field: on entry to the
    // tabbed view, and when the reader's search icon opens one. The short delay
    // lets the dialog window attach before the IME is asked to show — calling
    // show() in the same frame is silently dropped on some devices.
    //
    // Guarded rather than unconditional because the reader opens with no field at
    // all, and requesting focus on a requester that is not attached to anything
    // is an error rather than a no-op.
    LaunchedEffect(openSurah, readerSearchOpen) {
        if (openSurah != null && !readerSearchOpen) return@LaunchedEffect
        focusRequester.requestFocus()
        delay(150)
        keyboard?.show()
    }

    // Opening a different surah starts with the text, not with the last surah's
    // search term still narrowing it (§10).
    LaunchedEffect(openSurah) { readerSearchOpen = false }

    Dialog(
        onDismissRequest = onDismiss,
        // Full width + edge-to-edge so imePadding() below actually receives the
        // IME insets and the results list shrinks above the keyboard.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // ── Top bar: the back arrow, then the three tabs (§1) ──
                //
                // The arrow gets its own line's worth of height but shares the row
                // with the tabs, so the tabs sit where a reader's eye already is
                // and the field below gets the full width instead of being squeezed
                // between two controls. The tabs are equal thirds of the space the
                // arrow leaves — "centred" on a phone is this, and three unequal
                // chips would fit badly as soon as the names changed length.
                //
                // While a surah is open the row carries the surah's name instead:
                // there is nothing to switch to from inside a surah (the back
                // arrow is how you leave, §10), and the name is what the reader
                // needs to know at that point.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 12.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back leaves one layer at a time: out of a surah first, into
                    // the tabs it was opened from (§10), then out of the screen.
                    IconButton(onClick = {
                        if (openSurah != null) state.closeSurah() else onDismiss()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.quran_search_back)
                        )
                    }
                    if (openSurah == null) {
                        Row(
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QuranSegment(
                                selected = tab == QuranSheetTab.SEARCH,
                                label = stringResource(R.string.quran_search_mode_search),
                                modifier = Modifier.weight(1f),
                                onClick = { state.quranSheetTab = QuranSheetTab.SEARCH }
                            )
                            QuranSegment(
                                selected = tab == QuranSheetTab.SURAH,
                                label = stringResource(R.string.quran_search_mode_surah),
                                modifier = Modifier.weight(1f),
                                onClick = { state.quranSheetTab = QuranSheetTab.SURAH }
                            )
                            QuranSegment(
                                selected = tab == QuranSheetTab.BOOKMARKS,
                                label = stringResource(R.string.quran_search_mode_bookmarks),
                                modifier = Modifier.weight(1f),
                                onClick = { state.quranSheetTab = QuranSheetTab.BOOKMARKS }
                            )
                        }
                    } else {
                        Text(
                            text = QuranJsonParser.surahName(openSurah),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        // §10: searching INSIDE the surah, kept — as an icon that
                        // opens the field over the text rather than a field that
                        // holds a line of the screen open the whole time.
                        IconButton(onClick = {
                            readerSearchOpen = !readerSearchOpen
                            if (!readerSearchOpen) {
                                query = ""
                                keyboard?.hide()
                            }
                        }) {
                            Icon(
                                if (readerSearchOpen) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = stringResource(R.string.quran_reader_search)
                            )
                        }
                    }
                }

                // ── The field, on its own line under them ──
                //
                // Hidden in the reader until its search icon is tapped, and
                // cleared on the way out so the next surah opens whole.
                if (openSurah == null || readerSearchOpen) {
                OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                stringResource(
                                    when {
                                        // In the reader the field finds verses
                                        // inside the open surah, which is the only
                                        // thing it can usefully do there.
                                        openSurah != null -> R.string.quran_search_hint_in_surah
                                        tab == QuranSheetTab.SURAH -> R.string.quran_search_hint_surah
                                        tab == QuranSheetTab.BOOKMARKS -> R.string.quran_bookmarks_search_hint
                                        else -> R.string.quran_search_hint
                                    }
                                )
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.quran_search_clear)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                    )
                }

                Spacer(Modifier.height(4.dp))

                // A surah is open: the reader takes the whole screen below the
                // field, whichever tab it was opened from.
                if (openSurah != null) {
                    SurahReader(
                        state = state,
                        surahNumber = openSurah,
                        query = query
                    )
                    return@Column
                }

                when (tab) {
                    QuranSheetTab.SURAH -> SurahBrowseList(
                        state = state,
                        query = query
                    )

                    QuranSheetTab.BOOKMARKS -> BookmarksTab(
                        state = state,
                        query = query,
                        bookmarks = bookmarks,
                        onOpenVerse = {
                            state.goToVerse(it)
                            onDismiss()
                        },
                        onRemove = { pendingRemove = it }
                    )

                    QuranSheetTab.SEARCH -> {
                        // Capture the state once into an immutable local: `results`
                        // is a mutable state that becomes null when the query is
                        // cleared, and the lazy list content below is evaluated
                        // AFTER this `when` is chosen — reading `results!!` there
                        // would NPE on that transition.
                        val list = results
                        when {
                            searching && list == null -> Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.quran_search_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            list == null -> Text(
                                text = stringResource(R.string.quran_search_idle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                            )
                            list.isEmpty() -> Text(
                                text = stringResource(R.string.quran_search_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                            )
                            else -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.quran_search_results_count,
                                            list.size,
                                            list.size
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // Refining the query keeps previous results
                                    // visible; a small spinner shows the refresh.
                                    if (searching) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(list, key = { "${it.surahNumber}:${it.ayahNumber}" }) { verse ->
                                        VerseSearchRow(
                                            verse = verse,
                                            highlight = query,
                                            onClick = {
                                                keyboard?.hide()
                                                state.goToVerse(verse)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Remove confirmation (Remove / Cancel) ──────────────────────
    //
    // Lives on the screen rather than in the bookmarks list so the dialog is not
    // thrown away with the row it belongs to: the removed row is recomposed out
    // of the list the moment the bookmark changes, and the confirmation must
    // outlive that recomposition to be answered.
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.quran_bookmarks_remove_confirm_title)) },
            text = { Text(stringResource(R.string.quran_bookmarks_remove_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    state.removeBookmark(target.surahNumber, target.ayahNumber)
                    bookmarks = bookmarks?.filterNot {
                        it.surahNumber == target.surahNumber &&
                            it.ayahNumber == target.ayahNumber
                    } ?: emptyList()
                    pendingRemove = null
                }) {
                    Text(
                        text = stringResource(R.string.quran_bookmarks_remove_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.quran_cancel))
                }
            }
        )
    }
}

/**
 * The continuous surah reader (§2): one surah, one scroll, all of it.
 *
 * ## Why it replaces the verse-by-verse flow
 *
 * The old path opened a surah's *first verse* and then moved through the rest
 * with Previous / Next — so reading Al-Kahf was 110 screens and 110 taps, each
 * one a fresh lookup, and the reader could never see where they were in the
 * surah. A surah is a text you read by scrolling, which is what this is: the
 * whole surah in one lazy list, each verse carrying the same bookmark and copy
 * actions it has everywhere else.
 *
 * ## The field still does something
 *
 * Typing filters WITHIN the surah — matching the English text or the reference
 * ("18:10") — rather than searching the whole Quran under a heading that says
 * otherwise. A reader who wants the whole Quran taps back to the Search tab.
 */
/** The two ways a surah is read: the Arabic, or the translation (§3). */
private enum class QuranReadMode { ARABIC, ENGLISH }

/**
 * The surah reader (§1–§10): one continuous scroll of scripture.
 *
 * It used to be a column of rounded CARDS, one per verse, each with its own
 * reference line and copy/bookmark buttons — which is a list of verses, not a
 * surah. Reading Quran is continuous: the eye moves down the page through the
 * text, and four borders per screen, a repeated reference row and two buttons
 * per verse interrupt exactly that. So there are no cards here at all: the text
 * sits directly on the page, separated by space and typography, and the per-verse
 * actions appear under the verse they belong to when it is tapped (§8).
 *
 * There is no Previous/Next either (§6). Those exist in the one-verse dashboard
 * screen, where a single ayah is the unit; here the whole surah is loaded at once
 * and the reader moves through it by scrolling, which is what the app's own
 * dashboard already does.
 *
 * What is NOT changed: the data source, the parse, the search, the bookmark store
 * and the copy action are all the ones that were already here (§11).
 */
@Composable
private fun SurahReader(
    state: ContentHubState,
    surahNumber: Int,
    query: String
) {
    var verses by remember(surahNumber) { mutableStateOf<List<QuranVerse>?>(null) }
    // Arabic first: this is the Quran, and the translation is what a reader turns
    // to when they want it. The mode is not persisted between surahs on purpose —
    // opening a surah fresh is opening the Quran.
    var mode by remember(surahNumber) { mutableStateOf(QuranReadMode.ARABIC) }
    // Which verse's actions are showing, by key. One at a time: a second tap
    // moves them rather than opening a second set.
    var actionsFor by remember(surahNumber) { mutableStateOf<String?>(null) }
    var matchCursor by remember(surahNumber) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Parsed once per surah per process (the state caches it), off the main
    // thread — a 286-verse surah is a real parse.
    LaunchedEffect(surahNumber) {
        verses = withContext(Dispatchers.IO) { state.surahVerses(surahNumber) }
    }

    // The opening line, taken from the edition's own 1:1 rather than typed out
    // here. This corpus is IndoPak, where the basmala is spelled
    // "بِسْمِ اللَّهِ الرَّحْمٰنِ الرَّحِيمِ" — a hand-written Uthmani rendering would
    // have put a wasla under the alef on the one line in the surah that did not
    // match the 6235 around it. Blank when the Arabic is not cached, in which
    // case the reader opens without it: a missing line is better than an
    // invented one. Al-Faatiha itself is cached after the first reader opens.
    var basmala by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        basmala = withContext(Dispatchers.IO) {
            state.surahVerses(1).firstOrNull { it.ayahNumber == 1 }?.arabicText.orEmpty()
        }
    }

    val all = verses
    val q = query.trim()
    // The VERSES that match, not a filtered list: the surah stays whole and the
    // matches are marked in it, because a reader searching inside a surah is
    // looking for a place in the text, not a separate list of extracts (§10).
    val matches = remember(all, q) {
        val list = all ?: return@remember emptyList()
        matchingVerseIndices(list, q)
    }

    // A new term starts the count over, so "next" means the first match of what is
    // in the box rather than a position left over from the previous word.
    LaunchedEffect(q, all) { matchCursor = 0 }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── What is open ──
        //
        // Centred and plain, in the same visual language as the Quran dashboard
        // (§9): the name, its translation, and the surah's own numbers. Not in a
        // card — a card here would be the very thing this screen is removing.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = QuranJsonParser.surahName(surahNumber),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            val meaning = QuranJsonParser.surahTranslation(surahNumber)
            if (meaning.isNotBlank()) {
                Text(
                    text = meaning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.quran_reader_meta,
                    surahNumber,
                    all?.size ?: 0
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // ── Arabic | English, one at a time (§3) ──
        //
        // Never side by side: two columns of two scripts on a phone is a
        // comparison table, and what a reader wants is one text they can read.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuranSegment(
                selected = mode == QuranReadMode.ARABIC,
                label = stringResource(R.string.quran_reader_arabic),
                modifier = Modifier.weight(1f),
                onClick = { mode = QuranReadMode.ARABIC }
            )
            QuranSegment(
                selected = mode == QuranReadMode.ENGLISH,
                label = stringResource(R.string.quran_reader_english),
                modifier = Modifier.weight(1f),
                onClick = { mode = QuranReadMode.ENGLISH }
            )
        }

        // ── What was found in the surah, and the way to it (§10) ──
        //
        // A count and a Next rather than a scroll that fires while the reader is
        // still typing: the text jumps under the thumb once per keystroke
        // otherwise, and the reader cannot tell which match they were taken to.
        if (q.isNotEmpty() && all != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.quran_surah_matches_count,
                        matches.size,
                        matches.size,
                        all.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (matches.isNotEmpty()) {
                    TextButton(onClick = {
                        val index = matches[matchCursor.coerceIn(0, matches.size - 1)]
                        matchCursor = (matchCursor + 1) % matches.size
                        scope.launch { listState.animateScrollToItem(index) }
                    }) {
                        Text(stringResource(R.string.quran_reader_matches_next))
                    }
                }
            }
        }

        when {
            all == null -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.quran_bookmarks_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            all.isEmpty() -> Text(
                text = stringResource(R.string.quran_verse_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                // Wider than the gaps between the cards it replaced: with no
                // borders, SPACE is what separates one ayah from the next (§7).
                verticalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                item(key = "surah-head") {
                    SurahHead(surahNumber = surahNumber, mode = mode, basmala = basmala)
                }

                itemsIndexed(
                    all,
                    key = { _, verse -> "${verse.surahNumber}:${verse.ayahNumber}" }
                ) { index, verse ->
                    val key = "${verse.surahNumber}:${verse.ayahNumber}"
                    val bookmarked = key in state.bookmarkKeys
                    val matching = index in matches

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Tap or hold reveals this verse's actions (§8). Not a
                            // tap target that CHANGES the reading position: a tap
                            // in a list of 286 rows is a way to lose your place.
                            .combinedClickable(
                                onClick = { actionsFor = if (actionsFor == key) null else key },
                                onLongClick = { actionsFor = key }
                            )
                    ) {
                        if (mode == QuranReadMode.ARABIC) {
                            ArabicVerse(
                                verse = verse,
                                query = q,
                                bookmarked = bookmarked,
                                matching = matching
                            )
                        } else {
                            EnglishVerse(
                                verse = verse,
                                query = q,
                                matching = matching
                            )
                        }

                        if (actionsFor == key) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                QuranSegment(
                                    selected = false,
                                    label = stringResource(R.string.quran_verse_copy),
                                    onClick = {
                                        copyVerseToClipboard(context, verse)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.quran_verse_copied),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        actionsFor = null
                                    }
                                )
                                QuranSegment(
                                    selected = bookmarked,
                                    label = stringResource(
                                        if (bookmarked) R.string.quran_bookmark_removed
                                        else R.string.quran_bookmark
                                    ),
                                    onClick = {
                                        state.toggleBookmarkAt(
                                            verse.surahNumber,
                                            verse.ayahNumber
                                        )
                                        actionsFor = null
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The surah's own opening (§2): the basmala, in Arabic mode only.
 *
 * A liturgical line that the corpus carries as ayah 1 of Al-Faatiha and nowhere
 * else, passed in from there rather than written here (see the reader). It opens
 * every surah except At-Tawba, which is the one surah that does not carry it. It
 * is NOT shown in English mode: it is the Arabic that is being opened, and a
 * translated basmala above a translation of the surah is a line of the reader's
 * own commentary.
 */
@Composable
private fun SurahHead(surahNumber: Int, mode: QuranReadMode, basmala: String) {
    if (mode != QuranReadMode.ARABIC) return
    if (surahNumber == 9) return
    if (basmala.isBlank()) return

    Text(
        text = basmala,
        fontSize = 24.sp,
        lineHeight = 44.sp,
        fontFamily = FontFamily.Serif,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * One ayah, as scripture (§4).
 *
 * Large Arabic type, the app's own serif face, centred, with a line height that
 * leaves the harakat room to breathe — the same treatment the Quran dashboard
 * gives a verse, minus the card. The ayah number rides INSIDE the running text as
 * the end-of-ayah ornament `۝` followed by Arabic-Indic digits, which is what a
 * printed mushaf does and what "verse numbers integrated naturally" means: no
 * badge, no separate counter, no second row.
 */
@Composable
private fun ArabicVerse(
    verse: QuranVerse,
    query: String,
    bookmarked: Boolean,
    matching: Boolean
) {
    val accent = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.colorScheme.onSurface
    val ornament = if (bookmarked) accent else body.copy(alpha = 0.55f)

    val text = remember(verse, query, bookmarked, matching, ornament, accent) {
        buildAnnotatedString {
            if (verse.arabicText.isNotBlank()) {
                append(highlightMatches(verse.arabicText, query.ifBlank { null }, accent))
                append("  ")
            }
            withStyle(SpanStyle(color = ornament, fontWeight = FontWeight.SemiBold)) {
                append("۝" + arabicDigits(verse.ayahNumber))
            }
        }
    }

    Text(
        text = text,
        fontSize = 30.sp,
        lineHeight = 52.sp,
        fontFamily = FontFamily.Serif,
        textAlign = TextAlign.Center,
        // RTL is the script's own direction — the paragraph direction is taken
        // from the first strong character, so the Arabic lays itself out from the
        // right without being told.
        modifier = Modifier
            .fillMaxWidth()
            .then(if (matching) Modifier.background(accent.copy(alpha = 0.06f)) else Modifier)
    )
}

/**
 * One ayah, as the translation (§5).
 *
 * A paragraph, not a card and not a blockquote: the reference rides at the end of
 * the sentence it belongs to — `[N]`, the way the app's other translation surfaces
 * write it — and the type is the app's serif, set at reading size with room
 * between the lines. Left-aligned: a page of centred prose is harder to read, and
 * centring is what marks the Arabic as a different thing from the words around it.
 */
@Composable
private fun EnglishVerse(verse: QuranVerse, query: String, matching: Boolean) {
    val accent = MaterialTheme.colorScheme.primary

    val text = remember(verse, query, matching, accent) {
        buildAnnotatedString {
            append(highlightMatches(verse.text, query.ifBlank { null }, accent))
            append(" ")
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append("[" + verse.ayahNumber + "]")
            }
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Serif,
        lineHeight = 32.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (matching) Modifier.background(accent.copy(alpha = 0.06f)) else Modifier)
    )
}

/**
 * The indices of the verses that match [query], in reading order (§10).
 *
 * The same three-way match the tabbed search uses, and literally the same
 * reference regex — the translation, a `2:255`-style reference, or a bare ayah
 * number — because "why does it not find 2:255" is a question nobody should have
 * to ask twice. Matched against the TRANSLATION rather than the Arabic: a reader
 * on a phone keyboard is typing Latin letters or a reference, and
 * substring-matching Arabic from a Latin keyboard is not a feature.
 *
 * Scoped to the surah it is given, which changes two of the three branches:
 * a plain number means an AYAH here (the surah is already chosen, so "16" inside
 * An-Nahl is its sixteenth ayah, not every verse of it), and a reference to
 * another surah matches nothing rather than reaching outside the text on screen.
 * Without that, typing `1` in An-Nahl matched all 128 verses, because every
 * reference in the surah starts with the digits "16".
 */
internal fun matchingVerseIndices(verses: List<QuranVerse>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val lower = q.lowercase()
    val ref = verseReference(q)
    val refSurah = ref?.first
    val refAyah = ref?.second
    val plainNumber = q.toIntOrNull()

    return verses.indices.filter { index ->
        val verse = verses[index]
        when {
            refSurah != null && refAyah != null ->
                verse.surahNumber == refSurah && verse.ayahNumber == refAyah
            plainNumber != null -> verse.ayahNumber == plainNumber
            else ->
                verse.text.lowercase().contains(lower) ||
                    verse.surahName.lowercase().contains(lower) ||
                    "${verse.surahNumber}:${verse.ayahNumber}".contains(lower)
        }
    }
}

/**
 * [value] in Arabic-Indic digits, which is what an ayah number wears in the text.
 *
 * Unicode puts the digits INSIDE the `۝` ornament by convention, and the ornament
 * is a mark, not a font — so the digits have to be the ones a mushaf uses rather
 * than Latin ones sitting next to an Arabic symbol.
 */
internal fun arabicDigits(value: Int): String =
    value.toString().map { character ->
        if (character in '0'..'9') ('\u0660' + (character - '0')) else character
    }.joinToString("")

/**
 * The Bookmarks tab (§3, §10): every saved verse, filtered by the same field the
 * other two tabs use.
 *
 * The list is passed in already loaded (the screen owns that, so switching tabs
 * does not re-read the store) and removal is reported up to the screen's own
 * confirmation dialog.
 */
@Composable
private fun BookmarksTab(
    state: ContentHubState,
    query: String,
    bookmarks: List<QuranVerse>?,
    onOpenVerse: (QuranVerse) -> Unit,
    onRemove: (QuranVerse) -> Unit
) {
    val q = query.trim()
    val filtered = remember(bookmarks, q) {
        val list = bookmarks ?: return@remember emptyList()
        if (q.isEmpty()) list
        else {
            val lower = q.lowercase()
            list.filter {
                it.text.lowercase().contains(lower) ||
                    it.surahName.lowercase().contains(lower) ||
                    "${it.surahNumber}:${it.ayahNumber}".contains(lower)
            }
        }
    }

    when {
        bookmarks == null -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.quran_bookmarks_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        bookmarks.isEmpty() -> Text(
            text = stringResource(R.string.quran_bookmarks_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
        )
        filtered.isEmpty() -> Text(
            text = stringResource(R.string.quran_bookmarks_no_match),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
        )
        else -> Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = pluralStringResource(
                    R.plurals.quran_bookmarks_count,
                    bookmarks.size,
                    bookmarks.size
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { "${it.surahNumber}:${it.ayahNumber}" }) { verse ->
                    VerseSearchRow(
                        verse = verse,
                        highlight = query,
                        onClick = { onOpenVerse(verse) },
                        onRemove = { onRemove(verse) }
                    )
                }
            }
        }
    }
}

/**
 * All 114 surahs with number + transliterated name + translation, filtered
 * live by the shared search field ([query]); tapping one opens the continuous
 * reader for it (§2). The names come from [QuranJsonParser] — pure, no network —
 * so the list renders and filters immediately.
 */
@Composable
private fun SurahBrowseList(
    state: ContentHubState,
    query: String
) {
    // Filter the 114 surahs by the shared search field: a blank query shows
    // all of them; a pure number matches the surah number exactly ("2" →
    // Surah 2); anything else is a case-insensitive substring match on the
    // transliterated name or the English translation (e.g. "baqara", "cow",
    // "ya"). All from [QuranJsonParser] — pure local data, so filtering is
    // instant with no I/O.
    val q = query.trim()
    val surahs = remember(q) {
        if (q.isEmpty()) {
            (1..114).toList()
        } else {
            val lower = q.lowercase()
            val numeric = q.all { it.isDigit() }
            (1..114).filter { number ->
                (numeric && number.toString() == q) ||
                    QuranJsonParser.surahName(number).lowercase().contains(lower) ||
                    QuranJsonParser.surahTranslation(number).lowercase().contains(lower)
            }
        }
    }

    if (surahs.isEmpty()) {
        Text(
            text = stringResource(R.string.quran_surah_no_matches),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    ) {
        items(surahs, key = { it }) { number ->
            val name = QuranJsonParser.surahName(number)
            val translation = QuranJsonParser.surahTranslation(number)
            Card(
                // Opens the whole surah, not its first verse (§2): the reader
                // scrolls from here, and the surah's own text is what it needs —
                // nothing to look up first, so the tap is instant and cannot fail.
                onClick = { state.openSurah(number) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Two-digit number badge.
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = number.toString().padStart(2, '0'),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    // titleMedium / bodyMedium rather than the smaller pair this
                    // list used: the surah names are 114 rows of the thing the
                    // reader came for, not a dense index they are skimming past.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (translation.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = translation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * A single verse row (search result or bookmark): reference, Arabic + English
 * text, tap to open. Pass [onRemove] to show a trailing remove button (used by
 * the bookmarks manager); nested clicks are consumed, so tapping remove never
 * opens the verse. Pass [highlight] to bold/color matching substrings in the
 * English text (the search query / bookmark filter).
 */
@Composable
private fun VerseSearchRow(
    verse: QuranVerse,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    highlight: String? = null
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // "2:255 · Al-Baqara · 255/286" — the compact ayah/total form is
                // appended only when the surah counts are known (they come from
                // the cached edition, so this never blocks the list).
                val progress = if (verse.totalAyahs > 0) {
                    " · " + stringResource(
                        R.string.quran_ayah_progress_short,
                        verse.ayahNumber,
                        verse.totalAyahs
                    )
                } else {
                    ""
                }
                Text(
                    text = "${verse.surahNumber}:${verse.ayahNumber} · ${verse.surahName}$progress",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onRemove != null) {
                    // Default IconButton size keeps the 48dp touch target.
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.quran_bookmarks_remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (verse.arabicText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = verse.arabicText,
                    fontSize = 18.sp,
                    lineHeight = 30.sp,
                    fontFamily = FontFamily.Serif,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = highlightMatches(verse.text, highlight, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Returns [text] with every case-insensitive occurrence of [query] styled with
 * [color] + bold (for search/bookmark result highlighting). Plain when [query]
 * is blank or purely numeric/reference-shaped ("2:255" / "255" lookups match
 * verse numbers, so highlighting text would be noise).
 */
private fun highlightMatches(text: String, query: String?, color: Color): AnnotatedString {
    val q = query?.trim().orEmpty()
    if (q.isEmpty() || q.all { it.isDigit() || it == ':' || it == '.' || it.isWhitespace() }) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        var index = 0
        val lowerText = text.lowercase()
        val lowerQuery = q.lowercase()
        while (index < text.length) {
            val match = lowerText.indexOf(lowerQuery, index)
            if (match < 0) {
                append(text, index, text.length)
                break
            }
            append(text, index, match)
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                append(text, match, match + q.length)
            }
            index = match + q.length
        }
    }
}

/**
 * The notification centre (§5): everything ClearView has to tell the reader,
 * from every feature, behind the one bell in the Quran tab's bar.
 *
 * ## Why it is grouped rather than one list
 *
 * A channel that posted, a todo that is due and a verse reminder are three
 * different kinds of thing and only one of them is news — presented flat, a
 * reader scanning for "what happened" has to read every card to find out which
 * sort it is. The section heading (from the kind's own icon and name) answers
 * that before the card does, and the count beside it says how many in that group
 * are new.
 *
 * ## What the buttons do
 *
 * "Clear all" remains what it always was — a Media action, shown only when there
 * are channel updates to clear, because it cannot clear a todo or a reminder and
 * a button that silently did nothing to half the list would be worse than none.
 * Per-card ✕ is likewise offered only by the kinds that own a row that can be
 * removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(state: ContentHubState, onDismiss: () -> Unit) {
    val context = LocalContext.current

    // Rebuilt only when a source changes. Each entry carries a relative
    // timestamp, and building the list formats none of them without a recompose
    // anyway — but rebuilding on every frame would re-read the To Do leg and
    // allocate labels for nothing.
    val entries = remember(
        state.mediaUpdates,
        state.unreadUpdateIds,
        state.dueTodoNotifications,
        state.quranNotificationsEnabled,
        state.verse,
        state.refreshIntervalHours
    ) { state.notificationEntries() }

    val hasChannelUpdates = entries.any { it.kind == HubNotificationKind.MEDIA }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.quran_notifications_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (hasChannelUpdates) {
                    TextButton(onClick = { state.clearAllUpdates() }) {
                        Text(stringResource(R.string.media_update_clear_all))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            when {
                state.mediaUpdatesLoading && entries.isEmpty() -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.media_updates_checking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                entries.isEmpty() -> {
                    // Elegant empty state: a soft icon, a title and a hint.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Icon(
                                Icons.Outlined.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(16.dp).size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.media_no_updates_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.media_no_updates_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // The kinds in a fixed order rather than by time, so the
                        // reader learns where to look: channel news first, then
                        // what they owe today, then the standing reminder.
                        HubNotificationKind.entries.forEach { kind ->
                            val group = entries.filter { it.kind == kind }
                            if (group.isEmpty()) return@forEach
                            HubNotificationSectionHeader(
                                kind = kind,
                                unread = group.count { it.unread }
                            )
                            group.forEach { entry ->
                                HubNotificationCard(
                                    entry = entry,
                                    relativeTime = relativeUpdateTime(context, entry.at),
                                    onClick = entry.onOpen,
                                    onDismiss = entry.onDismiss
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact relative timestamp: "Just now", "10m ago", "3h ago", "Yesterday",
 * "4d ago", then a short date ("Aug 10").
 */
private fun relativeUpdateTime(context: Context, epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val dayDiff = LocalDate.now(zone).toEpochDay() - then.toLocalDate().toEpochDay()
    val diff = System.currentTimeMillis() - epochMillis
    return when {
        diff < 60_000L -> context.getString(R.string.media_updates_relative_just_now)
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        dayDiff <= 0L -> "${(diff / 3_600_000L).coerceAtLeast(1L)}h ago"
        dayDiff == 1L -> context.getString(R.string.media_updates_relative_yesterday)
        dayDiff < 7L -> "${dayDiff}d ago"
        else -> DateTimeFormatter.ofPattern("MMM d").format(then.toLocalDate())
    }
}

/** One labeled toggle row used by the settings sheet. */
@Composable
private fun SettingsToggleRow(
    icon: @Composable () -> Unit,
    title: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Islamic Date Adjustment bottom sheet (opened from the Quran tab's edit icon
 * beside the Islamic date): −1 day / Default / +1 day around the default
 * Umm al-Qura date. Selecting updates the "Current adjustment" line live and
 * persists via [ContentHubState.changeIslamicDateAdjustment]; the date under the
 * verse updates immediately behind the sheet. The informational note appears
 * ONLY here — never on the main Quran screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IslamicDateAdjustmentSheet(
    adjustment: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.islamic_date_adjustment_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(-1, 0, 1).forEach { days ->
                    FilterChip(
                        selected = adjustment == days,
                        onClick = { onSelect(days) },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                stringResource(
                                    when (days) {
                                        -1 -> R.string.islamic_date_adjustment_minus
                                        1 -> R.string.islamic_date_adjustment_plus
                                        else -> R.string.islamic_date_adjustment_default
                                    }
                                )
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    R.string.islamic_date_current,
                    stringResource(
                        when (adjustment) {
                            -1 -> R.string.islamic_date_adjustment_minus
                            1 -> R.string.islamic_date_adjustment_plus
                            else -> R.string.islamic_date_zero_days
                        }
                    )
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.islamic_date_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Whether the app still needs the runtime notification permission (Android 13+). */
private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}
