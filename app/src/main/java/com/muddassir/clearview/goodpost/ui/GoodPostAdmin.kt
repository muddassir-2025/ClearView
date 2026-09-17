package com.muddassir.clearview.goodpost.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel

/**
 * The administrator way in (§16).
 *
 * This is reached from "Create a channel" at the bottom of the channel list, and
 * it is the ONLY place the app asks anyone to identify themselves. That is the
 * whole point: a reader of Good Post never sees this screen, and the credentials
 * that work here were provisioned by the deployment rather than signed up for in
 * the app.
 *
 * The refusal is deliberately uninformative. A wrong address and a wrong
 * password produce the same sentence, because distinguishing them would turn
 * this screen into a way to discover which addresses exist — and the server
 * answers identically for both, so the wording here cannot leak what the API
 * withholds.
 */
@Composable
internal fun AdminLoginScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = stringResource(R.string.goodpost_create_channel),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Text(
                text = stringResource(R.string.goodpost_admin_signin_note),
                color = Wa.TextDim,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(24.dp))

            WaField(
                value = state.adminEmail,
                onValueChange = viewModel::onAdminEmailChange,
                label = stringResource(R.string.goodpost_email),
                placeholder = stringResource(R.string.goodpost_email_hint),
                enabled = !state.adminBusy,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )

            Spacer(Modifier.height(16.dp))

            WaField(
                value = state.adminPassword,
                onValueChange = viewModel::onAdminPasswordChange,
                label = stringResource(R.string.goodpost_password),
                placeholder = stringResource(R.string.goodpost_password_hint),
                enabled = !state.adminBusy,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onDone = viewModel::adminSignIn
            )

            Spacer(Modifier.height(24.dp))

            WaPrimaryButton(
                text = stringResource(R.string.goodpost_continue),
                enabled = !state.adminBusy &&
                    state.adminEmail.isNotBlank() &&
                    state.adminPassword.isNotBlank(),
                busy = state.adminBusy,
                onClick = viewModel::adminSignIn
            )

            Spacer(Modifier.height(28.dp))

            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Wa.Bar),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Wa.TextDim,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.goodpost_no_access),
                        color = Wa.Text,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // Configurable rather than baked in: the address belongs
                        // to whoever deploys this, and a hard-coded one would be
                        // wrong for every installation but one.
                        text = stringResource(
                            R.string.goodpost_contact,
                            stringResource(R.string.goodpost_admin_contact)
                        ),
                        color = Wa.TextDim,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * The administrator's own view (§19).
 *
 * A plain list of the channels this account may publish to — one for a channel
 * admin, all of them for a super admin, which the server decides and this screen
 * simply renders. It uses the same rows, avatar and type as the public list,
 * because an administrator is looking at the same thing with more rights.
 */
@Composable
internal fun AdminHomeScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = stringResource(R.string.goodpost_my_channels),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            },
            actions = {
                WaOverflowMenu(
                    items = listOf(
                        WaMenuItem(
                            label = stringResource(R.string.goodpost_sign_out),
                            onClick = viewModel::adminSignOut,
                            destructive = true
                        )
                    )
                )
            }
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                state.adminChannels.forEach { channel ->
                    item(key = channel.id) {
                        WaChannelRow(
                            title = channel.name,
                            preview = channel.description?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.goodpost_no_description),
                            onClick = { viewModel.openAdminChannel(channel.id) },
                            avatar = {
                                WaAvatar(name = channel.name, size = 49.dp, url = channel.iconUrl)
                            },
                            trailing = {
                                // Editing the channel itself lives here, beside
                                // the row, rather than a screen deeper: an
                                // administrator changes a name far less often
                                // than they publish, and it should not sit in
                                // the way of publishing.
                                WaTextAction(
                                    text = stringResource(R.string.goodpost_edit),
                                    onClick = { viewModel.startEditChannel(channel) }
                                )
                            }
                        )
                    }
                }

                if (state.adminChannels.isEmpty() && !state.adminChannelsLoading) {
                    item(key = "empty") {
                        WaEmptyState(
                            title = stringResource(R.string.goodpost_empty_admin_title),
                            note = stringResource(R.string.goodpost_empty_admin_note)
                        )
                    }
                }
            }

            WaFab(
                icon = Icons.Filled.Add,
                description = stringResource(R.string.goodpost_create_channel),
                onClick = viewModel::startCreateChannel,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            )
        }
    }
}

