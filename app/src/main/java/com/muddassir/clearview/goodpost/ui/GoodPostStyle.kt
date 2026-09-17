package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.muddassir.clearview.BuildConfig
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostError
import com.muddassir.clearview.goodpost.data.GoodPostImages
import com.muddassir.clearview.goodpost.goodPostErrorFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Good Post's presentation layer: the palette and the primitives every screen is
 * built from.
 *
 * The reference is WhatsApp's Updates → Channels screen, and what is borrowed is
 * the LAYOUT and the HIERARCHY: an almost-black list on a slightly darker
 * canvas, 49dp circular avatars, a bold one-line name over a gray one-line
 * preview, a timestamp on the right, hairline separators inset past the avatar,
 * compact rows rather than cards, and one green accent used sparingly. No
 * WhatsApp asset, logo or font is reproduced — the icons are Material's and the
 * palette is stated below.
 *
 * The palette is FLAT and DARK by design (§2, §29). Good Post has no light mode
 * and no theme picker: a channel list is read at a glance in a hallway, and the
 * dark surface is the one that makes the white names and the green timestamps
 * the loudest things on it. Nothing here is translucent, blurred or shadowed —
 * a channel row is separated by a hairline and a surface, which is what keeps a
 * list of fifty channels scannable.
 */
internal object Wa {

    /** Behind the list and every pushed screen. */
    val Canvas = Color(0xFF0B141A)

    /** Top bar surface - seamless with dark canvas */
    val TopBar = Color(0xFF0B141A)

    /** Behind a list — channels, Explore, the administrator list. */
    val List = Color(0xFF0B141A)

    /** The bars: search field, pill button, dialog surfaces. */
    val Bar = Color(0xFF202C33)

    /** A pressed row, and any control that is present but inert. */
    val Pressed = Color(0xFF182229)

    /** A post's container on the feed - WhatsApp channel update olive green (§9, Screenshot 4). */
    val Bubble = Color(0xFF334B19)

    /** Text inside the post bubble */
    val BubbleText = Color(0xFFE9EDEF)

    /** Subtle sage-green timestamp inside the olive bubble */
    val BubbleTime = Color(0xFFA4B898)

    /** Small forward button background next to post bubbles */
    val ForwardBg = Color(0xFF182229)

    /** Background for centered date separators */
    val DatePillBg = Color(0xFF182229)

    /** The accent: WhatsApp green, unread stamps, FAB, primary button. */
    val Accent = Color(0xFF00A884)

    /** Ink that goes ON the accent. */
    val OnAccent = Color(0xFF0B141A)

    val Text = Color(0xFFE9EDEF)
    val TextDim = Color(0xFF8696A0)
    val Divider = Color(0xFF1F2C34)

    /**
     * The fill of a selected row, and of the bar that acts on it (§5).
     *
     * A lifted surface rather than a tint, because selection is a state the list
     * is in and not a highlight on some text: WhatsApp darkens the row and swaps
     * the bar, and doing it with a green wash instead would collide with the
     * accent that means "this is a control".
     */
    val Selected = Color(0xFF2A3942)

    /** A recent timestamp, tinted WhatsApp vibrant green (§4). */
    val StampRecent = Color(0xFF25D366)

    val Danger = Color(0xFFF15C6D)

    /**
     * The Google button's own palette, and the sign-in screen's one departure
     * from the dark theme.
     *
     * Not an inconsistency: the button is Google's brand mark in button form, and
     * its whole value is that somebody recognises it before they read it. A
     * dark-styled lookalike would have to invent a Google wordmark in the
     * product's colours, which is worse on both counts — unrecognisable and
     * pretending to be something it is not.
     *
     * White surface, hairline border and near-black ink are the values Google's
     * own guidance puts on a light background; the four-colour G carries the
     * brand, so nothing else here has to.
     */
    val GoogleSurface = Color(0xFFFFFFFF)
    val GoogleBorder = Color(0xFFDADCE0)
    val GoogleLabel = Color(0xFF1F1F1F)

