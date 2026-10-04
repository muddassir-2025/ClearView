package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.DirectoryCategory
import com.muddassir.clearview.goodpost.data.DirectoryChannel
import com.muddassir.clearview.goodpost.data.DirectorySubcategory

/**
 * The external channel directory, as a super administrator maintains it.
 *
 * Three levels, and they are all one screen because they are all one job: a
 * category holds subcategories, a subcategory holds channels, and adding a
 * channel means saying where it goes. Splitting them would mean filing a channel
 * on one screen from a category you picked on another.
 *
 * ## It re-reads after every write
 *
 * Nothing here reconstructs the list from what it sent: a successful change is
 * followed by a fresh read, so the screen never disagrees with the server about
 * what is filed where. On a list this small that is the cheap half of the trade.
 *
 * ## The authority is the server's
 *
 * Only a super administrator is offered the way in, but that is a convenience.
 * Every route behind this screen is refused to anybody else on the server,
 * whatever the app draws.
 */
@Composable
internal fun DirectoryAdminScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    // Which form is open, if any. Local rather than in the ViewModel: each is a
    // step inside one gesture — a tap, a few fields, a save — and none of them
    // should survive the screen they belong to.
    var addCategoryOpen by remember { mutableStateOf(false) }
    var renameCategory by remember { mutableStateOf<DirectoryCategory?>(null) }
    var deleteCategory by remember { mutableStateOf<DirectoryCategory?>(null) }
    var addSubcategoryTo by remember { mutableStateOf<DirectoryCategory?>(null) }
    var renameSubcategory by remember { mutableStateOf<DirectorySubcategory?>(null) }
    var deleteSubcategory by remember { mutableStateOf<DirectorySubcategory?>(null) }
    var channelForm by remember { mutableStateOf<ChannelFormRequest?>(null) }
    var deleteChannel by remember { mutableStateOf<DirectoryChannel?>(null) }

    val busy = state.directoryBusy

    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = stringResource(R.string.goodpost_directory),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            },
            actions = {
                WaIconAction(
                    icon = Icons.Filled.Add,
                    description = stringResource(R.string.goodpost_directory_add_category),
                    onClick = { addCategoryOpen = true },
                    enabled = !busy
                )
            }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.directory.isEmpty && state.directoryLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Wa.Accent) }

                state.directory.isEmpty -> WaEmptyState(
                    title = stringResource(R.string.goodpost_directory),
                    note = stringResource(R.string.goodpost_directory_empty),
                    actionLabel = stringResource(R.string.goodpost_directory_add_category),
                    onAction = { addCategoryOpen = true }
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.directory.categories, key = { it.id }) { category ->
                        DirectoryCategoryCard(
                            category = category,
                            busy = busy,
                            onAddSubcategory = { addSubcategoryTo = category },
                            onRename = { renameCategory = category },
                            onDelete = { deleteCategory = category },
                            onAddChannel = {
                                channelForm = ChannelFormRequest(
                                    channel = null,
                                    categoryId = category.id,
                                    subcategoryId = null
                                )
                            },
                            onEditChannel = { channel ->
                                channelForm = ChannelFormRequest(
                                    channel = channel,
                                    categoryId = channel.categoryId,
                                    subcategoryId = channel.subcategoryId
                                )
                            },
                            onRefreshChannel = { viewModel.refreshDirectoryChannel(it.id) },
                            onDeleteChannel = { deleteChannel = it },
                            onRenameSubcategory = { renameSubcategory = it },
                            onDeleteSubcategory = { deleteSubcategory = it }
                        )
                    }

                    if (state.directory.unfiled.isNotEmpty()) {
                        item(key = "unfiled-all") {
                            WaSectionHeading(
                                stringResource(R.string.goodpost_directory_uncategorised)
                            )
                        }
                        item(key = "unfiled-card") {
                            WaCard {
                                state.directory.unfiled.forEach { channel ->
                                    DirectoryChannelRow(
                                        channel = channel,
                                        busy = busy,
                                        onEdit = {
                                            channelForm = ChannelFormRequest(
                                                channel = channel,
                                                categoryId = null,
                                                subcategoryId = null
                                            )
                                        },
                                        onRefresh = { viewModel.refreshDirectoryChannel(channel.id) },
                                        onDelete = { deleteChannel = channel }
                                    )
                                }
                            }
                        }
                    }

                    item(key = "add-channel") {
                        WaPrimaryButton(
                            text = stringResource(R.string.goodpost_directory_add_channel),
                            onClick = {
                                channelForm = ChannelFormRequest(
                                    channel = null,
                                    categoryId = null,
                                    subcategoryId = null
                                )
                            },
                            enabled = !busy
                        )
                    }
                }
            }
        }
    }

    // ── Forms ────────────────────────────────────────────────────────────
    if (addCategoryOpen) {
        DirectoryTextDialog(
            title = stringResource(R.string.goodpost_directory_add_category),
            label = stringResource(R.string.goodpost_directory_name_label),
            placeholder = stringResource(R.string.goodpost_directory_categories),
            initial = "",
            busy = busy,
            onDismiss = { addCategoryOpen = false },
            onConfirm = { name ->
                addCategoryOpen = false
                viewModel.createDirectoryCategory(name)
            }
        )
    }

    renameCategory?.let { category ->
        DirectoryTextDialog(
            title = stringResource(R.string.goodpost_directory_rename),
            label = stringResource(R.string.goodpost_directory_name_label),
            placeholder = category.name,
            initial = category.name,
            busy = busy,
            onDismiss = { renameCategory = null },
            onConfirm = { name ->
                renameCategory = null
                viewModel.renameDirectoryCategory(category.id, name)
            }
        )
    }

    addSubcategoryTo?.let { category ->
        DirectoryTextDialog(
            title = stringResource(R.string.goodpost_directory_add_subcategory),
            label = stringResource(R.string.goodpost_directory_name_label),
            placeholder = category.name,
            initial = "",
            busy = busy,
            onDismiss = { addSubcategoryTo = null },
            onConfirm = { name ->
                addSubcategoryTo = null
                viewModel.createDirectorySubcategory(category.id, name)
            }
        )
    }

    renameSubcategory?.let { subcategory ->
        DirectoryTextDialog(
            title = stringResource(R.string.goodpost_directory_rename),
            label = stringResource(R.string.goodpost_directory_name_label),
            placeholder = subcategory.name,
            initial = subcategory.name,
            busy = busy,
            onDismiss = { renameSubcategory = null },
            onConfirm = { name ->
                renameSubcategory = null
                viewModel.renameDirectorySubcategory(subcategory.id, name)
            }
        )
    }

    channelForm?.let { request ->
        DirectoryChannelFormDialog(
            request = request,
            categories = state.directory.categories,
            busy = busy,
            onDismiss = { channelForm = null },
            onSubmit = { platform, handle, name, categoryId, subcategoryId ->
                channelForm = null
                if (request.channel == null) {
                    viewModel.createDirectoryChannel(platform, handle, categoryId, subcategoryId)
                } else {
                    viewModel.updateDirectoryChannel(
                        request.channel.id, name, categoryId, subcategoryId
                    )
                }
            }
        )
    }

    deleteCategory?.let { category ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_directory_delete_category),
            message = stringResource(R.string.goodpost_directory_delete_category_note),
            confirmLabel = stringResource(R.string.goodpost_directory_delete),
            onConfirm = {
                deleteCategory = null
                viewModel.deleteDirectoryCategory(category.id)
            },
            onDismiss = { deleteCategory = null }
        )
    }

    deleteSubcategory?.let { subcategory ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_directory_delete_subcategory),
            message = stringResource(R.string.goodpost_directory_delete_subcategory_note),
            confirmLabel = stringResource(R.string.goodpost_directory_delete),
            onConfirm = {
                deleteSubcategory = null
                viewModel.deleteDirectorySubcategory(subcategory.id)
            },
            onDismiss = { deleteSubcategory = null }
        )
    }

    deleteChannel?.let { channel ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_directory_delete_channel),
            message = stringResource(R.string.goodpost_directory_delete_channel_note),
            confirmLabel = stringResource(R.string.goodpost_directory_delete),
            onConfirm = {
                deleteChannel = null
                viewModel.deleteDirectoryChannel(channel.id)
            },
            onDismiss = { deleteChannel = null }
        )
    }
}

