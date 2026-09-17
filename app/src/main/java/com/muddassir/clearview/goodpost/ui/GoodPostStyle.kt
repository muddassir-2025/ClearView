package com.muddassir.clearview.goodpost.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostThemeStore
import com.muddassir.clearview.goodpost.goodPostErrorFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Good Post's presentation layer, styled as a messaging app.
 *
 * WhatsApp Channels is the reference, so this file holds the palette and the
 * handful of primitives every screen is built from: a bar, an avatar, a list
 * row, a bubble, a pill, an input bar. Screens compose these rather than
 * reaching for Material defaults, because the point of the redesign is that the
 * whole tab looks like ONE product — a stray `Button` in the Material theme is
 * exactly what made the previous screens read as a form rather than a chat.
 *
 * What is borrowed is the LAYOUT and the BEHAVIOUR: where things sit, how rows
 * are spaced, how a bubble wraps its own timestamp, that a poll shows bars once
 * you have voted. No WhatsApp asset, logo, icon or font is reproduced here —
 * the icons are Material's and the palette is stated below.
 *
 * Colours are fixed rather than read from the app theme. Good Post is a dark
 * surface on purpose: the rest of ClearView is a reader people open in low
 * light, and a bright chat tab beside it would look like a different app.
 * Everything is named after its role, so a light theme later is a change to
 * this object and nothing else.
 */
internal object Wa {

    /**
     * The user's choices, read live.
     *
     * Every colour below that the user can change goes through this, and
     * reading `GoodPostThemeStore.current` inside a composable is what makes a
     * new accent take effect without restarting anything. Nothing else about
     * the palette moved: fixed neutrals stay fixed, because a chat has to stay
     * legible whatever accent is chosen.
     */
    private val theme get() = GoodPostThemeStore.current

    /** Behind a chat canvas — a thread, a channel's posts. */
    val Canvas = Color(0xFF0B141A)

    /** Behind a list — channels, Discover, the inbox. */
    val List = Color(0xFF111B21)

    /** The bars: top bar, search field, an incoming bubble. */
    val Bar = Color(0xFF202C33)

    /** A pressed row, and any control that is present but inert. */
    val Pressed = Color(0xFF2A3942)

    val BubbleIn = Color(0xFF202C33)

    /**
     * An outgoing bubble.
     *
     * Derived from the accent rather than fixed, so the messages a user writes
     * wear their own colour — and darkened towards the canvas first, because an
     * accent bright enough to be a button is too bright to read 15sp of text
     * against.
     */
    val BubbleOut: Color get() = darkened(Color(theme.accent.pressed), 0.18f)

    /** The accent: buttons, the send button, the unread badge, a FAB. */
    val Accent: Color get() = Color(theme.accent.fill)
    val AccentPressed: Color get() = Color(theme.accent.pressed)

    val Text = Color(0xFFE9EDEF)
    val TextDim = Color(0xFF8696A0)
    val Divider = Color(0xFF222D34)

    /** A channel's own name inside its bubble — WhatsApp tints it. */
    val NameTint = Color(0xFF7FDBCA)

    /** The read-receipt blue. */
    val Tick = Color(0xFF53BDEB)

    val Danger = Color(0xFFF15C6D)

    // ── Glass ───────────────────────────────────────────────────────────
    //
    // The surfaces above are flat, and everything below makes a few of them
    // translucent so the backdrop shows through. Glassmorphism here is a LAYER,
    // not a repaint: the bars, sheets, dialogs and cards float, while a message
    // bubble stays opaque — text you read has to sit on something solid, and a
    // blurred bubble is a readability bug wearing a trend.

    /** A panel that floats above the canvas: bars, sheets, cards, dialogs. */
    val Glass: Color get() = Color(theme.glass.fill)

    /** A lighter glass, for a panel on top of another panel. */
    val GlassHigh: Color get() = Color(theme.glass.high)

    /** How far the backdrop is blurred behind a panel, in dp. */
    val GlassBlur: Dp get() = theme.glass.blur.dp

