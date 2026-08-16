package com.zerochat.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.app.domain.connection.ConnectionState
import com.zerochat.app.ui.theme.*
import com.zerochat.app.ui.viewmodels.ConnectViewModel
import com.zerochat.app.ui.screens.guide.GuideOverlay
import com.zerochat.app.ui.screens.guide.GuideViewModel
import com.zerochat.app.ui.util.DocsLaunchButton
import com.zerochat.app.ui.util.DocsLinks
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    onNavigateToGroup: () -> Unit = {},
    viewModel: ConnectViewModel = hiltViewModel(),
    guideViewModel: GuideViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val sharedSecret by viewModel.sharedSecret.collectAsState()
    val completedGuideSteps by guideViewModel.completedSteps.collectAsState()
    var secretVisible by remember { mutableStateOf(false) }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) onConnected()
    }

    val isConnecting = connectionState !is ConnectionState.Idle
        && connectionState !is ConnectionState.Failed
        && connectionState !is ConnectionState.Disconnected
        && connectionState !is ConnectionState.Zeroized

    val showGuide5 = !completedGuideSteps.contains("step_5")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // ─── Sticky Top App Bar ────────────────────────────────────────────────
        // enhanced_encryption | ZEROCHAT | account_circle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF020617))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(0.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = "Security",
                    tint = Primary
                )
            }
            Text(
                text = "ZEROCHAT",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = 4.sp,
                color = Primary
            )
            IconButton(onClick = { guideViewModel.resetGuide() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = "App guide",
                    tint = Primary
                )
            }
        }

        // ─── Scrollable content ────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            // ── Key icon with glow ────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SurfaceContainerHigh)
                    .border(1.dp, SurfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── "Secure Connection" title ─────────────────────────────────────
            Text(
                text = "Secure Connection",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                letterSpacing = (-0.64).sp,
                color = OnBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Establish an end-to-end encrypted channel by entering the shared secret provided by your contact.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )

            Spacer(Modifier.height(40.dp))

            // ── Secret input with lock icon ───────────────────────────────────
            val borderColor = when {
                connectionState is ConnectionState.Failed -> Error
                sharedSecret.isNotEmpty() -> Primary
                else -> OutlineVariant
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .background(SurfaceContainerLowest)
            ) {
                OutlinedTextField(
                    value = sharedSecret,
                    onValueChange = { viewModel.updateSharedSecret(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Enter sequence (min 6 chars)",
                            color = OutlineVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = if (sharedSecret.isNotEmpty()) Primary else Outline
                        )
                    },
                    trailingIcon = {
                        TextButton(onClick = { secretVisible = !secretVisible }) {
                            Text(
                                text = if (secretVisible) "HIDE" else "SHOW",
                                style = MaterialTheme.typography.labelMedium,
                                color = Outline
                            )
                        }
                    },
                    visualTransformation = if (secretVisible) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !isConnecting,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        errorBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                        disabledTextColor = OnSurface.copy(alpha = 0.5f),
                        cursorColor = Primary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Status pill ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible = isConnecting || connectionState is ConnectionState.Failed,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    ConnectionStatusPill(connectionState)
                    Spacer(Modifier.height(16.dp))
                }
            }

            // ── Initiate Handshake / Cancel button ────────────────────────────
            if (isConnecting) {
                Button(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorContainer,
                        contentColor = OnErrorContainer
                    )
                ) {
                    Text(
                        text = "CANCEL",
                        fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp
                    )
                }
            } else {
                Button(
                    onClick = { viewModel.connect() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = sharedSecret.length >= 6,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary,
                        disabledContainerColor = SurfaceContainerHigh,
                        disabledContentColor = OnSurfaceVariant
                    )
                ) {
                    Text(
                        text = "Initiate Handshake",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onNavigateToGroup,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Primary
                    ),
                    border = BorderStroke(1.dp, OutlineVariant)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Groups,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Create / Join Group Session (N ≤ 10)",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // ── Protocol Directives card ──────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceContainerLow)
                    .border(1.dp, SurfaceContainerHighest, RoundedCornerShape(12.dp))
                    .padding(24.dp)
            ) {
                // Card header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "PROTOCOL DIRECTIVES",
                        fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        letterSpacing = 1.5.sp,
                        color = OnSurfaceVariant
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = SurfaceContainerHighest
                )

                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    DirectiveItem("01", "Agree on a unique, secure identifier with your contact via an offline or out-of-band channel.")
                    DirectiveItem("02", "Input the exact sequence in the secure vault field above. A minimum of 6 characters is enforced.")
                    DirectiveItem("03", "Standby as the system performs cryptographic verification and initiates the key exchange.")
                    DirectiveItem("04", "Upon successful handshake, an encrypted, zero-knowledge tunnel is permanently established.")
                }
            }

            // ── App Guide & Docs link ─────────────────────────────────────────
            Spacer(Modifier.height(40.dp))

            TextButton(onClick = { guideViewModel.resetGuide() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = OnSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Access Complete App Guide",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurfaceVariant
                )
            }
            
            DocsLaunchButton(url = DocsLinks.ARCHITECTURE) { onClick ->
                TextButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInBrowser,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Read Connection Architecture Guide",
                        style = MaterialTheme.typography.labelLarge,
                        color = OnSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
        }

        // --- GUIDES ---
        GuideOverlay(
            isVisible = showGuide5,
            onAcknowledge = { guideViewModel.markStepComplete("step_5") },
            title = "Initiate Secure Handshake",
            description = "Once you've entered the exact shared secret, initiate the handshake. ZeroChat will establish an encrypted tunnel directly to your contact.",
            stepProgress = 5,
            highlightTopPadding = 400.dp,
            highlightedContent = {
                Button(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Initiate Handshake", fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        )
    }
}


@Composable
private fun DirectiveItem(number: String, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(SurfaceContainerHighest)
                .border(1.dp, OutlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 0.96.sp,
                color = Primary
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = OnBackground,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ConnectionStatusPill(state: ConnectionState) {
    val (text, color) = when (state) {
        is ConnectionState.ConnectingToNym   -> "Connecting to Nym mixnet…"       to Primary
        is ConnectionState.DerivedRendezvous -> "Rendezvous point derived…"        to Primary
        is ConnectionState.PollingRendezvous -> "Awaiting peer on rendezvous…"     to Primary
        is ConnectionState.WaitingForPeer    -> "Waiting for peer…"                to Primary
        is ConnectionState.Handshaking       -> "Performing SPAKE2+ handshake…"   to Primary
        is ConnectionState.ExchangingHandles -> "Exchanging I2P destinations…"     to Primary
        is ConnectionState.EstablishingI2P   -> "Building I2P tunnel…"             to Primary
        is ConnectionState.Failed            -> state.reason                        to Error
        else                                 -> ""                                  to OnSurfaceVariant
    }
    if (text.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceContainerLow)
                .border(1.dp, OutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state !is ConnectionState.Failed) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Primary
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}
