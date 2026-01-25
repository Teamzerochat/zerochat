package com.zerochat.app.ui.screens.connect

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
 * Connect Screen - Enter ONLY shared secret
 * 
 * Security (UI-01): NO NYM addresses, routing handles, or identifiers shown
 * 
 * User enters: Shared secret only
 * App handles: Rendezvous derivation, routing, handles
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onConnected: (String) -> Unit,
    viewModel: ConnectViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(uiState) {
        if (uiState is ConnectUiState.Connected) {
            onConnected((uiState as ConnectUiState.Connected).sessionId)
        }
    }
    
    var sharedSecret by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "Connect to Peer",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Both users must be online",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Shared secret ONLY - no NYM address input (UI-01)
        OutlinedTextField(
            value = sharedSecret,
            onValueChange = { sharedSecret = it },
            label = { Text("Shared Secret") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Same phrase your contact is entering") },
            enabled = uiState !is ConnectUiState.Connecting
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Connection status
        when (val state = uiState) {
            is ConnectUiState.Connecting -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                // UI-03: Only permitted states shown
                Text(
                    text = "Connecting...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.attempt > 0) {
                    Text(
                        text = "Waiting for peer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            is ConnectUiState.PeerOffline -> {
                // FL-05: Same message for timeout AND auth failure
                Text(
                    text = "Peer not online",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Make sure your contact is also connecting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            
            is ConnectUiState.Error -> {
                // UI-05: Generic error only
                Text(
                    text = "Connection failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            else -> {
                Button(
                    onClick = { viewModel.connect(sharedSecret) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sharedSecret.length >= 6
                ) {
                    Text("Connect")
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Info card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "How to connect:",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. Agree on a secret phrase with your contact\n" +
                           "2. Both of you enter the same phrase\n" +
                           "3. Both must be online at the same time\n" +
                           "4. Connection happens automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Privacy notice
        Text(
            text = "No addresses or identifiers are exchanged",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
