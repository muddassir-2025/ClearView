package com.muddassir.clearview.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.muddassir.clearview.quran.model.QuranVerse
import com.muddassir.clearview.quran.util.copyVerseToClipboard
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

    // Autofocus + open the keyboard the moment the screen opens. The short
    // delay lets the dialog window attach before the IME is asked to show —
    // calling show() in the same frame is silently dropped on some devices.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        delay(150)
        keyboard?.show()
    }

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
                    }
                }

                // ── The field, on its own line under them ──
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
                                    // In the reader the field narrows the surah,
                                    // which is the only thing it can usefully do
                                    // there.
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
@Composable
private fun SurahReader(
    state: ContentHubState,
    surahNumber: Int,
    query: String
) {
    var verses by remember(surahNumber) { mutableStateOf<List<QuranVerse>?>(null) }

    // Parsed once per surah per process (the state caches it), off the main
    // thread — a 286-verse surah is a real parse.
    LaunchedEffect(surahNumber) {
        verses = withContext(Dispatchers.IO) { state.surahVerses(surahNumber) }
    }

    val all = verses
    val q = query.trim()
    val shown = remember(all, q) {
        val list = all ?: return@remember emptyList()
        if (q.isEmpty()) list
        else {
            val lower = q.lowercase()
            list.filter {
                it.text.lowercase().contains(lower) ||
                    "${it.surahNumber}:${it.ayahNumber}".contains(lower) ||
                    it.ayahNumber.toString() == lower
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // What is open and how long it is. The counts come from the verses that
        // loaded, so they can never disagree with the list under them. The surah's
        // name itself is in the top row (next to the back arrow), so this is the
        // reading position rather than a second title.
        Text(
            text = when {
                all == null -> stringResource(R.string.quran_bookmarks_loading)
                q.isNotEmpty() -> pluralStringResource(
                    R.plurals.quran_surah_matches_count,
                    shown.size,
                    shown.size,
                    all.size
                )
                else -> pluralStringResource(
                    R.plurals.quran_surah_verses_count,
                    all.size,
                    all.size
                )
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

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
            shown.isEmpty() -> Text(
                text = stringResource(R.string.quran_surah_no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { "${it.surahNumber}:${it.ayahNumber}" }) { verse ->
                    SurahVerseRow(
                        verse = verse,
                        query = q,
                        bookmarked = "${verse.surahNumber}:${verse.ayahNumber}" in
                            state.bookmarkKeys,
                        onToggleBookmark = {
                            state.toggleBookmarkAt(verse.surahNumber, verse.ayahNumber)
                        }
                    )
                }
            }
        }
    }
}

/**
 * One verse of the reader: reference, the Arabic, the translation, and the two
 * per-verse actions a reader actually uses — bookmark and copy.
 *
 * Deliberately not a tap target. Tapping a verse used to mean "make this the
 * current verse", which in a list of 286 rows is a way to lose your place; the
 * actions are on the row instead, where they cannot be hit by accident.
 */
@Composable
private fun SurahVerseRow(
    verse: QuranVerse,
    query: String,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.quran_ayah_progress_short,
                        verse.ayahNumber,
                        verse.totalAyahs
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    copyVerseToClipboard(context, verse)
                    Toast.makeText(
                        context,
                        context.getString(R.string.quran_verse_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.quran_verse_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (bookmarked) Icons.Filled.Bookmark
                        else Icons.Outlined.Bookmark,
                        contentDescription = stringResource(
                            if (bookmarked) R.string.quran_bookmark_removed
                            else R.string.quran_bookmark
                        ),
                        tint = if (bookmarked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (verse.arabicText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = verse.arabicText,
                    fontSize = 23.sp,
                    lineHeight = 40.sp,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(Modifier.height(8.dp))
            // bodyLarge rather than bodyMedium: this is the text a reader is
            // actually here to read, one verse at a time, and the smaller size
            // that was right for a two-line search snippet is not right for a
            // page of scripture.
            Text(
                text = highlightMatches(
                    verse.text,
                    query,
                    MaterialTheme.colorScheme.primary
                ),
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
