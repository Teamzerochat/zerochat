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
import com.zerochat.app.ui.viewmodels.ConnectViewModel
import com.zerochat.app.domain.connection.ConnectionState

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
    val connectionState by viewModel.connectionState.collectAsState()
    val sharedSecret by viewModel.sharedSecret.collectAsState()
    
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            onConnected("connected") // Session ID handled internally
        }
    }
    
    // Shared secret now managed by ViewModel
    
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
            onValueChange = { viewModel.updateSharedSecret(it) },
            label = { Text("Shared Secret") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Same phrase your contact is entering") },
            enabled = connectionState is ConnectionState.Idle || 
                     connectionState is ConnectionState.Failed ||
                     connectionState is ConnectionState.Disconnected
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Connection status
        when (val state = connectionState) {
            is ConnectionState.ConnectingToNym,
            is ConnectionState.DerivedRendezvous,
            is ConnectionState.PollingRendezvous,
            is ConnectionState.WaitingForPeer,
            is ConnectionState.Handshaking,
            is ConnectionState.ExchangingHandles,
            is ConnectionState.EstablishingI2P -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                val statusText = when (state) {
                    is ConnectionState.ConnectingToNym -> "Connecting to network..."
                    is ConnectionState.PollingRendezvous -> "Waiting for peer..."
                    is ConnectionState.WaitingForPeer -> "Waiting for peer to connect..."
                    is ConnectionState.Handshaking -> "Establishing secure connection..."
                    is ConnectionState.ExchangingHandles -> "Exchanging keys..."
                    is ConnectionState.EstablishingI2P -> "Establishing I2P tunnel..."
                    else -> "Connecting..."
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            is ConnectionState.Failed -> {
                Text(
                    text = if (state.reason.contains("timeout", ignoreCase = true)) {
                        "Peer not online"
                    } else {
                        "Connection failed"
                    },
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
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.reset() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }
            }
            
            is ConnectionState.Idle,
            is ConnectionState.Disconnected -> {
                Button(
                    onClick = { viewModel.connect() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sharedSecret.length >= 6
                ) {
                    Text("Connect")
                }
            }
            
            else -> {}
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
    }
}
