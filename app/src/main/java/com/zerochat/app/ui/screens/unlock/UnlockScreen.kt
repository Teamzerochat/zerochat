package com.zerochat.app.ui.screens.unlock

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import com.zerochat.app.ui.theme.*
import com.zerochat.app.ui.screens.guide.GuideOverlay
import com.zerochat.app.ui.screens.guide.GuideViewModel

/**
 * Unlock Screen — Vault locked state.
 * Matches the "unlock_modern_ui" design: centered vault identity,
 * passphrase input, ambient glow background, and pulsing status dot.
 */
@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    onNeedSetup: () -> Unit,
    viewModel: UnlockViewModel = hiltViewModel(),
    guideViewModel: GuideViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val completedGuideSteps by guideViewModel.completedSteps.collectAsState()

    LaunchedEffect(uiState) {
        when (uiState) {
            is UnlockUiState.Unlocked -> onUnlocked()
            is UnlockUiState.NeedSetup -> onNeedSetup()
            else -> {}
        }
    }

    var passphrase by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Ambient glow pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "ambientGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )

    val showGuide4 = !completedGuideSteps.contains("step_4")


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        // Ambient background glow
        Box(
            modifier = Modifier
                .size(500.dp)
                .align(Alignment.Center)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Primary.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
                .blur(80.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Brand identity
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(SurfaceContainerLow)
                    .border(1.dp, SurfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "ZEROCHAT",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 40.sp,
                letterSpacing = 4.sp,
                color = OnBackground
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Vault locked. Enter your passphrase to decrypt your local environment.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp)
            )

            Spacer(Modifier.height(40.dp))

            // Passphrase input
            val isError = uiState is UnlockUiState.Error
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .border(
                        width = 1.dp,
                        color = if (isError) Error else if (passphrase.isNotEmpty()) Primary else SurfaceContainerHighest,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .background(SurfaceContainerLowest)
            ) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Enter Passphrase",
                            color = OutlineVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = if (passphrase.isNotEmpty()) Primary else Outline
                        )
                    },
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                text = if (passwordVisible) "HIDE" else "SHOW",
                                style = MaterialTheme.typography.labelMedium,
                                color = Outline
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    isError = isError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        errorBorderColor = Color.Transparent,
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                        cursorColor = Primary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                    )
                )
            }

            if (isError) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = (uiState as UnlockUiState.Error).message,
                    color = Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            // Primary — DECRYPT button
            Button(
                onClick = { viewModel.unlock(passphrase) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = passphrase.isNotEmpty() && uiState !is UnlockUiState.Loading,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = OnPrimary,
                    disabledContainerColor = SurfaceContainerHigh,
                    disabledContentColor = OnSurfaceVariant
                )
            ) {
                if (uiState is UnlockUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = OnPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "DECRYPT",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // Secondary — App Guide ghost button
            OutlinedButton(
                onClick = { guideViewModel.resetGuide() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Primary
                )
            ) {
                Text(
                    text = "APP GUIDE",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(Modifier.height(32.dp))

            // System status indicator — pulsing dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = dotAlpha))
                )
                Text(
                    text = "SYSTEM ONLINE & SECURE",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    color = OnSurfaceVariant
                )
            }
        }

        // --- GUIDES ---
        GuideOverlay(
            isVisible = showGuide4,
            onAcknowledge = { guideViewModel.markStepComplete("step_4") },
            title = "Decrypt Your Vault",
            description = "Enter your passphrase to load your keys into memory. ZeroChat will wipe them instantly if the device locks.",
            stepProgress = 4,
            highlightTopPadding = 420.dp,
            highlightedContent = {
                Button(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("DECRYPT", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        )
    }
}

