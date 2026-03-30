package com.zerochat.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zerochat.app.domain.connection.ConnectionState
import com.zerochat.app.ui.viewmodels.ConnectViewModel

/**
 * ConnectScreen - Connection initiation UI
 * 
 * Features:
 * - Shared secret input with show/hide
 * - Single connect button (roles derived automatically)
 * - Connection status display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val sharedSecret by viewModel.sharedSecret.collectAsState()
    
    var secretVisible by remember { mutableStateOf(false) }
    
    // Navigate to chat when connected
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            onConnected()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZeroChat - Connect") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Title
            Text(
                text = "Connect to Peer",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            
            // Shared secret input
            OutlinedTextField(
                value = sharedSecret,
                onValueChange = { viewModel.updateSharedSecret(it) },
                label = { Text("Shared Secret") },
                placeholder = { Text("Enter secret key") },
                visualTransformation = if (secretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { secretVisible = !secretVisible }) {
                        Text(if (secretVisible) "Hide" else "Show")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                enabled = connectionState is ConnectionState.Idle || 
                         connectionState is ConnectionState.Failed ||
                         connectionState is ConnectionState.Disconnected
            )
            
            // Connection status
            ConnectionStatusCard(connectionState)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Connect buttons
            if (connectionState is ConnectionState.Idle || 
                connectionState is ConnectionState.Failed ||
                connectionState is ConnectionState.Disconnected) {
                
                Button(
                    onClick = { viewModel.connect() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sharedSecret.isNotBlank()
                ) {
                    Text("Connect")
                }
                
            } else {
                // Disconnect button
                Button(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Help text
            Text(
                text = "Both peers must use the same secret key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ConnectionStatusCard(state: ConnectionState) {
    val (statusText, statusColor) = when (state) {
        is ConnectionState.Idle -> "Ready to connect" to MaterialTheme.colorScheme.onSurface
        is ConnectionState.ConnectingToNym -> "Connecting to Nym..." to MaterialTheme.colorScheme.primary
        is ConnectionState.DerivedRendezvous -> "Rendezvous derived" to MaterialTheme.colorScheme.primary
        is ConnectionState.PollingRendezvous -> "Waiting for peer..." to MaterialTheme.colorScheme.primary
        is ConnectionState.WaitingForPeer -> "Waiting for peer to connect..." to MaterialTheme.colorScheme.primary
        is ConnectionState.Handshaking -> "Handshaking..." to MaterialTheme.colorScheme.primary
        is ConnectionState.ExchangingHandles -> "Exchanging handles..." to MaterialTheme.colorScheme.primary
        is ConnectionState.EstablishingI2P -> "Establishing I2P tunnel..." to MaterialTheme.colorScheme.primary
        is ConnectionState.Connected -> "Connected!" to MaterialTheme.colorScheme.tertiary
        is ConnectionState.Failed -> state.reason to MaterialTheme.colorScheme.error
        is ConnectionState.Disconnected -> "Disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionState.Fallback -> "⚠ Degraded: ${state.reason}" to MaterialTheme.colorScheme.error
        is ConnectionState.Zeroized -> "Session terminated — keys destroyed" to MaterialTheme.colorScheme.error
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state is ConnectionState.ConnectingToNym ||
                state is ConnectionState.PollingRendezvous ||
                state is ConnectionState.WaitingForPeer ||
                state is ConnectionState.Handshaking ||
                state is ConnectionState.ExchangingHandles ||
                state is ConnectionState.EstablishingI2P) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = statusColor
            )
        }
    }
}