    /**
     * Avatar fills, picked by name so a channel keeps its colour.
     *
     * A stable colour per name is what makes a list scannable: the eye learns
     * "the teal one" and finds it again without reading the label.
     */
    private val AvatarFills = listOf(
        Color(0xFF6A7175),
        Color(0xFF00A884),
        Color(0xFF53BDEB),
        Color(0xFFECB22E),
        Color(0xFFE542A3),
        Color(0xFF9C6ADE),
        Color(0xFFF15C6D),
        Color(0xFF7FDBCA)
    )

    fun avatarFill(seed: String): Color =
        if (seed.isEmpty()) AvatarFills.first()
        else AvatarFills[abs(seed.hashCode()) % AvatarFills.size]
}

// ── Surfaces ────────────────────────────────────────────────────────────

/** The background every Good Post screen is painted on. A flat colour (§29). */
@Composable
internal fun WaBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(Wa.List)) { content() }
}

// ── Bars ────────────────────────────────────────────────────────────────

/**
 * The top bar: a title, an optional back arrow, and the bar's actions.
 *
 * The title is large and bold (§3) rather than Material's 19sp medium, because
 * "Good Post" here is the name of the screen rather than the name of a document.
 */
@Composable
internal fun WaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    /**
     * Opens the channel's information page when the title is tapped.
     */
    onTitleClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    actions: @Composable () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth().background(Wa.TopBar)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigation?.invoke()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                    .padding(start = if (navigation == null) 16.dp else 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        color = Wa.Text,
                        fontSize = if (subtitle == null) 24.sp else 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = Wa.TextDim,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            actions()
        }

        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Wa.Divider))
        }
    }
}

/** A 48dp icon target, tinted for the bar. */
@Composable
internal fun WaIconAction(
    icon: ImageVector,
    description: String?,
    onClick: () -> Unit,
    tint: Color = Wa.Text,
    enabled: Boolean = true
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else Wa.TextDim)
    }
}

// ── Avatar ──────────────────────────────────────────────────────────────

/**
 * A channel's circular profile image, or its initial (§2, §4).
 *
 * One component for both states rather than two, because every screen that
 * draws a channel draws this: the list row, Explore, the feed header, the
 * channel information page and the admin list. A second "avatar with image"
 * variant is how one of those ends up showing letters while the rest show faces.
 *
 * The image is loaded through the same downsampling loader a post's media uses,
 * at the avatar's own pixel width — so a 49dp row decodes a 49dp bitmap rather
 * than a 4000px photo, and scrolling a long channel list reuses the bitmaps
 * instead of re-fetching them (§26).
 *
 * The initial is not a placeholder that flashes: it is drawn until (and unless)
 * a bitmap exists, and it stays for a null URL, an expired signature or a
 * deployment with no bucket — all of which are normal states rather than errors
 * (§22).
 */
