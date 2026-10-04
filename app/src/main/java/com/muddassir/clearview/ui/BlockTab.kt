package com.muddassir.clearview.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.muddassir.clearview.R
import com.muddassir.clearview.brainrot.BrainRotClient
import com.muddassir.clearview.viewmodel.MainViewModel

/**
 * Block tab — the password-protected security dashboard.
 *
 * Cards (top to bottom):
 *  1. Protection toggle — reflects the Accessibility Service state, turns
 *     green when active, and opens the system settings to enable/disable it.
 *  2. Strict Mode — adds the curated risky-but-innocent discovery terms
 *     (bikini, lingerie, cleavage, ...) on top of the always-on adult terms.
 *  3. Block Shorts — blocks YouTube Shorts in Chrome and the YouTube app
 *     (no need to add "shorts" as a keyword).
 *  4. YouTube Chrome Test — Stage-1 experiment: Shorts + long-video blocking
 *     in Chrome (pause + protection overlay).
 *  5. YouTube Chrome Test Keywords — separate test-only keyword list.
 *  6. Blocked Items — dotted editable list card: add / view keywords and
 *     websites.
 *  7. DNS protection — network-level filtering.
 *  8. Advanced — device admin, uninstall protection, app lock.
 *
 * Every card carries an info (i) icon that expands the FULL context of what
 * that feature does — tap it on any card to see everything it covers. That is
 * what lets each on-card summary stay ONE short line.
 *
 * Locking: the filtering features (2-4) are enforced BY the Protection
 * service, so while it is off their switches are DISABLED and a single hint
 * above the group says why. DNS, the device-admin features and the Block-tab
 * password are independent of it and stay usable.
 */
