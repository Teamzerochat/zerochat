package com.zerochat.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

data class ChatMessage(
    val id: String,
    val content: String,
    val isOutgoing: Boolean,
    val timestamp: Long
)

/**
 * Chat Screen - Live encrypted messaging
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String,
    onDisconnect: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    val listState = rememberLazyListState()
    
    // Scroll to bottom when new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Secure Chat",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = peerId.take(12) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    TextButton(onClick = onDisconnect) {
                        Text("End", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        },
        bottomBar = {
            // Message input
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    IconButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                // Add message to list
                                messages = messages + ChatMessage(
                                    id = System.currentTimeMillis().toString(),
                                    content = messageText,
                                    isOutgoing = true,
                                    timestamp = System.currentTimeMillis()
                                )
                                messageText = ""
                                // TODO: Send via WebRTC DataChannel
                            }
                        },
                        enabled = messageText.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (messageText.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Session info banner
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "🔒 End-to-end encrypted session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            
            // Messages
            items(messages) { message ->
                MessageBubble(message = message)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val backgroundColor = if (message.isOutgoing)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isOutgoing)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant
    
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
