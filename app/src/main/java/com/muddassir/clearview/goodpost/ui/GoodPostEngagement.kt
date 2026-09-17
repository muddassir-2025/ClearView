package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GOODPOST_REACTIONS
import com.muddassir.clearview.goodpost.data.GoodPostEngagement
import com.muddassir.clearview.goodpost.data.GoodPostPoll
import com.muddassir.clearview.goodpost.data.GoodPostPollOption
import com.muddassir.clearview.goodpost.data.goodPostReactionEmoji

/**
 * Reactions (§13) and polls (§14), as a reader meets them.
 *
 * Both render AGGREGATES only. The payloads carry no reactor and no voter —
 * that is an API guarantee, not a rendering rule (§38) — so nothing here can
 * leak an identity even by mistake, and the strongest statement this file makes
 * is which counts it draws.
 */
@Composable
internal fun ReactionBar(
    engagement: GoodPostEngagement,
    busy: Boolean,
    /**
     * False where the server would refuse a reaction. §13's gate is "must be
     * following", and an owner cannot follow their own channel — so their
     * controls would be controls that always fail (§32).
     */
    interactive: Boolean,
    onReact: (String) -> Unit
) {
    // The picker is opened by the react button and closed by choosing, which is
    // the whole interaction: a reaction is one tap, and a permanently visible
    // row of six emoji next to every post would be six taps of noise.
    var pickerOpen by remember { mutableStateOf(false) }

    val existing = engagement.reactions.filter { it.count > 0 }

    // Nothing to show and nothing to press: an owner reading a post nobody has
    // reacted to gets no empty control.
    if (existing.isEmpty() && !interactive) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            existing.forEach { reaction ->
                val mine = engagement.viewerReaction == reaction.reaction
                ReactionPill(
                    emoji = goodPostReactionEmoji(reaction.reaction),
                    count = reaction.count,
                    mine = mine,
                    enabled = interactive && !busy,
                    onClick = { onReact(reaction.reaction) }
                )
            }

            // A viewer who has reacted with something nobody else chose must
            // still see their own choice — the server only lists reactions with
            // a count, and a cleared reaction row would look like the tap did
            // nothing.
            val mineAlone = engagement.viewerReaction != null &&
                existing.none { it.reaction == engagement.viewerReaction }
            if (mineAlone) {
                ReactionPill(
                    emoji = goodPostReactionEmoji(engagement.viewerReaction.orEmpty()),
                    count = 0,
                    mine = true,
                    enabled = interactive && !busy,
                    onClick = { onReact(engagement.viewerReaction.orEmpty()) }
                )
            }
        }

        if (interactive) {
            Spacer(Modifier.width(6.dp))
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Wa.Accent
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Wa.Bar)
                        .clickable { pickerOpen = !pickerOpen },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AddReaction,
                        contentDescription = stringResource(R.string.goodpost_reactions_choose),
                        tint = if (pickerOpen) Wa.Accent else Wa.TextDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (pickerOpen && !busy) {
        Row(
            modifier = Modifier
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Wa.Bar)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            GOODPOST_REACTIONS.forEach { reaction ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            pickerOpen = false
                            onReact(reaction)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = goodPostReactionEmoji(reaction), fontSize = 20.sp)
                }
            }
        }
    }
}

/** One reaction: the emoji, and its count when there is one. */
@Composable
private fun ReactionPill(
    emoji: String,
    count: Int,
    mine: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (mine) Wa.Accent.copy(alpha = 0.22f) else Wa.Bar)
            .border(
                width = 1.dp,
                color = if (mine) Wa.Accent else Wa.Divider,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, fontSize = 14.sp)
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = count.toString(),
                color = if (mine) Wa.Accent else Wa.TextDim,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * A poll (§14).
 *
 * Before answering, the reader gets controls; afterwards, results — the same
 * switch WhatsApp makes, and the reason a poll is not simply a list of buttons.
 * The bars are drawn against every selection rather than against the winning
 * option, because in a multiple-choice poll two options can both be chosen by
 * everyone and their bars should both be full.
 */
