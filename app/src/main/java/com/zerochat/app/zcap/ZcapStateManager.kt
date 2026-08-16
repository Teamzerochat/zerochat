package com.zerochat.app.zcap

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.zerochat.app.data.local.ZeroChatDatabase
import com.zerochat.app.data.local.entity.ZcapRatchetEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.nym_transport.zcapFetchMessages

/**
 * ZcapStateManager
 *
 * Manages the ZCAP offline-message polling loop, binding it to an Android
 * [LifecycleOwner] so polling is automatically cancelled when the Activity
 * or Fragment is destroyed.
 *
 * @param lifecycleOwner    Owner whose scope bounds the polling coroutine.
 * @param tunnelStatusFlow  Flow<Boolean> — true = I2P tunnel up, false = down.
 * @param database          Injected [ZeroChatDatabase] (SQLCipher).
 * @param peerNymAddress    Nym address of the peer whose mailbox we poll.
 * @param kShared           Shared secret (32 bytes). Caller must zeroize after passing.
 * @param gatewayIdentities Sorted gateway identity list for the Rust swarm module.
 */
class ZcapStateManager(
    private val lifecycleOwner: LifecycleOwner,
    private val tunnelStatusFlow: Flow<Boolean>,
    private val database: ZeroChatDatabase,
    private val peerNymAddress: String,
    private val kShared: ByteArray,
    private val gatewayIdentities: List<String>
) {

    companion object {
        private const val TAG = "ZcapStateManager"
        private const val POLL_INTERVAL_MS = 30_000L
    }

    private var pollingJob: Job? = null
    private val _offlineMessages = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64
    )

    /**
     * Decrypted ZCAP payloads recovered from offline mailboxes.
     *
     * Consumers should feed these bytes into the same message pipeline used by
     * live Nym/I2P payloads, so UI code does not care how a message arrived.
     */
    val offlineMessages: SharedFlow<ByteArray> = _offlineMessages.asSharedFlow()

    /**
     * Start the ZCAP polling loop.
     * Polling is gated by [tunnelStatusFlow] — paused when the I2P tunnel is up.
     * Safe to call multiple times.
     */
    fun startZcapPolling() {
        stopZcapPolling()
        Log.i(TAG, "Starting ZCAP polling for peer $peerNymAddress")

        pollingJob = lifecycleOwner.lifecycleScope.launch {
            tunnelStatusFlow.collectLatest { tunnelUp ->
                if (tunnelUp) {
                    Log.d(TAG, "I2P tunnel is UP — pausing ZCAP polling for $peerNymAddress")
                    return@collectLatest
                }

                Log.d(TAG, "I2P tunnel is DOWN — starting ZCAP poll loop for $peerNymAddress")
                while (isActive) {
                    runCatching { pollOnce() }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            Log.e(TAG, "ZCAP poll error for $peerNymAddress: ${e.message}", e)
                        }
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    /** Stop the ZCAP polling loop. Safe to call when inactive. */
    fun stopZcapPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.i(TAG, "ZCAP polling stopped for $peerNymAddress")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun pollOnce() {
        val dao = database.zcapRatchetDao()

        val stateBlob = dao.getRatchetState(peerNymAddress)?.serializedState
            ?: run {
                Log.w(TAG, "No ratchet state for $peerNymAddress — waiting for handshake")
                return
            }

        val utcNowSecs = (System.currentTimeMillis() / 1000L).toULong()

        // Call Rust via the generated UniFFI binding.
        // TODO: Pass actual transportHandle once NymTransportClient is injected.
        val result = try {
            zcapFetchMessages(
                transportHandle = 0uL,
                serializedState = stateBlob.toList().map { it.toUByte() },
                kShared = kShared.toList().map { it.toUByte() },
                utcNowSecs = utcNowSecs,
                gatewayIdentities = gatewayIdentities
            )
        } catch (e: Exception) {
            Log.e(TAG, "zcap_fetch_messages failed for $peerNymAddress: ${e.message}", e)
            return
        }

        // Deliver decrypted messages (property access — UniFFI dicts are not Pairs).
        if (result.decryptedMessages.isNotEmpty()) {
            Log.i(TAG, "Received ${result.decryptedMessages.size} offline message(s) for $peerNymAddress")
            result.decryptedMessages.forEach { plaintext ->
                _offlineMessages.emit(plaintext.map { it.toByte() }.toByteArray())
            }
        }

        // Persist the advanced ratchet state.
        val updatedStateArray = result.updatedState.map { it.toByte() }.toByteArray()
        dao.insertOrUpdate(
            ZcapRatchetEntity(
                peerNymAddress = peerNymAddress,
                serializedState = updatedStateArray
            )
        )

        // Zeroize the local copy — DB is now the sole owner.
        updatedStateArray.fill(0)
    }
}