@Composable
internal fun WaAvatar(
    name: String,
    size: Dp = 49.dp,
    modifier: Modifier = Modifier,
    /** A signed URL for the channel's image, or null to draw the initial. */
    url: String? = null
) {
    val density = LocalDensity.current
    val widthPx = with(density) { size.roundToPx() }

    // Keyed on the URL, so a refresh that re-signs the same image does not
    // re-render the avatar from scratch — and a changed image does.
    var image by remember(url) { mutableStateOf(GoodPostImages.peek(url)) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        image = GoodPostImages.load(url, widthPx)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Wa.avatarFill(name)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.trim().take(1).uppercase(Locale.getDefault()),
                color = Color.White,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Lists ───────────────────────────────────────────────────────────────

/**
 * One channel row: circular avatar, bold name, gray preview, right-aligned time.
 *
 * The shape is the familiar one and each part earns its place. The preview is
 * ONE ellipsised line because rows of different heights stop being scannable —
 * what a reader needs from a preview is whether the row is worth opening, not
 * everything it says. The stamp is right-aligned so the eye can scan a single
 * column of them, which is how a list answers "what is new" without any of the
 * numbers this product deliberately does not have (§1, §5).
 */
@Composable
internal fun WaChannelRow(
    title: String,
    preview: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * True when [preview] is a post BODY rather than prose about the channel.
     *
     * A preview of a post is the post's text, markers and all, so it is parsed
     * the same way the feed parses it — otherwise a channel whose newest update
     * opens with `*bold*` shows its asterisks in the list and loses them once
     * opened, which reads as the list and the post disagreeing. A description is
     * not a post: it has no formatting, and parsing it would turn a channel
     * called `*star*` into one called star.
     */
    previewIsPostBody: Boolean = false,
    timestamp: String? = null,
    /** True for today's posts, which tint the stamp green (§4). */
    timestampRecent: Boolean = false,
    avatar: @Composable () -> Unit,
    previewIcon: ImageVector? = null,
    /**
     * Long press (§5): selection, or opening the contextual actions.
     *
     * A row's actions are reached by holding it rather than by a row of icons
     * beside every line, which is the difference between a list you read and a
     * toolbar you navigate.
     */
    onLongClick: (() -> Unit)? = null,
    /** True while this row is part of a selection (§5). */
    selected: Boolean = false,
    trailing: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) Wa.Selected else Wa.Canvas)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = null
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            avatar()

            Spacer(Modifier.width(15.dp))

            // §5: a row's OWN gesture is a hold, so the platform must not
            // answer the same press with a selection menu over the row's text.
            WaNoTextSelection {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = Wa.Text,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (timestamp != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = timestamp,
                            color = if (timestampRecent) Wa.StampRecent else Wa.TextDim,
                            fontSize = 12.sp,
                            fontWeight = if (timestampRecent) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(3.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (previewIcon != null) {
                            Icon(
                                previewIcon,
                                contentDescription = null,
                                tint = Wa.TextDim,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = if (previewIsPostBody) {
                                parseGoodPostText(preview)
                            } else {
                                AnnotatedString(preview)
                            },
                            color = Wa.TextDim,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    trailing()
                }
            }
            }
        }

        // Inset past the avatar, the way a channel list separates rows: a
        // full-width rule would cut each avatar off from its own text.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 80.dp)
                .height(0.8.dp)
                .background(Wa.Divider)
        )
    }
}

/**
 * The bar a selection replaces the title bar with (§5).
 *
 * This is the whole of the "action mode" idea: the same row of icons in the same
 * place, showing what is possible for what is selected. Nothing floats over the
 * content, nothing is added to every row, and there is one obvious way out.
 *
 * When nothing is selected the caller renders its ordinary bar instead — which
 * is why this is a separate composable rather than a mode inside [WaTopBar].
 */
@Composable
internal fun WaSelectionBar(
    count: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth().background(Wa.Selected)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WaIconAction(
                icon = Icons.Filled.Close,
                description = stringResource(R.string.goodpost_clear_selection),
                onClick = onClose
            )

            Text(
                text = pluralStringResource(
                    R.plurals.goodpost_selected_count,
                    count,
                    count
                ),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                color = Wa.Text,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            actions()
        }
    }
}

/**
 * A confirmation for the one action that cannot be taken back (§17).
 *
 * Deleting a channel removes its posts, its media and the login that ran it, and
 * there is nothing on the other end to restore from. Every other control in the
 * app is recoverable — a post's removal is soft and reversible — so this is the
 * only place a confirmation is warranted, and it says exactly what will go.
 */
