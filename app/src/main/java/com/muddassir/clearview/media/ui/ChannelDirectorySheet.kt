package com.muddassir.clearview.media.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.SavedChannel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelDirectorySheet(
    channels: List<SavedChannel>,
    query: String,
    platform: MediaPlatform?,
    unreadCounts: Map<String, Int>,
    onQueryChange: (String) -> Unit,
    onPlatformChange: (MediaPlatform?) -> Unit,
    onSelect: (SavedChannel) -> Unit,
    onRemove: (SavedChannel) -> Unit,
    /** Mutes or un-mutes one channel's notifications, on any source. */
    onToggleNotifications: (SavedChannel) -> Unit,
    /** Leaves this sheet and opens the add-channel dialog. */
    onAddChannel: () -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = channels.filter { channel ->
        (platform == null || channel.platform == platform) &&
            (query.isBlank() || channel.displayName.contains(query, true) || channel.sourceRef.contains(query, true))
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All channels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search channels") }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = platform == null, onClick = { onPlatformChange(null) }, label = { Text("All") })
                MediaPlatform.entries.forEach { item ->
                    FilterChip(selected = platform == item, onClick = { onPlatformChange(item) }, label = { Text(item.label()) })
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // The way IN, at the top: adding is the first thing a user looks
                // for when a channel is missing, and on an empty directory it is
                // the only useful action on the screen.
                item(key = "add-channel") {
                    OutlinedButton(
                        onClick = onAddChannel,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Add channel")
                    }
                }
                items(filtered, key = { it.channelId }) { channel ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { onSelect(channel) }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(46.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                if (channel.avatarUrl != null) RemoteImage(url = channel.avatarUrl, modifier = Modifier.fillMaxWidth())
                                else Text(channelInitials(channel.displayName), modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(channel.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(channel.platform.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                unreadCounts[channel.channelId]?.takeIf { it > 0 }?.let { count ->
                                    Text(
                                        "$count unread update${if (count == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            // Notification bell, PER CHANNEL and independent of
                            // every other channel and of the source: a filled
                            // bell means this channel notifies, a struck-through
                            // one means it is silenced. It sits beside the ✕ so
                            // "silence" and "unsubscribe" are two clearly
                            // different actions rather than one destructive tap.
                            IconButton(onClick = { onToggleNotifications(channel) }) {
                                Icon(
                                    imageVector = if (channel.notificationsMuted) {
                                        Icons.Filled.NotificationsOff
                                    } else {
                                        Icons.Filled.Notifications
                                    },
                                    contentDescription = if (channel.notificationsMuted) {
                                        "Unmute notifications for ${channel.displayName}"
                                    } else {
                                        "Mute notifications for ${channel.displayName}"
                                    },
                                    tint = if (channel.notificationsMuted) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                            IconButton(onClick = { onRemove(channel) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${channel.displayName}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun channelInitials(name: String): String = name
    .split(" ", "_", "-")
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifBlank { "?" }

private fun MediaPlatform.label(): String = when (this) {
    MediaPlatform.YOUTUBE -> "YouTube"
    MediaPlatform.INSTAGRAM -> "Instagram"
    MediaPlatform.X -> "X"
}
