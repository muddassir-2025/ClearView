package com.muddassir.clearview.goodpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.goodpost.data.GoodPostAnalytics

/**
 * Channel analytics (§15).
 *
 * A screen rather than a tab, opened from the channel it belongs to: these
 * numbers are management data for one channel, and a tab would have to ask
 * "which channel?" before it could show anything.
 *
 * What is deliberately NOT here: any per-follower or per-viewer detail. §15
 * gives the owner totals, a series and a post ranking — never who looked.
 *
 * The bar chart is drawn from the series as the database produced it, zero days
 * included. A chart that skipped empty days would make a quiet week look busy,
 * which is the opposite of what an owner needs to see.
 */
@Composable
internal fun AnalyticsDialog(
    channelName: String,
    analytics: GoodPostAnalytics?,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    WaFullScreen(onDismiss = onDismiss, background = Wa.List) {
        WaTopBar(
            title = stringResource(R.string.goodpost_analytics_title),
            subtitle = channelName,
            navigation = {
                WaIconAction(
                    icon = Icons.Filled.Close,
                    description = stringResource(R.string.goodpost_cancel),
                    onClick = onDismiss
                )
            }
        )

        if (loading || analytics == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Wa.Accent)
            }
            return@WaFullScreen
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.goodpost_analytics_window,
                    analytics.windowDays
                ),
                color = Wa.TextDim,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(12.dp))

            // Two columns of three, so a phone does not have to scroll to
            // compare follower growth with views.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = stringResource(R.string.goodpost_analytics_followers),
                        value = analytics.totals.followers,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.goodpost_analytics_posts),
                        value = analytics.totals.posts,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = stringResource(R.string.goodpost_analytics_viewers),
                        value = analytics.totals.uniqueViewers,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.goodpost_analytics_views),
                        value = analytics.totals.totalViews,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        label = stringResource(R.string.goodpost_analytics_reactions),
                        value = analytics.totals.reactions,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = stringResource(R.string.goodpost_analytics_poll_votes),
                        value = analytics.totals.pollVotes,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.goodpost_analytics_views),
                color = Wa.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))

            if (analytics.peakViews == 0) {
                Text(
                    text = stringResource(R.string.goodpost_analytics_none),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
            } else {
                ViewSeries(analytics = analytics)
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.goodpost_analytics_top_posts),
                color = Wa.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(10.dp))

            if (analytics.topPosts.isEmpty()) {
                Text(
                    text = stringResource(R.string.goodpost_analytics_none),
                    color = Wa.TextDim,
                    fontSize = 13.sp
                )
            } else {
                analytics.topPosts.forEach { post ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Insights,
                            contentDescription = null,
                            tint = Wa.Accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = post.type,
                            color = Wa.Text,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            // Views and reactions, the two numbers that say
                            // whether a post worked.
                            text = "${post.uniqueViewers} · ${post.reactions}",
                            color = Wa.TextDim,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

/** One big number with its label. */
@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Wa.Bar)
            .padding(12.dp)
    ) {
        Text(
            text = value.toString(),
            color = Wa.Accent,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, color = Wa.TextDim, fontSize = 12.sp)
    }
}

/**
 * Views per day, as bars scaled to the busiest day in the window.
 *
 * Scaled to the peak rather than to a fixed axis: a channel doing tens of views
 * a day would otherwise see a row of invisible slivers. The bars share the width
 * evenly, so a 90-day window is 90 thin bars — still readable as a shape.
 */
@Composable
private fun ViewSeries(analytics: GoodPostAnalytics) {
    val peak = analytics.peakViews.coerceAtLeast(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        analytics.series.forEach { point ->
            // A floor of 2.dp so a zero day is a visible baseline rather than a
            // gap, which would read as missing data.
            val height = (2 + (116 * point.views / peak)).dp
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(if (point.views == 0) Wa.Divider else Wa.Accent)
            )
        }
    }
}
