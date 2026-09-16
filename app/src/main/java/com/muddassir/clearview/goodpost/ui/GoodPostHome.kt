package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostHomeUiState
import com.muddassir.clearview.goodpost.GoodPostHomeViewModel
import com.muddassir.clearview.goodpost.GoodPostSection
import com.muddassir.clearview.goodpost.data.ChannelSort
import com.muddassir.clearview.goodpost.data.GoodPostCategory
import com.muddassir.clearview.goodpost.data.GoodPostChannel

/**
 * Good Post home: the Channels view and Discover (§4, §5).
 *
 * Presentation only. Every action delegates to [GoodPostHomeViewModel], which
 * is where the session, the network and the cache live (§35) — nothing here
 * decides what is true, and nothing here calls the API.
 *
 * A channel detail is a full-screen dialog, matching how the rest of ClearView
 * presents a feature screen, so it covers the tab bar the same way and Back
 * dismisses it without the tab changing underneath.
 */
@Composable
fun GoodPostHome(
    state: GoodPostHomeUiState,
    accountName: String,
    onSignOut: () -> Unit,
    viewModel: GoodPostHomeViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            accountName = accountName,
            onRefresh = viewModel::refresh,
            onSignOut = onSignOut,
            busy = state.loading
        )

        SectionTabs(
            selected = state.section,
            onSelect = viewModel::selectSection
        )

        // §36: saved data is labelled as saved. Presenting a cached list as
        // live would be claiming a success the server never confirmed.
        if (state.stale) {
            StaleBanner()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (state.section) {
                GoodPostSection.Channels -> ChannelsSection(
                    state = state,
                    onOpen = viewModel::open,
                    onCreate = viewModel::startCreate,
                    onRetry = viewModel::refresh
                )

                GoodPostSection.Discover -> DiscoverSection(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = viewModel::search,
                    onCategory = viewModel::selectCategory,
                    onSort = viewModel::selectSort,
                    onOpen = viewModel::open,
                    onLoadMore = viewModel::loadMore
                )
            }

            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }

    state.channel?.let { channel ->
        ChannelDetail(
            channel = channel,
            busy = state.busyChannelId == channel.id,
            onDismiss = viewModel::closeDetail,
            onToggleFollow = viewModel::toggleFollow,
            onToggleMute = viewModel::toggleMute,
            onToggleBlock = viewModel::toggleBlock,
            onEdit = viewModel::startEdit
        )
    }

    if (state.creating) {
        ChannelFormDialog(
            title = stringResource(R.string.goodpost_create_title),
            confirmLabel = stringResource(R.string.goodpost_create),
            initialName = "",
            initialDescription = "",
            categories = state.categories,
            errorCode = state.messageCode,
            onDismiss = viewModel::cancelCreate,
            onConfirm = viewModel::createChannel
        )
    }

    if (state.editing && state.channel != null) {
        ChannelFormDialog(
            title = stringResource(R.string.goodpost_edit_title),
            confirmLabel = stringResource(R.string.goodpost_save),
            initialName = state.channel.name,
            initialDescription = state.channel.description.orEmpty(),
            categories = state.categories,
            errorCode = state.messageCode,
            onDismiss = viewModel::cancelEdit,
            onConfirm = { name, description, category ->
                viewModel.saveEdit(
                    name = name,
                    description = description,
                    // Blank means "remove it", which the backend distinguishes
                    // from "leave it alone" — so the intent is sent explicitly.
                    clearDescription = description.isNullOrBlank(),
                    categorySlug = category
                )
            }
        )
    }
}

// ── Header and chrome ───────────────────────────────────────────────────

@Composable
private fun Header(
    accountName: String,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    busy: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Campaign,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = accountName.ifBlank { stringResource(R.string.goodpost_tab) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.goodpost_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRefresh, enabled = !busy) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.goodpost_refresh))
        }
        TextButton(onClick = onSignOut) {
            Text(stringResource(R.string.goodpost_sign_out))
        }
    }
}

