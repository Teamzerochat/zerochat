package com.zerochat.app.ui.screens.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.app.domain.group.GroupSessionState
import com.zerochat.app.ui.theme.*
import com.zerochat.app.ui.viewmodels.GroupViewModel

/**
 * Group Setup Screen — Code entry + group size selection + connection progress.
 *
 * Flow:
 * 1. User enters 6-digit code and selects group size (2-10).
 * 2. Taps "Create Group" → lifecycle begins.
 * 3. Progress indicators show: slot claiming → peer discovery → sealing.
 * 4. On SEALED → shows SAS verification words.
 * 5. On ACTIVE → navigates to GroupChatScreen.
 * 6. On SECURITY_VIOLATION → shows emergency alert.
 */
@Composable
fun GroupSetupScreen(
    onGroupActive: () -> Unit,
    onBack: () -> Unit,
    viewModel: GroupViewModel = hiltViewModel()
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val sharedSecret by viewModel.sharedSecret.collectAsState()
    val groupSize by viewModel.groupSize.collectAsState()
    val displayName by viewModel.displayName.collectAsState()
    val isCreator by viewModel.isCreator.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val sasWords by viewModel.sasWords.collectAsState()
    var secretVisible by remember { mutableStateOf(false) }

    // Navigate to chat when active
    LaunchedEffect(sessionState) {
        if (sessionState is GroupSessionState.Active) {
            onGroupActive()
        }
    }

    val isConnecting = sessionState !is GroupSessionState.Idle &&
            sessionState !is GroupSessionState.Failed &&
            sessionState !is GroupSessionState.Terminated &&
            sessionState !is GroupSessionState.SecurityViolation

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ─── Top App Bar ───
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF020617))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(0.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = Primary
                    )
                }

                Text(
                    text = "GROUP SESSION",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary,
                    letterSpacing = 2.sp
                )

                Icon(
                    Icons.Outlined.Groups,
                    contentDescription = "Group",
                    tint = Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // ─── Main Content ───
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (sessionState) {
                    is GroupSessionState.Idle,
                    is GroupSessionState.Failed,
                    is GroupSessionState.Terminated -> {
                        GroupSetupForm(
                            sharedSecret = sharedSecret,
                            groupSize = groupSize,
                            displayName = displayName,
                            isCreator = isCreator,
                            secretVisible = secretVisible,
                            isConnecting = isConnecting,
                            errorMessage = (sessionState as? GroupSessionState.Failed)?.reason
                                ?: (sessionState as? GroupSessionState.Terminated)?.reason,
                            onSecretChange = viewModel::updateSharedSecret,
                            onGroupSizeChange = viewModel::updateGroupSize,
                            onDisplayNameChange = viewModel::updateDisplayName,
                            onIsCreatorChange = viewModel::setIsCreator,
                            onToggleVisibility = { secretVisible = !secretVisible },
                            onConnect = viewModel::startGroupSession
                        )
                    }

                    is GroupSessionState.Probing,
                    is GroupSessionState.Claiming,
                    is GroupSessionState.Announcing,
                    is GroupSessionState.Sealing -> {
                        GroupConnectionProgress(
                            state = sessionState,
                            peerCount = peerCount,
                            groupSize = groupSize,
                            onCancel = viewModel::terminateSession
                        )
                    }

                    is GroupSessionState.Sealed -> {
                        SASVerificationCard(
                            sasWords = sasWords
                        )
                    }

                    is GroupSessionState.SecurityViolation -> {
                        SecurityViolationAlert(
                            violation = sessionState as GroupSessionState.SecurityViolation,
                            onDismiss = viewModel::reset
                        )
                    }

                    is GroupSessionState.Active -> {
                        // Navigation handled by LaunchedEffect
                    }
                }
            }
        }
    }
}

// ─── Setup Form ─────────────────────────────────────────────────────────────────

