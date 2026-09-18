package com.muddassir.clearview.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.R

/**
 * The one notification list the app has (§5).
 *
 * ## Why this exists
 *
 * The bell in the Quran tab's bar used to mean "channel updates": its badge
 * counted media updates and the sheet behind it listed media updates, so every
 * other thing ClearView tells the reader about — a To Do reminder that is due, a
 * new verse — was invisible in the one place a reader looks for "did anything
 * happen".
 *
 * ## One shape, several sources
 *
 * A [HubNotification] is what a notification is after the feature that raised it
 * has been forgotten: a kind (which only decides the icon and the group heading),
 * words, a time, whether it is new, and what tapping it does. Everything else —
 * where it came from, whether it can be dismissed, what it opens — travels in the
 * entry itself, so a new feature contributes entries and nothing else. Adding a
 * kind is three lines here plus a builder in [ContentHubState.notificationEntries]
 * and a string; no screen changes, and no second notification store.
 *
 * ## What is NOT here
 *
 * Nothing invented. An entry exists only when the feature behind it has something
 * real to say: a media update that the background check actually recorded, a todo
 * whose reminder is actually scheduled for today, the verse reminder the app
 * actually cycles. A kind with no data contributes no entries rather than an
 * empty placeholder, which is why the sheet can be empty and say so.
 */
enum class HubNotificationKind(
    val icon: ImageVector,
    /** The heading a group of these is filed under. */
    @StringRes val section: Int
) {
    /** A channel the reader follows published something (Media tab). */
    MEDIA(Icons.Filled.PlayCircle, R.string.media_notifications),

    /** A todo's reminder for today, or one that is already due. */
    TO_DO(Icons.Filled.TaskAlt, R.string.todo_notifications),

    /** The Quran verse reminder and where it has got to. */
    QURAN(Icons.AutoMirrored.Filled.MenuBook, R.string.quran_notifications)
}

/** One thing the app wants the reader to know about. */
data class HubNotification(
    /** Stable across rebuilds, so a list can be keyed and diffed. */
    val id: String,
    val kind: HubNotificationKind,
    val title: String,
    val body: String?,
    /** Epoch millis, or 0 for something that has no moment (a cadence). */
    val at: Long,
    /** Unread entries count towards the bell's badge. */
    val unread: Boolean,
    val onOpen: () -> Unit,
    /**
     * Removes it for good, when the feature can do that.
     *
     * Null for a derived entry — a todo that is due today is not a row that can
     * be deleted from a notification list; completing the todo is what makes it
     * go away, and offering an ✕ that only hid it would be lying about that.
     */
    val onDismiss: (() -> Unit)? = null
)

/**
 * The list in the order the sheet draws it: newest first.
 *
 * Entries with no moment (the Quran cadence, `at == 0`) sort last, which is where
 * a standing arrangement belongs — below the things that happened.
 */
internal fun List<HubNotification>.inNotificationOrder(): List<HubNotification> =
    sortedByDescending { it.at }

/**
 * How many of these are worth a badge.
 *
 * Separate from the list so the number on the bell and the grouping in the sheet
 * cannot disagree: both read the same [HubNotification.unread].
 */
internal fun List<HubNotification>.unreadNotificationCount(): Int = count { it.unread }

/**
 * The heading for one kind of notification, with the number of new ones in it.
 *
 * Shown even for a group of one, because the heading is what distinguishes the
 * types: a reader who sees two cards stacked should be able to tell that one is a
 * channel and one is a todo without reading them.
 */
@Composable
internal fun HubNotificationSectionHeader(kind: HubNotificationKind, unread: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(kind.section),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (unread > 0) {
            Text(
                text = "$unread",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * One notification: the kind's icon, the words, when, and the two controls.
 *
 * The same card for every kind, because the differences between them are in the
 * words rather than in the furniture — what a reader needs is to recognise the
 * type at a glance (the icon and the group heading do that) and to open or clear
 * it.
 */
@Composable
internal fun HubNotificationCard(
    entry: HubNotification,
    relativeTime: String,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)?
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.unread) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            ) {
                Icon(
                    entry.kind.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp).size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.unread) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(8.dp)
                        ) {}
                    }
                }
                entry.body?.takeIf { it.isNotBlank() }?.let { body ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (relativeTime.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.media_update_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
