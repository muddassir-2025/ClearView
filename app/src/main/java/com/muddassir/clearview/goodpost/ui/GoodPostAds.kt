package com.muddassir.clearview.goodpost.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.AD_DEFAULT_TEXT_COLOR
import com.muddassir.clearview.goodpost.data.AD_TEXT_COLORS
import com.muddassir.clearview.goodpost.data.AdDuration
import com.muddassir.clearview.goodpost.data.GoodPostAd
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.data.GoodPostUploadState
import com.muddassir.clearview.goodpost.data.formatAdDate
import com.muddassir.clearview.goodpost.data.isoFromLocalStart
import com.muddassir.clearview.goodpost.data.localStartToPickerMillis
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
/**
 * A card's ink, parsed from its `#RRGGBB`.
 *
 * Falls back to the reader's ordinary text colour for anything the painter
 * cannot read, so a hand-edited row or an older payload draws a legible card
 * rather than an invisible one.
 */
private fun adTextColor(value: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(value ?: AD_DEFAULT_TEXT_COLOR)) }
        .getOrDefault(Wa.Text)

/**
 * The crop rectangle an image card draws with.
 *
 * Expressed as a BIAS rather than a computed rect: `BiasAlignment` takes -1..1
 * where 0 is centred, and the card's stored focus is 0..1, so the focus point is
 * mapped across. Doing it this way means the same framing applies at every card
 * size — the carousel, the editor's preview and the manager's thumbnail are three
 * different rectangles and all three should keep the part of the picture that was
 * chosen.
 */
private fun adBias(focus: Float): Float = (focus.coerceIn(0f, 1f) * 2f) - 1f

/** The alignment an image card is drawn with, from its stored focus. */
private fun adAlignment(focusX: Float, focusY: Float): Alignment =
    BiasAlignment(adBias(focusX), adBias(focusY))

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
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center
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
            alignment = alignment,
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
            AdRemoteImage(
                url = ad.imageUrl,
                contentScale = if (ad.imageFit == "contain") ContentScale.Fit else ContentScale.Crop,
                alignment = adAlignment(ad.imageFocusX, ad.imageFocusY)
            )
        } else {
            Text(
                text = ad.text.orEmpty(),
                color = adTextColor(ad.textColor),
                fontSize = 16.sp,
                // Monospace, like the default contact card it was seeded from: a
                // card is a poster, and a fixed advance is what makes a hand-laid
                // out one line up.
                fontFamily = FontFamily.Monospace,
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

    // The two calendars are opened from the schedule block below. Held here, at
    // the screen, because a dialog is a sibling of the form rather than a child
    // of the row that opens it — a dialog composed inside a scrolling column is
    // anchored to its row, not to the window.
    var showStartPicker by remember { mutableStateOf(false) }
    var showExpiryPicker by remember { mutableStateOf(false) }

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
                AdSegmented(
                    options = listOf(
                        "text" to stringResource(R.string.goodpost_ad_type_text),
                        "image" to stringResource(R.string.goodpost_ad_type_image)
                    ),
                    selected = state.adFormContentType,
                    enabled = !state.adminBusy,
                    onSelect = viewModel::onAdContentTypeChange
                )

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
                    AdSectionLabel(stringResource(R.string.goodpost_ad_text_color))
                    AdColorRow(
                        selected = state.adFormTextColor,
                        enabled = !state.adminBusy,
                        onSelect = viewModel::onAdTextColorChange
                    )
                    Spacer(Modifier.height(16.dp))
                } else {
                    AdSectionLabel(stringResource(R.string.goodpost_ad_image_fit))
                    AdSegmented(
                        options = listOf(
                            "cover" to stringResource(R.string.goodpost_ad_fit_cover),
                            "contain" to stringResource(R.string.goodpost_ad_fit_contain)
                        ),
                        selected = state.adFormImageFit,
                        enabled = !state.adminBusy,
                        onSelect = viewModel::onAdImageFitChange
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.goodpost_ad_fit_note),
                        color = Wa.TextDim,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    // The crop step: the card's own frame, with the picture in
                    // it, dragged to choose what a reader sees. It only exists
                    // while there is something to frame, because an empty box
                    // that can be dragged is a control that appears to do
                    // nothing.
                    if (preview != null || state.adFormExistingImageUrl != null) {
                        Spacer(Modifier.height(14.dp))
                        AdSectionLabel(stringResource(R.string.goodpost_ad_crop))
                        AdCropFrame(
                            localImage = preview,
                            remoteUrl = if (preview == null) state.adFormExistingImageUrl else null,
                            focusX = state.adFormFocusX,
                            focusY = state.adFormFocusY,
                            fit = state.adFormImageFit,
                            enabled = !state.adminBusy,
                            onFocus = viewModel::onAdFocusChange
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.goodpost_ad_crop_note),
                            color = Wa.TextDim,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
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

                AdSchedule(
                    state = state,
                    enabled = !state.adminBusy,
                    onDuration = viewModel::onAdDurationChange,
                    onPickStart = { showStartPicker = true },
                    onPickExpiry = { showExpiryPicker = true }
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

    // The calendars themselves, opened by the schedule block above. A start date
    // and an end date rather than one range: a card's window is "from then until
    // then", and each half is optional — a card with no start begins now, and one
    // with no end never expires.
    if (showStartPicker) {
        val startState = rememberDatePickerState(
            initialSelectedDateMillis = localStartToPickerMillis(state.adFormStartsAt)
                ?: localStartToPickerMillis(isoFromLocalStart(System.currentTimeMillis()))
        )
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onAdStartDatePicked(startState.selectedDateMillis)
                    showStartPicker = false
                }) { Text(stringResource(R.string.goodpost_ad_date_ok)) }
            },
            dismissButton = {
                // Clears the date rather than leaving it: this is also the way to
                // take a scheduled start back to "begins as soon as I save".
                TextButton(onClick = {
                    viewModel.onAdStartDatePicked(null)
                    showStartPicker = false
                }) { Text(stringResource(R.string.goodpost_ad_date_clear)) }
            }
        ) {
            DatePicker(state = startState)
        }
    }

    if (showExpiryPicker) {
        val expiryState = rememberDatePickerState(
            initialSelectedDateMillis = localStartToPickerMillis(state.adFormExpiresAt)
        )
        DatePickerDialog(
            onDismissRequest = { showExpiryPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onAdExpiryDatePicked(expiryState.selectedDateMillis)
                    showExpiryPicker = false
                }) { Text(stringResource(R.string.goodpost_ad_date_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.onAdExpiryDatePicked(null)
                    showExpiryPicker = false
                }) { Text(stringResource(R.string.goodpost_ad_date_clear)) }
            }
        ) {
            DatePicker(state = expiryState)
        }
    }
}