@Composable
internal fun WaTypedConfirmDialog(
    title: String,
    message: String,
    /** What has to be typed, exactly, before the destructive action unlocks. */
    expected: String,
    label: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Held here rather than in the ViewModel: it is a keystroke, not a fact
    // about the account or the channel, and nothing outside this dialog can act
    // on it. It also cannot survive its own dialog, which is the property that
    // matters — the confirmation is not "remembered" for the next deletion.
    var typed by remember { mutableStateOf("") }
    val matched = typed.trim().equals(expected.trim(), ignoreCase = true)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Wa.Bar, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(text = title, color = Wa.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(text = message, color = Wa.TextDim, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))

            // The same field every other form in the tab uses, so the dialog
            // does not introduce a second visual language for one input.
            WaField(
                value = typed,
                onValueChange = { typed = it },
                label = label,
                placeholder = expected,
                singleLine = true
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WaTextAction(
                    text = stringResource(R.string.goodpost_cancel),
                    onClick = onDismiss
                )
                Spacer(Modifier.weight(1f))
                WaTextAction(
                    text = confirmLabel,
                    onClick = onConfirm,
                    destructive = true,
                    // Disabled rather than hidden: the rule has to be visible
                    // BEFORE it is satisfied, or the button simply looks broken.
                    enabled = matched
                )
            }
        }
    }
}

/**
 * Text a reader can hold without the platform offering to select it (§5).
 *
 * Long-pressing a post card is the app's OWN gesture — it puts the post into
 * selection, which is what the Edit / Copy / Delete bar above it is for. On a
 * current Compose the text inside that card is selectable by default, so the
 * same press also raised Android's own Cut / Copy / Paste / Read aloud bubble
 * over the bubble being held: two answers to one gesture, one of them in a
 * light menu that belongs to no app in particular.
 *
 * [DisableSelection] is what turns that off, and it is deliberately scoped
 * rather than global: the composer's field NEEDS selection, because the
 * formatting controls act on exactly what is highlighted (§7). Read-only
 * surfaces get this wrapper; input fields do not.
 */
@Composable
internal fun WaNoTextSelection(content: @Composable () -> Unit) {
    DisableSelection(content = content)
}

@Composable
internal fun WaConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Wa.Bar, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(text = title, color = Wa.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(text = message, color = Wa.TextDim, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WaTextAction(
                    text = stringResource(R.string.goodpost_cancel),
                    onClick = onDismiss
                )
                Spacer(Modifier.weight(1f))
                WaTextAction(
                    text = confirmLabel,
                    onClick = onConfirm,
                    destructive = true
                )
            }
        }
    }
}

/** The small green pill used by "Explore" and the channel page's action (§3). */
@Composable
internal fun WaPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (filled) Wa.Accent else Wa.Bar)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (filled) Wa.OnAccent else Wa.Text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * A rounded search field, matching the one at the top of a channel list.
 *
 * The field is the target and the trailing icon is the action, because a search
 * bar whose only way in is a small icon is a search bar people miss.
 */
@Composable
internal fun WaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSearch: () -> Unit = {},
    onClear: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Wa.Bar)
            .height(44.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = Wa.TextDim,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))

        // `BasicTextField`, not `TextField` (§2). Material's `TextField` enforces
        // a 56dp minimum height of its own, and this pill is 44dp — so the value
        // and the placeholder were laid out taller than the box that clipped
        // them, which is why the search hint came out cut off. A basic field has
        // no minimum of its own and the pill's height is the only one in play.
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Wa.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(Wa.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = Wa.TextDim,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = if (value.isEmpty()) onSearch else onClear),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (value.isEmpty()) Icons.Filled.Search else Icons.Filled.Close,
                contentDescription = null,
                tint = Wa.TextDim,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** A full-width green action: "Sign in", "Create channel", "Create post". */
