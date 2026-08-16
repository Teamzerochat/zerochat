package com.zerochat.app.ui.screens.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.app.domain.group.GroupChatMessage
import com.zerochat.app.domain.group.GroupSessionState
import com.zerochat.app.ui.theme.*
import com.zerochat.app.ui.viewmodels.GroupViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Group Chat Screen — Live encrypted group messaging interface.
 *
 * Displays:
 * - Group member count indicator
 * - SAS verification badge
 * - Message list with sender differentiation
 * - Emergency security violation banner
 * - Message input with send button
 */
@Composable
fun GroupChatScreen(
    onDisconnect: () -> Unit,
    viewModel: GroupViewModel = hiltViewModel()
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val messageInput by viewModel.messageInput.collectAsState()
    val sasWords by viewModel.sasWords.collectAsState()
    val myMemberIndex by viewModel.myMemberIndex.collectAsState()
    val groupSize by viewModel.groupSize.collectAsState()

    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Navigate back on termination
    LaunchedEffect(sessionState) {
        when (sessionState) {
            is GroupSessionState.Terminated,
            is GroupSessionState.Idle -> onDisconnect()
            else -> { }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // ─── Top Bar ───
        GroupChatTopBar(
            groupSize = groupSize,
            sasWords = sasWords,
            onLeave = { viewModel.terminateSession() }
        )

        // ─── Security Violation Banner ───
        AnimatedVisibility(
            visible = sessionState is GroupSessionState.SecurityViolation,
            enter = fadeIn() + slideInVertically()
        ) {
            val violation = sessionState as? GroupSessionState.SecurityViolation
            if (violation != null) {
                SecurityBanner(
                    message = violation.reason,
                    onDismiss = {
                        viewModel.reset()
                        onDisconnect()
                    }
                )
            }
        }

        // ─── Message List ───
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Encrypted session header
            item {
                EncryptedSessionHeader(sasWords = sasWords)
            }

            items(messages) { message ->
                GroupMessageBubble(
                    message = message,
                    isOwnMessage = message.senderIndex == myMemberIndex
                )
            }
        }

        // ─── Message Input ───
        if (sessionState is GroupSessionState.Active) {
            GroupMessageInput(
                value = messageInput,
                onValueChange = viewModel::updateMessageInput,
                onSend = viewModel::sendMessage
            )
        }
    }
}

// ─── Top Bar ─────────────────────────────────────────────────────────────────────

@Composable
private fun GroupChatTopBar(
    groupSize: Int,
    sasWords: List<String>,
    onLeave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF020617))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Group icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Groups,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Group Chat",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = OnBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Primary)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$groupSize members • E2E Encrypted",
                    fontSize = 12.sp,
                    color = Primary
                )
            }
        }

        // SAS badge
        if (sasWords.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = PrimaryContainer.copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = sasWords.joinToString(" "),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Leave button
        IconButton(onClick = onLeave) {
            Icon(
                Icons.AutoMirrored.Outlined.Logout,
                contentDescription = "Leave Group",
                tint = Tertiary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ─── Encrypted Session Header ────────────────────────────────────────────────────

@Composable
private fun EncryptedSessionHeader(sasWords: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = Primary.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Messages are end-to-end encrypted with AES-256-GCM.",
            fontSize = 12.sp,
            color = OnSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        if (sasWords.isNotEmpty()) {
            Text(
                text = "Safety Words: ${sasWords.joinToString(" - ")}",
                fontSize = 11.sp,
                color = Primary.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ─── Message Bubble ──────────────────────────────────────────────────────────────

// Member colors for visual differentiation
private val memberColors = listOf(
    Color(0xFF4EDEA3), // Primary emerald
    Color(0xFF60A5FA), // Blue
    Color(0xFFFBBF24), // Amber
    Color(0xFFF87171), // Red
    Color(0xFFA78BFA), // Purple
    Color(0xFF34D399), // Green
    Color(0xFFFB923C), // Orange
    Color(0xFF818CF8), // Indigo
    Color(0xFF2DD4BF), // Teal
    Color(0xFFE879F9), // Pink
)

@Composable
private fun GroupMessageBubble(
    message: GroupChatMessage,
    isOwnMessage: Boolean
) {
    val memberColor = if (message.senderIndex >= 0) {
        memberColors[message.senderIndex % memberColors.size]
    } else {
        Primary
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start
    ) {
        // Sender label (for received messages)
        if (!isOwnMessage && message.senderIndex >= 0) {
            Text(
                text = "Member ${message.senderIndex + 1}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = memberColor,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
            )
        }

        Card(
            modifier = Modifier
                .widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isOwnMessage)
                    Primary.copy(alpha = 0.15f)
                else
                    SurfaceContainerHigh
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                bottomEnd = if (isOwnMessage) 4.dp else 16.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.text,
                    fontSize = 15.sp,
                    color = OnBackground,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    fontSize = 10.sp,
                    color = OnSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

// ─── Message Input ───────────────────────────────────────────────────────────────

@Composable
private fun GroupMessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF020617))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Message...", color = Outline, fontSize = 14.sp) },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp, max = 120.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary.copy(alpha = 0.5f),
                unfocusedBorderColor = OutlineVariant,
                focusedTextColor = OnBackground,
                unfocusedTextColor = OnBackground,
                cursorColor = Primary
            ),
            shape = RoundedCornerShape(22.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 4,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        FilledIconButton(
            onClick = onSend,
            enabled = value.isNotBlank(),
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Primary,
                contentColor = OnPrimary,
                disabledContainerColor = SurfaceVariant,
                disabledContentColor = Outline
            ),
            shape = CircleShape
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = "Send",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─── Security Banner ─────────────────────────────────────────────────────────────

@Composable
private fun SecurityBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF7F1D1D)
        ),
        shape = RoundedCornerShape(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.GppBad,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SECURITY ALERT",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Dismiss & Return", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}
