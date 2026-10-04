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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
import com.muddassir.clearview.goodpost.data.GoodPostBrainRotDemand
import com.muddassir.clearview.goodpost.data.GoodPostBrainRotRule
import com.muddassir.clearview.goodpost.data.GoodPostBrainRotSubmission

/**
 * The global Brain Rot repository, as an administrator reviews it.
 *
 * Two halves under one bar, because they are one job: the QUEUE is what the
 * community has asked for, and the RULES are what everyone is currently
 * protected by. A reviewer moves a suggestion from one to the other, so putting
 * them on separate screens would mean the one thing the operator is actually
 * doing — deciding whether this becomes a rule — happens between two screens.
 *
 * ## Approving is the only path from a suggestion to a rule
 *
 * There is deliberately no "add from the queue" shortcut: an approval writes the
 * rule and marks the suggestion in one transaction on the server, so a rule can
 * never exist whose suggestion still looks unreviewed. A direct add exists too,
 * but it is a separate act with its own fields — the operator typing a rule is
 * not the same decision as ratifying somebody else's.
 *
 * Nothing here talks to the network: every action goes through the ViewModel, so
 * the authorization, the renewal and the error wording stay in one place, the
 * same as every other admin surface in this tab.
 */
@Composable
internal fun BrainRotReviewScreen(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    var pendingDelete by remember { mutableStateOf<GoodPostBrainRotRule?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Wa.Canvas)) {
        WaTopBar(
            title = stringResource(R.string.goodpost_brainrot),
            navigation = {
                WaIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    description = stringResource(R.string.goodpost_back),
                    onClick = { viewModel.back() }
                )
            }
        )

        // The three surfaces as pills, matching every other filter row in the
        // tab. The dashboard comes first because it is what an operator opens
        // the screen to see: what is waiting, and what people are asking for.
        WaFilterRow {
            WaFilterPill(
                label = stringResource(R.string.goodpost_brainrot_dashboard),
                selected = state.brainRotTab == 0,
                onClick = { viewModel.selectBrainRotTab(0) }
            )
            WaFilterPill(
                label = stringResource(R.string.goodpost_brainrot_queue),
                selected = state.brainRotTab == 1,
                onClick = { viewModel.selectBrainRotTab(1) }
            )
            WaFilterPill(
                label = stringResource(R.string.goodpost_brainrot_rules),
                selected = state.brainRotTab == 2,
                onClick = { viewModel.selectBrainRotTab(2) }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (state.brainRotTab) {
                0 -> BrainRotDashboard(state = state, viewModel = viewModel)
                1 -> BrainRotQueue(state = state, viewModel = viewModel)
                else -> BrainRotRules(
                    state = state,
                    viewModel = viewModel,
                    onDelete = { pendingDelete = it }
                )
            }
        }
    }

    pendingDelete?.let { rule ->
        WaConfirmDialog(
            title = stringResource(R.string.goodpost_brainrot_delete_title),
            message = stringResource(R.string.goodpost_brainrot_delete_note),
            confirmLabel = stringResource(R.string.goodpost_delete),
            onConfirm = {
                pendingDelete = null
                viewModel.deleteBrainRotRule(rule)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ── The dashboard ───────────────────────────────────────────────────────

/**
 * What is waiting, and what people are asking for.
 *
 * The counts come first because they are the question the screen exists to
 * answer — "how much is in my queue" — and the two demand lists follow, because
 * they are the other half of a decision: which of those requests are many people
 * making. Both numbers on a demand row are shown, and they are different numbers
 * on purpose: how many devices blocked the target, and how many asked for it to
 * be blocked for everyone.
 */
@Composable
private fun BrainRotDashboard(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    val dashboard = state.brainRotDashboard

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "totals") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    WaSectionHeading(stringResource(R.string.goodpost_brainrot_dashboard))
                    WaCard {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DashboardStat(
                                label = stringResource(R.string.goodpost_brainrot_pending),
                                value = dashboard.pending,
                                modifier = Modifier.weight(1f)
                            )
                            DashboardStat(
                                label = stringResource(R.string.goodpost_brainrot_under_review),
                                value = dashboard.underReview,
                                modifier = Modifier.weight(1f)
                            )
                            DashboardStat(
                                label = stringResource(R.string.goodpost_brainrot_approved),
                                value = dashboard.approved,
                                modifier = Modifier.weight(1f)
                            )
                            DashboardStat(
                                label = stringResource(R.string.goodpost_brainrot_rejected),
                                value = dashboard.rejected,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Wa.Divider)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.goodpost_brainrot_total),
                            color = Wa.TextDim,
                            fontSize = 12.sp
                        )
                        Text(
                            text = dashboard.total.toString(),
                            color = Wa.Text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            item(key = "channels-header") {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    WaSectionHeading(stringResource(R.string.goodpost_brainrot_top_channels))
                }
            }
            if (dashboard.topChannels.isEmpty()) {
                item(key = "channels-empty") {
                    EmptyDemandNote()
                }
            }
            items(dashboard.topChannels, key = { "dc-${it.value}" }) { row ->
                DemandRow(row)
            }

            item(key = "keywords-header") {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    WaSectionHeading(stringResource(R.string.goodpost_brainrot_top_keywords))
                }
            }
            if (dashboard.topKeywords.isEmpty()) {
                item(key = "keywords-empty") {
                    EmptyDemandNote()
                }            }
            items(dashboard.topKeywords, key = { "dk-${it.value}" }) { row ->
                DemandRow(row)
            }

        }

        if (state.brainRotDashboardLoading && dashboard.total == 0) {
            CenteredProgress()
        }
    }
}