@Composable
private fun SectionTabs(selected: GoodPostSection, onSelect: (GoodPostSection) -> Unit) {
    val sections = listOf(
        GoodPostSection.Channels to stringResource(R.string.goodpost_section_channels),
        GoodPostSection.Discover to stringResource(R.string.goodpost_section_discover)
    )

    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        sections.forEachIndexed { index, (section, label) ->
            SegmentedButton(
                selected = selected == section,
                onClick = { onSelect(section) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sections.size)
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun StaleBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.goodpost_stale_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Channels (§4) ───────────────────────────────────────────────────────

@Composable
private fun ChannelsSection(
    state: GoodPostHomeUiState,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onRetry: () -> Unit
) {
    if (state.following.isEmpty() && !state.loading) {
        Column(modifier = Modifier.fillMaxSize()) {
            Notice(
                icon = { Icon(Icons.Filled.Campaign, contentDescription = null) },
                title = stringResource(R.string.goodpost_empty_following_title),
                note = stringResource(R.string.goodpost_empty_following_note),
                actionLabel = stringResource(R.string.goodpost_refresh),
                onAction = onRetry
            )
            Spacer(Modifier.height(8.dp))
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.following, key = { it.id }) { channel ->
            ChannelRow(channel = channel, onClick = { onOpen(channel.id) })
            HorizontalDivider()
        }

        item {
            OutlinedButton(
                onClick = onCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.goodpost_create_channel))
            }
        }
    }
}

// ── Discover (§5) ───────────────────────────────────────────────────────

@Composable
private fun DiscoverSection(
    state: GoodPostHomeUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCategory: (String?) -> Unit,
    onSort: (ChannelSort) -> Unit,
    onOpen: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.goodpost_search_label)) },
                placeholder = { Text(stringResource(R.string.goodpost_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.goodpost_search))
            }
        }

        Spacer(Modifier.height(8.dp))

        SortRow(selected = state.sort, onSelect = onSort)

        CategoryRow(
            categories = state.categories,
            selected = state.category,
            onSelect = onCategory
        )

        HorizontalDivider()

        if (state.discover.isEmpty() && !state.loading) {
            Notice(
                icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                title = stringResource(R.string.goodpost_empty_discover_title),
                note = stringResource(R.string.goodpost_empty_discover_note),
                actionLabel = stringResource(R.string.goodpost_refresh),
                onAction = onSearch
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.discover, key = { it.id }) { channel ->
                ChannelRow(channel = channel, onClick = { onOpen(channel.id) })
                HorizontalDivider()
            }

            if (state.nextCursor != null) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.loadingMore,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            if (state.loadingMore) {
                                stringResource(R.string.goodpost_working)
                            } else {
                                stringResource(R.string.goodpost_more)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortRow(selected: ChannelSort, onSelect: (ChannelSort) -> Unit) {
    val options = listOf(
        ChannelSort.Popular to stringResource(R.string.goodpost_sort_popular),
        ChannelSort.Active to stringResource(R.string.goodpost_sort_active),
        ChannelSort.New to stringResource(R.string.goodpost_sort_new)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (sort, label) ->
            FilterChip(
                selected = selected == sort,
                onClick = { onSelect(sort) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun CategoryRow(
    categories: List<GoodPostCategory>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    if (categories.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.goodpost_all_categories)) }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selected == category.slug,
                onClick = { onSelect(category.slug) },
                label = { Text(category.label) }
            )
        }
    }
}

// ── Rows and detail ─────────────────────────────────────────────────────

@Composable
private fun ChannelRow(channel: GoodPostChannel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelBadge(channel)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                // An unread marker rather than a count: the count is not known
                // until posts exist (§4), and a dot is what it will show then.
                if (channel.hasUnread) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                    )
                }
            }

            Text(
                text = channelSubtitle(channel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (channel.isMuted) {
            Icon(
                Icons.Filled.NotificationsOff,
                contentDescription = stringResource(R.string.goodpost_muted),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (channel.isBlocked) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Block,
                contentDescription = stringResource(R.string.goodpost_blocked),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** The second line of a row: what the channel is, and how big it is. */
@Composable
private fun channelSubtitle(channel: GoodPostChannel): String {
    val followers = stringResource(R.string.goodpost_followers, channel.followerCount.toString())
    val label = channel.categoryLabel
    return if (label.isNullOrBlank()) followers else "$label · $followers"
}

@Composable
private fun ChannelBadge(channel: GoodPostChannel) {
    // A generated initial instead of an icon: channel icons are an S3 upload
    // that arrives in M3, and a placeholder image now would be a lie about what
    // the channel has.
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = channel.name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ChannelDetail(
    channel: GoodPostChannel,
    busy: Boolean,
    onDismiss: () -> Unit,
    onToggleFollow: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleBlock: () -> Unit,
    onEdit: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.goodpost_cancel)
                        )
                    }
                    ChannelBadge(channel)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = channelSubtitle(channel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (channel.isOwner) {
                        AssistChip(
                            onClick = onEdit,
                            label = { Text(stringResource(R.string.goodpost_owner_note)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                    if (channel.isBlocked) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.goodpost_blocked)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = channel.description
                        ?: stringResource(R.string.goodpost_channel_no_description),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(24.dp))

                // The actions the server actually offers. Follow is hidden for
                // the owner because the backend refuses it outright, and a
                // button that always errors is worse than no button.
                if (!channel.isOwner) {
                    Button(
                        onClick = onToggleFollow,
                        enabled = !busy && channel.isAvailable && !channel.isBlocked,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (channel.isFollowing) R.string.goodpost_unfollow
                                else R.string.goodpost_follow
                            )
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onToggleMute,
                        enabled = !busy && channel.isFollowing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (channel.notificationsEnabled) R.string.goodpost_mute
                                else R.string.goodpost_unmute
                            )
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onToggleBlock,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (channel.isBlocked) R.string.goodpost_unblock
                            else R.string.goodpost_block
                        )
                    )
                }

                if (!channel.isAvailable) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.goodpost_error_channel_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ── Create / edit dialog (§6, §7) ───────────────────────────────────────

/**
 * One dialog for create and edit.
 *
 * They collect the same fields and differ only in their labels and their
 * confirm action, so a second near-identical composable would only be a place
 * for the two to drift.
 */
@Composable
private fun ChannelFormDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialDescription: String,
    categories: List<GoodPostCategory>,
    errorCode: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var category by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.goodpost_channel_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.goodpost_channel_description_label)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                if (categories.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.goodpost_category_label),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { option ->
                            FilterChip(
                                selected = category == option.slug,
                                onClick = {
                                    // Tapping the selected chip clears it, so a
                                    // category can be unchosen without a
                                    // separate "none" control.
                                    category = if (category == option.slug) null else option.slug
                                },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }

                if (errorCode != null) {
                    Spacer(Modifier.height(12.dp))
                    ErrorNotice(errorCode)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, description.takeIf { it.isNotBlank() }, category) },
                enabled = name.isNotBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.goodpost_cancel))
            }
        }
    )
}
