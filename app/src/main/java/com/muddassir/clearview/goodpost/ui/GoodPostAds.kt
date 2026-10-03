package com.muddassir.clearview.goodpost.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostAd
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.readGoodPostAttachment
import kotlinx.coroutines.delay

/**
 * Advertisement cards (§9–§17).
 *
 * Three surfaces, one file, because they are one feature: the reader's carousel
 * at the top of Channels and Explore, the manager's list of every card, and the
 * editor for one. Splitting them across files would put the card's layout in
 * three places — and the preview on the editor would then be a second drawing of
 * the thing a reader sees, which is precisely how a preview stops matching.
 *
 * Nothing here talks to the network. Every action goes through the ViewModel, so
 * the upload handshake, the authorization and the error wording stay in one place
 * per concern, exactly as the channel screens do it.
 */

/** How long a card holds the carousel before the next one slides in (§12). */
private const val AD_AUTO_SLIDE_MS = 5_000L

/**
 * Open a card's target, wherever it points.
 *
 * A `mailto:` address is opened with `ACTION_SENDTO`, not `ACTION_VIEW`: the
 * former asks the system for something that can COMPOSE a message to that
 * address, while the latter can be claimed by a browser that would merely show
 * the address as text. An `http(s)` link is the other case and opens in whatever
 * the device browses with.
 *
 * A failure is swallowed on purpose. A device with nothing registered for the
 * target should ignore the tap, not be shown an error over an advertisement.
 */
private fun openAdTarget(context: Context, url: String?) {
    val target = url?.trim().orEmpty()
    if (target.isBlank()) return
    val uri = Uri.parse(target)
    val intent = if (target.startsWith("mailto:", ignoreCase = true)) {
        Intent(Intent.ACTION_SENDTO, uri)
    } else {
        Intent(Intent.ACTION_VIEW, uri)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * A picture from a signed URL, decoded once and reused.
 *
 * The same peek-then-load shape the media gallery and the avatar use, so a card
 * that is on screen every time the tab opens is decoded once rather than on each
 * frame. A null URL draws nothing rather than an error: a text card has no image,
 * and an image card on a deployment with no bucket is a state the editor words
 * and a reader never sees.
 */
@Composable
private fun AdRemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(url) { mutableStateOf(GoodPostImages.peek(url)) }

    LaunchedEffect(url) {
        if (bitmap == null && url != null) bitmap = GoodPostImages.load(url, maxWidthPx = 1080)
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier.fillMaxSize()
        )
    }
}

/**
 * The reader's carousel (§12).
 *
 * A horizontal pager rather than a stack: a card is a fixed shape, and MORE than
 * one can be active for a placement, so the reader is shown them one at a time
 * with the count as dots beneath. It slides on its own so a card is seen without
 * being touched, and stops at the last one to begin again rather than looping
 * instantly — a card nobody chose to advance past is still being read.
 *
 * Absent entirely when there is no active card, and that is the ordinary state:
 * a deployment that has created none, and one whose cards have all expired, both
 * leave the screen exactly as it was before this feature existed.
 */
@Composable
internal fun AdCarousel(ads: List<GoodPostAd>, modifier: Modifier = Modifier) {
    if (ads.isEmpty()) return

    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { ads.size })

    if (ads.size > 1) {
        LaunchedEffect(pagerState, ads.size) {
            while (true) {
                delay(AD_AUTO_SLIDE_MS)
                val next = (pagerState.currentPage + 1) % ads.size
                pagerState.animateScrollToPage(next)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 10.dp
        ) { page ->
            AdCard(ad = ads[page], onClick = { openAdTarget(context, ads[page].targetUrl) })
        }

        if (ads.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ads.indices.forEach { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (active) 7.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (active) Wa.Accent else Wa.Divider)
                    )
                }
            }
        }
    }
}

/**
 * One card, as it appears to a reader (§12).
 *
 * An image card is its picture with a small "Sponsored" stamp; a text card is its
 * words over the same surface. Both are the same rounded rectangle, so the two
 * kinds do not read as two different features — and both open [targetUrl] on a
 * tap.
 */
