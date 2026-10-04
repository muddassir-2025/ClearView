package com.muddassir.clearview.goodpost.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.DirectoryCategory
import com.muddassir.clearview.goodpost.data.DirectoryChannel
import com.muddassir.clearview.goodpost.data.DirectorySnapshot
import com.muddassir.clearview.goodpost.data.DirectorySubcategory

/**
 * The external channel directory, as a super administrator maintains it.
 *
 * ## Three levels, walked one at a time
 *
 * Categories, then the subcategories inside one, then the channels inside one.
 * A single page holding all of it at once was the wrong shape for two reasons: a
 * list of five categories with four channels each is thirty rows deep on a phone,
 * and adding a channel meant picking its filing out of a scrolling strip of every
 * category and subcategory in the product. Drilled down, WHERE the channel goes is
 * the page you are standing on — so the form no longer has to ask.
 *
 * ## One control per row
 *
 * Every row carries exactly one `⋮`. The destructive and the rare live behind it;
 * the one action worth keeping on the surface is that level's own "add".
 *
 * ## It re-reads after every write
 *
 * Nothing reconstructs the list from what it sent: a successful change is followed
 * by a fresh read, so the screen cannot disagree with the server about what is
 * filed where.
 *
 * ## The authority is the server's
 *
 * Only a super administrator is offered the way in, but that is a convenience.
 * Every route behind this screen is refused to anybody else on the server.
 */