@Composable
fun BlockTab(
    viewModel: MainViewModel,
    deviceAdminLauncher: ActivityResultLauncher<Intent>
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Every feature below is enforced BY the Protection service, so while
        // it is off their switches are locked (one shared hint says why,
        // instead of repeating it on each card).
        val protectionOn = viewModel.isAccessibilityEnabled

        // What this screen protects, and where — stated once, up front, so the
        // page explains itself before the user reads a single switch.
        item { BlockHeader(viewModel) }

        item { ProtectionCard(viewModel, context) }

        // ── YOUTUBE PROTECTION ─────────────────────────────────────
        //
        // Deliberately its own section, and deliberately BEFORE the global one.
        // These rules apply to YouTube and nothing else — a keyword added here
        // will never block a news site — and keeping them in one place is what
        // makes that true rather than something the user has to infer.
        item { SectionHeading(R.string.block_youtube_section) }
        if (!protectionOn) {
            item { LockedGroupHint() }
        }
        item { BrainRotProtectionCard(viewModel, locked = !protectionOn) }
        item { BlockShortsCard(viewModel, locked = !protectionOn) }
        item { BrainRotKeywordsCard(viewModel) }
        item { BrainRotChannelsCard(viewModel) }

        // ── MY BLOCKED ITEMS (global / every website) ──────────────
        //
        // Everything in this section applies across Chrome and every website
        // the user visits through it. There are no built-in keywords here: the
        // list is exactly what the user added, so an empty list means nothing
        // extra is being blocked.
        item { SectionHeading(R.string.block_my_items_section) }
        if (!protectionOn) {
            item { LockedGroupHint() }
        }
        item { StrictModeCard(viewModel, context, locked = !protectionOn) }
        item { BlockedItemsCard(viewModel) }

        // ── GLOBAL REPOSITORY ──────────────────────────────────────
        //
        // Rules an administrator approved for EVERYONE, plus this device's own
        // submissions and their status. Read-only from here: the way to change a
        // global rule is to submit one, which is the next card down.
        item { SectionHeading(R.string.block_global_section) }
        item { GlobalRulesCard(viewModel) }
        item { MySubmissionsCard(viewModel) }

        // ── PROTECTION ACTIVITY ────────────────────────────────────
        item { SectionHeading(R.string.block_activity_title) }
        item { ActivityCard(viewModel) }

        // ── ADVANCED ───────────────────────────────────────────────
        // Device admin, uninstall protection, the block-tab password and DNS all
        // live here: they are setup-once, rarely-touched settings, and keeping
        // them below the everyday switches is what makes the top of the page a
        // protection screen rather than a settings list.
        item { SectionHeading(R.string.block_advanced_section) }
        item { DeviceAdminCard(viewModel, context, deviceAdminLauncher) }
        item { UninstallProtectionCard(viewModel, context) }
        item { AppLockCard(viewModel) }
        item { DnsCard(viewModel, context) }

        // ── Privacy ────────────────────────────────────────────────
        item { PrivacyCard() }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

// ── Shared: page header + section headings ───────────────────────

/**
 * The page's opening line, and the notification bell.
 *
 * The scope is deliberately plain — "websites in Chrome and Google Search" —
 * because a user who cannot tell WHAT is protected cannot trust any of it. The
 * bell sits here because this is the tab where a person comes to see what
 * ClearView has been doing, and the notifications are the answer to that.
 */
@Composable
private fun BlockHeader(viewModel: MainViewModel) {
    var showNotifications by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.block_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            // The bell, with its unread count. Tapping opens the centre.
            Box {
                IconButton(onClick = { showNotifications = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = stringResource(R.string.block_notifications_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val unread = viewModel.unreadNotificationCount
                if (unread > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (unread > 9) "9+" else unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.block_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showNotifications) {
        NotificationCentreSheet(
            viewModel = viewModel,
            onDismiss = { showNotifications = false }
        )
    }
}

/**
 * The notification centre.
 *
 * Opens as a full screen, the same way every list on this tab does, because a
 * notification is a thing with a value, a status and a time in it — and a
 * dialog that printed one undifferentiated paragraph per item made all three
 * hard to find. Each entry is a tinted row with the icon its KIND deserves, the
 * value in bold, the explanation underneath and the time on the right, so the
 * list can be read down the middle.
 */
@Composable
private fun NotificationCentreSheet(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val items = viewModel.notifications

    BlockManagerDialog(
        title = stringResource(R.string.block_notifications_title),
        onDismiss = onDismiss
    ) {
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.block_notifications_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.markAllNotificationsRead() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.block_notifications_mark_read), fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.clearNotifications()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.block_notifications_clear), fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            items.forEach { item -> NotificationRow(item) }
        }
    }
    // Opening the centre is the moment the badge stops meaning anything.
    LaunchedEffect(Unit) { viewModel.markAllNotificationsRead() }
}

/** One notification: its kind's icon, the value, the message and the time. */
@Composable
private fun NotificationRow(item: com.muddassir.clearview.brainrot.NotificationStore.Item) {
    val accent = when (item.kind) {
        com.muddassir.clearview.brainrot.NotificationStore.Kind.BLOCKED ->
            MaterialTheme.colorScheme.onSurfaceVariant
        com.muddassir.clearview.brainrot.NotificationStore.Kind.GLOBAL_APPROVED -> Color(0xFF2E7D32)
        com.muddassir.clearview.brainrot.NotificationStore.Kind.GLOBAL_REJECTED ->
            MaterialTheme.colorScheme.error
        com.muddassir.clearview.brainrot.NotificationStore.Kind.GLOBAL_SUBMITTED ->
            MaterialTheme.colorScheme.primary
    }
    val icon = when (item.kind) {
        com.muddassir.clearview.brainrot.NotificationStore.Kind.BLOCKED -> Icons.Outlined.Block
        com.muddassir.clearview.brainrot.NotificationStore.Kind.GLOBAL_APPROVED -> Icons.Filled.CheckCircle
        com.muddassir.clearview.brainrot.NotificationStore.Kind.GLOBAL_REJECTED -> Icons.Filled.Close
        com.muddassir.clearview.brainrot.NotificationStore.Kind.GLOBAL_SUBMITTED -> Icons.Outlined.Upload
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName ?: item.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = relativeTime(item.atMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A short, human "how long ago" for a notification. */
private fun relativeTime(atMs: Long): String {
    val delta = System.currentTimeMillis() - atMs
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

/** A quiet all-caps heading that groups the cards below it. */
/**
 * A section label: a short accent bar, then the section name.
 *
 * The bar is what makes the page scannable — sections read as groups at a
 * glance instead of one long column of same-shaped cards, which is what made
 * the old layout feel unstructured.
 */
@Composable
private fun SectionHeading(@StringRes titleRes: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(titleRes).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = 0.8.sp
        )
    }
}

// ── Shared: info icon + full-context details expander ────────────

/**
 * Small info (i) icon button on a card header. Tapping it toggles the card's
 * expanded FULL-CONTEXT details section (FeatureDetailBlock) — everything that
 * feature covers, since the one-line summaries can't hold it all.
 */
@Composable
private fun InfoToggleButton(expanded: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (expanded) Icons.Filled.Info else Icons.Outlined.Info,
            contentDescription = if (expanded) "Hide details" else "Show full context",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The expanded body shown under a card when its info icon is tapped. */
@Composable
private fun FeatureDetailBlock(bullets: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        bullets.forEach { bullet ->
            Text(
                text = "• $bullet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Shared: collapsible header chevron ──────────────────────────

/** The down/up chevron a compact card uses to show it can be expanded. */
@Composable
private fun ExpandChevron(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp)
    )
}

// ── Shared: card header with an icon badge ──────────────────────

/**
 * The header every feature card shares: a tinted icon badge, a title, one
 * subtitle line, then whatever trailing control the card needs and — when the
 * card expands — a chevron. One header shape across the tab is what stops the
 * page reading as a pile of differently-built boxes.
 */
@Composable
private fun CardHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val click = onClick ?: onToggle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (click != null) Modifier.clickable { click() } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
        if (onToggle != null) {
            Spacer(modifier = Modifier.width(4.dp))
            ExpandChevron(expanded)
        }
    }
}

/** The right-pointing arrow a compact card uses to say "this opens a screen". */
@Composable
private fun ManageArrow(onClick: (() -> Unit)? = null) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = "Open",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .size(24.dp)
    )
}

// ── Shared: a full-screen manager opened from a card's arrow ─────

/**
 * The screen a compact card's arrow opens.
 *
 * The cards on the tab are deliberately one line each — exact counts, and an
 * arrow. Everything you can DO with the list lives here, on a full screen with
 * room to breathe, rather than being crammed under the card where it made the
 * page read as a wall of controls.
 */
@Composable
private fun BlockManagerDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .safeDrawingPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

// ── Shared: one compact, editable list row ──────────────────────

/**
 * One entry in a blocked-items list: a leading glyph, the value on a single
 * line, then the actions.
 *
 * This replaces the old rows that put a submit button and a delete button side
 * by side with a weighted text column. When the value was long the column lost
 * the width fight and the text wrapped one character per line — the "keyword
 * appears vertically" bug — and the two tap targets sat on top of each other.
 * Here the text is always one ellipsised line and the actions are fixed-size
 * icon buttons, so nothing can overlap whatever the value is.
 */
@Composable
private fun BlockListItem(
    label: String,
    icon: ImageVector,
    onRemove: () -> Unit,
    removeLabel: String,
    subtitle: String? = null,
    onSend: (() -> Unit)? = null,
    sendLabel: String = "Send to global review"
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onSend != null) {
            IconButton(onClick = onSend, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = sendLabel,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = removeLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** A read-only rule row, for lists the user cannot edit (the global rules). */
@Composable
private fun RuleRow(label: String, icon: ImageVector, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The "waiting on a decision" group a manager screen shows above its own list.
 *
 * A submission is inert — nothing is blocked by it — so it is drawn as its own
 * labelled, tinted group rather than as a row in the list it was sent from. A
 * pending request that sat among real rules would read as one. Nothing here has
 * a Send action: it is already sent.
 */
@Composable
private fun QueuedForReview(
    items: List<BrainRotClient.SubmissionStatus>,
    icon: ImageVector
) {
    if (items.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1565C0).copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Queued for review",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1565C0)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Waiting on an administrator. Not blocking anyone yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { submission ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF1565C0),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = submission.displayName ?: submission.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    SubmissionStatusPill(submission.status)
                }
            }
        }
    }
}

/**
 * The group of this device's requests that an administrator APPROVED.
 *
 * Approved rules are global now, so they are drawn separately from the user's
 * own blocks — and separately from the queue, because "waiting" and "live" are
 * different facts about a request. Keeping them out of the user's own list is
 * also what keeps that list honest: it holds only what the user themselves put
 * there and can therefore remove.
 */
@Composable
private fun ApprovedGlobally(
    items: List<BrainRotClient.SubmissionStatus>,
    icon: ImageVector
) {
    if (items.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF2E7D32).copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Approved globally",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2E7D32)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Live for every ClearView user. You do not need to send it again.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { submission ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = submission.displayName ?: submission.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    SubmissionStatusPill("approved")
                }
            }
        }
    }
}