@Composable
private fun AdCard(ad: GoodPostAd, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Wa.Bar)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (ad.isImage && ad.imageUrl != null) {
            AdRemoteImage(url = ad.imageUrl, contentScale = ContentScale.Crop)
        } else {
            Text(
                text = ad.text.orEmpty(),
                color = Wa.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 22.dp)
            )
        }

        // The disclosure that this is paid placement, on every card and in the
        // same corner. Not optional and not a per-card setting: a reader is
        // entitled to know, and a card that could switch it off would be a card
        // pretending to be a channel update.
        Text(
            text = stringResource(R.string.goodpost_ad_sponsored),
            color = Wa.TextDim,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Wa.Canvas)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ── The manager's list (§15) ────────────────────────────────────────────

/**
 * Every card, active or not (§15).
 *
 * One row per card with enough to recognise it — its picture or words, where it
 * runs, and whether it is on — because the manager's job here is to find the card
 * that is (or is not) doing something and open it. Creating and editing are steps
 * over this screen, the same shape the channel list has.
 */
@Composable
internal fun GoodPostAdsScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    var pendingDelete by remember { mutableStateOf<GoodPostAd?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = stringResource(R.string.goodpost_ads),
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
                    description = stringResource(R.string.goodpost_ad_new),
                    onClick = { viewModel.openAdForm(null) }
                )
            }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(state.adminAds, key = { it.id }) { ad ->
                    AdAdminRow(
                        ad = ad,
                        modifier = Modifier.animateItem(),
                        onClick = { viewModel.openAdForm(ad) },
                        onDelete = { pendingDelete = ad }
                    )
                }

                if (state.adminAds.isEmpty() && !state.adminAdsLoading) {
                    item(key = "empty") {
                        WaEmptyState(
                            title = stringResource(R.string.goodpost_ads_empty_title),
                            note = stringResource(R.string.goodpost_ads_empty_note),
                            actionLabel = stringResource(R.string.goodpost_ad_new),
                            onAction = { viewModel.openAdForm(null) }
                        )
                    }
                }
            }

            if (state.adminAdsLoading && state.adminAds.isEmpty()) {
                CenteredProgress()
            }
        }
    }

    pendingDelete?.let { ad ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_ad_delete_title),
            message = stringResource(R.string.goodpost_ad_delete_note),
            confirmLabel = stringResource(R.string.goodpost_delete),
            onConfirm = {
                pendingDelete = null
                viewModel.deleteAd(ad.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** One card in the manager's list. */
@Composable
private fun AdAdminRow(
    ad: GoodPostAd,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val placements = buildList {
        if (ad.showInChannels) add(stringResource(R.string.goodpost_ad_channel_placement))
        if (ad.showInExplore) add(stringResource(R.string.goodpost_ad_explore_placement))
    }.joinToString(" · ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Wa.Pressed),
            contentAlignment = Alignment.Center
        ) {
            if (ad.isImage && ad.imageUrl != null) {
                AdRemoteImage(url = ad.imageUrl)
            } else {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = Wa.TextDim,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ad.text?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.goodpost_ad_no_text),
                color = Wa.Text,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (placements.isBlank()) {
                    stringResource(R.string.goodpost_ad_explore_placement)
                } else {
                    placements
                },
                color = Wa.TextDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = stringResource(
                if (ad.enabled) R.string.goodpost_ad_status_on
                else R.string.goodpost_ad_status_off
            ),
            color = if (ad.enabled) Wa.Accent else Wa.TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        WaIconAction(
            icon = Icons.Filled.Delete,
            description = stringResource(R.string.goodpost_delete),
            tint = Wa.Danger,
            onClick = onDelete
        )
    }
}

// ── The editor (§15, §16) ───────────────────────────────────────────────

/**
 * Decode the just-picked picture for the editor's preview.
 *
 * Keyed on the uri so a second pick replaces the first, and nulled when the pick
 * is dropped. The decode is the shared downsampling loader, run off the main
 * thread — a twelve-megapixel photograph is tens of milliseconds and a form must
 * not stutter while one runs.
 */
@Composable
private fun rememberAdLocalPreview(uri: String?): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = if (uri == null) null else GoodPostImages.loadLocal(context, uri, AD_PREVIEW_PX)
    }

    return bitmap
}