@Composable
internal fun DirectoryAdminScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    var stack by remember { mutableStateOf<List<DirLevel>>(listOf(DirLevel.Root)) }

    var addCategoryOpen by remember { mutableStateOf(false) }
    var addSubcategoryOpen by remember { mutableStateOf(false) }
    var renameCategory by remember { mutableStateOf<DirectoryCategory?>(null) }
    var renameSubcategory by remember { mutableStateOf<DirectorySubcategory?>(null) }
    var deleteCategory by remember { mutableStateOf<DirectoryCategory?>(null) }
    var deleteSubcategory by remember { mutableStateOf<DirectorySubcategory?>(null) }
    var deleteChannel by remember { mutableStateOf<DirectoryChannel?>(null) }
    var channelForm by remember { mutableStateOf<ChannelFormRequest?>(null) }

    val busy = state.directoryBusy
    val directory = state.directory

    // What the current level actually resolves to. A category deleted underneath
    // this screen (or a read that has not landed yet) leaves a level pointing at
    // something that is gone; falling back to the root is what stops the page
    // rendering a title with nothing under it.
    val level = stack.last()
    val category = (level as? DirLevel.Category)
        ?.let { target -> directory.categories.firstOrNull { it.id == target.id } }
    val subcategory = (level as? DirLevel.Subcategory)?.let { target ->
        directory.categories.firstOrNull { it.id == target.categoryId }
            ?.subcategories?.firstOrNull { it.id == target.id }
    }
    val effectiveLevel: DirLevel = when {
        level is DirLevel.Category && category == null -> DirLevel.Root
        level is DirLevel.Subcategory && subcategory == null -> DirLevel.Root
        else -> level
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = when (effectiveLevel) {
                DirLevel.Root -> stringResource(R.string.goodpost_directory)
                DirLevel.Uncategorised ->
                    stringResource(R.string.goodpost_directory_uncategorised)
                is DirLevel.Category -> category?.name
                    ?: stringResource(R.string.goodpost_directory)
                is DirLevel.Subcategory -> subcategory?.name
                    ?: stringResource(R.string.goodpost_directory)
            },
            subtitle = when (effectiveLevel) {
                is DirLevel.Subcategory -> category?.name
                else -> null
            },
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = {
                        if (stack.size > 1) pop() else viewModel.back()
                    }
                )
            },
            actions = {
                // The `+` means this level's own "add", so the primary act of a
                // page is always reachable without scrolling to the bottom of it.
                WaIconAction(
                    icon = Icons.Filled.Add,
                    description = when (effectiveLevel) {
                        is DirLevel.Subcategory -> stringResource(R.string.goodpost_directory_add_channel)
                        is DirLevel.Category -> stringResource(R.string.goodpost_directory_add_subcategory)
                        else -> stringResource(R.string.goodpost_directory_add_category)
                    },
                    onClick = {
                        when (effectiveLevel) {
                            is DirLevel.Subcategory -> {
                                val sub = subcategory
                                if (sub != null) {
                                    channelForm = ChannelFormRequest(
                                        channel = null,
                                        categoryId = sub.categoryId,
                                        subcategoryId = sub.id
                                    )
                                }
                            }
                            is DirLevel.Category -> addSubcategoryOpen = true
                            else -> addCategoryOpen = true
                        }
                    },
                    enabled = !busy
                )
            }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                directory.isEmpty && state.directoryLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Wa.Accent) }

                directory.isEmpty -> WaEmptyState(
                    title = stringResource(R.string.goodpost_directory_empty_title),
                    note = stringResource(R.string.goodpost_directory_empty),
                    actionLabel = stringResource(R.string.goodpost_directory_add_category),
                    onAction = { addCategoryOpen = true }
                )

                else -> when (effectiveLevel) {
                    DirLevel.Root -> CategoryLevel(
                        directory = directory,
                        busy = busy,
                        onOpenCategory = { stack = stack + DirLevel.Category(it.id) },
                        onOpenUncategorised = { stack = stack + DirLevel.Uncategorised },
                        onRename = { renameCategory = it },
                        onDelete = { deleteCategory = it },
                        onAddCategory = { addCategoryOpen = true }
                    )

                    DirLevel.Uncategorised -> ChannelLevel(
                        channels = directory.unfiled,
                        busy = busy,
                        emptyNote = stringResource(R.string.goodpost_directory_no_channels),
                        onEdit = { channel ->
                            channelForm = ChannelFormRequest(
                                channel = channel,
                                categoryId = null,
                                subcategoryId = null
                            )
                        },
                        onRefresh = { viewModel.refreshDirectoryChannel(it.id) },
                        onDelete = { deleteChannel = it },
                        onAdd = {
                            channelForm = ChannelFormRequest(
                                channel = null,
                                categoryId = null,
                                subcategoryId = null
                            )
                        }
                    )

                    is DirLevel.Category -> CategoryDetailLevel(
                        category = category!!,
                        busy = busy,
                        onOpenSubcategory = { sub ->
                            stack = stack + DirLevel.Subcategory(category.id, sub.id)
                        },
                        onRenameSubcategory = { renameSubcategory = it },
                        onDeleteSubcategory = { deleteSubcategory = it },
                        onAddSubcategory = { addSubcategoryOpen = true },
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
                        onDeleteChannel = { deleteChannel = it }
                    )

                    is DirLevel.Subcategory -> ChannelLevel(
                        channels = subcategory!!.channels,
                        busy = busy,
                        emptyNote = stringResource(R.string.goodpost_directory_no_channels),
                        onEdit = { channel ->
                            channelForm = ChannelFormRequest(
                                channel = channel,
                                categoryId = subcategory.categoryId,
                                subcategoryId = subcategory.id
                            )
                        },
                        onRefresh = { viewModel.refreshDirectoryChannel(it.id) },
                        onDelete = { deleteChannel = it },
                        onAdd = {
                            channelForm = ChannelFormRequest(
                                channel = null,
                                categoryId = subcategory.categoryId,
                                subcategoryId = subcategory.id
                            )
                        }
                    )
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────

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

    if (addSubcategoryOpen) {
        val parent = category
        if (parent != null) {
            DirectoryTextDialog(
                title = stringResource(R.string.goodpost_directory_add_subcategory),
                label = stringResource(R.string.goodpost_directory_name_label),
                placeholder = parent.name,
                initial = "",
                busy = busy,
                onDismiss = { addSubcategoryOpen = false },
                onConfirm = { name ->
                    addSubcategoryOpen = false
                    viewModel.createDirectorySubcategory(parent.id, name)
                }
            )
        } else {
            addSubcategoryOpen = false
        }
    }

    renameCategory?.let { target ->
        DirectoryTextDialog(
            title = stringResource(R.string.goodpost_directory_rename),
            label = stringResource(R.string.goodpost_directory_name_label),
            placeholder = target.name,
            initial = target.name,
            busy = busy,
            onDismiss = { renameCategory = null },
            onConfirm = { name ->
                renameCategory = null
                viewModel.renameDirectoryCategory(target.id, name)
            }
        )
    }

    renameSubcategory?.let { target ->
        DirectoryTextDialog(
            title = stringResource(R.string.goodpost_directory_rename),
            label = stringResource(R.string.goodpost_directory_name_label),
            placeholder = target.name,
            initial = target.name,
            busy = busy,
            onDismiss = { renameSubcategory = null },
            onConfirm = { name ->
                renameSubcategory = null
                viewModel.renameDirectorySubcategory(target.id, name)
            }
        )
    }

    channelForm?.let { request ->
        DirectoryChannelForm(
            request = request,
            filing = filingLabel(directory, request),
            busy = busy,
            onDismiss = { channelForm = null },
            onSubmit = { platform, handle, name ->
                channelForm = null
                if (request.channel == null) {
                    viewModel.createDirectoryChannel(
                        platform, handle, request.categoryId, request.subcategoryId
                    )
                } else {
                    viewModel.updateDirectoryChannel(
                        request.channel.id,
                        name,
                        request.categoryId,
                        request.subcategoryId
                    )
                }
            }
        )
    }

    deleteCategory?.let { target ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_directory_delete_category),
            message = stringResource(R.string.goodpost_directory_delete_category_note),
            confirmLabel = stringResource(R.string.goodpost_directory_delete),
            onConfirm = {
                deleteCategory = null
                viewModel.deleteDirectoryCategory(target.id)
            },
            onDismiss = { deleteCategory = null }
        )
    }

    deleteSubcategory?.let { target ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_directory_delete_subcategory),
            message = stringResource(R.string.goodpost_directory_delete_subcategory_note),
            confirmLabel = stringResource(R.string.goodpost_directory_delete),
            onConfirm = {
                deleteSubcategory = null
                viewModel.deleteDirectorySubcategory(target.id)
            },
            onDismiss = { deleteSubcategory = null }
        )
    }

    deleteChannel?.let { target ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_directory_delete_channel),
            message = stringResource(R.string.goodpost_directory_delete_channel_note),
            confirmLabel = stringResource(R.string.goodpost_directory_remove),
            onConfirm = {
                deleteChannel = null
                viewModel.deleteDirectoryChannel(target.id)
            },
            onDismiss = { deleteChannel = null }
        )
    }
}