// ── Shared: one clean "type and add" control ────────────────────

/**
 * A single rounded row that holds the input and its add button.
 *
 * The keyword/channel/website editors used to stack a full labelled
 * OutlinedTextField, a floating label and stray helper text above every list —
 * three heavy blocks per list, which is what made those cards feel cluttered.
 * This is one quiet row, so the card is mostly the LIST it exists to show.
 */
@Composable
private fun AddField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onAdd: () -> Unit
) {
    val canAdd = value.trim().isNotEmpty()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                }
            )
            FilledIconButton(
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── 1. Protection toggle ──────────────────────────────────────────

@Composable
private fun ProtectionCard(viewModel: MainViewModel, context: Context) {
    val isEnabled = viewModel.isAccessibilityEnabled
    val activeGreen = Color(0xFF2E7D32)
    // Prominent disclosure + consent for the Accessibility Service (Google Play
    // policy for non-accessibility-tool apps): shown whenever the user attempts
    // to ENABLE protection. The service is described, its on-device nature is
    // stated, and consent is explicit — system Settings is only opened after
    // the user taps Continue. Disabling stays one tap away as before.
    var showDisclosure by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    // A single compact hero row: icon, name, one status line, the switch and the
    // info button. The old card printed a whole paragraph here on top of the
    // status line and pushed every real control below the fold; the paragraph
    // lives behind the (i) now, which is what it is for.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) activeGreen else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Protection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isEnabled) "Active · watching Chrome & Google" else "Off — tap to enable",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEnabled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { _ ->
                        if (!isEnabled) {
                            // Enabling: require explicit consent via the
                            // prominent-disclosure dialog before the user is
                            // sent to system Settings to turn the service on.
                            showDisclosure = true
                        } else {
                            // Disabling: no disclosure needed — straight to
                            // settings. The service can't be toggled
                            // programmatically.
                            viewModel.openAccessibilitySettings(context)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF1B5E20),
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }
            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Always-on adult filter — explicit websites, searches and video content are blocked in Chrome and the Google app.",
                        "Incognito mode — detected and closed automatically, so blocked content can never be reached privately.",
                        "YouTube Shorts — short-form videos are paused and covered with a protection overlay (toggle below).",
                        "Long YouTube videos — the real title and description are checked on the watch page; blocked videos are paused exactly once and covered with a dark overlay and a \"Go to YouTube Home\" button.",
                        "Pattern blocking — innocent words like women, girl, hot or beach only block when combined with adult terms (e.g. \"women bikini\"), so everyday browsing is never blocked.",
                        "Custom keywords & websites — everything under Blocked Items is enforced in every monitored app.",
                        "100% on-device — screen text is processed instantly on your phone and never leaves it."
                    )
                )
            }
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Enable ClearView protection?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ClearView uses the Accessibility service to read the text currently shown on your screen inside Chrome and the Google app — only to block adult content, your blocked keywords and websites, and incognito mode.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "• Screen text is processed instantly on your device\n" +
                            "• It is never stored, logged, or sent anywhere\n" +
                            "• No personal data is collected\n" +
                            "• You can turn it off anytime in Settings → Accessibility",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        viewModel.openAccessibilitySettings(context)
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) {
                    Text("Not now")
                }
            }
        )
    }
}