/** How wide a picked picture is decoded for the editor's preview. */
private const val AD_PREVIEW_PX = 720

/**
 * Create or edit one card (§15, §16).
 *
 * A full screen over the manager list, like the channel form: it is a step, not a
 * destination, and it does not enter the back stack. Every field is here — the
 * picture, the words, the target, both placements, the on/off switch, the
 * priority and the schedule — because each one is part of what a card IS, and a
 * card whose schedule could only be set elsewhere would be half a screen.
 */
@Composable
internal fun AdFormScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    val creating = state.adFormId == null
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val attachment = readGoodPostAttachment(context, uri)
        if (attachment == null) viewModel.reportUnsupportedMedia() else viewModel.onAdImagePicked(attachment)
    }

    val picked = state.adFormImage
    val uploading = picked?.state is GoodPostUploadState.Uploading
    val failed = (picked?.state as? GoodPostUploadState.Failed)?.code
    val preview = rememberAdLocalPreview(picked?.uri)

    WaBackdrop {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            WaTopBar(
                title = stringResource(
                    if (creating) R.string.goodpost_ad_new else R.string.goodpost_ad_edit
                ),
                navigation = {
                    WaIconAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        description = stringResource(R.string.goodpost_back),
                        onClick = viewModel::cancelAdForm
                    )
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = stringResource(R.string.goodpost_ad_content_type),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                WaFilterRow {
                    WaFilterPill(
                        label = stringResource(R.string.goodpost_ad_type_text),
                        selected = state.adFormContentType == "text",
                        onClick = { viewModel.onAdContentTypeChange("text") }
                    )
                    WaFilterPill(
                        label = stringResource(R.string.goodpost_ad_type_image),
                        selected = state.adFormContentType == "image",
                        onClick = { viewModel.onAdContentTypeChange("image") }
                    )
                }

                Spacer(Modifier.height(18.dp))

                // The preview: the card as a reader will meet it.
                AdFormPreview(
                    state = state,
                    localImage = preview,
                    uploading = uploading,
                    onPick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemove = viewModel::removeAdImage
                )

                if (failed != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = goodPostErrorText(failed),
                        color = Wa.Danger,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(18.dp))

                if (state.adFormContentType == "text") {
                    WaField(
                        value = state.adFormText,
                        onValueChange = viewModel::onAdTextChange,
                        label = stringResource(R.string.goodpost_ad_text_label),
                        placeholder = stringResource(R.string.goodpost_ad_text_hint),
                        enabled = !state.adminBusy,
                        singleLine = false,
                        minHeight = 90.dp
                    )
                    Spacer(Modifier.height(16.dp))
                }

                WaField(
                    value = state.adFormTargetUrl,
                    onValueChange = viewModel::onAdTargetUrlChange,
                    label = stringResource(R.string.goodpost_ad_target_label),
                    placeholder = stringResource(R.string.goodpost_ad_target_hint),
                    enabled = !state.adminBusy,
                    keyboardType = KeyboardType.Uri
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.goodpost_ad_target_note),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.goodpost_ad_placement),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(4.dp))

                AdToggleRow(
                    label = stringResource(R.string.goodpost_ad_channel_placement),
                    checked = state.adFormShowInChannels,
                    enabled = !state.adminBusy,
                    onToggle = viewModel::toggleAdShowInChannels
                )
                AdToggleRow(
                    label = stringResource(R.string.goodpost_ad_explore_placement),
                    checked = state.adFormShowInExplore,
                    enabled = !state.adminBusy,
                    onToggle = viewModel::toggleAdShowInExplore
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.goodpost_ad_settings),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(4.dp))

                AdToggleRow(
                    label = stringResource(R.string.goodpost_ad_enabled),
                    checked = state.adFormEnabled,
                    enabled = !state.adminBusy,
                    onToggle = viewModel::toggleAdEnabled
                )

                Spacer(Modifier.height(12.dp))

                WaField(
                    value = state.adFormPriority,
                    onValueChange = viewModel::onAdPriorityChange,
                    label = stringResource(R.string.goodpost_ad_priority),
                    placeholder = stringResource(R.string.goodpost_ad_priority_hint),
                    enabled = !state.adminBusy,
                    keyboardType = KeyboardType.Number
                )

                Spacer(Modifier.height(14.dp))

                WaField(
                    value = state.adFormStartsAt,
                    onValueChange = viewModel::onAdStartsAtChange,
                    label = stringResource(R.string.goodpost_ad_starts),
                    placeholder = stringResource(R.string.goodpost_ad_date_hint),
                    enabled = !state.adminBusy
                )

                Spacer(Modifier.height(14.dp))

                WaField(
                    value = state.adFormExpiresAt,
                    onValueChange = viewModel::onAdExpiresAtChange,
                    label = stringResource(R.string.goodpost_ad_expires),
                    placeholder = stringResource(R.string.goodpost_ad_date_hint),
                    enabled = !state.adminBusy
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.goodpost_ad_schedule_note),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(26.dp))

                WaPrimaryButton(
                    text = stringResource(R.string.goodpost_save),
                    enabled = !state.adminBusy,
                    busy = state.adminBusy,
                    onClick = viewModel::submitAdForm
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * The editor's preview, and the way to change the picture.
 *
 * Drawn from the SAME [AdCard] a reader is shown for an image card, so what the
 * manager is looking at is the card and not a description of it. A just-picked
 * picture is drawn from the local file rather than waiting for the upload, which
 * is what makes choosing one feel answered.
 */
@Composable
private fun AdFormPreview(
    state: GoodPostUiState,
    localImage: Bitmap?,
    uploading: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    val isImage = state.adFormContentType == "image"
    val existingUrl = if (state.adFormImage != null) null else state.adFormExistingImageUrl

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Wa.Bar),
        contentAlignment = Alignment.Center
    ) {
        when {
            localImage != null -> Image(
                bitmap = localImage.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            isImage && existingUrl != null ->
                AdRemoteImage(url = existingUrl, contentScale = ContentScale.Crop)

            else -> Text(
                text = state.adFormText.ifBlank { stringResource(R.string.goodpost_ad_preview) },
                color = Wa.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 22.dp)
            )
        }

        Text(
            text = stringResource(R.string.goodpost_ad_sponsored),
            color = Wa.TextDim,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Wa.Canvas)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        if (uploading) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
                color = Wa.Accent
            )
        }
    }

    if (isImage) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            WaTextAction(
                text = stringResource(
                    if (state.adFormImage != null || state.adFormExistingImageUrl != null) {
                        R.string.goodpost_ad_image_change
                    } else {
                        R.string.goodpost_ad_image_add
                    }
                ),
                enabled = !state.adminBusy && state.composerMediaAvailable && !uploading,
                onClick = onPick
            )

            if (state.adFormImage != null) {
                Spacer(Modifier.width(16.dp))
                WaTextAction(
                    text = stringResource(R.string.goodpost_ad_image_remove),
                    enabled = !state.adminBusy,
                    onClick = onRemove,
                    destructive = true
                )
            }
        }
    }
}

/** A labelled switch, for the placements and the on/off state (§15). */
@Composable
private fun AdToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (enabled) Wa.Text else Wa.TextDim,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Wa.OnAccent,
                checkedTrackColor = Wa.Accent,
                checkedBorderColor = Wa.Accent,
                uncheckedThumbColor = Wa.TextDim,
                uncheckedTrackColor = Wa.Bar,
                uncheckedBorderColor = Wa.Divider
            )
        )
    }
}
