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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.GoodPostUiState
import com.muddassir.clearview.goodpost.GoodPostViewModel
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

        // The two halves as pills, matching every other filter row in the tab.
        WaFilterRow {
            WaFilterPill(
                label = stringResource(R.string.goodpost_brainrot_queue),
                selected = state.brainRotTab == 0,
                onClick = { viewModel.selectBrainRotTab(0) }
            )
            WaFilterPill(
                label = stringResource(R.string.goodpost_brainrot_rules),
                selected = state.brainRotTab == 1,
                onClick = { viewModel.selectBrainRotTab(1) }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (state.brainRotTab == 0) {
                BrainRotQueue(state = state, viewModel = viewModel)
            } else {
                BrainRotRules(
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
    onReject: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindChip(isChannel = submission.isChannel)
            Spacer(Modifier.width(10.dp))
            Text(
                text = submission.value,
                color = Wa.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }

        // The community's signal, on the row it decides. One person asking and
        // two hundred devices asking are very different cases, and a reviewer
        // should not have to leave the screen to tell them apart.
        if (submission.reports > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.goodpost_brainrot_reports, submission.reports),
                color = Wa.Accent,
                fontSize = 12.sp
            )
        }

        // A note the suggestor wrote, when there is one.
        submission.note?.takeIf { it.isNotBlank() }?.let { note ->
            Spacer(Modifier.height(4.dp))
            Text(text = note, color = Wa.TextDim, fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))

        if (submission.isPending) {
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
            }
        } else {
            // An already-decided row says so rather than offering the buttons
            // again: the server refuses a second review, and a button that can
            // only fail is worse than a label.
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
                SectionHeading(stringResource(R.string.goodpost_brainrot_section_keywords))
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
                SectionHeading(stringResource(R.string.goodpost_brainrot_section_channels))
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        WaField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = hint,
            enabled = enabled
        )
        Spacer(Modifier.height(6.dp))
        WaTextAction(
            text = stringResource(R.string.goodpost_brainrot_add),
            enabled = enabled && value.isNotBlank(),
            onClick = onAdd
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.value,
                color = if (rule.enabled) Wa.Text else Wa.TextDim,
                fontSize = 15.sp
            )
            rule.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(Modifier.height(2.dp))
                Text(text = reason, color = Wa.TextDim, fontSize = 12.sp)
            }
            if (rule.reports > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.goodpost_brainrot_reports, rule.reports),
                    color = Wa.Accent,
                    fontSize = 12.sp
                )
            }
        }

        // Enable/disable rather than delete-and-recreate: a rule switched off
        // because it was too broad can be switched back on once it is narrowed,
        // and the row it lived in is where that decision belongs.
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

        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.goodpost_delete),
            tint = Wa.Danger,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !busy, onClick = onDelete)
                .padding(8.dp)
        )
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

/** A quiet all-caps heading between two groups in the rules list. */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        color = Wa.TextDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
    )
}