/** Which channel form is open: a new row, or one being edited. */
private data class ChannelFormRequest(
    val channel: DirectoryChannel?,
    val categoryId: String?,
    val subcategoryId: String?
)

@Composable
private fun DirectoryCategoryCard(
    category: DirectoryCategory,
    busy: Boolean,
    onAddSubcategory: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddChannel: () -> Unit,
    onEditChannel: (DirectoryChannel) -> Unit,
    onRefreshChannel: (DirectoryChannel) -> Unit,
    onDeleteChannel: (DirectoryChannel) -> Unit,
    onRenameSubcategory: (DirectorySubcategory) -> Unit,
    onDeleteSubcategory: (DirectorySubcategory) -> Unit
) {
    WaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    color = Wa.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${category.channelCount} " +
                        stringResource(R.string.goodpost_directory_add_channel).lowercase(),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )
            }
            DirectoryMiniAction(
                icon = Icons.Filled.Add,
                description = stringResource(R.string.goodpost_directory_add_subcategory),
                onClick = onAddSubcategory,
                enabled = !busy
            )
            DirectoryMiniAction(
                icon = Icons.Filled.Edit,
                description = stringResource(R.string.goodpost_directory_rename),
                onClick = onRename,
                enabled = !busy
            )
            DirectoryMiniAction(
                icon = Icons.Filled.Delete,
                description = stringResource(R.string.goodpost_directory_delete),
                onClick = onDelete,
                enabled = !busy,
                destructive = true
            )
        }

        category.channels.forEach { channel ->
            DirectoryChannelRow(
                channel = channel,
                busy = busy,
                onEdit = { onEditChannel(channel) },
                onRefresh = { onRefreshChannel(channel) },
                onDelete = { onDeleteChannel(channel) }
            )
        }

        category.subcategories.forEach { subcategory ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subcategory.name.uppercase(),
                    color = Wa.TextDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                DirectoryMiniAction(
                    icon = Icons.Filled.Edit,
                    description = stringResource(R.string.goodpost_directory_rename),
                    onClick = { onRenameSubcategory(subcategory) },
                    enabled = !busy
                )
                DirectoryMiniAction(
                    icon = Icons.Filled.Delete,
                    description = stringResource(R.string.goodpost_directory_delete),
                    onClick = { onDeleteSubcategory(subcategory) },
                    enabled = !busy,
                    destructive = true
                )
            }
            subcategory.channels.forEach { channel ->
                DirectoryChannelRow(
                    channel = channel,
                    busy = busy,
                    onEdit = { onEditChannel(channel) },
                    onRefresh = { onRefreshChannel(channel) },
                    onDelete = { onDeleteChannel(channel) }
                )
            }
        }

        // A category with no channels anywhere still needs a way in, so the one
        // obvious "add a channel here" is always present.
        WaTextAction(
            text = stringResource(R.string.goodpost_directory_add_channel),
            onClick = onAddChannel,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun DirectoryChannelRow(
    channel: DirectoryChannel,
    busy: Boolean,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WaAvatar(name = channel.displayName, size = 36.dp, url = channel.iconUrl)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                color = Wa.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${channel.handle} · ${directoryPlatformLabel(channel.platform)}",
                color = Wa.TextDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DirectoryMiniAction(
            icon = Icons.Filled.Refresh,
            description = stringResource(R.string.goodpost_directory_refresh_icon),
            onClick = onRefresh,
            enabled = !busy
        )
        DirectoryMiniAction(
            icon = Icons.Filled.Edit,
            description = stringResource(R.string.goodpost_directory_rename),
            onClick = onEdit,
            enabled = !busy
        )
        DirectoryMiniAction(
            icon = Icons.Filled.Delete,
            description = stringResource(R.string.goodpost_directory_delete),
            onClick = onDelete,
            enabled = !busy,
            destructive = true
        )
    }
}