@Composable
internal fun PollCard(
    poll: GoodPostPoll,
    busy: Boolean,
    interactive: Boolean,
    onVote: (List<String>) -> Unit
) {
    // Selection is local until it is submitted: a multiple-choice poll is
    // answered by ticking several boxes and then voting, and firing a request
    // per tick would both be wrong (it would replace the previous answer) and
    // cost a round trip for each tap.
    var selected by remember(poll.id) { mutableStateOf(emptySet<String>()) }

    val showResults = poll.hasVoted || poll.isClosed || !interactive
    val canVote = interactive && !poll.isClosed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.List)
            .padding(10.dp)
    ) {
        Text(
            text = poll.question,
            color = Wa.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(8.dp))

        poll.options.forEach { option ->
            PollOptionRow(
                option = option,
                poll = poll,
                selected = selected.contains(option.id),
                interactive = canVote,
                showResults = showResults,
                enabled = canVote && !busy,
                onToggle = {
                    if (poll.allowMultiple) {
                        selected = if (selected.contains(option.id)) {
                            selected - option.id
                        } else {
                            selected + option.id
                        }
                    } else {
                        // A single-choice poll is answered by the tap itself:
                        // there is nothing to confirm, and a one-item
                        // selection plus a Vote button is a step for nothing.
                        onVote(listOf(option.id))
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.goodpost_poll_votes, poll.totalVotes.toString()),
                color = Wa.TextDim,
                fontSize = 12.sp
            )

            if (poll.isClosed) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.goodpost_poll_closed),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.weight(1f))

            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Wa.Accent
                )

                // A multiple-choice poll needs the button: the boxes are a
                // draft, and this is what sends it.
                canVote && poll.allowMultiple -> WaTextAction(
                    text = stringResource(
                        if (poll.hasVoted) R.string.goodpost_poll_change
                        else R.string.goodpost_poll_vote
                    ),
                    enabled = selected.isNotEmpty(),
                    onClick = { onVote(selected.toList()) }
                )

                poll.hasVoted -> Text(
                    text = stringResource(R.string.goodpost_poll_you_voted),
                    color = Wa.Accent,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * One option: its result bar, a control before voting, and the count after.
 *
 * The bar sits BEHIND the label rather than beside it, which is what makes a
 * long option readable — a bar in a separate column forces the eye to jump
 * between two places to answer one question.
 */
@Composable
private fun PollOptionRow(
    option: GoodPostPollOption,
    poll: GoodPostPoll,
    selected: Boolean,
    interactive: Boolean,
    showResults: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val mine = poll.viewerVotes.contains(option.id)
    val fraction = if (poll.totalVotes <= 0) 0f
    else (option.votes.toFloat() / poll.totalVotes.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.Bar)
            .clickable(enabled = enabled, onClick = onToggle)
    ) {
        // The bar is only meaningful once there is a result to show. Drawing it
        // beforehand would be drawing a zero as though it were information.
        if (showResults && fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .heightIn(min = 40.dp)
                    .background(Wa.Accent.copy(alpha = 0.25f))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (interactive && poll.allowMultiple) {
                // A box rather than a Material checkbox: the whole row is the
                // control, and a Material checkbox beside a tappable row reads
                // as two targets when there is one.
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected || mine) Wa.Accent else Color.Transparent)
                        .border(
                            width = 1.5.dp,
                            color = if (selected || mine) Wa.Accent else Wa.TextDim,
                            shape = RoundedCornerShape(4.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected || mine) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Wa.Canvas,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
            }

            Text(
                text = option.label,
                color = Wa.Text,
                fontSize = 14.sp,
                fontWeight = if (mine) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )

            if (showResults) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = option.votes.toString(),
                    color = if (mine) Wa.Accent else Wa.TextDim,
                    fontSize = 13.sp,
                    fontWeight = if (mine) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}