// ── Navigation ──────────────────────────────────────────────────────────

/** Where the manager currently is. A stack, so back means what it looks like. */
private sealed interface DirLevel {
    data object Root : DirLevel
    data object Uncategorised : DirLevel
    data class Category(val id: String) : DirLevel
    data class Subcategory(val categoryId: String, val id: String) : DirLevel
}

/** Which channel form is open: a new row, or one being edited. */
private data class ChannelFormRequest(
    val channel: DirectoryChannel?,
    val categoryId: String?,
    val subcategoryId: String?
)

/** The words for where a channel will be filed, for the form's subtitle. */
@Composable
private fun filingLabel(
    directory: DirectorySnapshot,
    request: ChannelFormRequest
): String {
    val subcategory = request.subcategoryId?.let { id ->
        directory.categories.asSequence()
            .flatMap { it.subcategories.asSequence() }
            .firstOrNull { it.id == id }
    }
    val category = request.categoryId?.let { id ->
        directory.categories.firstOrNull { it.id == id }
    }
    return when {
        subcategory != null && category != null -> "${category.name} › ${subcategory.name}"
        category != null -> category.name
        else -> stringResource(R.string.goodpost_directory_uncategorised)
    }
}

// ── Level 1: the categories ─────────────────────────────────────────────

@Composable
private fun CategoryLevel(
    directory: DirectorySnapshot,
    busy: Boolean,
    onOpenCategory: (DirectoryCategory) -> Unit,
    onOpenUncategorised: () -> Unit,
    onRename: (DirectoryCategory) -> Unit,
    onDelete: (DirectoryCategory) -> Unit,
    onAddCategory: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(directory.categories, key = { it.id }) { category ->
            DirectoryCard(
                name = category.name,
                count = category.channelCount,
                busy = busy,
                onClick = { onOpenCategory(category) },
                menu = listOf(
                    WaMenuItem(
                        label = stringResource(R.string.goodpost_directory_rename),
                        onClick = { onRename(category) }
                    ),
                    WaMenuItem(
                        label = stringResource(R.string.goodpost_directory_delete),
                        onClick = { onDelete(category) },
                        destructive = true
                    )
                )
            )
        }

        if (directory.unfiled.isNotEmpty()) {
            item(key = "unfiled") {
                DirectoryCard(
                    name = stringResource(R.string.goodpost_directory_uncategorised),
                    count = directory.unfiled.size,
                    busy = busy,
                    onClick = onOpenUncategorised,
                    menu = emptyList()
                )
            }
        }

        item(key = "add") {
            WaTextAction(
                text = stringResource(R.string.goodpost_directory_add_category),
                onClick = onAddCategory,
                enabled = !busy
            )
        }
    }
}

