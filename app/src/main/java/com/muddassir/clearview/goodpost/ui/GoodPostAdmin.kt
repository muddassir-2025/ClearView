package com.muddassir.clearview.goodpost.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.muddassir.clearview.BuildConfig
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.readGoodPostAttachment

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
/**
 * The last step of becoming a creator: naming the channel you will run (§16).
 *
 * A step rather than a screen in the navigation stack, because it is not a place
 * the reader can navigate TO or away from - it is the second half of one
 * sign-in, and backing out of it should leave them on the channel list with no
 * account created, which is exactly what happens: nothing is written server-side
 * until the button below is pressed.
 */
@Composable
internal fun CreatorChannelStep(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas).imePadding()) {
        WaTopBar(
            title = stringResource(R.string.goodpost_create_channel),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    // Back to the credentials, not out of the flow. This step is
                    // the second half of one sign-in, so its arrow is how somebody
                    // who signed in with the wrong account goes back and chooses
                    // another one — and it is the only way out that does not leave
                    // the half-made creator state behind.
                    onClick = viewModel::backToSignIn
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
                text = stringResource(R.string.goodpost_creator_name_channel),
                color = Wa.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(8.dp))

            // Which identity the channel will belong to, said plainly. It is the
            // account the reader just signed in as, so showing it here is the
            // one place they can notice it is the wrong one before a channel
            // exists under it.
            state.creatorEmail?.let { email ->
                Text(text = email, color = Wa.Accent, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = stringResource(R.string.goodpost_creator_name_note),
                color = Wa.TextDim,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(24.dp))

            WaField(
                value = state.creatorChannelName,
                onValueChange = viewModel::onCreatorChannelNameChange,
                label = stringResource(R.string.goodpost_creator_channel_name),
                placeholder = stringResource(R.string.goodpost_creator_channel_name_hint),
                enabled = !state.adminBusy,
                imeAction = ImeAction.Done,
                onDone = viewModel::creatorCreateChannel
            )

            Spacer(Modifier.height(24.dp))

            WaPrimaryButton(
                text = stringResource(R.string.goodpost_continue),
                // Disabled rather than refused: an empty name is a field that is
                // not finished, not an error to be announced.
                enabled = !state.adminBusy && state.creatorChannelName.isNotBlank(),
                busy = state.adminBusy,
                onClick = viewModel::creatorCreateChannel
            )
        }
    }
}