@Composable
private fun GroupSetupForm(
    sharedSecret: String,
    groupSize: Int,
    displayName: String,
    isCreator: Boolean,
    secretVisible: Boolean,
    isConnecting: Boolean,
    errorMessage: String?,
    onSecretChange: (String) -> Unit,
    onGroupSizeChange: (Int) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onIsCreatorChange: (Boolean) -> Unit,
    onToggleVisibility: () -> Unit,
    onConnect: () -> Unit
) {
    // Group icon with gradient
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Primary.copy(alpha = 0.3f), Color.Transparent)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Groups,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(40.dp)
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = if (isCreator) "Create Group Session" else "Join Group Session",
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = OnBackground
    )

    Text(
        text = if (isCreator) "Set your name, enter a shared code,\nand choose the group size."
               else "Set your name and enter the same\ncode the creator used.",
        fontSize = 14.sp,
        color = OnSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))
    
    // Create / Join Tabs
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Create Tab
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isCreator) Primary else Color.Transparent)
                .clickable { onIsCreatorChange(true) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Create Group",
                color = if (isCreator) OnPrimary else OnSurfaceVariant,
                fontWeight = if (isCreator) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp
            )
        }
        
        // Join Tab
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!isCreator) Primary else Color.Transparent)
                .clickable { onIsCreatorChange(false) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Join Group",
                color = if (!isCreator) OnPrimary else OnSurfaceVariant,
                fontWeight = if (!isCreator) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Error message
    AnimatedVisibility(
        visible = errorMessage != null,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = ErrorContainer.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Error,
                    contentDescription = null,
                    tint = Error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = errorMessage ?: "",
                    color = Error,
                    fontSize = 13.sp
                )
            }
        }
    }

    // Display Name input
    OutlinedTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        label = { Text("Display Name", color = OnSurfaceVariant) },
        placeholder = { Text("e.g. Alice", color = Outline) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = OutlineVariant,
            focusedTextColor = OnBackground,
            unfocusedTextColor = OnBackground,
            cursorColor = Primary
        ),
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Shared secret input
    OutlinedTextField(
        value = sharedSecret,
        onValueChange = onSecretChange,
        label = { Text("Shared Secret Code", color = OnSurfaceVariant) },
        placeholder = { Text("Enter 6-digit code", color = Outline) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = if (secretVisible) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    if (secretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = "Toggle visibility",
                    tint = Outline
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            unfocusedBorderColor = OutlineVariant,
            focusedTextColor = OnBackground,
            unfocusedTextColor = OnBackground,
            cursorColor = Primary
        ),
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Group size selector (only for creators)
    AnimatedVisibility(visible = isCreator) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Group Size: $groupSize members",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = OnBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            Slider(
                value = groupSize.toFloat(),
                onValueChange = { onGroupSizeChange(it.toInt()) },
                valueRange = 2f..10f,
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Primary,
                    activeTrackColor = Primary,
                    inactiveTrackColor = SurfaceVariant
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("2", color = Outline, fontSize = 12.sp)
                Text("10", color = Outline, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Connect button
    Button(
        onClick = onConnect,
        enabled = sharedSecret.length >= 6 && displayName.isNotBlank() && !isConnecting,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = OnPrimary,
            disabledContainerColor = SurfaceVariant,
            disabledContentColor = Outline
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Outlined.Groups, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isCreator) "Create Group" else "Join Group", 
            fontWeight = FontWeight.Bold, 
            fontSize = 16.sp
        )
    }
}

// ─── Connection Progress ─────────────────────────────────────────────────────────

@Composable
private fun GroupConnectionProgress(
    state: GroupSessionState,
    peerCount: Int,
    groupSize: Int,
    onCancel: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "progress")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Animated progress icon
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Primary.copy(alpha = pulseAlpha * 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(60.dp),
                color = Primary,
                strokeWidth = 3.dp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Status text
        val statusText = when (state) {
            is GroupSessionState.Probing -> "Initializing slot matrix..."
            is GroupSessionState.Claiming -> "Claiming virtual slot..."
            is GroupSessionState.Announcing -> "Discovering peers..."
            is GroupSessionState.Sealing -> "Deriving group key..."
            else -> "Connecting..."
        }

        Text(
            text = statusText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = OnBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Peer discovery progress
        if (state is GroupSessionState.Announcing ||
            state is GroupSessionState.Sealing) {
            Text(
                text = "Discovered $peerCount / $groupSize members",
                fontSize = 14.sp,
                color = Primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = peerCount.toFloat() / groupSize.toFloat(),
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Primary,
                trackColor = SurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Cancel button
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.height(44.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Tertiary
            ),
            border = BorderStroke(1.dp, Tertiary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Cancel", fontWeight = FontWeight.Medium)
        }
    }
}

// ─── SAS Verification Card ───────────────────────────────────────────────────────

@Composable
private fun SASVerificationCard(
    sasWords: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.Verified,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Group Verified",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Primary
            )

            Text(
                text = "Verify these words match on all devices:",
                fontSize = 14.sp,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // SAS words display
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                sasWords.forEach { word ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = PrimaryContainer.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = word,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Entering group chat...",
                fontSize = 13.sp,
                color = OnSurfaceVariant
            )
        }
    }
}

// ─── Security Violation Alert ────────────────────────────────────────────────────

@Composable
private fun SecurityViolationAlert(
    violation: GroupSessionState.SecurityViolation,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ErrorContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.GppBad,
                contentDescription = null,
                tint = Error,
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "⚠️ Security Alert",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Error
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = violation.reason,
                fontSize = 14.sp,
                color = OnBackground,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Expected ${violation.expectedMembers} members, " +
                        "detected ${violation.actualMembers}.",
                fontSize = 13.sp,
                color = Tertiary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Error,
                    contentColor = OnError
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Dismiss & Re-establish", fontWeight = FontWeight.Bold)
            }
        }
    }
}