/** One headline number with its label, centred in its tile. */
@Composable
private fun DashboardStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value.toString(),
            color = Wa.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = Wa.TextDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * One target's demand, as a card row.
 *
 * "247 users blocking · 182 global requests" is the sentence an operator needs:
 * the first is how many devices independently reported it, the second is how
 * many asked for it to be blocked for everyone. They are not the same question,
 * so they are not the same number.
 */
@Composable
private fun DemandRow(row: GoodPostBrainRotDemand) {
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        WaCard {
            WaCardHeader(
                icon = if (row.isChannel) Icons.Outlined.PlayCircle else Icons.Outlined.Block,
                title = row.displayName ?: row.value,
                // The handle under a channel's name, so the identity is never
                // hidden by the display name.
                subtitle = if (row.isChannel && row.displayName != null) {
                    row.value
                } else {
                    stringResource(R.string.goodpost_brainrot_keyword)
                },
                trailing = {
                    WaStatusPill(
                        text = stringResource(
                            R.string.goodpost_brainrot_users_blocking,
                            row.usersBlocking
                        ),
                        color = Wa.Accent
                    )
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.goodpost_brainrot_global_requests,
                    row.globalRequests
                ),
                color = Wa.TextDim,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun EmptyDemandNote() {
    Text(
        text = stringResource(R.string.goodpost_brainrot_no_demand),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        color = Wa.TextDim,
        fontSize = 13.sp
    )
}

// ── The review queue ────────────────────────────────────────────────────

@Composable
private fun BrainRotQueue(state: GoodPostUiState, viewModel: GoodPostViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Which queue, as pills. The status is part of the read, so changing it
        // re-reads rather than filtering what is already on screen — a rejected
        // suggestion is not in the pending response at all.
        WaFilterRow {
            QueueStatus.entries.forEach { status ->
                WaFilterPill(
                    label = stringResource(status.label),
                    selected = state.brainRotQueueStatus == status.wire,
                    onClick = { viewModel.setBrainRotQueueStatus(status.wire) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(state.brainRotSubmissions, key = { it.id }) { submission ->
                    SubmissionRow(
                        submission = submission,
                        busy = state.brainRotBusy,
                        onApprove = {
                            viewModel.reviewBrainRotSubmission(submission.id, "approved")
                        },
                        onReject = {
                            viewModel.reviewBrainRotSubmission(submission.id, "rejected")
                        },
                        onReview = {
                            viewModel.reviewBrainRotSubmission(submission.id, "under_review")
                        }
                    )
                }

                if (state.brainRotSubmissions.isEmpty() && !state.brainRotQueueLoading) {
                    item(key = "empty") {
                        WaEmptyState(
                            title = stringResource(R.string.goodpost_brainrot_queue_empty_title),
                            note = stringResource(R.string.goodpost_brainrot_queue_empty_note)
                        )
                    }
                }
            }

            if (state.brainRotQueueLoading && state.brainRotSubmissions.isEmpty()) {
                CenteredProgress()
            }
        }
    }
}

/** Which queue a pill selects, and the words it shows. */
private enum class QueueStatus(val wire: String, val label: Int) {
    Pending("pending", R.string.goodpost_brainrot_pending),
    UnderReview("under_review", R.string.goodpost_brainrot_under_review),
    Approved("approved", R.string.goodpost_brainrot_approved),
    Rejected("rejected", R.string.goodpost_brainrot_rejected),
    All("all", R.string.goodpost_brainrot_all)
}

/** One suggestion: what it is, how many asked, and the two decisions. */
@Composable
private fun SubmissionRow(
    submission: GoodPostBrainRotSubmission,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onReview: () -> Unit
) {
    val statusColor = when (submission.status) {
        "approved" -> Wa.Accent
        "rejected" -> Wa.Danger
        "under_review" -> MaterialTheme.colorScheme.tertiary
        else -> Wa.TextDim
    }

    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        WaCard {
            WaCardHeader(
                icon = if (submission.isChannel) Icons.Outlined.PlayCircle else Icons.Outlined.Block,
                title = submission.displayName ?: submission.value,
                // The handle under a channel's name, so the identity the block
                // will use is never hidden behind a display name.
                subtitle = if (submission.isChannel && submission.displayName != null) {
                    submission.value
                } else {
                    stringResource(
                        when (submission.source) {
                            "youtube_not_interested" -> R.string.goodpost_brainrot_source_youtube
                            "app" -> R.string.goodpost_brainrot_source_app
                            else -> R.string.goodpost_brainrot_source_unknown
                        }
                    )
                },
                trailing = {
                    WaStatusPill(
                        text = stringResource(
                            when (submission.status) {
                                "approved" -> R.string.goodpost_brainrot_approved
                                "rejected" -> R.string.goodpost_brainrot_rejected
                                "under_review" -> R.string.goodpost_brainrot_under_review
                                else -> R.string.goodpost_brainrot_pending
                            }
                        ),
                        color = statusColor
                    )
                }
            )

            // The two demand numbers, on the row they decide. One person asking
            // and two hundred devices asking are very different cases, and a
            // reviewer should not have to leave the screen to tell them apart.
            if (submission.reports > 0 || submission.requesters > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.goodpost_brainrot_users_blocking,
                        submission.reports
                    ) + "  ·  " + stringResource(
                        R.string.goodpost_brainrot_global_requests,
                        submission.requesters
                    ),
                    color = Wa.Accent,
                    fontSize = 12.sp
                )
            }

            // A note the suggestor wrote, when there is one.
            submission.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(6.dp))
                Text(text = note, color = Wa.TextDim, fontSize = 13.sp)
            }

            Spacer(Modifier.height(10.dp))

            if (submission.isOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaTextAction(
                        text = stringResource(R.string.goodpost_brainrot_approve),
                        enabled = !busy,
                        onClick = onApprove
                    )
                    Spacer(Modifier.width(8.dp))
                    WaTextAction(
                        text = stringResource(R.string.goodpost_brainrot_reject),
                        enabled = !busy,
                        onClick = onReject,
                        destructive = true
                    )
                    Spacer(Modifier.width(8.dp))
                    WaTextAction(
                        text = stringResource(R.string.goodpost_brainrot_mark_review),
                        enabled = !busy && submission.status == "pending",
                        onClick = onReview
                    )
                }
            } else {
                // An already-decided row says so rather than offering the
                // buttons again: the server refuses a second review, and a
                // button that can only fail is worse than a label.
                Text(
                    text = stringResource(
                        if (submission.status == "approved") R.string.goodpost_brainrot_approved_note
                        else R.string.goodpost_brainrot_rejected_note
                    ),
                    color = Wa.TextDim,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── The rules ───────────────────────────────────────────────────────────

@Composable
private fun BrainRotRules(
    state: GoodPostUiState,
    viewModel: GoodPostViewModel,
    onDelete: (GoodPostBrainRotRule) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "add-keyword") {
                AddRow(
                    label = stringResource(R.string.goodpost_brainrot_add_keyword),
                    hint = stringResource(R.string.goodpost_brainrot_add_keyword_hint),
                    value = state.brainRotNewKeyword,
                    onValueChange = viewModel::onBrainRotNewKeywordChange,
                    onAdd = viewModel::addBrainRotKeyword,
                    enabled = !state.brainRotBusy
                )
            }
            item(key = "add-channel") {
                AddRow(
                    label = stringResource(R.string.goodpost_brainrot_add_channel),
                    hint = stringResource(R.string.goodpost_brainrot_add_channel_hint),
                    value = state.brainRotNewChannel,
                    onValueChange = viewModel::onBrainRotNewChannelChange,
                    onAdd = viewModel::addBrainRotChannel,
                    enabled = !state.brainRotBusy
                )
            }

            item(key = "keywords-header") {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    WaSectionHeading(stringResource(R.string.goodpost_brainrot_section_keywords))
                }
            }
            items(state.brainRotKeywords, key = { "k-${it.id}" }) { rule ->
                RuleRow(
                    rule = rule,
                    busy = state.brainRotBusy,
                    onToggle = { enabled -> viewModel.setBrainRotRuleEnabled(rule, enabled) },
                    onDelete = { onDelete(rule) }
                )
            }

            item(key = "channels-header") {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    WaSectionHeading(stringResource(R.string.goodpost_brainrot_section_channels))
                }
            }
            items(state.brainRotChannels, key = { "c-${it.id}" }) { rule ->
                RuleRow(
                    rule = rule,
                    busy = state.brainRotBusy,
                    onToggle = { enabled -> viewModel.setBrainRotRuleEnabled(rule, enabled) },
                    onDelete = { onDelete(rule) }
                )
            }

            if (state.brainRotKeywords.isEmpty() &&
                state.brainRotChannels.isEmpty() &&
                !state.brainRotRulesLoading
            ) {
                item(key = "rules-empty") {
                    WaEmptyState(
                        title = stringResource(R.string.goodpost_brainrot_rules_empty_title),
                        note = stringResource(R.string.goodpost_brainrot_rules_empty_note)
                    )
                }
            }
        }

        if (state.brainRotRulesLoading &&
            state.brainRotKeywords.isEmpty() &&
            state.brainRotChannels.isEmpty()
        ) {
            CenteredProgress()
        }
    }
}