@Composable
internal fun AdminLoginScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    // §16: a creator who has signed in and has no channel yet. Checked here
    // rather than in the navigation, because it belongs to this screen's flow.
    if (state.creatorNeedsChannel) {
        CreatorChannelStep(state, viewModel)
        return
    }

    // §19 Bug 1: the sign-in fields are the only thing on this screen, and the
    // Continue button sits below them. `imePadding()` keeps it above the keyboard
    // rather than behind it, so the form can be completed in one go.
    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas).imePadding()) {
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
                text = stringResource(R.string.goodpost_creator_note),
                color = Wa.TextDim,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(22.dp))

            // The Good Post artwork, at the top of the one screen in the product
            // that is about the product rather than about a channel. `Fit`
            // rather than `Crop` deliberately: the drawing is very nearly square,
            // and cropping a near-square into a wide band is how a logo loses its
            // edges on the one screen where it is the first thing anybody sees.
            // Nothing here scales it up either — a fixed height and Fit mean it
            // is drawn whole, or smaller.
            Image(
                painter = painterResource(id = R.drawable.goodpost),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(Modifier.height(22.dp))

            // Google, when this build has a web client id to send Google.
            //
            // The id comes from google-services.json at build time, so a build
            // made before the Google provider was enabled in the Firebase
            // console simply has none - and then this button is absent rather
            // than present and guaranteed to fail.
            //
            // It shows the device's Google accounts, which is the flow a creator
            // signs up through: pick the account the channel will belong to, and
            // the next screen asks only for a name.
            val activity = LocalContext.current as? Activity
            if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() && activity != null) {
                WaGoogleButton(
                    text = stringResource(R.string.goodpost_continue_with_google),
                    enabled = !state.adminBusy,
                    onClick = { viewModel.creatorSignInWithGoogle(activity) }
                )

                Spacer(Modifier.height(18.dp))
            }

            // The second way in, as a card that opens the form when it is tapped.
            //
            // Closed by default, and that is the whole design: a creator signs up
            // with the card above and never sees a password field, while the super
            // administrator and the channel administrators a deployment
            // provisioned get to their own form without being told they are a
            // different kind of account. See [GoodPostUiState.otherWaysOpen].
            WaExpandableCard(
                text = stringResource(R.string.goodpost_other_ways),
                icon = Icons.Filled.Email,
                expanded = state.otherWaysOpen,
                enabled = !state.adminBusy,
                onClick = viewModel::toggleOtherWays
            )

            if (state.otherWaysOpen) {
                Spacer(Modifier.height(16.dp))

                WaField(
                    value = state.adminEmail,
                    onValueChange = viewModel::onAdminEmailChange,
                    label = stringResource(R.string.goodpost_email),
                    placeholder = stringResource(R.string.goodpost_email_hint),
                    enabled = !state.adminBusy,
                    keyboardType = KeyboardType.Email,
                    // A login form, declared as one: this is where a saved
                    // credential belongs, and saying so is what keeps the
                    // service from guessing its way onto the other fields.
                    autofillContentType = ContentType.EmailAddress,
                    imeAction = ImeAction.Next
                )

                Spacer(Modifier.height(14.dp))

                WaField(
                    value = state.adminPassword,
                    onValueChange = viewModel::onAdminPasswordChange,
                    label = stringResource(R.string.goodpost_password),
                    placeholder = stringResource(R.string.goodpost_password_hint),
                    enabled = !state.adminBusy,
                    keyboardType = KeyboardType.Password,
                    masked = true,
                    autofillContentType = ContentType.Password,
                    imeAction = ImeAction.Done,
                    onDone = viewModel::signIn
                )

                Spacer(Modifier.height(18.dp))

                // ONE action for the form. It is not labeled "Sign in" or "Sign
                // up" because it is both: the server decides whether these
                // credentials are a provisioned administrator, and Firebase decides
                // whether they are a creator who signed up this way. Asking the
                // person to choose would be asking them to know something the app
                // can find out in one round trip — see [GoodPostViewModel.signIn].
                WaPrimaryButton(
                    text = stringResource(R.string.goodpost_continue),
                    enabled = !state.adminBusy &&
                        state.adminEmail.isNotBlank() &&
                        state.adminPassword.isNotBlank(),
                    busy = state.adminBusy,
                    onClick = viewModel::signIn
                )
            }
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
 * Create or edit a channel as a clean full screen (§19, §20).
 */