/**
 * A channel's profile image, on the form that creates or edits it (§21).
 *
 * Four states, and each is drawn from the real one rather than guessed:
 *
 *   no image, nothing picked   → the picker
 *   an upload in flight        → the avatar with "Uploading…"
 *   a new image ready to save  → the avatar with "Ready", and a Remove
 *   the channel's existing one → the avatar, with a Remove that will clear it
 *
 * The preview is the SAME [WaAvatar] every other screen uses, so the form shows
 * what the list row will show: a chosen image uploaded and then rendered at
 * 64dp, or the channel's initial while it is still on its way.
 */
@Composable
private fun ChannelIconField(
    state: GoodPostUiState,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onKeep: () -> Unit
) {
    val picked = state.channelFormIcon
    val removed = state.channelFormIconRemoved

    // The image being saved wins over the saved one, so the preview is what the
    // POST will leave behind rather than what the server still holds.
    val existingUrl = if (removed) null else state.channelFormExistingIconUrl
    val showingPicked = picked != null && picked.mediaId != null

    Row(verticalAlignment = Alignment.CenterVertically) {
        WaAvatar(
            name = state.channelFormName.ifBlank { "?" },
            size = 64.dp,
            // A picked file is not previewed from its local URI: that would be a
            // second image pipeline, and the upload is short. The initial stands
            // in and the state is spelled out beside it.
            url = if (showingPicked) null else existingUrl
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.goodpost_channel_image),
                color = Wa.Text,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = when {
                    !state.composerMediaAvailable ->
                        stringResource(R.string.goodpost_error_media_unavailable)
                    picked?.state is GoodPostUploadState.Uploading ->
                        stringResource(R.string.goodpost_attachment_uploading)
                    picked?.state is GoodPostUploadState.Failed ->
                        stringResource(R.string.goodpost_attachment_failed)
                    showingPicked -> stringResource(R.string.goodpost_attachment_ready)
                    removed -> stringResource(R.string.goodpost_channel_image_removing)
                    existingUrl != null -> stringResource(R.string.goodpost_channel_image_current)
                    else -> stringResource(R.string.goodpost_channel_image_none)
                },
                color = Wa.TextDim,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (removed) {
                    // Reversible until Save: a removal that could not be undone
                    // would send someone back out of the form to recover.
                    WaTextAction(
                        text = stringResource(R.string.goodpost_channel_image_keep),
                        enabled = !busy,
                        onClick = onKeep
                    )
                } else {
                    WaTextAction(
                        text = stringResource(
                            if (picked != null || existingUrl != null) {
                                R.string.goodpost_channel_image_change
                            } else {
                                R.string.goodpost_channel_image_add
                            }
                        ),
                        enabled = !busy && state.composerMediaAvailable,
                        onClick = onPick
                    )
                }

                if (picked != null || existingUrl != null) {
                    Spacer(Modifier.width(16.dp))
                    WaTextAction(
                        text = stringResource(R.string.goodpost_channel_image_remove),
                        enabled = !busy,
                        onClick = onRemove,
                        destructive = true
                    )
                }
            }
        }
    }
}

/**
 * Create or edit a channel (§19, §20).
 *
 * A super admin creating a channel also names the account that will run it,
 * because the server creates both in one step — a channel with no administrator
 * would be one nobody could publish to. A channel admin editing their own
 * channel sees no credential fields at all, and the server would refuse them if
 * they were sent.
 */