@Composable
internal fun WaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled && !busy) Wa.Accent else Wa.Pressed)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Wa.OnAccent
            )
        } else {
            Text(
                text = text,
                color = if (enabled) Wa.OnAccent else Wa.TextDim,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * "Continue with Google", as a card of its own.
 *
 * Deliberately NOT [WaPrimaryButton] with different colours: that button is the
 * product's accent on the product's canvas, and neither of its colours survives a
 * white surface. This is the one control on the sign-in screen that should look
 * like it came from Google, so it is drawn as one — light surface, hairline
 * border, the four-colour G and near-black ink.
 *
 * It sits above the email-and-password form rather than beside it, because the
 * two are not equals on this screen: Google is how a creator signs up, and the
 * panel below it is for the accounts a deployment provisions.
 */
@Composable
internal fun WaGoogleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled && !busy) Wa.GoogleSurface else Wa.Pressed)
            .border(1.dp, Wa.GoogleBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled && !busy, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Wa.GoogleLabel
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_google_g),
                // The label beside it already says "Google", so reading out the
                // mark again would make the button announce itself twice.
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                color = Wa.GoogleLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * A card on the sign-in screen that opens something below it ("Other ways").
 *
 * The same shape and height as [WaGoogleButton], on purpose: the two sit one
 * above the other and are the same KIND of thing — a way in, chosen by tapping a
 * card. What differs is only the surface, which is what keeps the Google card
 * the obvious first move and this one the second.
 *
 * It carries a state rather than performing an action, which is why the label
 * does not change when it opens: the panel appearing underneath IS the answer,
 * and swapping "Other ways" for "Hide" would make the same card read as two
 * different controls between one tap and the next.
 */
@Composable
internal fun WaExpandableCard(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    expanded: Boolean,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Wa.Bar else Wa.Pressed)
            .border(1.dp, Wa.Divider, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            // The label beside it says the same thing more precisely.
            contentDescription = null,
            tint = if (enabled) Wa.Accent else Wa.TextDim,
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(14.dp))

        Text(
            text = text,
            color = if (enabled) Wa.Text else Wa.TextDim,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            // Announced, because it is the only thing that says the card changes
            // anything: the row opens and closes a form rather than going
            // somewhere.
            contentDescription = stringResource(
                if (expanded) R.string.goodpost_collapse else R.string.goodpost_expand
            ),
            tint = Wa.TextDim,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** A borderless text action: green, or in the warning colour when destructive. */
@Composable
internal fun WaTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * Rendered in the colour that says this removes something.
     *
     * Used by the channel form's Remove, which discards the image currently
     * stored — the one control in that form whose effect cannot be taken back
     * by editing a field again afterwards. Disabled still wins, so a busy form
     * reads as unavailable rather than as dangerous.
     */
    destructive: Boolean = false
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = when {
            !enabled -> Wa.TextDim
            destructive -> Wa.Danger
            else -> Wa.Accent
        },
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium
    )
}

/**
 * Follow / Following, on a channel row (§4).
 *
 * Two states of one control rather than a button that only appears when not
 * followed: a reader scanning a list needs to see what they are already in, and
 * a control that vanishes leaves them checking their own home screen to find
 * out. Unfollow is therefore the same tap on the same target — the state is
 * legible before it is changed, which is what keeps an accidental unfollow from
 * being a thing that happens while scrolling.
 *
 * Outlined when not following and flat when following, so the heavier treatment
 * is on the action being offered rather than on the state already held. While a
 * request is in flight the label is withheld rather than flickering between the
 * two, which is the one thing a toggle must not do.
 */