// ── Shared feature row (Strict Mode / Shorts / YouTube test) ──────
//
// These three features do NOTHING on their own: they are enforced by the
// always-on Protection service (the Accessibility service that reads the
// screen). So they share ONE compact row — icon, title, a single short line,
// the switch and the (i) context expander — and when Protection is OFF their
// switch is DISABLED. A switch that looks on while nothing is watching would
// claim protection that isn't running.
//
// The full context still lives behind the (i), which is what lets every
// summary stay one short line.

/** Shown once above the locked group (kept out of every card: less text). */
@Composable
private fun LockedGroupHint() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.block_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    locked: Boolean,
    details: List<String>,
    onToggle: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = { if (!locked) onToggle() },
                    enabled = !locked
                )
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }
            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(bullets = details)
            }
        }
    }
}

// ── 2. Strict Mode ────────────────────────────────────────────────

@Composable
private fun StrictModeCard(viewModel: MainViewModel, context: Context, locked: Boolean) {
    FeatureCard(
        icon = Icons.Outlined.Warning,
        title = "Strict Mode",
        summary = "Also blocks discovery words like bikini, lingerie or cleavage.",
        checked = viewModel.isStrictMode,
        locked = locked,
        details = listOf(
            "Adds a curated list of risky-but-innocent discovery terms (bikini, lingerie, cleavage, beach, hot, ...) on top of the always-on adult filter.",
            "Gender and family words — women, female, girl, transgender, mom, wife, sister, daughter and more — are never blocked alone; they only block when combined with an adult term.",
            "Pattern matching applies everywhere: Chrome (every search tab), the Google app, YouTube, and your blocked list.",
            "When Strict Mode is off, only the always-on adult terms block — the discovery terms are ignored."
        ),
        onToggle = { viewModel.toggleStrictMode(context) }
    )
}

// ── 3. Block Shorts ───────────────────────────────────────────────

@Composable
private fun BlockShortsCard(viewModel: MainViewModel, locked: Boolean) {
    FeatureCard(
        icon = Icons.Outlined.PlayCircle,
        title = "Block Shorts",
        summary = "Pauses YouTube Shorts in Chrome and the YouTube app.",
        checked = viewModel.blockShorts,
        locked = locked,
        details = listOf(
            "Blocks YouTube Shorts in Chrome and the YouTube app — no need to add \"shorts\" as a keyword.",
            "Blocked Shorts are paused and covered with a protection overlay, so taps can never reveal the controls or resume the video.",
            "Vertical swipes still work, so you can move between Shorts normally.",
            "Works together with the always-on adult filter and your custom keywords."
        ),
        onToggle = { viewModel.toggleBlockShorts() }
    )
}

// ── 3b. Brain Rot Protection (was "YouTube Chrome Test") ─────────

@Composable
private fun BrainRotProtectionCard(viewModel: MainViewModel, locked: Boolean) {
    FeatureCard(
        icon = Icons.Outlined.Psychology,
        title = stringResource(R.string.block_brainrot_title),
        summary = stringResource(R.string.block_brainrot_summary),
        checked = viewModel.youTubeChromeTest,
        locked = locked,
        details = listOf(
            "Blocks content using your keywords and blocked channels — on YouTube Shorts and long videos in Chrome.",
            "Shorts are detected on-screen, matched, paused once and covered with a protection overlay; swipes between Shorts still work.",
            "Long videos — the real title AND description are extracted from the watch page and matched against your keywords (browser strings like \"Share\", \"Subscribe\" or \"New tab\" are ignored).",
            "A blocked long video is paused exactly once, then protected by a dark overlay with a \"Go to YouTube Home\" button.",
            "Detection interrupts the content rather than warning about it — a blocked video cannot simply be dismissed and continued.",
            "Allowed videos keep playing untouched — no overlay, no pause."
        ),
        onToggle = { viewModel.toggleYouTubeChromeTest() }
    )
}

// ── 3c. Brain Rot: blocked keywords ──────────────────────────────