    /** The 1px edge that makes a translucent panel read as a pane of glass. */
    val GlassBorder = Color(0x33FFFFFF)

    /**
     * The dreamy canvas: two stops, painted top to bottom.
     *
     * A gradient rather than a colour because a flat dark surface reads as
     * "terminal". These are the same two hues as [Canvas] and [List] with a
     * little more blue in them, so every existing screen stays legible and only
     * the mood changes.
     */
    val DreamTop: Color get() = Color(theme.backdrop.top)
    val DreamBottom: Color get() = Color(theme.backdrop.bottom)

    /** The two soft glows that sit behind the gradient. */
    val GlowOne = Color(0x3327C4A6)
    val GlowTwo = Color(0x2E4C6FE8)

    /**
     * Avatar fills, picked by name so a channel keeps its colour.
     *
     * A stable colour per name is what makes a list scannable: the eye learns
     * "the teal one" and finds it again without reading the label. Deriving it
     * from the id instead would be equally stable and equally useless to look
     * at, which is why the name wins.
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

    /**
     * A colour darkened towards black.
     *
     * Used to turn a button-bright accent into a bubble's background: the two
     * have to be different tints of the same hue, and scaling the channels is
     * the only honest way to keep them related when the accent is a choice.
     */
    private fun darkened(color: Color, amount: Float): Color = Color(
        red = color.red * (1f - amount),
        green = color.green * (1f - amount),
        blue = color.blue * (1f - amount),
        alpha = color.alpha
    )
}

// ── The dreamy layer ────────────────────────────────────────────────────

/**
 * The background every Good Post screen is painted on.
 *
 * Two stops, top to bottom, plus two wide radial glows near the top corners.
 * The glows are what the glass panels above actually show through — a
 * translucent bar over a flat colour looks like a grey bar, and the same bar
 * over a gradient looks like glass, which is the whole effect.
 *
 * Drawn with plain [Box]es rather than a shader or an image: a gradient and two
 * radial gradients are cheap, they cost nothing to scroll over, and they scale
 * to any screen. An asset would need density buckets and would still not be
 * smoother.
 */
@Composable
internal fun WaDreamyBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(Wa.DreamBottom)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Wa.DreamTop, Wa.DreamBottom))
                )
        )

        // The teal glow, off the top-left corner.
        Box(
            modifier = Modifier
                .size(360.dp)
                .offset(x = (-120).dp, y = (-140).dp)
                .background(Brush.radialGradient(listOf(Wa.GlowOne, Color.Transparent)))
        )

        // The blue one, top-right, so the two never read as a single light
        // source and the canvas has a direction.
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopEnd)
                .offset(x = 120.dp, y = (-100).dp)
                .background(Brush.radialGradient(listOf(Wa.GlowTwo, Color.Transparent)))
        )

        content()
    }
}

/**
 * A translucent panel: the bar, the composer tray, a card, a dialog.
 *
 * `blurBehind` applies a real blur to whatever is drawn BENEATH it, which is
 * what makes a scrolling list look like it is passing under glass. It is
 * opt-in because it costs a render pass, and it is skipped below API 31 where
 * `Modifier.blur` does nothing — the translucent fill and the hairline border
 * already carry the look on their own, so the fallback is a slightly flatter
 * panel rather than a missing one.
 */
@Composable
internal fun WaGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    color: Color = Wa.Glass,
    border: Color = Wa.GlassBorder,
    blurBehind: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val glassy = if (blurBehind > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        modifier.clip(shape).blur(blurBehind)
    } else {
        modifier
    }

    Box(
        modifier = glassy
            .background(color, shape)
            .border(width = 0.5.dp, color = border, shape = shape)
    ) {
        content()
    }
}

// ── Bars ────────────────────────────────────────────────────────────────

/**
 * The top bar: one line of title, an optional second line, up to two actions.
 *
 * Hand-built rather than Material's `TopAppBar` because this bar is 56dp with a
 * 19sp medium title, and those two numbers are most of what makes a screen read
 * as familiar.
 */
