package com.muddassir.clearview.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muddassir.clearview.R
import com.muddassir.clearview.browser.BrowserBlock

/**
 * The in-tab ClearView block screen.
 *
 * Deliberately the same visual language as [com.muddassir.clearview.ui.
 * BlockOverlayActivity] — "FEAR GOD", a thin accent rule, "BLOCKED", the
 * matched rule in an error-coloured chip — so a block inside the Browser tab
 * is recognisably the same protection as a block in Chrome. The overlay covers
 * the whole WebView and the only way out is to leave the blocked page.
 */
@Composable
fun BrowserBlockOverlay(
    block: BrowserBlock,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(visible = visible, enter = fadeIn()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Fully opaque black: the blocked page must not show through,
                // not even as a dimmed thumbnail behind the message.
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1C1C1E))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.browser_block_fear_god),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 42.sp,
                    letterSpacing = 6.sp,
                    color = Color.White
                )

                Spacer(Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.35f))
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.browser_block_status),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    letterSpacing = 10.sp,
                    color = Color.White.copy(alpha = 0.62f)
                )

                Spacer(Modifier.height(32.dp))

                Text(
                    text = block.message(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Surface(
                    color = Color(0xFF2A1113),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = block.item,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFFB4AB)
                    )
                }

                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            if (block.isContent) R.string.browser_block_go_home
                            else R.string.browser_block_back
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.browser_block_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