@Composable
internal fun ChannelFormDialog(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    val creating = state.channelFormId == null
    val mintsAdmin = state.channelFormAdminEmail != null
    val context = LocalContext.current

    /**
     * Pick the profile image.
     *
     * `PickVisualMedia` for a single IMAGE, not `ImageAndVideo`: the avatar is
     * drawn into a circle, so a video has no meaning in one and offering it would
     * be a choice that can only be refused. `SingleSelect` still returns a list
     * on some OEM implementations, so the first element is taken rather than the
     * result being trusted to have exactly one.
     */
    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val attachment = readGoodPostAttachment(context, uri)
        if (attachment == null) viewModel.reportUnsupportedMedia() else viewModel.onChannelIconPicked(attachment)
    }

    Dialog(onDismissRequest = viewModel::cancelChannelForm) {
        Column(
            modifier = Modifier
                .background(Wa.Bar, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(
                    if (creating) R.string.goodpost_create_channel
                    else R.string.goodpost_edit_channel
                ),
                color = Wa.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            // The image, at the top of the form because it is the one field a
            // reader sees before anything else the channel says.
            ChannelIconField(
                state = state,
                busy = state.adminBusy,
                onPick = {
                    iconPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemove = viewModel::removeChannelIcon,
                onKeep = viewModel::keepChannelIcon
            )

            Spacer(Modifier.height(16.dp))

            WaField(
                value = state.channelFormName,
                onValueChange = viewModel::onChannelFormNameChange,
                label = stringResource(R.string.goodpost_channel_name),
                placeholder = stringResource(R.string.goodpost_channel_name_hint),
                enabled = !state.adminBusy
            )

            Spacer(Modifier.height(12.dp))

            WaField(
                value = state.channelFormDescription,
                onValueChange = viewModel::onChannelFormDescriptionChange,
                label = stringResource(R.string.goodpost_channel_description),
                placeholder = stringResource(R.string.goodpost_channel_description_hint),
                enabled = !state.adminBusy,
                singleLine = false,
                minHeight = 84.dp
            )

            if (state.categories.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.goodpost_category),
                    color = Wa.Accent,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                WaFilterRow {
                    WaFilterPill(
                        label = stringResource(R.string.goodpost_no_category),
                        selected = state.channelFormCategory == null,
                        onClick = { viewModel.onChannelFormCategoryChange(null) }
                    )
                    state.categories.forEach { category ->
                        WaFilterPill(
                            label = category.label,
                            selected = state.channelFormCategory == category.slug,
                            onClick = { viewModel.onChannelFormCategoryChange(category.slug) }
                        )
                    }
                }
            }

            if (mintsAdmin) {
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.goodpost_channel_admin_section),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(10.dp))

                WaField(
                    value = state.channelFormAdminEmail.orEmpty(),
                    onValueChange = viewModel::onChannelFormAdminEmailChange,
                    label = stringResource(R.string.goodpost_admin_email),
                    placeholder = stringResource(R.string.goodpost_email_hint),
                    enabled = !state.adminBusy,
                    keyboardType = KeyboardType.Email
                )

                Spacer(Modifier.height(12.dp))

                WaField(
                    value = state.channelFormAdminPassword.orEmpty(),
                    onValueChange = viewModel::onChannelFormAdminPasswordChange,
                    label = stringResource(R.string.goodpost_admin_password),
                    placeholder = stringResource(R.string.goodpost_password_hint),
                    enabled = !state.adminBusy,
                    keyboardType = KeyboardType.Password
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                WaTextAction(
                    text = stringResource(R.string.goodpost_cancel),
                    enabled = !state.adminBusy,
                    onClick = viewModel::cancelChannelForm
                )
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.width(150.dp)) {
                    WaPrimaryButton(
                        text = stringResource(
                            if (creating) R.string.goodpost_create else R.string.goodpost_save
                        ),
                        enabled = !state.adminBusy && state.channelFormName.isNotBlank(),
                        busy = state.adminBusy,
                        onClick = viewModel::submitChannelForm
                    )
                }
            }
        }
    }
}
