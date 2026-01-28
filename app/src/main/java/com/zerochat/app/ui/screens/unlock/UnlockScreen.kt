package com.zerochat.app.ui.screens.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Unlock Screen - Passphrase entry
 * 
 * Security: First screen shown, requires passphrase to derive KEK
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    onNeedSetup: () -> Unit,
    viewModel: UnlockViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(uiState) {
        when (uiState) {
            is UnlockUiState.Unlocked -> onUnlocked()
            is UnlockUiState.NeedSetup -> onNeedSetup()
            else -> {}
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Icon/Title
        Text(
            text = "🔒",
            style = MaterialTheme.typography.displayLarge
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "ZeroChat",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Your Secure Vault",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Passphrase input
        var passphrase by remember { mutableStateOf("") }
        
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = uiState is UnlockUiState.Error
        )
        
        if (uiState is UnlockUiState.Error) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = (uiState as UnlockUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { viewModel.unlock(passphrase) },
            modifier = Modifier.fillMaxWidth(),
            enabled = passphrase.isNotEmpty() && uiState !is UnlockUiState.Loading
        ) {
            if (uiState is UnlockUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Unlock")
        }
    }
}