@Composable
private fun BrainRotKeywordsCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Outlined.Science,
                title = stringResource(R.string.block_stat_keywords),
                subtitle = if (viewModel.youtubeTestKeywords.isEmpty()) {
                    "No keywords yet — tap to add"
                } else {
                    "${viewModel.youtubeTestKeywords.size} keywords"
                },
                onClick = { open = true },
                trailing = { ManageArrow() }
            )
        }
    }

    if (open) {
        BlockManagerDialog(
            title = stringResource(R.string.block_stat_keywords),
            onDismiss = { open = false }
        ) {
            QueuedForReview(
                items = viewModel.pendingSubmissions("keyword"),
                icon = Icons.Outlined.Science
            )
            ApprovedGlobally(
                items = viewModel.approvedSubmissions("keyword"),
                icon = Icons.Outlined.Science
            )
            AddField(
                value = viewModel.newYoutubeTestKeywordText,
                onValueChange = { viewModel.updateNewYoutubeTestKeyword(it) },
                placeholder = stringResource(R.string.block_youtube_keyword_hint),
                onAdd = { viewModel.addYoutubeTestKeyword() }
            )
            if (viewModel.youtubeTestKeywords.isEmpty()) {
                Text(
                    text = "No keywords yet. Add one above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                viewModel.youtubeTestKeywords.forEach { keyword ->
                    BlockListItem(
                        label = keyword,
                        icon = Icons.Outlined.Science,
                        onRemove = { viewModel.removeYoutubeTestKeyword(keyword) },
                        removeLabel = "Remove $keyword",
                        // The Send action disappears once this value is queued or
                        // approved, so the same request cannot be sent twice.
                        onSend = if (
                            viewModel.globalRulesAvailable &&
                            viewModel.submissionStatusFor("keyword", keyword) == null
                        ) {
                            {
                                viewModel.submitKeywordToGlobal(keyword) { ok ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            if (ok) R.string.block_global_suggest_queued
                                            else R.string.block_global_suggest_failed
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } else null,
                        subtitle = if (viewModel.isQueuedForReview("keyword", keyword)) {
                            "Queued for review"
                        } else if (viewModel.isApprovedGlobally("keyword", keyword)) {
                            "Approved globally"
                        } else if (viewModel.isRemovedGlobally("keyword", keyword)) {
                            "Removed"
                        } else {
                            viewModel.reasonFor(keyword)?.reason
                        }
                    )
                }
            }
        }
    }
}

// ── 3d. Brain Rot: blocked channels ──────────────────────────────

@Composable
private fun BrainRotChannelsCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val channels = viewModel.filteredBrainRotChannels()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Outlined.Block,
                title = stringResource(R.string.block_stat_channels),
                subtitle = if (viewModel.brainRotChannels.isEmpty()) {
                    "No channels yet — tap to add"
                } else {
                    "${viewModel.brainRotChannels.size} channels"
                },
                onClick = { open = true },
                trailing = { ManageArrow() }
            )
        }
    }

    if (open) {
        BlockManagerDialog(
            title = stringResource(R.string.block_stat_channels),
            onDismiss = { open = false }
        ) {
            QueuedForReview(
                items = viewModel.pendingSubmissions("channel"),
                icon = Icons.Outlined.PlayCircle
            )
            ApprovedGlobally(
                items = viewModel.approvedSubmissions("channel"),
                icon = Icons.Outlined.PlayCircle
            )
            AddField(
                value = viewModel.newChannelHandleText,
                onValueChange = { viewModel.updateNewChannelHandle(it) },
                placeholder = stringResource(R.string.block_channel_add_hint),
                onAdd = {
                    if (!viewModel.addBrainRotChannel()) {
                        Toast.makeText(context, context.getString(R.string.block_channel_invalid), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            if (viewModel.brainRotChannels.size > 5) {
                OutlinedTextField(
                    value = viewModel.channelSearchText,
                    onValueChange = { viewModel.updateChannelSearch(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.block_channel_search_hint)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (viewModel.channelSearchText.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateChannelSearch("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                )
            }

            if (channels.isEmpty()) {
                Text(
                    text = if (viewModel.brainRotChannels.isEmpty()) {
                        stringResource(R.string.block_channels_empty)
                    } else {
                        "No channel matches that search."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                channels.forEach { channel ->
                    BlockListItem(
                        label = channel.handle,
                        icon = Icons.Outlined.PlayCircle,
                        onRemove = { viewModel.removeBrainRotChannel(channel.handle) },
                        removeLabel = "Unblock ${channel.handle}",
                        // No Send. A channel you blocked yourself is your own
                        // rule; offering to make it global would be offering to
                        // decide for everyone else. Only YouTube protection items
                        // are sendable.
                        onSend = null,
                        subtitle = when {
                            viewModel.isQueuedForReview("channel", channel.handle) -> "Queued for review"
                            viewModel.isApprovedGlobally("channel", channel.handle) -> "Approved globally"
                            viewModel.isRemovedGlobally("channel", channel.handle) -> "Removed"
                            else -> channel.name ?: stringResource(R.string.block_channel_my_block)
                        }
                    )
                }
            }

            Text(
                text = stringResource(R.string.block_channel_handle_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

// ── 3d-ii. Global (community) rules ──────────────────────────────

/**
 * The centrally-maintained rule set.
 *
 * Shown separately from the user's own rules on purpose: the spec requires a
 * person to always be able to tell whether something is blocked because of
 * their own rule or because of the global repository, and two clearly-labelled
 * cards is how that stays true.
 */
@Composable
private fun GlobalRulesCard(viewModel: MainViewModel) {
    var open by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Outlined.Public,
                title = stringResource(R.string.block_global_title),
                subtitle = if (viewModel.globalRulesAvailable) {
                    stringResource(
                        R.string.block_global_summary,
                        viewModel.globalKeywordCount,
                        viewModel.globalChannelCount
                    )
                } else {
                    stringResource(R.string.block_global_unavailable)
                },
                onClick = { open = true },
                trailing = { ManageArrow() }
            )
        }
    }

    if (open) {
        BlockManagerDialog(
            title = stringResource(R.string.block_global_title),
            onDismiss = { open = false }
        ) {
            OutlinedButton(
                onClick = { viewModel.syncGlobalRules() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.globalRulesSyncing && viewModel.globalRulesAvailable
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (viewModel.globalRulesSyncing) R.string.block_global_syncing
                        else R.string.block_global_sync
                    ),
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Approved keywords",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (viewModel.globalKeywords.isEmpty()) {
                Text(
                    text = "No global keywords yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                viewModel.globalKeywords.forEach { keyword ->
                    RuleRow(label = keyword, icon = Icons.Outlined.Block)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Approved channels",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (viewModel.globalChannels.isEmpty()) {
                Text(
                    text = "No global channels yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                viewModel.globalChannels.forEach { handle ->
                    RuleRow(label = handle, icon = Icons.Outlined.PlayCircle)
                }
            }
        }
    }

}

// ── 3d-iii. My requests to the global repository ────────────────

/**
 * The requests this device has made, and where each one got to.
 *
 * The other half of the global repository: the card above says what IS global,
 * and this says what this person asked for and what happened to it. Without it,
 * submitting something would be an act with no visible outcome until an
 * administrator happened to act — which is exactly the "I sent it and nothing
 * happened" experience the notification centre and this list exist to remove.
 */
@Composable
private fun MySubmissionsCard(viewModel: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    val submissions = viewModel.mySubmissions

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Outlined.Upload,
                title = stringResource(R.string.block_my_submissions_title),
                subtitle = "${submissions.size} submitted",
                onClick = { open = true },
                trailing = { ManageArrow() }
            )
        }
    }

    if (open) {
        BlockManagerDialog(
            title = stringResource(R.string.block_my_submissions_title),
            onDismiss = { open = false }
        ) {
            OutlinedButton(
                onClick = { viewModel.refreshMySubmissions(notifyOnChange = false) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Refresh", fontSize = 13.sp)
            }

            if (submissions.isEmpty()) {
                Text(
                    text = stringResource(R.string.block_my_submissions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                submissions.forEach { submission ->
                    SubmissionRow(submission, viewModel.displayStatus(submission))
                }
            }
        }
    }
}

/** One device submission, with its status pill. */
@Composable
private fun SubmissionRow(submission: BrainRotClient.SubmissionStatus, status: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (submission.kind == "channel") {
                    Icons.Outlined.PlayCircle
                } else {
                    Icons.Outlined.Block
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = submission.displayName ?: submission.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            SubmissionStatusPill(status)
        }
    }
}

/** Small rounded chip showing where one of this device's requests stands. */
@Composable
private fun SubmissionStatusPill(status: String) {
    val labelRes = when (status) {
        "approved" -> R.string.block_my_submission_approved
        "rejected" -> R.string.block_my_submission_rejected
        "under_review" -> R.string.block_my_submission_under_review
        "removed" -> R.string.block_my_submission_removed
        else -> R.string.block_my_submission_pending
    }
    val color = when (status) {
        "approved" -> Color(0xFF2E7D32)
        "rejected" -> MaterialTheme.colorScheme.error
        "under_review" -> Color(0xFF1565C0)
        "removed" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ── 3e. Activity (protection statistics) ─────────────────────────

@Composable
private fun ActivityCard(viewModel: MainViewModel) {
    val summary = viewModel.brainRotSummary
    var open by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // One line on the page, like every other card: what happened TODAY, then an
    // arrow. The counters, the streak and the top lists used to be printed in
    // full here and pushed everything below them down the screen.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Outlined.Insights,
                title = "Protection activity",
                subtitle = when {
                    summary.totalBlocks == 0 -> "Nothing blocked yet"
                    summary.todayBlocks == 1 -> "1 blocked today"
                    summary.todayBlocks > 1 -> "${summary.todayBlocks} blocked today"
                    else -> "${summary.totalBlocks} blocked in total"
                },
                onClick = { open = true },
                trailing = { ManageArrow() }
            )
        }
    }

    if (open) {
        BlockManagerDialog(title = "Protection activity", onDismiss = { open = false }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCell(stringResource(R.string.block_today), "${summary.todayBlocks}", Modifier.weight(1f))
                StatCell(stringResource(R.string.block_this_week), "${summary.weekBlocks}", Modifier.weight(1f))
                StatCell(stringResource(R.string.block_stat_blocks), "${summary.totalBlocks}", Modifier.weight(1f))
            }

            if (summary.streakDays > 0) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = if (summary.streakDays == 1) {
                            stringResource(R.string.block_streak_one_day)
                        } else {
                            stringResource(R.string.block_streak_days, summary.streakDays)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (summary.topKeywords.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.block_most_triggered),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                for ((keyword, count) in summary.topKeywords) {
                    ActivityRow(keyword, "$count")
                }
            }

            if (summary.topChannels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.block_top_channel),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                for ((channel, count) in summary.topChannels) {
                    ActivityRow(channel, "$count")
                }
            }

            if (summary.totalBlocks == 0) {
                Text(
                    text = stringResource(R.string.block_activity_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.block_clear_activity), fontSize = 13.sp)
            }
        }
    }

    if (confirmClear) {
        ClearActivityDialog(
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                viewModel.clearBrainRotActivity()
            }
        )
    }
}

/**
 * The destructive-confirmation for clearing protection activity.
 *
 * Deliberately NOT a one-tap dialog. This erases the whole history — every
 * counter, the streak and both top lists — and it cannot be undone, so the
 * confirm button stays disabled until the user literally types the word. That
 * turns a mis-tap into a deliberate act, which is the only thing that makes a
 * "clear everything" button safe to keep one tap away.
 */
@Composable
private fun ClearActivityDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val armed = typed.trim().equals("clear", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.block_clear_activity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "This erases your whole protection history: the counters, the streak " +
                        "and the most-triggered lists. It cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Type clear to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("clear") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = armed) {
                Text(
                    text = stringResource(R.string.block_reset_confirm),
                    color = if (armed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.block_cancel))
            }
        }
    )
}

/** One number in its own rounded tile, for the activity counters. */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A label on the left, its count as a small pill on the right. */
@Composable
private fun ActivityRow(label: String, count: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface
        ) {
            Text(
                text = count,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

// ── 3f. Privacy ──────────────────────────────────────────────────

@Composable
private fun PrivacyCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.block_privacy_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.block_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 4. Blocked Items (dotted, editable) ───────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockedItemsCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    // Hoisted out of drawBehind: MaterialTheme is a composable read and cannot
    // be accessed inside the non-composable DrawScope lambda.
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val strokeWidth = 2.dp.toPx()
                val dash = floatArrayOf(strokeWidth * 5, strokeWidth * 5)
                drawRoundRect(
                    color = outlineColor,
                    style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(dash)),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            },
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardHeader(
                icon = Icons.Outlined.Block,
                title = "Blocked Items",
                subtitle = "${viewModel.userKeywords.size} keywords · ${viewModel.blockedDomains.size} websites",
                onClick = { open = true },
                trailing = { ManageArrow() }
            )
        }
    }

    if (open) {
        BlockManagerDialog(title = "Blocked Items", onDismiss = { open = false }) {
            // Deliberately NO "Queued for review" / "Approved globally" groups
            // here. Nothing in this list can ever be sent for review — its own
            // keywords and websites are the user's private rules, and the Send
            // action is gone from every row for exactly that reason. Showing
            // them meant a request made from the YouTube section (same "keyword"
            // kind) appeared as pending/approved against a list it was never
            // sent from, which reads as "this card submits things" when it does
            // not. Those groups belong on the YouTube keyword card, which is the
            // only place a term can actually be submitted.
            Text(
                text = "Keywords",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AddField(
                value = viewModel.newKeywordText,
                onValueChange = { viewModel.updateNewKeyword(it) },
                placeholder = stringResource(R.string.block_keyword_hint),
                onAdd = { viewModel.addKeyword() }
            )
            if (viewModel.userKeywords.isEmpty()) {
                Text(
                    text = "No keywords yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                viewModel.userKeywords.forEach { keyword ->
                    BlockListItem(
                        label = keyword,
                        icon = Icons.Outlined.Block,
                        subtitle = viewModel.reasonFor(keyword)?.reason,
                        onRemove = { viewModel.removeKeyword(keyword) },
                        removeLabel = "Remove $keyword",
                        // No Send. Your own keywords and websites are YOURS, and
                        // the shared blocklist is not a place to push them — one
                        // person's rule must not become everyone's, which is the
                        // whole reason a rule needs an operator to approve it.
                        // Only YouTube protection items can be sent (see the
                        // YouTube section), because those are the terms ClearView
                        // already suggests on a user's behalf.
                        onSend = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Websites",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AddField(
                value = viewModel.newDomainText,
                onValueChange = { viewModel.updateNewDomain(it) },
                placeholder = stringResource(R.string.block_domain_hint),
                onAdd = { viewModel.addDomain() }
            )
            if (viewModel.blockedDomains.isEmpty()) {
                Text(
                    text = "No websites yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (domain in viewModel.blockedDomains) {
                        BlockedChip(
                            label = domain,
                            onDelete = { viewModel.removeDomain(domain) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedChip(label: String, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove $label",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ── 5. DNS protection ─────────────────────────────────────────────

@Composable
private fun DnsCard(viewModel: MainViewModel, context: Context) {
    var showSetupSheet by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val currentDns = viewModel.getPrivateDnsProvider(context)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "DNS Protection",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Blocks adult content at the network level — in every app, including incognito.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = showDetails) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Network-level filtering that works on ALL apps.",
                        "Enforced automatically by ClearView using Device Owner policy.",
                        "Android Settings will be locked so it cannot be bypassed.",
                        "Works alongside ClearView's app-level blocking."
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showSetupSheet = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Select DNS Provider", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (currentDns != null) "Active: $currentDns" else "Not active",
                style = MaterialTheme.typography.bodySmall,
                color = if (currentDns != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontWeight = if (currentDns != null) FontWeight.Bold else FontWeight.Normal
            )
        }
    }

    if (showSetupSheet) {
        DnsSetupSheet(
            viewModel = viewModel,
            context = context,
            onDismiss = { showSetupSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsSetupSheet(
    viewModel: MainViewModel,
    context: Context,
    onDismiss: () -> Unit
) {
    var activeProvider by remember { mutableStateOf(viewModel.getPrivateDnsProvider(context)) }

    fun selectProvider(hostname: String) {
        val success = viewModel.setPrivateDnsProvider(context, hostname)
        if (success) {
            activeProvider = hostname
            Toast.makeText(context, "Private DNS set and locked", Toast.LENGTH_SHORT).show()
            onDismiss()
        } else {
            Toast.makeText(context, "Failed to set Private DNS. Check Device Owner status.", Toast.LENGTH_LONG).show()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Select DNS Provider",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Device Owner Enforcement",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Selecting a provider will enforce it system-wide and lock the Android Settings UI to prevent bypass.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DnsProviderCard(
                name = "Cloudflare Family (1.1.1.3)",
                description = "Blocks malware + adult content. Fast, free, no account.",
                hostname = viewModel.cloudflareFamilyHostname(),
                isActive = activeProvider == viewModel.cloudflareFamilyHostname(),
                onSelect = { selectProvider(it) }
            )

            DnsProviderCard(
                name = "CleanBrowsing Family Filter",
                description = "Blocks adult content + malware. Strict family filter.",
                hostname = viewModel.cleanBrowsingFamilyHostname(),
                isActive = activeProvider == viewModel.cleanBrowsingFamilyHostname(),
                onSelect = { selectProvider(it) }
            )
        }
    }
}

@Composable
private fun DnsProviderCard(
    name: String,
    description: String,
    hostname: String,
    isActive: Boolean,
    onSelect: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = { if (!isActive) onSelect(hostname) }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isActive) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = hostname,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ── 6. Advanced: Device Admin ─────────────────────────────────────

@Composable
private fun DeviceAdminCard(
    viewModel: MainViewModel,
    context: Context,
    deviceAdminLauncher: ActivityResultLauncher<Intent>
) {
    val isAdmin = viewModel.isDeviceAdminEnabled
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAdmin)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AdminPanelSettings,
                contentDescription = null,
                tint = if (isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Device Admin",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isAdmin)
                        "Active — adds uninstall protection step"
                    else
                        "Inactive — app can be uninstalled freely",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isAdmin) {
                TextButton(
                    onClick = {
                        val intent = Intent(
                            android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                        ).apply {
                            putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                android.content.ComponentName(
                                    context,
                                    com.muddassir.clearview.receiver.DeviceAdminReceiver::class.java
                                )
                            )
                            putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Activating device admin adds a deactivation step before uninstall, " +
                                    "making it harder to accidentally remove the protection app."
                            )
                        }
                        try {
                            deviceAdminLauncher.launch(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("BlockTab", "Failed to launch Device Admin: ${e.message}")
                            val fallbackIntent = Intent(
                                android.provider.Settings.ACTION_SECURITY_SETTINGS
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(fallbackIntent)
                        }
                    }
                ) {
                    Text("Activate", fontSize = 13.sp)
                }
            }
            InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
        }
        AnimatedVisibility(visible = showDetails) {
            FeatureDetailBlock(
                bullets = listOf(
                    "Makes the app a Device Admin, which adds a confirmation step before the app can be uninstalled.",
                    "Required before Device Owner (full uninstall block) can be set.",
                    "Can be revoked anytime from Settings → Security → Device admin apps."
                )
            )
        }
    }
}

// ── 6. Advanced: Uninstall Protection (Device Owner) ──────────────

@Composable
private fun UninstallProtectionCard(viewModel: MainViewModel, context: Context) {
    val isOwner = viewModel.isDeviceOwner
    val isAdmin = viewModel.isDeviceAdminEnabled
    var showRemoveOwnerConfirm by remember { mutableStateOf(false) }
    // Compact by default: only the header line shows. Everything else (details,
    // the ADB steps, the remove button) is behind a tap, because this is
    // setup-once content that should not push the everyday cards off screen.
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOwner && isAdmin)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isOwner && isAdmin) Icons.Filled.VerifiedUser else Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    tint = if (isOwner && isAdmin)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Uninstall Protection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when {
                            isOwner && isAdmin -> "✅ Uninstall completely blocked — factory reset required"
                            isAdmin -> "Device Admin active — adds uninstall friction (1 extra step)"
                            else -> "No protection — app can be uninstalled freely"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ExpandChevron(expanded)
            }

            AnimatedVisibility(visible = expanded) {
                FeatureDetailBlock(
                    bullets = listOf(
                        "Device Owner blocks uninstall completely — a factory reset is required to remove the app.",
                        "Set up via ADB (the steps appear on this card once Device Admin is active).",
                        "Use \"Remove Uninstall Protection\" when you need to update or uninstall — all your data is kept."
                    )
                )
            }

            if (expanded && isOwner && isAdmin) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Device Owner is active — the app cannot be uninstalled. " +
                        "Tap below to lift the lock (needed to update or remove the app). " +
                        "All your data is kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showRemoveOwnerConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove Uninstall Protection", fontSize = 13.sp)
                }
            }

            if (showRemoveOwnerConfirm) {
                AlertDialog(
                    onDismissRequest = { showRemoveOwnerConfirm = false },
                    title = { Text("Remove Uninstall Protection?") },
                    text = {
                        Text(
                            "The app will stop being Device Owner, so you can update or " +
                                "uninstall it normally again. Your blocked lists and settings are kept."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showRemoveOwnerConfirm = false
                            viewModel.removeUninstallProtection(context)
                        }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemoveOwnerConfirm = false }) { Text("Cancel") }
                    }
                )
            }

            if (expanded && !isOwner && isAdmin) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Upgrade to Device Owner for full protection:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. Connect your phone to a computer with ADB\n" +
                        "2. Temporarily remove your Google account (Settings > Accounts)\n" +
                        "3. Run this command in terminal:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = viewModel.getDeviceOwnerAdbCommand(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "4. Re-add your Google account\n" +
                        "5. Re-open the app — uninstall will be blocked permanently",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 6. Advanced: App Lock ─────────────────────────────────────────

@Composable
private fun AppLockCard(viewModel: MainViewModel) {
    val hasPwd = viewModel.hasPassword
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasPwd)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (hasPwd) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = if (hasPwd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Block Tab Password",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (hasPwd)
                        "Password set — Block tab locks when the app goes to background"
                    else
                        "No password — Block tab is open to anyone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasPwd) {
                TextButton(onClick = { viewModel.clearAppPassword() }) {
                    Text("Remove", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { viewModel.appLockTriggered = true }) {
                    Text("Set", fontSize = 13.sp)
                }
            }
            InfoToggleButton(expanded = showDetails) { showDetails = !showDetails }
        }
        AnimatedVisibility(visible = showDetails) {
            FeatureDetailBlock(
                bullets = listOf(
                    "Locks the Block tab whenever the app goes to the background.",
                    "The password protects the dashboard and its settings from being changed by anyone else.",
                    "No password = the tab is open to anyone."
                )
            )
        }
    }
}
