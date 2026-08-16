package com.zerochat.app.ui.screens.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.ui.platform.LocalContext
import com.zerochat.app.ui.util.DocsLinks
import com.zerochat.app.ui.util.DocsLaunchButton
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.app.ui.theme.*
import com.zerochat.app.ui.screens.guide.GuideOverlay
import com.zerochat.app.ui.screens.guide.GuideViewModel
import com.zerochat.app.ui.viewmodels.ChatMessage
import com.zerochat.app.ui.viewmodels.ChatViewModel
import com.zerochat.app.ui.viewmodels.MessageStatus
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatScreen — Secure Conversation interface.
 * Matches the "secure_chat_modern_ui" design: sticky ZEROCHAT top bar,
 * chat header with identity + E2EE badge, message bubbles (inbound/outbound),
 * sticky bottom message composer with send button, and bottom nav bar.
 */
@Composable
fun ChatScreen(
    onDisconnect: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    guideViewModel: GuideViewModel = hiltViewModel()
) {
    val allMessages by viewModel.allMessages.collectAsState()
    val messageInput by viewModel.messageInput.collectAsState()
    val completedGuideSteps by guideViewModel.completedSteps.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(allMessages.size) {
        if (allMessages.isNotEmpty()) {
            listState.animateScrollToItem(allMessages.size - 1)
        }
    }

    val showGuide6 = !completedGuideSteps.contains("step_6")

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
        ) {
            // ─── Sticky Top App Bar ───────────────────────────────────────────────
            ChatTopBar(onGuideClick = { guideViewModel.resetGuide() })

        // ─── Chat contact header ──────────────────────────────────────────────
        ChatContactHeader(onDisconnect = onDisconnect)

        // ─── Message list ─────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Surface)
        ) {
            if (allMessages.isEmpty()) {
                // Empty state — centered
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = OnSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Encrypted tunnel active",
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Send a message to begin the secure exchange.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(allMessages, key = { it.id }) { message ->
                        SecureMessageBubble(message)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

            // ─── Message input bar ────────────────────────────────────────────────
            SecureMessageInputBar(
                value = messageInput,
                onValueChange = { viewModel.updateMessageInput(it) },
                onSend = { viewModel.sendMessage() }
            )
        }

        // --- GUIDES ---
        GuideOverlay(
            isVisible = showGuide6,
            onAcknowledge = { guideViewModel.markStepComplete("step_6") },
            title = "Secure Transmission",
            description = "Everything sent through here is encrypted, broken into indistinguishable packets, and mixed with dummy traffic to obscure metadata.",
            stepProgress = 6,
            highlightTopPadding = 600.dp,
            highlightedContent = {
                SecureMessageInputBar(
                    value = messageInput,
                    onValueChange = {},
                    onSend = {}
                )
            }
        )
    }
}

@Composable
private fun ChatTopBar(onGuideClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF020617))
            .border(
                width = 1.dp,
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Opens Security Deep Dive docs with external browser privacy warning
        DocsLaunchButton(url = DocsLinks.SECURITY) { openDocs ->
            IconButton(onClick = openDocs) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = "Security Documentation",
                    tint = Primary
                )
            }
        }
        Text(
            text = "ZEROCHAT",
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            letterSpacing = 4.sp,
            color = Primary
        )
        IconButton(onClick = onGuideClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = "App Guide",
                tint = Primary
            )
        }
    }
}