@Composable
internal fun WaFollowAction(
    following: Boolean,
    busy: Boolean,
    onClick: () -> Unit
) {
    val label = when {
        busy -> null
        following -> stringResource(R.string.goodpost_following)
        else -> stringResource(R.string.goodpost_follow)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (following) Wa.Pressed else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (following) Wa.Divider else Wa.Accent,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        if (label == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = Wa.Accent
            )
        } else {
            Text(
                text = label,
                color = if (following) Wa.TextDim else Wa.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * A labelled field, for the administrator forms.
 *
 * `TextField` rather than `OutlinedTextField`: on a dark surface an outline fights
 * the fill, and every field here sits on the same background anyway.
 */
@Composable
internal fun WaField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minHeight: Dp = 0.dp,
    keyboardType: KeyboardType = KeyboardType.Text,
    /**
     * Treat the value as a password (§20).
     *
     * Set it for every password field. `KeyboardType.Password` only asks the IME
     * to disable suggestions and learning — it does not hide anything, so without
     * this a typed password is rendered in full, and is readable to anything that
     * captures the screen or reads the accessibility tree.
     *
     * The masking is Android's own [PasswordVisualTransformation], the same one
     * the platform's password fields use, and revealing it takes a deliberate tap
     * on the eye. That cursor is the whole of §20: a password is hidden by
     * default, and showing it is a decision its owner makes on purpose, rather
     * than a state the field drifts into.
     */
    masked: Boolean = false,
    /**
     * What this field holds, for the platform's Autofill service (§10).
     *
     * Declared rather than guessed, because the service guesses well and wrongly
     * here: a field it cannot classify is offered whatever saved credential it
     * has for the app. That is right for the sign-in screen and wrong for the
     * form that MINTS a password for somebody else — a saved login silently
     * replacing a typed one is a password that reaches the server at the wrong
     * length, with nothing on screen to show it happened.
     */
    autofillContentType: ContentType? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, color = Wa.Accent, fontSize = 13.sp)
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Wa.Bar)
                .border(1.dp, Wa.Divider, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp)
        ) {
            // Resets whenever the field leaves composition — i.e. whenever the
            // screen is reopened. A revealed password must not survive the form
            // it was typed into, or walking away from an unlocked phone would
            // hand the next person the text.
            var revealed by remember { mutableStateOf(false) }

            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                placeholder = { Text(text = placeholder, color = Wa.TextDim, fontSize = 16.sp) },
                textStyle = TextStyle(fontSize = 16.sp),
                colors = WaFieldColors(),
                visualTransformation = if (masked && !revealed) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (!masked) {
                    null
                } else {
                    {
                        Icon(
                            imageVector = if (revealed) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (revealed) R.string.goodpost_hide_password
                                else R.string.goodpost_show_password
                            ),
                            tint = Wa.TextDim,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(enabled = enabled) { revealed = !revealed }
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .then(
                        if (autofillContentType == null) {
                            Modifier
                        } else {
                            Modifier.semantics { contentType = autofillContentType }
                        }
                    )
            )
        }
    }
}

/** One row of a [WaOverflowMenu]. */
internal data class WaMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false
)

/** The overflow menu: three dots in the bar, a list of actions in a dark card. */
@Composable
internal fun WaOverflowMenu(
    items: List<WaMenuItem>,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Wa.Text)
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = item.label,
                            color = if (item.destructive) Wa.Danger else Wa.Text,
                            fontSize = 15.sp
                        )
                    },
                    onClick = {
                        open = false
                        item.onClick()
                    }
                )
            }
        }
    }
}

/** A horizontally scrolling strip of filter chips, for category and sort. */
@Composable
internal fun WaFilterRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

/** A selectable pill, used for categories and sorts. */
@Composable
internal fun WaFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Wa.Accent else Wa.Bar)
            .border(
                width = 1.dp,
                color = if (selected) Wa.Accent else Wa.Divider,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Wa.OnAccent else Wa.Text,
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

// ── Posts ───────────────────────────────────────────────────────────────

/**
 * One post's container (§9).
 *
 * A rounded dark panel, not a chat bubble with a tail: a channel broadcasts, so
 * every post is on the same side. The radius is generous and the surface is one
 * step lighter than the canvas, which is what makes a run of posts read as a
 * single column rather than as a list of separate rows.
 */
@Composable
internal fun WaPostContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Wa.Bubble)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        content = content
    )
}

/** The timestamp line under a post's content, right-aligned. */
@Composable
internal fun WaTimeLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Wa.BubbleTime
) {
    Text(text = text, modifier = modifier, color = color, fontSize = 11.sp)
}

/** A centred dark pill for a date separator (§10, Screenshot 4). */
@Composable
internal fun WaDatePill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Wa.DatePillBg)
                .padding(horizontal = 12.dp, vertical = 4.5.dp),
            color = Wa.TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** A large rounded media placeholder: a photo still loading, or a video. */
