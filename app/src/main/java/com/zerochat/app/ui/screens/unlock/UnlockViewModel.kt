package com.zerochat.app.ui.screens.unlock

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.domain.crypto.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject

val Context.dataStore by preferencesDataStore(name = "zerochat_prefs")

sealed class UnlockUiState {
    object Initial : UnlockUiState()
    object Loading : UnlockUiState()
    object Unlocked : UnlockUiState()
    object NeedSetup : UnlockUiState()
    data class Error(val message: String) : UnlockUiState()
}

@HiltViewModel
class UnlockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<UnlockUiState>(UnlockUiState.Initial)
    val uiState: StateFlow<UnlockUiState> = _uiState
    
    companion object {
        val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val KEY_SALT = stringPreferencesKey("salt")
        val KEY_ENCRYPTED_DB_KEY = stringPreferencesKey("encrypted_db_key")
        val KEY_DB_NONCE = stringPreferencesKey("db_nonce")
        val KEY_DURESS_HASH = stringPreferencesKey("duress_hash")
    }
    
    init {
        checkSetupStatus()
    }
    
    private fun checkSetupStatus() {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            val isSetupComplete = prefs[KEY_SETUP_COMPLETE] ?: false
            
            if (!isSetupComplete) {
                _uiState.value = UnlockUiState.NeedSetup
            }
        }
    }
    
    fun unlock(passphrase: String) {
        viewModelScope.launch {
            _uiState.value = UnlockUiState.Loading
            
            try {
                val prefs = context.dataStore.data.first()
                
                val saltBase64 = prefs[KEY_SALT] 
                    ?: throw Exception("Setup incomplete")
                val encryptedDbKeyBase64 = prefs[KEY_ENCRYPTED_DB_KEY] 
                    ?: throw Exception("Setup incomplete")
                val nonceBase64 = prefs[KEY_DB_NONCE] 
                    ?: throw Exception("Setup incomplete")
                val duressHashBase64 = prefs[KEY_DURESS_HASH]
                
                val salt = Base64.getDecoder().decode(saltBase64)
                val encryptedDbKey = Base64.getDecoder().decode(encryptedDbKeyBase64)
                val nonce = Base64.getDecoder().decode(nonceBase64)
                
                // Check for duress passphrase first
                if (duressHashBase64 != null) {
                    val duressHash = Base64.getDecoder().decode(duressHashBase64)
                    val isDuress = withContext(Dispatchers.Default) {
                        keyManager.checkDuress(passphrase, duressHash, salt)
                    }
                    if (isDuress) {
                        // DURESS TRIGGERED - Silently wipe ALL data
                        // Then behave as if this is a fresh install
                        // Adversary sees: "app was never setup" (plausible deniability)
                        context.dataStore.edit { prefs ->
                            prefs.clear()  // Wipe all preferences
                        }
                        // Show setup screen as if fresh install
                        _uiState.value = UnlockUiState.NeedSetup
                        return@launch
                    }
                }
                
                // Derive KEK from passphrase (this takes time due to Argon2id)
                val success = withContext(Dispatchers.Default) {
                    keyManager.deriveKEK(passphrase, salt)
                }
                
                if (!success) {
                    _uiState.value = UnlockUiState.Error("Failed to derive key")
                    return@launch
                }
                
                // Unwrap database key
                val dbKey = keyManager.unwrapDatabaseKey(encryptedDbKey, nonce)
                
                if (dbKey == null) {
                    keyManager.clearKEK()
                    _uiState.value = UnlockUiState.Error("Incorrect passphrase")
                    return@launch
                }
                
                // Success - database can now be opened
                _uiState.value = UnlockUiState.Unlocked
                
            } catch (e: Exception) {
                _uiState.value = UnlockUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
