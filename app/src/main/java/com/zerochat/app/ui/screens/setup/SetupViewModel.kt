package com.zerochat.app.ui.screens.setup

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.ui.screens.unlock.UnlockViewModel.Companion.KEY_DB_NONCE
import com.zerochat.app.ui.screens.unlock.UnlockViewModel.Companion.KEY_DURESS_HASH
import com.zerochat.app.ui.screens.unlock.UnlockViewModel.Companion.KEY_ENCRYPTED_DB_KEY
import com.zerochat.app.ui.screens.unlock.UnlockViewModel.Companion.KEY_SALT
import com.zerochat.app.ui.screens.unlock.UnlockViewModel.Companion.KEY_SETUP_COMPLETE
import com.zerochat.app.ui.screens.unlock.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject

private const val TAG = "SetupViewModel"

sealed class SetupUiState {
    object Initial : SetupUiState()
    object Loading : SetupUiState()
    object Complete : SetupUiState()
    data class Error(val message: String) : SetupUiState()
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Initial)
    val uiState: StateFlow<SetupUiState> = _uiState
    
    fun setup(passphrase: String, duressPassphrase: String?) {
        viewModelScope.launch {
            _uiState.value = SetupUiState.Loading
            
            try {
                Log.d(TAG, "Starting setup...")
                
                // Generate salt for Argon2id
                val salt = keyManager.generateSalt()
                Log.d(TAG, "Salt generated")
                
                // Derive KEK from passphrase (slow - Argon2id)
                Log.d(TAG, "Deriving KEK...")
                val success = withContext(Dispatchers.Default) {
                    keyManager.deriveKEK(passphrase, salt)
                }
                Log.d(TAG, "KEK derived: $success")
                
                if (!success) {
                    _uiState.value = SetupUiState.Error("Failed to derive key")
                    return@launch
                }
                
                // Generate random database key
                val dbKey = keyManager.generateDatabaseKey()
                Log.d(TAG, "DB key generated")
                
                // Wrap database key with KEK
                val wrapResult = keyManager.wrapDatabaseKey(dbKey)
                if (wrapResult == null) {
                    _uiState.value = SetupUiState.Error("Failed to wrap database key")
                    return@launch
                }
                val (encryptedDbKey, nonce) = wrapResult
                Log.d(TAG, "DB key wrapped")
                
                // Hash duress passphrase if provided
                val duressHash = if (!duressPassphrase.isNullOrEmpty()) {
                    Log.d(TAG, "Hashing duress passphrase...")
                    withContext(Dispatchers.Default) {
                        keyManager.hashDuressPassphrase(duressPassphrase, salt)
                    }
                } else null
                Log.d(TAG, "Duress hash: ${duressHash != null}")
                
                // Store encrypted credentials
                Log.d(TAG, "Storing to DataStore...")
                context.dataStore.edit { prefs ->
                    prefs[KEY_SALT] = Base64.getEncoder().encodeToString(salt)
                    prefs[KEY_ENCRYPTED_DB_KEY] = Base64.getEncoder().encodeToString(encryptedDbKey)
                    prefs[KEY_DB_NONCE] = Base64.getEncoder().encodeToString(nonce)
                    prefs[KEY_SETUP_COMPLETE] = true
                    
                    if (duressHash != null) {
                        prefs[KEY_DURESS_HASH] = Base64.getEncoder().encodeToString(duressHash)
                    }
                }
                Log.d(TAG, "Stored to DataStore")
                
                // Clear KEK - user will need to unlock again
                keyManager.clearKEK()
                Log.d(TAG, "Setup complete!")
                
                _uiState.value = SetupUiState.Complete
                
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                keyManager.clearKEK()
                _uiState.value = SetupUiState.Error(e.message ?: "Setup failed")
            }
        }
    }
}