@Composable
internal fun WaMediaPlaceholder(
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    content: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Wa.Pressed),
        contentAlignment = Alignment.Center
    ) {
        content()
        if (icon != null || label != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = Wa.TextDim, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                }
                if (label != null) {
                    Text(text = label, color = Wa.TextDim, fontSize = 13.sp)
                }
            }
        }
    }
}

/** A small rounded chip describing media, e.g. `Photo · 1.2 MB`. */
@Composable
internal fun WaChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Wa.Pressed)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = Wa.TextDim,
        fontSize = 12.sp
    )
}

// ── States ──────────────────────────────────────────────────────────────

/**
 * The offline notice (§27).
 *
 * A bar, not a dialog: saved content is still readable, and interrupting someone
 * to tell them they are offline tells them something they can already see.
 */
@Composable
internal fun WaStaleBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Wa.Bar)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.goodpost_stale_note),
            color = Wa.TextDim,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        WaTextAction(text = stringResource(R.string.goodpost_retry), onClick = onRetry)
    }
}

/** Centred title, note and optional action — an empty list, or a failure. */
@Composable
internal fun WaEmptyState(
    title: String,
    note: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, color = Wa.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = note, color = Wa.TextDim, fontSize = 14.sp)
        }
        if (actionLabel != null) {
            Spacer(Modifier.height(4.dp))
            WaTextAction(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * The words for a failure, formatted where a message has to name a number.
 *
 * The sibling of [goodPostErrorMessage], and the reason it exists: a message
 * that takes an argument would be worded correctly on the screen that
 * remembered to pass one and read `%1$d` on the one that did not. Every screen
 * goes through here instead of reaching for a resource id itself.
 */
@Composable
internal fun goodPostErrorText(code: String): String {
    val error = goodPostErrorFor(code)
    return when (error) {
        // The one message that has to say HOW long, because "too short" without
        // a number is what sent somebody to try four passwords in a row (§10).
        GoodPostError.WeakPassword -> stringResource(
            R.string.goodpost_error_weak_password,
            BuildConfig.ADMIN_MIN_PASSWORD_LENGTH
        )

        else -> stringResource(goodPostErrorMessage(error))
    }
}

/** An error line, coloured for this surface. */
@Composable
internal fun WaErrorNotice(code: String, modifier: Modifier = Modifier) {
    Text(
        text = goodPostErrorText(code),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.Canvas)
            .padding(10.dp),
        color = Wa.Danger,
        fontSize = 13.sp
    )
}

/**
 * The single translation from a [GoodPostError] to words a person can read.
 *
 * One place, so two screens cannot word the same failure differently — and so
 * that the screens which never show an error code stay that way.
 */
internal fun goodPostErrorMessage(error: GoodPostError): Int = when (error) {
    GoodPostError.Offline -> R.string.goodpost_error_offline
    GoodPostError.NotConfigured -> R.string.goodpost_error_not_configured
    GoodPostError.NotFound -> R.string.goodpost_error_not_found
    GoodPostError.InvalidCredentials -> R.string.goodpost_error_invalid_credentials
    GoodPostError.RateLimited -> R.string.goodpost_error_rate_limited
    GoodPostError.Forbidden -> R.string.goodpost_error_forbidden
    GoodPostError.ServerFault -> R.string.goodpost_error_server
    GoodPostError.InvalidInput -> R.string.goodpost_error_invalid_input
    GoodPostError.MediaUnavailable -> R.string.goodpost_error_media_unavailable
    GoodPostError.AdminLocked -> R.string.goodpost_error_admin_locked
    GoodPostError.MediaTooLarge -> R.string.goodpost_error_media_too_large
    GoodPostError.UnsupportedMedia -> R.string.goodpost_error_media_type
    GoodPostError.AttachmentUploading -> R.string.goodpost_error_attachment_uploading
    GoodPostError.AttachmentFailed -> R.string.goodpost_error_attachment_failed
    GoodPostError.AdminUnavailable -> R.string.goodpost_error_admin_unavailable
    GoodPostError.SessionExpired -> R.string.goodpost_error_session_expired
    GoodPostError.GoogleSignInFailed -> R.string.goodpost_error_google_signin
    GoodPostError.EmailAlreadyUsed -> R.string.goodpost_error_email_taken
    GoodPostError.ChannelExists -> R.string.goodpost_error_channel_exists
    GoodPostError.NameTaken -> R.string.goodpost_error_name_taken
    GoodPostError.SignInUnavailable -> R.string.goodpost_error_sign_in_unavailable
    // Has an argument, so it is worded by [goodPostErrorText] rather than
    // rendered straight from this id: on its own it would print `%1$d`.
    GoodPostError.WeakPassword -> R.string.goodpost_error_weak_password
    GoodPostError.CannotDisableYourself -> R.string.goodpost_error_cannot_disable_self
    GoodPostError.Unknown -> R.string.goodpost_error_unknown
}

/** The colour set shared by every text field on this surface. */
@Composable
private fun WaFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = Wa.Accent,
    focusedTextColor = Wa.Text,
    unfocusedTextColor = Wa.Text,
    disabledTextColor = Wa.TextDim,
    focusedPlaceholderColor = Wa.TextDim,
    unfocusedPlaceholderColor = Wa.TextDim,
    disabledPlaceholderColor = Wa.TextDim
)