@Composable
internal fun WaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    WaGlass(
        modifier = modifier.fillMaxWidth(),
        color = Wa.Glass,
        // A real backdrop blur on the bar is what makes a list slide under it
        // like a sheet of glass rather than behind an opaque strip. Its
        // strength follows the glass setting, so choosing "Airy" thickens the
        // blur as well as thinning the fill.
        blurBehind = Wa.GlassBlur
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        navigation?.invoke()

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (navigation == null) 16.dp else 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = Wa.Text,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
 * A round avatar holding the first letter of [name].
 *
 * A channel has no icon column in its payload (§6), so a letter is the only
 * honest thing to draw. It is coloured from the name, which is what keeps it
 * recognisable between a list row and the channel header.
 */
@Composable
internal fun WaAvatar(
    name: String,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Wa.avatarFill(name)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().take(1).uppercase(Locale.getDefault()),
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Lists ───────────────────────────────────────────────────────────────

/**
 * One row of a chat list: avatar, two lines of text, a right-hand column of
 * time over status.
 *
 * The shape is the familiar one, and each part of it earns its place: the time
 * is right-aligned so the eye can scan a single column of them, and the preview
 * is one ellipsised line because rows of different heights stop being scannable
 * — what a reader needs from a preview is whether the row is worth opening, not
 * everything it says.
 *
 * [timestamp] is what makes a channel list read as a conversation list: the
 * time sits in the preview line rather than in the trailing corner, which is
 * where a messaging app puts it and where it answers "when" at the same glance
 * as "what".
 */
@Composable
internal fun WaListRow(
    title: String,
    preview: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    timestamp: String? = null,
    avatar: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            avatar()

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Wa.Text,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))

                if (timestamp == null) {
                    Text(
                        text = preview,
                        color = Wa.TextDim,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // One Text with two spans rather than a Row: a Row would
                    // ellipsise each part independently, so a long preview
                    // would push the time out of the line instead of the two
                    // sharing the space.
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            append(timestamp)
                            append("  •  ")
                            append(preview)
                        },
                        color = Wa.TextDim,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                trailing()
            }
        }

        // Inset past the avatar, the way a WhatsApp list separates rows: a
        // full-width rule would cut each avatar off from its own text.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 80.dp)
                .height(1.dp)
                .background(Wa.Divider)
        )
    }
}

/** The green unread pill. Caps at `99+` rather than growing without limit. */
@Composable
internal fun WaUnreadBadge(count: Int) {
    if (count <= 0) return
    Box(
        modifier = Modifier
            .heightIn(min = 20.dp)
            .clip(CircleShape)
            .background(Wa.Accent)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Wa.Canvas,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** One tab: its label, and how much unread mail is waiting under it. */
internal data class WaTab(val label: String, val badge: Int = 0)

/**
 * A tab strip with a green underline, the shape used by a messaging app's
 * Updates screen.
 *
 * Scrollable rather than evenly weighted: Good Post has four sections with
 * names of three different lengths, and four equal columns would ellipsise the
 * longer ones on a narrow phone.
 */
@Composable
internal fun WaTabRow(
    tabs: List<WaTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Wa.Bar)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            Column(
                modifier = Modifier.clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tab.label,
                        color = if (selected) Wa.Accent else Wa.TextDim,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    if (tab.badge > 0) {
                        Spacer(Modifier.width(6.dp))
                        WaUnreadBadge(tab.badge)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected) Wa.Accent else Color.Transparent)
                )
            }
        }
    }
}

/**
 * The two-option segmented control from a messaging app's Updates screen.
 *
 * Evenly weighted, unlike [WaTabRow]: with two options there is no ellipsis
 * risk, and equal halves are what makes the control read as a switch rather
 * than a pair of tabs.
 */