/** A field and an Add button, for adding a rule directly. */
@Composable
private fun AddRow(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    enabled: Boolean
) {
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        WaCard {
            WaField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                placeholder = hint,
                enabled = enabled
            )
            Spacer(Modifier.height(10.dp))
            WaPrimaryButton(
                text = stringResource(R.string.goodpost_brainrot_add),
                enabled = enabled && value.isNotBlank(),
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** One rule: its value, whether it is in force, and how to remove it. */
@Composable
private fun RuleRow(
    rule: GoodPostBrainRotRule,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        WaCard {
            WaCardHeader(
                icon = Icons.Outlined.Block,
                title = rule.value,
                subtitle = rule.reason?.takeIf { it.isNotBlank() }
                    ?: if (rule.enabled) {
                        stringResource(R.string.goodpost_brainrot_rule_on)
                    } else {
                        stringResource(R.string.goodpost_brainrot_rule_off)
                    },
                trailing = {
                    // Enable/disable rather than delete-and-recreate: a rule
                    // switched off because it was too broad can be switched back
                    // on once it is narrowed, and the row it lived in is where
                    // that decision belongs.
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = onToggle,
                        enabled = !busy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Wa.OnAccent,
                            checkedTrackColor = Wa.Accent,
                            checkedBorderColor = Wa.Accent,
                            uncheckedThumbColor = Wa.TextDim,
                            uncheckedTrackColor = Wa.Bar,
                            uncheckedBorderColor = Wa.Divider
                        )
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onDelete, enabled = !busy) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.goodpost_delete),
                            tint = Wa.Danger,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
            if (rule.reports > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.goodpost_brainrot_reports, rule.reports),
                    color = Wa.Accent,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ── Shared bits ─────────────────────────────────────────────────────────

/** A small label saying whether a row is a keyword or a channel. */
@Composable
private fun KindChip(isChannel: Boolean) {
    Text(
        text = stringResource(
            if (isChannel) R.string.goodpost_brainrot_channel
            else R.string.goodpost_brainrot_keyword
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Wa.Pressed)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = Wa.TextDim,
        fontSize = 11.sp
    )
}