/** The shape a post's media uses, so an image and a video agree. */
internal val WaMediaShape: Shape = RoundedCornerShape(10.dp)

// ── Time (§4, §10) ──────────────────────────────────────────────────────

private val clockFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

private val listStampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yy", Locale.getDefault())

/** The clock time inside a post: `9:41 am`. */
internal fun waClock(epochMs: Long?): String {
    if (epochMs == null) return ""
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFormat).lowercase(Locale.getDefault())
}

/**
 * The stamp in a channel row: the time today, "Yesterday", a weekday within the
 * last week, and a short date beyond it — the progression a channel list uses,
 * and narrow enough to sit in a column without wrapping (§4).
 */
internal fun waListStamp(epochMs: Long?): String {
    if (epochMs == null) return ""
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = LocalDate.now(zone)
    val day = at.toLocalDate()

    return when {
        day == today -> at.format(clockFormat).lowercase(Locale.getDefault())
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) ->
            at.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))

        else -> at.format(listStampFormat)
    }
}

/**
 * Whether a stamp should be tinted as recent.
 *
 * Today's posts are the ones a reader is looking for (§4), so they get the
 * accent; anything older is gray, which keeps a long list quiet.
 */
internal fun waStampIsRecent(epochMs: Long?): Boolean {
    if (epochMs == null) return false
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate() == LocalDate.now(zone)
}

/** The separator between days in a feed: "Today", "Yesterday", or a date (§10). */
internal fun waDayLabel(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val day = at.toLocalDate()
    val today = LocalDate.now(zone)

    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) ->
            at.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))

        else -> at.format(DateTimeFormatter.ofPattern("dd/MM/yy", Locale.getDefault()))
    }
}

/** Whether two instants fall on the same calendar day, for a date separator. */
internal fun waSameDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

/**
 * `dd/MM/yy`, for the channel page's "Created on" (§12).
 *
 * An unreadable instant formats to an empty string rather than to a wrong date:
 * "01/01/70" on a channel is worse than saying nothing.
 */
internal fun waShortDate(epochMs: Long?): String {
    if (epochMs == null) return ""
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(listStampFormat)
}

/** `Photo · 1.2 MB` — a one-line description of an asset (§9). */
internal fun waDescribeBytes(kind: String, bytes: Long): String = when {
    bytes >= 1_048_576 -> "$kind · ${String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)}"
    bytes >= 1024 -> "$kind · ${String.format(Locale.US, "%.0f KB", bytes / 1024.0)}"
    else -> kind
}