@Composable
private fun DirectoryMiniAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    destructive: Boolean = false
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (!enabled) Wa.TextDim.copy(alpha = 0.4f)
            else if (destructive) Wa.Danger
            else Wa.TextDim,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * A single-field form, shared by every rename and every "new group".
 *
 * One dialog for one text field, because a category, a subcategory and a rename
 * are the same act with different words — three dialogs that each laid out a
 * field would be three places for the label to drift.
 */
@Composable
private fun DirectoryTextDialog(
    title: String,
    label: String,
    placeholder: String,
    initial: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge, color = Wa.Text) },
        text = {
            WaField(
                value = value,
                onValueChange = { value = it },
                label = label,
                placeholder = placeholder,
                enabled = !busy,
                onDone = { if (value.isNotBlank()) onConfirm(value.trim()) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank() && !busy
            ) {
                Text(
                    text = stringResource(R.string.goodpost_save),
                    color = if (value.isNotBlank()) Wa.Accent else Wa.TextDim
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.goodpost_cancel), color = Wa.Accent)
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/**
 * The channel form: which platform, which handle, and where it is filed.
 *
 * The handle is only editable when adding. It is the channel's identity on the
 * platform, and the server keys uniqueness on it — so changing it is not an edit
 * of this row but the removal of one and the addition of another, which is a
 * delete and an add rather than a field.
 */
@Composable
private fun DirectoryChannelFormDialog(
    request: ChannelFormRequest,
    categories: List<DirectoryCategory>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (platform: String, handle: String, name: String?, categoryId: String?, subcategoryId: String?) -> Unit
) {
    val editing = request.channel
    var platform by remember { mutableStateOf(editing?.platform ?: "youtube") }
    var handle by remember { mutableStateOf(editing?.handle.orEmpty()) }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var categoryId by remember { mutableStateOf(request.categoryId) }
    var subcategoryId by remember { mutableStateOf(request.subcategoryId) }

    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    val canSubmit = editing != null || handle.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (editing == null) {
                    stringResource(R.string.goodpost_directory_add_channel)
                } else {
                    stringResource(R.string.goodpost_directory_rename)
                },
                style = MaterialTheme.typography.titleLarge,
                color = Wa.Text
            )
        },
        text = {
            Column(modifier = Modifier.imePadding()) {
                if (editing == null) {
                    Text(
                        text = stringResource(R.string.goodpost_directory_platform_label),
                        color = Wa.Accent,
                        fontSize = 13.sp
                    )
                    WaFilterRow {
                        DIRECTORY_PLATFORMS.forEach { option ->
                            WaFilterPill(
                                label = directoryPlatformLabel(option),
                                selected = platform == option,
                                onClick = { platform = option }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    WaField(
                        value = handle,
                        onValueChange = { handle = it },
                        label = stringResource(R.string.goodpost_directory_handle_label),
                        placeholder = stringResource(R.string.goodpost_directory_handle_hint),
                        enabled = !busy
                    )
                    Spacer(Modifier.height(10.dp))
                }

                WaField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.goodpost_directory_name_label),
                    placeholder = stringResource(R.string.goodpost_directory_name_optional),
                    enabled = !busy
                )

                if (categories.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.goodpost_directory_file_under),
                        color = Wa.Accent,
                        fontSize = 13.sp
                    )
                    WaFilterRow {
                        WaFilterPill(
                            label = stringResource(R.string.goodpost_directory_uncategorised),
                            selected = categoryId == null,
                            onClick = {
                                categoryId = null
                                subcategoryId = null
                            }
                        )
                        categories.forEach { category ->
                            WaFilterPill(
                                label = category.name,
                                selected = categoryId == category.id,
                                onClick = {
                                    categoryId = category.id
                                    subcategoryId = null
                                }
                            )
                        }
                    }
                    val subs = selectedCategory?.subcategories.orEmpty()
                    if (subs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WaFilterPill(
                                label = "—",
                                selected = subcategoryId == null,
                                onClick = { subcategoryId = null }
                            )
                            subs.forEach { sub ->
                                WaFilterPill(
                                    label = sub.name,
                                    selected = subcategoryId == sub.id,
                                    onClick = { subcategoryId = sub.id }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmit(
                        platform,
                        handle.trim().removePrefix("@"),
                        name.trim().ifBlank { null },
                        categoryId,
                        subcategoryId
                    )
                },
                enabled = canSubmit && !busy
            ) {
                Text(
                    text = stringResource(R.string.goodpost_save),
                    color = if (canSubmit) Wa.Accent else Wa.TextDim
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.goodpost_cancel), color = Wa.Accent)
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/** The three platforms a directory channel can live on. */
internal val DIRECTORY_PLATFORMS = listOf("youtube", "instagram", "x")

/** A platform's own name for itself. */
internal fun directoryPlatformLabel(platform: String): String = when (platform) {
    "youtube" -> "YouTube"
    "instagram" -> "Instagram"
    "x" -> "X"
    else -> platform
}