@Composable
internal fun WaSegmentedControl(
    segments: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    WaGlass(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = Wa.Glass,
        border = Wa.GlassBorder
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        segments.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (selected) Wa.Accent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) Wa.Canvas else Wa.TextDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
        }
    }
}

/**
 * The "Find channels" row that heads a channel list.
 *
 * A list row rather than a search field, which is what a messaging app uses:
 * tapping it opens a screen with room for categories and sorts, where a field
 * inline would have to render the results under itself and lose the list it was
 * filtering.
 */
@Composable
internal fun WaFindChannelsRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Wa.Bar),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = Wa.Accent,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = stringResource(R.string.goodpost_find_channels),
                color = Wa.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 72.dp)
                .height(1.dp)
                .background(Wa.Divider)
        )
    }
}

/** A section label above a list, e.g. "Channels you follow". */
@Composable
internal fun WaSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
        color = Wa.Accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    )
}

// ── Controls ────────────────────────────────────────────────────────────

/**
 * A pill search field, matching the one at the top of a WhatsApp list.
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

        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(text = placeholder, color = Wa.TextDim, fontSize = 16.sp) },
            textStyle = TextStyle(fontSize = 16.sp),
            colors = WaFieldColors(),
            modifier = Modifier.weight(1f)
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

/**
 * The bottom input bar: a rounded field and a round send button.
 *
 * [leading] is the attachment slot, which the composer and a thread both use.
 */
@Composable
internal fun WaInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sending: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Wa.Bar)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        leading?.invoke()

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(Wa.List)
                .padding(horizontal = 14.dp, vertical = 2.dp)
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                maxLines = 4,
                placeholder = { Text(text = placeholder, color = Wa.TextDim, fontSize = 16.sp) },
                textStyle = TextStyle(fontSize = 16.sp),
                colors = WaFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (trailing != null) {
            trailing()
        } else {
            Spacer(Modifier.width(6.dp))
            val canSend = enabled && value.isNotBlank() && !sending
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) Wa.Accent else Wa.Pressed)
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Wa.Text
                    )
                } else {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = null,
                        tint = if (canSend) Wa.Canvas else Wa.TextDim,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/** A full-width green action, the shape of every "Continue" in the app. */
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
                color = Wa.Canvas
            )
        } else {
            Text(
                text = text,
                color = if (enabled) Wa.Canvas else Wa.TextDim,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** A borderless green text action. */
@Composable
internal fun WaTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = if (enabled) Wa.Accent else Wa.TextDim,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium
    )
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
            color = if (selected) Wa.Canvas else Wa.Text,
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

/** One row of a [WaOverflowMenu]. */
internal data class WaMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false
)

/**
 * The overflow menu: three dots in the bar, a list of actions in a dark card.
 *
 * This is where a channel's management actions live, which is where WhatsApp
 * puts them too — the difference being that a reader who is not an owner is
 * offered only the actions the server would accept (§32).
 */
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

/**
 * The green FAB: a rounded square, which is the shape a messaging app uses for
 * "start something new".
 *
 * Positioned by its parent rather than by itself, so a screen that also has a
 * bottom bar can lift it above the bar instead of on top of it.
 */
@Composable
internal fun WaFab(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Wa.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Wa.Canvas,
            modifier = Modifier.size(26.dp)
        )
    }
}

// ── Surfaces ────────────────────────────────────────────────────────────

/**
 * A dark card, the container every dialog here uses.
 *
 * Padded and rounded rather than Material's `AlertDialog`, whose light surface
 * and centred title would be the one bright rectangle in the tab.
 */
@Composable
internal fun WaDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        // A pane of glass rather than an opaque card: the dialog sits over a
        // screen the user can still see, which is what makes it a sheet on top
        // of the app instead of a new page.
        WaGlass(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Wa.GlassHigh,
            border = Wa.GlassBorder,
            blurBehind = 20.dp
        ) {
            Column(modifier = Modifier.padding(20.dp), content = content)
        }
    }
}