/**
 * How long the card runs (§11), and what that resolves to.
 *
 * A row of lengths — a day, a week, a month, no end — plus a way into the
 * calendar for the case none of them covers. It replaces two text fields that
 * asked an administrator to type an ISO timestamp, which is a format a person
 * should never have to know, let alone get right on a phone keyboard.
 *
 * The resolved dates are printed underneath, because a length is a promise about
 * a window and the two timestamps are what the server actually stores. Seeing
 * "3 Oct 2026 → 10 Oct 2026" is how the choice stops being abstract.
 */
@Composable
private fun AdSchedule(
    state: GoodPostUiState,
    enabled: Boolean,
    onDuration: (AdDuration) -> Unit,
    onPickStart: () -> Unit,
    onPickExpiry: () -> Unit
) {
    Text(
        text = stringResource(R.string.goodpost_ad_schedule),
        color = Wa.TextDim,
        fontSize = 13.sp
    )
    Spacer(Modifier.height(8.dp))

    WaFilterRow {
        WaFilterPill(
            label = stringResource(R.string.goodpost_ad_duration_none),
            selected = state.adFormDuration == AdDuration.NoExpiry,
            onClick = { if (enabled) onDuration(AdDuration.NoExpiry) }
        )
        WaFilterPill(
            label = stringResource(R.string.goodpost_ad_duration_day),
            selected = state.adFormDuration == AdDuration.OneDay,
            onClick = { if (enabled) onDuration(AdDuration.OneDay) }
        )
        WaFilterPill(
            label = stringResource(R.string.goodpost_ad_duration_week),
            selected = state.adFormDuration == AdDuration.OneWeek,
            onClick = { if (enabled) onDuration(AdDuration.OneWeek) }
        )
        WaFilterPill(
            label = stringResource(R.string.goodpost_ad_duration_month),
            selected = state.adFormDuration == AdDuration.OneMonth,
            onClick = { if (enabled) onDuration(AdDuration.OneMonth) }
        )
        WaFilterPill(
            label = stringResource(R.string.goodpost_ad_duration_custom),
            selected = state.adFormDuration == AdDuration.Custom,
            onClick = { if (enabled) onDuration(AdDuration.Custom) }
        )
    }

    Spacer(Modifier.height(4.dp))

    AdDateRow(
        label = stringResource(R.string.goodpost_ad_starts),
        value = formatAdDate(state.adFormStartsAt)
            ?: stringResource(R.string.goodpost_ad_starts_now),
        enabled = enabled,
        onClick = onPickStart
    )
    AdDateRow(
        label = stringResource(R.string.goodpost_ad_expires),
        value = formatAdDate(state.adFormExpiresAt)
            ?: stringResource(R.string.goodpost_ad_never_expires),
        enabled = enabled,
        onClick = onPickExpiry
    )

    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.goodpost_ad_schedule_note),
        color = Wa.TextDim,
        fontSize = 12.sp,
        lineHeight = 17.sp
    )
}