@Composable
internal fun ChannelFormScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    val creating = state.channelFormId == null
    //
    // Two different things, and they were one field for a while:
    //
    //  * `mintsAdmin` — a NEW administrator is being created beside the channel,
    //    which is what the create form does and only a super admin may do (§20).
    //  * `editsPassword` — the password of the EXISTING account is on offer,
    //    which is the edit form (§20). It is here so a forgotten channel password
    //    is a field rather than a support request, and so a reset does not require
    //    the channel to be deleted and recreated.
    //
    // The email field belongs to the first and NOT the second: the account
    // already exists and its address is what it signs in with.
    val mintsAdmin = state.channelFormAdminEmail != null
    val editsPassword = !mintsAdmin && state.channelFormAdminPassword != null
    val context = LocalContext.current

    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val attachment = readGoodPostAttachment(context, uri)
        if (attachment == null) viewModel.reportUnsupportedMedia() else viewModel.onChannelIconPicked(attachment)
    }

    val picked = state.channelFormIcon
    val removed = state.channelFormIconRemoved
    val existingUrl = if (removed) null else state.channelFormExistingIconUrl
    val showingPicked = picked != null && picked.mediaId != null

    WaBackdrop {
        // §19 Bug 1: Create/Save is at the bottom of a scrolling form, so without
        // this the last fields and the button would sit under the keyboard.
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            WaTopBar(
                title = stringResource(
                    if (creating) R.string.goodpost_new_channel
                    else R.string.goodpost_edit_channel
                ),
                navigation = {
                    WaIconAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        description = stringResource(R.string.goodpost_back),
                        onClick = viewModel::cancelChannelForm
                    )
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Centered circular avatar
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Wa.Bar)
                        .clickable(
                            enabled = !state.adminBusy && state.composerMediaAvailable,
                            onClick = {
                                iconPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    WaAvatar(
                        name = state.channelFormName.ifBlank { "?" },
                        size = 100.dp,
                        url = if (showingPicked) null else existingUrl
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (removed) {
                        WaTextAction(
                            text = stringResource(R.string.goodpost_channel_image_keep),
                            enabled = !state.adminBusy,
                            onClick = viewModel::keepChannelIcon
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
                            enabled = !state.adminBusy && state.composerMediaAvailable,
                            onClick = {
                                iconPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                    }

                    if (!removed && (picked != null || existingUrl != null)) {
                        Spacer(Modifier.width(16.dp))
                        WaTextAction(
                            text = stringResource(R.string.goodpost_channel_image_remove),
                            enabled = !state.adminBusy,
                            onClick = viewModel::removeChannelIcon,
                            destructive = true
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                WaField(
                    value = state.channelFormName,
                    onValueChange = viewModel::onChannelFormNameChange,
                    label = stringResource(R.string.goodpost_channel_name),
                    placeholder = stringResource(R.string.goodpost_channel_name_hint),
                    enabled = !state.adminBusy
                )

                Spacer(Modifier.height(16.dp))

                WaField(
                    value = state.channelFormDescription,
                    onValueChange = viewModel::onChannelFormDescriptionChange,
                    label = stringResource(R.string.goodpost_channel_description),
                    placeholder = stringResource(R.string.goodpost_channel_description_hint),
                    enabled = !state.adminBusy,
                    singleLine = false,
                    minHeight = 90.dp
                )

                if (state.categories.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))

                    Text(
                        text = stringResource(R.string.goodpost_channel_category),
                        color = Wa.TextDim,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    WaFilterRow {
                        WaFilterPill(
                            label = stringResource(R.string.goodpost_channel_category_none),
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

                if (mintsAdmin || editsPassword) {
                    Spacer(Modifier.height(20.dp))

                    Text(
                        text = stringResource(
                            if (mintsAdmin) R.string.goodpost_channel_admin_section
                            else R.string.goodpost_channel_admin_section_edit
                        ),
                        color = Wa.TextDim,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (editsPassword) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.goodpost_admin_password_keep),
                            color = Wa.TextDim,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    if (mintsAdmin) {
                        WaField(
                            value = state.channelFormAdminEmail.orEmpty(),
                            onValueChange = viewModel::onChannelFormAdminEmailChange,
                            label = stringResource(R.string.goodpost_admin_email),
                            placeholder = stringResource(R.string.goodpost_email_hint),
                            enabled = !state.adminBusy,
                            keyboardType = KeyboardType.Email
                        )

                        Spacer(Modifier.height(14.dp))
                    }

                    WaField(
                        value = state.channelFormAdminPassword.orEmpty(),
                        onValueChange = viewModel::onChannelFormAdminPasswordChange,
                        // The floor is IN the label, not only in the refusal.
                        // "Admin password" plus "that password is too short" is
                        // how somebody tries four passwords in a row that were
                        // all one character short of a rule nobody stated (§10).
                        label = stringResource(
                            if (mintsAdmin) R.string.goodpost_admin_password
                            else R.string.goodpost_admin_password_new,
                            BuildConfig.ADMIN_MIN_PASSWORD_LENGTH
                        ),
                        placeholder = stringResource(R.string.goodpost_password_hint),
                        enabled = !state.adminBusy,
                        keyboardType = KeyboardType.Password,
                        // The password being minted for a new channel's
                        // administrator: whoever is creating the channel reads it
                        // off the screen to hand it over, which is exactly why it
                        // must not be sitting in the clear while they type it.
                        masked = true,
                        // ...and why a SAVED password must not be offered here:
                        // this is a new credential for somebody else, so the
                        // field declares itself as one and the Autofill service
                        // stops volunteering the operator's own login.
                        autofillContentType = ContentType.NewPassword
                    )
                }

                Spacer(Modifier.height(32.dp))

                WaPrimaryButton(
                    text = stringResource(
                        if (creating) R.string.goodpost_create_channel else R.string.goodpost_save
                    ),
                    enabled = !state.adminBusy && state.channelFormName.isNotBlank(),
                    busy = state.adminBusy,
                    onClick = viewModel::submitChannelForm
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