// ── Level 2: the subcategories inside one category ──────────────────────

@Composable
private fun CategoryDetailLevel(
    category: DirectoryCategory,
    busy: Boolean,
    onOpenSubcategory: (DirectorySubcategory) -> Unit,
    onRenameSubcategory: (DirectorySubcategory) -> Unit,
    onDeleteSubcategory: (DirectorySubcategory) -> Unit,
    onAddSubcategory: () -> Unit,
    onAddChannel: () -> Unit,
    onEditChannel: (DirectoryChannel) -> Unit,
    onRefreshChannel: (DirectoryChannel) -> Unit,
    onDeleteChannel: (DirectoryChannel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "subcategories-head") {
            WaSectionHeading(stringResource(R.string.goodpost_directory_subcategories))
        }

        if (category.subcategories.isEmpty()) {
            item(key = "subcategories-empty") {
                Text(
                    text = stringResource(R.string.goodpost_directory_no_subcategories),
                    color = Wa.TextDim,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }

        items(category.subcategories, key = { it.id }) { sub ->
            DirectoryCard(
                name = sub.name,
                count = sub.channels.size,
                busy = busy,
                onClick = { onOpenSubcategory(sub) },
                menu = listOf(
                    WaMenuItem(
                        label = stringResource(R.string.goodpost_directory_rename),
                        onClick = { onRenameSubcategory(sub) }
                    ),
                    WaMenuItem(
                        label = stringResource(R.string.goodpost_directory_remove),
                        onClick = { onDeleteSubcategory(sub) },
                        destructive = true
                    )
                )
            )
        }

        item(key = "add-subcategory") {
            WaTextAction(
                text = stringResource(R.string.goodpost_directory_add_subcategory),
                onClick = onAddSubcategory,
                enabled = !busy
            )
        }

        // Channels filed on the category itself, with no subcategory. Kept below
        // the subcategories because that is the exception, and given their own
        // heading so they are never mistaken for one of them.
        if (category.channels.isNotEmpty()) {
            item(key = "direct-head") {
                WaSectionHeading(stringResource(R.string.goodpost_directory_channels))
            }
            items(category.channels, key = { "c-" + it.id }) { channel ->
                WaCard {
                    DirectoryChannelRow(
                        channel = channel,
                        busy = busy,
                        onEdit = { onEditChannel(channel) },
                        onRefresh = { onRefreshChannel(channel) },
                        onDelete = { onDeleteChannel(channel) }
                    )
                }
            }
        }

        item(key = "add-channel") {
            WaTextAction(
                text = stringResource(R.string.goodpost_directory_add_channel),
                onClick = onAddChannel,
                enabled = !busy
            )
        }
    }
}

// ── Level 3: the channels inside one subcategory ────────────────────────

@Composable
private fun ChannelLevel(
    channels: List<DirectoryChannel>,
    busy: Boolean,
    emptyNote: String,
    onEdit: (DirectoryChannel) -> Unit,
    onRefresh: (DirectoryChannel) -> Unit,
    onDelete: (DirectoryChannel) -> Unit,
    onAdd: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (channels.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = emptyNote,
                    color = Wa.TextDim,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }

        items(channels, key = { it.id }) { channel ->
            WaCard {
                DirectoryChannelRow(
                    channel = channel,
                    busy = busy,
                    onEdit = { onEdit(channel) },
                    onRefresh = { onRefresh(channel) },
                    onDelete = { onDelete(channel) }
                )
            }
        }

        item(key = "add") {
            WaTextAction(
                text = stringResource(R.string.goodpost_directory_add_channel),
                onClick = onAdd,
                enabled = !busy
            )
        }
    }
}

// ── Shared rows ─────────────────────────────────────────────────────────

/**
 * A tappable card: a tinted badge, a name, a count, and one `⋮`.
 *
 * Used for both a category and a subcategory, so the two levels read the same way
 * and the only difference between them is how deep you are.
 */
@Composable
private fun DirectoryCard(
    name: String,
    count: Int,
    busy: Boolean,
    onClick: () -> Unit,
    menu: List<WaMenuItem>
) {
    WaCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Wa.Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Collections,
                    contentDescription = null,
                    tint = Wa.Accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = Wa.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.goodpost_directory_channel_count, count),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )
            }
            if (menu.isNotEmpty()) {
                WaOverflowMenu(items = menu, modifier = Modifier.size(40.dp))
            }
        }
    }
}

