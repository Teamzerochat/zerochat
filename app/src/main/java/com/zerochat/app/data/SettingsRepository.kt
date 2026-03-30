package com.zerochat.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zerochat_settings")

/**
 * Settings Repository — DataStore-backed app preferences.
 *
 * Security-relevant settings:
 * - skipNymRendezvous: bypasses Nym for nation-state threat models (>30% Sybil)
 * - meteredWarningShown: tracks first-launch data warning
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val SKIP_NYM_RENDEZVOUS = booleanPreferencesKey("skip_nym_rendezvous")
        private val METERED_WARNING_SHOWN = booleanPreferencesKey("metered_warning_shown")
    }

    // ── I2P-Only Mode ──────────────────────────────────────────────────
    // When enabled, skip Nym rendezvous entirely and wait for full I2P init.
    // Paper §3: for users facing >30% Nym Sybil penetration.

    val skipNymRendezvous: Flow<Boolean> = context.dataStore.data
        .map { it[SKIP_NYM_RENDEZVOUS] ?: false }

    suspend fun setSkipNymRendezvous(skip: Boolean) {
        context.dataStore.edit { it[SKIP_NYM_RENDEZVOUS] = skip }
    }

    suspend fun getSkipNymRendezvousSync(): Boolean {
        return context.dataStore.data.first()[SKIP_NYM_RENDEZVOUS] ?: false
    }

    // ── Metered Data Warning ───────────────────────────────────────────
    // Track whether the 1.34× bandwidth overhead warning has been shown.
    // Paper Table 6: ZeroChat only appropriate when user can tolerate overhead.

    val meteredWarningShown: Flow<Boolean> = context.dataStore.data
        .map { it[METERED_WARNING_SHOWN] ?: false }

    suspend fun setMeteredWarningShown(shown: Boolean) {
        context.dataStore.edit { it[METERED_WARNING_SHOWN] = shown }
    }

    suspend fun getMeteredWarningShownSync(): Boolean {
        return context.dataStore.data.first()[METERED_WARNING_SHOWN] ?: false
    }
}