@Composable
private fun ChatContactHeader(onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceContainerLow.copy(alpha = 0.9f))
            .border(
                width = 1.dp,
                color = OutlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar with online dot
            Box(modifier = Modifier.size(48.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(SurfaceContainerHighest)
                        .border(1.dp, OutlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "A",
                        style = MaterialTheme.typography.headlineMedium,
                        color = OnSurface
                    )
                }
                // Online dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(Primary)
                        .border(2.dp, SurfaceContainerLow, CircleShape)
                )
            }
            Column {
                Text(
                    text = "Secure Peer",
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 18.sp),
                    color = OnSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Verified Identity",
                        style = MaterialTheme.typography.labelLarge,
                        color = OnSurfaceVariant
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // E2EE badge
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(PrimaryContainer.copy(alpha = 0.1f))
                    .border(1.dp, Primary.copy(alpha = 0.4f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val pulsing = rememberInfiniteTransition(label = "e2eePulse")
                val alpha by pulsing.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "lockAlpha"
                )
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Primary.copy(alpha = alpha),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "E2EE",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    color = Primary
                )
            }

            // Disconnect
            IconButton(onClick = onDisconnect, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Disconnect",
                    tint = OnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DateSeparator(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clip(CircleShape)
                .background(SurfaceContainerLow)
                .border(1.dp, OutlineVariant.copy(alpha = 0.5f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            color = OnSurfaceVariant
        )
    }
}

@Composable
private fun SecureMessageBubble(message: ChatMessage) {
    val isOutbound = message.isMine
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutbound) Alignment.End else Alignment.Start
    ) {
        Box(modifier = Modifier.widthIn(max = 300.dp)) {
            if (isOutbound) {
                // Outbound — Deep Mint background, Emerald border
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 12.dp, topEnd = 12.dp,
                                bottomStart = 12.dp, bottomEnd = 2.dp
                            )
                        )
                        .background(Color(0xFF0B513D))
                        .border(
                            1.dp,
                            Primary.copy(alpha = 0.3f),
                            RoundedCornerShape(
                                topStart = 12.dp, topEnd = 12.dp,
                                bottomStart = 12.dp, bottomEnd = 2.dp
                            )
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB0F0D6)
                    )
                }
            } else {
                // Inbound — Slate surface, subtle border
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 2.dp, topEnd = 12.dp,
                                bottomStart = 12.dp, bottomEnd = 12.dp
                            )
                        )
                        .background(SurfaceContainerHighest)
                        .border(
                            1.dp,
                            OutlineVariant,
                            RoundedCornerShape(
                                topStart = 2.dp, topEnd = 12.dp,
                                bottomStart = 12.dp, bottomEnd = 12.dp
                            )
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface
                    )
                }
            }
        }

        if (isOutbound) {
            Spacer(Modifier.height(2.dp))
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = null,
                tint = when (message.status) {
                    MessageStatus.SENT, MessageStatus.RECEIVED -> Primary
                    MessageStatus.SENDING -> OnSurfaceVariant
                    MessageStatus.FAILED -> Error
                },
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.End)
                    .padding(end = 4.dp)
            )
        }
    }
}

@Composable
private fun SecureMessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceContainerLow.copy(alpha = 0.95f))
            .border(
                width = 1.dp,
                color = OutlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Background)
                .border(
                    width = 1.dp,
                    color = if (value.isNotEmpty()) Primary else OutlineVariant,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Attachment icon button
            IconButton(
                onClick = {},
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddCircle,
                    contentDescription = "Attach",
                    tint = OnSurfaceVariant
                )
            }

            // Text field
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                placeholder = {
                    Text(
                        "Transmit secure message…",
                        color = OnSurfaceVariant.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    cursorColor = Primary
                )
            )

            // Send button
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (value.isNotBlank()) Primary else SurfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank()) OnPrimary else OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = "END-TO-END ENCRYPTED SESSION ACTIVE",
            modifier = Modifier.fillMaxWidth(),
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            color = OnSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ChatBottomNav() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color(0xFF0F172A))
            .border(
                width = 1.dp,
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(icon = Icons.Outlined.MailOutline, label = "MESSAGES", active = true)
        NavItem(icon = Icons.Outlined.Lock, label = "VAULT", active = false)
        NavItem(icon = Icons.Outlined.Share, label = "NETWORK", active = false)
        NavItem(icon = Icons.Outlined.Settings, label = "SETTINGS", active = false)
    }
}

@Composable
private fun NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Primary.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) Primary else Color(0xFF64748B),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            color = if (active) Primary else Color(0xFF64748B)
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp)).uppercase()
}