/**
 * One channel: avatar, name, handle with its platform, and one `⋮`.
 *
 * Tapping the row opens the editor, because that is the common intent; the rarer
 * and the destructive sit behind the menu.
 */
@Composable
private fun DirectoryChannelRow(
    channel: DirectoryChannel,
    busy: Boolean,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !busy, onClick = onEdit),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WaAvatar(name = channel.displayName, size = 40.dp, url = channel.iconUrl)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                color = Wa.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${channel.handle}  ·  ${directoryPlatformLabel(channel.platform)}",
                color = Wa.TextDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        WaOverflowMenu(
            items = listOf(
                WaMenuItem(
                    label = stringResource(R.string.goodpost_directory_fetch),
                    onClick = onRefresh
                ),
                WaMenuItem(
                    label = stringResource(R.string.goodpost_directory_rename),
                    onClick = onEdit
                ),
                WaMenuItem(
                    label = stringResource(R.string.goodpost_directory_remove),
                    onClick = onDelete,
                    destructive = true
                )
            ),
            modifier = Modifier.size(40.dp)
        )
    }
}

// ── The channel form ────────────────────────────────────────────────────

/**
 * Add or edit one channel.
 *
 * Filing is not a question here: the page you tapped "Add channel" on IS the
 * answer, and its name is shown in the bar so there is no doubt where the channel
 * is about to land. That is what the drill-down buys — three fields instead of
 * three fields plus a list of every category in the product.
 */
@Composable
private fun DirectoryChannelForm(
    request: ChannelFormRequest,
    filing: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (platform: String, handle: String, name: String?) -> Unit
) {
    val editing = request.channel
    var platform by remember { mutableStateOf(editing?.platform ?: "youtube") }
    var handle by remember { mutableStateOf(editing?.handle.orEmpty()) }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }

    val canSave = editing != null || handle.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas).imePadding()) {
            WaTopBar(
                title = stringResource(
                    if (editing == null) R.string.goodpost_directory_add_channel
                    else R.string.goodpost_directory_edit_channel
                ),
                subtitle = filing,
                navigation = {
                    WaIconAction(
                        icon = Icons.Filled.Close,
                        description = stringResource(R.string.goodpost_cancel),
                        onClick = onDismiss
                    )
                }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                if (editing == null) {
                    Text(
                        text = stringResource(R.string.goodpost_directory_platform_label),
                        color = Wa.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DIRECTORY_PLATFORMS.forEach { option ->
                            PlatformChoice(
                                label = directoryPlatformLabel(option),
                                selected = platform == option,
                                onClick = { platform = option },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    WaField(
                        value = handle,
                        onValueChange = { handle = it },
                        label = stringResource(R.string.goodpost_directory_handle_label),
                        placeholder = stringResource(R.string.goodpost_directory_handle_hint),
                        enabled = !busy
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.goodpost_directory_handle_preview,
                            directoryPlatformLabel(platform),
                            handle.trim().removePrefix("@").ifBlank {
                                stringResource(R.string.goodpost_directory_handle_hint)
                            }
                        ),
                        color = Wa.TextDim,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(20.dp))
                } else {
                    Text(
                        text = "@${editing.handle}  ·  ${directoryPlatformLabel(editing.platform)}",
                        color = Wa.TextDim,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                }

                WaField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.goodpost_directory_name_label),
                    placeholder = stringResource(R.string.goodpost_directory_name_optional),
                    enabled = !busy
                )
            }

            Box(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                WaPrimaryButton(
                    text = stringResource(
                        if (editing == null) R.string.goodpost_directory_add_channel
                        else R.string.goodpost_save
                    ),
                    onClick = {
                        onSubmit(
                            platform,
                            handle.trim().removePrefix("@"),
                            name.trim().ifBlank { null }
                        )
                    },
                    enabled = canSave,
                    busy = busy
                )
            }
        }
    }
}

/** One of the platform options, as an equal third rather than a small pill. */
@Composable
private fun PlatformChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Wa.Accent else Wa.Bar)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Wa.OnAccent else Wa.Text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** A single-field form, shared by every rename and every "new group". */
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

/** The three platforms a directory channel can live on. */
internal val DIRECTORY_PLATFORMS = listOf("youtube", "instagram", "x")

/** A platform's own name for itself. */
internal fun directoryPlatformLabel(platform: String): String = when (platform) {
    "youtube" -> "YouTube"
    "instagram" -> "Instagram"
    "x" -> "X"
    else -> platform
}
