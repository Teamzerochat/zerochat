package com.zerochat.app.ui.screens.setup

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.zerochat.app.ui.util.DocsLinks
import com.zerochat.app.ui.util.DocsLaunchButton

/**
 * Setup Screen — Initialize Vault (first-time passphrase creation).
 * Matches the "sign_up_modern_ui" design: glassmorphism card, passphrase
 * fields, Duress Mode toggle with warning field, GENERATE KEYS CTA.
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
    guideViewModel: GuideViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val completedGuideSteps by guideViewModel.completedSteps.collectAsState()


    LaunchedEffect(uiState) {
        if (uiState is SetupUiState.Complete) {
            onSetupComplete()
        }
    }

    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var duressPassphrase by remember { mutableStateOf("") }
    var duressEnabled by remember { mutableStateOf(false) }
    var passVisible by remember { mutableStateOf(false) }
    var duressVisible by remember { mutableStateOf(false) }

    val mismatch = confirmPassphrase.isNotEmpty() && passphrase != confirmPassphrase
    val canSubmit = passphrase.isNotEmpty()
        && passphrase == confirmPassphrase
        && passphrase.length >= 8
        && uiState !is SetupUiState.Loading

    // Determine current guide step (1, 2, or 3)
    val showGuide1 = !completedGuideSteps.contains("step_1")
    val showGuide2 = !showGuide1 && !completedGuideSteps.contains("step_2")
    val showGuide3 = !showGuide1 && !showGuide2 && !completedGuideSteps.contains("step_3")


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        // Ambient center glow
        Box(
            modifier = Modifier
                .size(600.dp)
                .align(Alignment.Center)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Primary.copy(alpha = 0.05f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
                .blur(100.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Glassmorphism card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceContainerLow.copy(alpha = 0.9f))
                    .border(1.dp, SurfaceContainerHighest, RoundedCornerShape(12.dp))
                    .padding(40.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Lock icon header
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(SurfaceContainer)
                            .border(1.dp, OutlineVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Initialize Vault",
                        style = MaterialTheme.typography.displayMedium,
                        color = OnSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Establish your local cryptographic identity. This passphrase never leaves your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 280.dp)
                    )

                    Spacer(Modifier.height(32.dp))

                    // ─── Passphrase field ───────────────────────────────────────
                    VaultLabel("Passphrase")
                    Spacer(Modifier.height(4.dp))
                    VaultTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        placeholder = "••••••••••••••••",
                        leadingIcon = Icons.Outlined.Lock,
                        isPassword = true,
                        isVisible = passVisible,
                        onVisibilityToggle = { passVisible = !passVisible },
                        isError = false
                    )

                    Spacer(Modifier.height(16.dp))

                    // ─── Confirm Passphrase field ───────────────────────────────
                    VaultLabel("Confirm Passphrase")
                    Spacer(Modifier.height(4.dp))
                    VaultTextField(
                        value = confirmPassphrase,
                        onValueChange = { confirmPassphrase = it },
                        placeholder = "••••••••••••••••",
                        leadingIcon = Icons.Outlined.CheckCircle,
                        isPassword = true,
                        isVisible = false,
                        onVisibilityToggle = null,
                        isError = mismatch
                    )
                    if (mismatch) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Passphrases do not match",
                            color = Error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // ─── Duress Mode toggle ─────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceContainer)
                            .border(1.dp, OutlineVariant, RoundedCornerShape(8.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Warning,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .padding(top = 2.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Enable Duress Mode",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (duressEnabled) Primary else OnSurface
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "Creates a secondary vault that securely wipes main data when unlocked.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = duressEnabled,
                                    onCheckedChange = { duressEnabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = OnPrimary,
                                        checkedTrackColor = Primary,
                                        uncheckedThumbColor = OnSurfaceVariant,
                                        uncheckedTrackColor = SurfaceContainerHighest,
                                        uncheckedBorderColor = OutlineVariant
                                    )
                                )
                            }

                            AnimatedVisibility(visible = duressEnabled) {
                                Column {
                                    Spacer(Modifier.height(16.dp))
                                    HorizontalDivider(color = SurfaceContainerHighest)
                                    Spacer(Modifier.height(16.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Duress Passphrase",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = OnSurfaceVariant
                                        )
                                        Icon(
                                            imageVector = Icons.Outlined.Warning,
                                            contentDescription = null,
                                            tint = Error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    VaultTextField(
                                        value = duressPassphrase,
                                        onValueChange = { duressPassphrase = it },
                                        placeholder = "••••••••••••••••",
                                        leadingIcon = Icons.Outlined.Lock,
                                        isPassword = true,
                                        isVisible = duressVisible,
                                        onVisibilityToggle = { duressVisible = !duressVisible },
                                        isError = false
                                    )
                                }
                            }
                        }
                    }

                    if (uiState is SetupUiState.Error) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = (uiState as SetupUiState.Error).message,
                            color = Error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(32.dp))

                    // ─── GENERATE KEYS CTA ──────────────────────────────────────
                    Button(
                        onClick = {
                            viewModel.setup(
                                passphrase = passphrase,
                                duressPassphrase = if (duressEnabled && duressPassphrase.isNotEmpty())
                                    duressPassphrase else null
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = canSubmit,
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = OnPrimary,
                            disabledContainerColor = SurfaceContainerHigh,
                            disabledContentColor = OnSurfaceVariant
                        )
                    ) {
                        if (uiState is SetupUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = OnPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = "GENERATE KEYS",
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { guideViewModel.resetGuide() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary)
                    ) {
                        Text(
                            text = "APP GUIDE",
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "By initializing your vault, you acknowledge that lost passphrases cannot be recovered.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Outline,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    // Architecture docs link
                    DocsLaunchButton(url = DocsLinks.ARCHITECTURE) { openDocs ->
                        TextButton(onClick = openDocs) {
                            Text(
                                text = "How does ZeroChat protect your keys? →",
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary,
                                fontFamily = Inter,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // --- GUIDES ---
        GuideOverlay(
            isVisible = showGuide1,
            onAcknowledge = { guideViewModel.markStepComplete("step_1") },
            title = "Set Your Passphrase",
            description = "Your vault is protected by a local passphrase. Choose something strong, as this is the only way to access your keys.",
            stepProgress = 1,
            highlightTopPadding = 200.dp,
            highlightedContent = {
                VaultTextField(
                    value = passphrase,
                    onValueChange = {},
                    placeholder = "••••••••••••••••",
                    leadingIcon = Icons.Outlined.Lock,
                    isPassword = true,
                    isVisible = passVisible,
                    onVisibilityToggle = null,
                    isError = false
                )
            }
        )

        GuideOverlay(
            isVisible = showGuide2,
            onAcknowledge = { guideViewModel.markStepComplete("step_2") },
            title = "Optional: Duress Mode",
            description = "If forced to open your vault, a duress passphrase will unlock a secondary, empty account while securely wiping your real data.",
            stepProgress = 2,
            highlightTopPadding = 300.dp,
            highlightedContent = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable Duress Mode", style = MaterialTheme.typography.labelLarge)
                    Switch(checked = false, onCheckedChange = {})
                }
            }
        )

        GuideOverlay(
            isVisible = showGuide3,
            onAcknowledge = { guideViewModel.markStepComplete("step_3") },
            title = "Generate Cryptographic Keys",
            description = "Once your passphrase is set, ZeroChat will generate a unique cryptographic identity for you. This happens entirely offline.",
            stepProgress = 3,
            highlightTopPadding = 450.dp,
            highlightedContent = {
                Button(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("GENERATE KEYS", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        )
    }
}


@Composable
private fun VaultLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = OnSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
    )
}

@Composable
private fun VaultTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    isPassword: Boolean,
    isVisible: Boolean,
    onVisibilityToggle: (() -> Unit)?,
    isError: Boolean
) {
    val borderColor = when {
        isError -> Error
        value.isNotEmpty() -> Primary
        else -> OutlineVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .background(SurfaceContainerLowest)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(placeholder, color = Outline.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
            },
            leadingIcon = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (value.isNotEmpty()) Primary else Outline
                )
            },
            trailingIcon = if (onVisibilityToggle != null) ({
                TextButton(onClick = onVisibilityToggle) {
                    Text(
                        text = if (isVisible) "HIDE" else "SHOW",
                        style = MaterialTheme.typography.labelMedium,
                        color = Outline
                    )
                }
            }) else null,
            visualTransformation = if (isPassword && !isVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text),
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
}
