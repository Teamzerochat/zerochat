package com.zerochat.app.ui.screens.setup

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
 * Setup Screen - First-time passphrase creation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(uiState) {
        if (uiState is SetupUiState.Complete) {
            onSetupComplete()
        }
    }
    
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var duressPassphrase by remember { mutableStateOf("") }
    var showDuressSection by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "Setup ZeroChat",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Create your encryption passphrase",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Passphrase
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Confirm passphrase
        OutlinedTextField(
            value = confirmPassphrase,
            onValueChange = { confirmPassphrase = it },
            label = { Text("Confirm Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = confirmPassphrase.isNotEmpty() && passphrase != confirmPassphrase
        )
        
        if (confirmPassphrase.isNotEmpty() && passphrase != confirmPassphrase) {
            Text(
                text = "Passphrases do not match",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Duress passphrase section
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = showDuressSection,
                        onCheckedChange = { showDuressSection = it }
                    )
                    Text(
                        text = "Set duress passphrase (optional)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                if (showDuressSection) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ If entered, this passphrase will permanently destroy all data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = duressPassphrase,
                        onValueChange = { duressPassphrase = it },
                        label = { Text("Duress Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (uiState is SetupUiState.Error) {
            Text(
                text = (uiState as SetupUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        
        Button(
            onClick = {
                viewModel.setup(
                    passphrase = passphrase,
                    duressPassphrase = if (showDuressSection && duressPassphrase.isNotEmpty()) 
                        duressPassphrase else null
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = passphrase.isNotEmpty() && 
                      passphrase == confirmPassphrase && 
                      passphrase.length >= 8 &&
                      uiState !is SetupUiState.Loading
        ) {
            if (uiState is SetupUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Create Secure Vault")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Minimum 8 characters. This passphrase cannot be recovered.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