/**
 * A sheet that fills the screen — a channel, a thread, the composer.
 *
 * [background] paints an optional translucent layer over the dreamy backdrop,
 * which is how a channel's canvas (darker) is told apart from a list (lighter)
 * without either of them losing the gradient behind it.
 */
@Composable
internal fun WaFullScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        WaDreamyBackdrop {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .then(
                        if (background != null) {
                            Modifier.background(background.copy(alpha = 0.55f))
                        } else {
                            Modifier
                        }
                    ),
                content = content
            )
        }
    }
}

/** One bubble, with the tail corner WhatsApp leaves flat. */
@Composable
internal fun WaBubble(
    outgoing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = if (outgoing) {
        RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp)
    } else {
        RoundedCornerShape(12.dp, 12.dp, 12.dp, 2.dp)
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(if (outgoing) Wa.BubbleOut else Wa.BubbleIn)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        content = content
    )
}

/** The timestamp line inside or under a bubble. */
@Composable
internal fun WaTimeLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Wa.TextDim,
    read: Boolean = false
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = text, color = color, fontSize = 11.sp)
        if (read) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.DoneAll,
                contentDescription = null,
                tint = Wa.Tick,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** A centred pill for a date, an unread marker, and other separators. */
@Composable
internal fun WaDatePill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Wa.Bar)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            color = Wa.TextDim,
            fontSize = 12.sp
        )
    }
}

/**
 * The offline notice (§36).
 *
 * A bar, not a dialog: saved content is still readable, and interrupting
 * someone to tell them they are offline tells them something they can already
 * see.
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
            .padding(horizontal = 32.dp, vertical = 48.dp),
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
 * An error line, coloured for this surface.
 *
 * The wording still comes from [goodPostErrorMessage], so there is exactly one
 * place that decides what a backend code means; this only says where it sits.
 */
@Composable
internal fun WaErrorNotice(code: String, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(goodPostErrorMessage(goodPostErrorFor(code))),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.Canvas)
            .padding(10.dp),
        color = Wa.Danger,
        fontSize = 13.sp
    )
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
    disabledPlaceholderColor = Wa.TextDim,
    focusedLabelColor = Wa.Accent,
    unfocusedLabelColor = Wa.TextDim
)

// ── Time ────────────────────────────────────────────────────────────────


/**
 * The clock time inside a bubble: `9:41 AM`.
 *
 * No date. A bubble already sits under a date pill when the day changes, and
 * repeating the date on every message is noise. An unreadable timestamp
 * formats to an empty string rather than to "1970" — a wrong date is worse than
 * no date at all.
 */
internal fun waClock(epochMs: Long?): String {
    if (epochMs == null) return ""
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(clockFormat)
}

/**
 * The stamp in a list row: the time today, "Yesterday", a weekday within the
 * last week, and a short date beyond it — the progression WhatsApp uses, and
 * narrow enough to sit in a column without wrapping.
 */
internal fun waListStamp(epochMs: Long?): String {
    if (epochMs == null) return ""
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = LocalDate.now(zone)
    val day = at.toLocalDate()

    return when {
        day == today -> at.format(clockFormat)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) ->
            at.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))

        else -> at.format(DateTimeFormatter.ofPattern("dd/MM/yy", Locale.getDefault()))
    }
}

/** The separator between days in a thread: "Today", "Yesterday", or a date. */
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

        else -> at.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
    }
}

/** Whether two instants fall on the same calendar day, for a date pill. */
internal fun waSameDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

private val clockFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

/** `Photo · 53 KB` — the one-line description of an asset. */
internal fun waDescribeBytes(kind: String, bytes: Long): String = when {
    bytes >= 1_048_576 -> "$kind · ${String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)}"
    bytes >= 1024 -> "$kind · ${String.format(Locale.US, "%.0f KB", bytes / 1024.0)}"
    else -> kind
}

/** The kind word shown beside an asset's size. */
internal fun waKindOf(kind: String): String = when (kind) {
    "image" -> "Photo"
    "video" -> "Video"
    "audio" -> "Audio"
    else -> "File"
}