/** One of the schedule's two dates: a label, its resolved value, and the calendar. */
@Composable
private fun AdDateRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (enabled) Wa.Text else Wa.TextDim,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = Wa.Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
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
        val fit = if (state.adFormImageFit == "contain") ContentScale.Fit else ContentScale.Crop
        val alignment = adAlignment(state.adFormFocusX, state.adFormFocusY)

        when {
            localImage != null -> Image(
                bitmap = localImage.asImageBitmap(),
                contentDescription = null,
                contentScale = fit,
                alignment = alignment,
                modifier = Modifier.fillMaxSize()
            )

            isImage && existingUrl != null ->
                AdRemoteImage(url = existingUrl, contentScale = fit, alignment = alignment)

            else -> Text(
                text = state.adFormText.ifBlank { stringResource(R.string.goodpost_ad_preview) },
                color = adTextColor(state.adFormTextColor),
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
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

/** A small heading over one block of the editor. */
@Composable
private fun AdSectionLabel(text: String) {
    Text(text = text, color = Wa.TextDim, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
}

/**
 * Two or more mutually exclusive options, as one joined control.
 *
 * A segmented row rather than the filter pills the rest of the app uses: the
 * pills read as "show me this subset", and the choice here is a property of the
 * card being written — text or image, crop or fit — where exactly one value is
 * always chosen. The shape says that; a strip of pills does not.
 */
@Composable
private fun AdSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Wa.Bar)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Wa.Accent else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Wa.OnAccent else Wa.Text,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * The colours a text card can be written in.
 *
 * Swatches rather than names: the choice is what the card will look like, and a
 * row of the actual inks answers that in a glance. The selected one is ringed
 * rather than ticked, so the row does not need a legend.
 */
@Composable
private fun AdColorRow(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AD_TEXT_COLORS.forEach { hex ->
            val color = adTextColor(hex)
            val isSelected = hex.equals(selected, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) Wa.Accent else Wa.Divider,
                        shape = CircleShape
                    )
                    .clickable(enabled = enabled) { onSelect(hex) }
            )
        }
    }
}

/**
 * The crop step (§12).
 *
 * The card's own frame with the picture inside it, draggable: the point of the
 * picture the administrator leaves the drag on is the point a reader sees, and it
 * is stored on the CARD — so a photograph is uploaded once and can be reframed
 * later without touching the file.
 *
 * The drag is measured against the frame's own size, which is what makes the
 * stored value a fraction rather than a pixel count: the same card is drawn at
 * three different sizes in this app, and a focus that only worked at one of them
 * would be a crop that moved when the screen did.
 */
@Composable
private fun AdCropFrame(
    localImage: Bitmap?,
    remoteUrl: String?,
    focusX: Float,
    focusY: Float,
    fit: String,
    enabled: Boolean,
    onFocus: (Float, Float) -> Unit
) {
    var boxWidth by remember { mutableStateOf(1f) }
    var boxHeight by remember { mutableStateOf(1f) }
    val alignment = adAlignment(focusX, focusY)
    val contentScale = if (fit == "contain") ContentScale.Fit else ContentScale.Crop

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Wa.Pressed)
            .onSizeChanged { size ->
                boxWidth = size.width.toFloat().coerceAtLeast(1f)
                boxHeight = size.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(enabled, fit) {
                if (!enabled || fit == "contain") return@pointerInput
                detectDragGestures { change, drag ->
                    change.consume()
                    // A drag RIGHT moves the picture right, which means the
                    // window is looking further LEFT: the focus moves against
                    // the gesture, which is what makes the drag feel like moving
                    // the image rather than the frame.
                    onFocus(
                        (focusX - drag.x / boxWidth).coerceIn(0f, 1f),
                        (focusY - drag.y / boxHeight).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        when {
            localImage != null -> Image(
                bitmap = localImage.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.fillMaxSize()
            )

            remoteUrl != null ->
                AdRemoteImage(url = remoteUrl, contentScale = contentScale, alignment = alignment)
        }

        // The chosen point, drawn on the picture. Without it a drag has no
        // visible state at all: the image moves under the gesture and there is
        // nothing to say where the centre ended up.
        if (fit != "contain") {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x = focusX * size.width
                val y = focusY * size.height
                drawCircle(color = Wa.Accent, radius = 5.dp.toPx(), center = Offset(x, y))
                drawCircle(
                    color = Wa.OnAccent,
                    radius = 5.dp.toPx(),
                    center = Offset(x, y),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
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
