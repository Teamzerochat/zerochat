package com.zerochat.app.domain.group

import android.util.Log
import com.zerochat.app.domain.transport.TransportController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Group Discovery Manager — Swarm Announcement & Continuous Swarm Guard
 *
 * Responsibilities:
 * 1. Claim a virtual slot via the slot matrix.
 * 2. Broadcast a swarm announcement (Claimed_Slot_ID, Random_128bit_Nonce,
 *    Ephemeral_DH_PublicKey) to all 50 virtual slots.
 * 3. Poll the claimed slot for peer announcements.
 * 4. Sort collected nonces lexicographically to assign deterministic member indices.
 * 5. Run the **Continuous Swarm Guard** throughout the session: if a K+1 announcement
 *    is ever received, emit a quorum violation event for immediate session termination.
 *
 * The discovery manager emits events through Kotlin Flows for the GroupManager to consume.
 */
class GroupDiscoveryManager(
    private val controller: TransportController,
    private val slotMatrix: GroupSlotMatrix,
    private var groupSize: Int,
    private val displayName: String = "Anonymous",
    private val isCreator: Boolean = true
) {
    companion object {
        private const val TAG = "GroupDiscoveryManager"
        private const val ANNOUNCEMENT_NONCE_SIZE = 16 // 128-bit random nonce
        private const val POLL_INTERVAL_MS = 2_000L
        private const val DISCOVERY_TIMEOUT_MS = 120_000L // 2 minutes max
        private const val POST_SEAL_POLL_INTERVAL_MS = 5_000L // Lighter polling post-seal
    }

    // This device's announcement data
    private var myNonce: ByteArray? = null
    private var myPublicKey: ByteArray? = null
    private var claimedSlotIndex: Int = -1
    private var claimedPointId: String = ""

    // Whether the group size has been resolved (always true for creator, set when joiner gets a creator announcement)
    private var groupSizeResolved: Boolean = isCreator

    // Discovered peer announcements
    private val discoveredPeers = mutableMapOf<String, PeerAnnouncement>() // nonceHex → announcement

    // Discovery state
    private val _discoveryState = MutableStateFlow(DiscoveryState.IDLE)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    // Peer count (for UI progress)
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    // Quorum violation event (K+1 detected)
    private val _quorumViolation = MutableSharedFlow<QuorumViolation>(extraBufferCapacity = 1)
    val quorumViolation: SharedFlow<QuorumViolation> = _quorumViolation.asSharedFlow()

    // Sorted member list (after discovery completes)
    private var sortedMembers: List<PeerAnnouncement>? = null

    // Coroutine scope for background monitoring
    private var guardJob: Job? = null
    private var discoveryJob: Job? = null

    /**
     * Phase 1-4: Execute the full discovery flow.
     *
     * 1. Select and claim a random slot.
     * 2. Broadcast swarm announcement to all 50 slots.
     * 3. Poll claimed slot for peer announcements.
     * 4. When K members are discovered, sort nonces and return ordered list.
     *
     * @param ephemeralPublicKey This device's ephemeral DH public key
     * @return Sorted list of PeerAnnouncements (including self) or null on failure/timeout
     */
    suspend fun executeDiscovery(ephemeralPublicKey: ByteArray): List<PeerAnnouncement>? {
        _discoveryState.value = DiscoveryState.SELECTING_SLOT

        // Generate random 128-bit nonce
        myNonce = ByteArray(ANNOUNCEMENT_NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        myPublicKey = ephemeralPublicKey

        // Phase 1: Select a random slot with jitter
        val claim = slotMatrix.selectRandomSlot()
        claimedSlotIndex = claim.slotIndex
        claimedPointId = claim.pointId

        Log.i(TAG, "Phase 1: Selected slot ${claim.slotIndex}, applying ${claim.jitterDelayMs}ms jitter")
        delay(claim.jitterDelayMs)

        // Phase 2: Claim the slot (connect via TransportController)
        _discoveryState.value = DiscoveryState.CLAIMING_SLOT
        val claimResult = claimSlot(claim)
        if (!claimResult) {
            Log.e(TAG, "Failed to claim any slot after retries")
            _discoveryState.value = DiscoveryState.FAILED
            return null
        }

        // Register self in discovered peers
        val myAnnouncement = PeerAnnouncement(
            slotIndex = claimedSlotIndex,
            nonce = myNonce!!,
            publicKey = ephemeralPublicKey,
            isSelf = true,
            displayName = displayName,
            isCreator = isCreator
        )
        discoveredPeers[myAnnouncement.nonceHex] = myAnnouncement
        _peerCount.value = 1

        // Phase 3: Broadcast announcement to all 50 slots
        _discoveryState.value = DiscoveryState.ANNOUNCING
        broadcastAnnouncement()

        // Phase 4: Poll for peer announcements until K members discovered
        _discoveryState.value = DiscoveryState.POLLING
        val success = pollForPeers()

        if (!success) {
            _discoveryState.value = DiscoveryState.FAILED
            return null
        }

        // Phase 5: Sort by nonce (lexicographic)
        val sorted = discoveredPeers.values.toList().sortedWith(
            compareBy(ByteArrayComparator) { it.nonce }
        )
        sortedMembers = sorted

        // Assign member indices
        sorted.forEachIndexed { index, peer ->
            peer.memberIndex = index
            if (peer.isSelf) {
                Log.i(TAG, "Phase 5: My member index = $index (of ${sorted.size})")
            }
        }

        _discoveryState.value = DiscoveryState.DISCOVERED
        return sorted
    }

    /**
     * Phase 7: Start the Continuous Swarm Guard.
     *
     * Runs in the background AFTER group sealing, continuously polling the
     * claimed slot for additional swarm announcements. If K+1 announcements
     * are detected, emits a QuorumViolation event.
     *
     * @param scope CoroutineScope for the guard lifecycle
     */
    fun startSwarmGuard(scope: CoroutineScope) {
        guardJob?.cancel()
        guardJob = scope.launch {
            Log.i(TAG, "Continuous Swarm Guard started (monitoring for K+1 announcements)")
            while (isActive) {
                delay(POST_SEAL_POLL_INTERVAL_MS)

                try {
                    val newAnnouncements = pollSlotForAnnouncements()
                    for (announcement in newAnnouncements) {
                        val hex = announcement.nonceHex
                        if (hex !in discoveredPeers) {
                            // NEW ANNOUNCEMENT after sealing — quorum violation!
                            discoveredPeers[hex] = announcement
                            _peerCount.value = discoveredPeers.size

                            Log.e(TAG, "⚠️ QUORUM VIOLATION: K+1 announcement detected " +
                                    "(${discoveredPeers.size} > $groupSize). " +
                                    "Possible late infiltrator.")

                            _quorumViolation.emit(
                                QuorumViolation(
                                    expectedSize = groupSize,
                                    actualSize = discoveredPeers.size,
                                    extraNonce = hex
                                )
                            )
                            return@launch // Guard's job is done — manager will terminate session
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Swarm guard poll error (non-fatal)", e)
                }
            }
        }
    }

    /**
     * Get this device's member index (0-based, determined by nonce sorting).
     * Returns -1 if discovery hasn't completed.
     */
    fun getMyMemberIndex(): Int {
        return sortedMembers?.indexOfFirst { it.isSelf } ?: -1
    }

    /**
     * Get the sorted list of discovered peers (including self).
     */
    fun getSortedMembers(): List<PeerAnnouncement>? = sortedMembers

    /**
     * Get the claimed slot point ID.
     */
    fun getClaimedPointId(): String = claimedPointId

    /**
     * Get the claimed slot index.
     */
    fun getClaimedSlotIndex(): Int = claimedSlotIndex

    /**
     * Get the resolved group size (may have been learned from creator).
     */
    fun getResolvedGroupSize(): Int = groupSize

    /**
     * Get a map of memberIndex → displayName for all discovered peers.
     */
    fun getDisplayNameMap(): Map<Int, String> {
        return sortedMembers?.associate { it.memberIndex to it.displayName } ?: emptyMap()
    }

    /**
     * Tear down all discovery resources.
     */
    fun teardown() {
        guardJob?.cancel()
        discoveryJob?.cancel()
        discoveredPeers.clear()
        sortedMembers = null
        myNonce?.fill(0)
        myNonce = null
        myPublicKey = null
        _peerCount.value = 0
        _discoveryState.value = DiscoveryState.IDLE
        Log.i(TAG, "Discovery manager torn down")
    }

    // --- Private Implementation ---

    /**
     * Attempt to claim a slot. On collision, try alternative slots.
     */
    private suspend fun claimSlot(initialClaim: SlotClaim): Boolean {
        val attemptedSlots = mutableSetOf<Int>()

        var currentClaim = initialClaim
        while (true) {
            attemptedSlots.add(currentClaim.slotIndex)

            val result = try {
                controller.withTransport { transport ->
                    transport.connectRendezvous(currentClaim.pointId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Slot ${currentClaim.slotIndex} claim failed: ${e.message}")
                Result.failure<String>(e)
            }

            if (result.isSuccess) {
                claimedSlotIndex = currentClaim.slotIndex
                claimedPointId = currentClaim.pointId
                Log.i(TAG, "Successfully claimed slot ${currentClaim.slotIndex}")
                return true
            }

            // Collision — try alternative
            val alternative = slotMatrix.selectAlternativeSlot(attemptedSlots) ?: return false
            Log.i(TAG, "Slot collision on ${currentClaim.slotIndex}, trying ${alternative.slotIndex}")
            delay(alternative.jitterDelayMs)
            currentClaim = alternative
        }
    }

    /**
     * Broadcast swarm announcement to all 50 virtual slots via Sphinx packets.
     *
     * Announcement format (v2):
     *   [1: Version=0x02] [2: Slot Index] [16: Random Nonce]
     *   [1: Flags (bit0=isCreator)] [1: Group Size]
     *   [2: Display Name Length] [N: Display Name UTF-8]
     *   [Variable: Ephemeral DH Public Key]
     */
    private suspend fun broadcastAnnouncement() {
        val announcement = buildAnnouncementPayload()
        val allSlotIds = slotMatrix.getAllSlotPointIds()

        Log.i(TAG, "Broadcasting announcement to ${allSlotIds.size} slots (name='$displayName', creator=$isCreator, size=$groupSize)")

        var successCount = 0
        for ((index, slotPointId) in allSlotIds.withIndex()) {
            if (index == claimedSlotIndex) continue // Don't send to self

            try {
                controller.withTransport { transport ->
                    transport.sendMessage(
                        handle = slotPointId.toByteArray(Charsets.UTF_8),
                        payload = announcement
                    )
                }
                successCount++
            } catch (e: Exception) {
                // Non-fatal: some slots may not be occupied
                Log.v(TAG, "Announcement to slot $index failed (expected for unoccupied slots)")
            }

            // Micro-jitter between broadcasts (10-50ms) to avoid burst detection
            delay(10L + SecureRandom().nextLong() % 41)
        }

        Log.i(TAG, "Announcement broadcast complete ($successCount/${allSlotIds.size - 1} slots)")
    }

    /**
     * Build the v2 announcement payload with displayName, isCreator, and groupSize.
     */
    private fun buildAnnouncementPayload(): ByteArray {
        val pubKey = myPublicKey ?: throw IllegalStateException("No ephemeral key")
        val nonce = myNonce ?: throw IllegalStateException("No nonce generated")
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        val flags: Byte = if (isCreator) 0x01 else 0x00

        // v2 format: [1:version][2:slot][16:nonce][1:flags][1:groupSize][2:nameLen][N:name][pubKey]
        val totalSize = 1 + 2 + ANNOUNCEMENT_NONCE_SIZE + 1 + 1 + 2 + nameBytes.size + pubKey.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(0x02.toByte()) // Version 2
        buffer.putShort(claimedSlotIndex.toShort())
        buffer.put(nonce)
        buffer.put(flags)
        buffer.put(groupSize.toByte())
        buffer.putShort(nameBytes.size.toShort())
        buffer.put(nameBytes)
        buffer.put(pubKey)
        return buffer.array()
    }

    /**
     * Parse an announcement payload received from a peer.
     * Supports both v1 (legacy) and v2 (with displayName/creator/size) formats.
     */
    private fun parseAnnouncementPayload(data: ByteArray): PeerAnnouncement? {
        if (data.size < 2 + ANNOUNCEMENT_NONCE_SIZE + 1) return null

        return try {
            val buffer = ByteBuffer.wrap(data)
            val firstByte = buffer.get(0)

            if (firstByte == 0x02.toByte()) {
                // V2 format
                buffer.get() // consume version byte
                val slotIndex = buffer.short.toInt()
                val nonce = ByteArray(ANNOUNCEMENT_NONCE_SIZE)
                buffer.get(nonce)
                val flags = buffer.get()
                val peerIsCreator = (flags.toInt() and 0x01) != 0
                val peerGroupSize = buffer.get().toInt() and 0xFF
                val nameLen = buffer.short.toInt() and 0xFFFF
                val nameBytes = ByteArray(nameLen)
                buffer.get(nameBytes)
                val peerDisplayName = String(nameBytes, Charsets.UTF_8)
                val publicKey = ByteArray(buffer.remaining())
                buffer.get(publicKey)

                PeerAnnouncement(
                    slotIndex = slotIndex,
                    nonce = nonce,
                    publicKey = publicKey,
                    isSelf = false,
                    displayName = peerDisplayName,
                    isCreator = peerIsCreator
                ).also {
                    // If we're a joiner and this is a creator, adopt their group size
                    if (!this.isCreator && peerIsCreator && !groupSizeResolved) {
                        groupSize = peerGroupSize
                        groupSizeResolved = true
                        Log.i(TAG, "Joiner learned group size from creator: $groupSize")
                    }
                }
            } else {
                // V1 legacy format: [2:slot][16:nonce][pubKey]
                val slotIndex = buffer.short.toInt()
                val nonce = ByteArray(ANNOUNCEMENT_NONCE_SIZE)
                buffer.get(nonce)
                val publicKey = ByteArray(buffer.remaining())
                buffer.get(publicKey)

                PeerAnnouncement(
                    slotIndex = slotIndex,
                    nonce = nonce,
                    publicKey = publicKey,
                    isSelf = false
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse announcement", e)
            null
        }
    }

    /**
     * Poll the claimed slot for peer announcements until K members are discovered.
     */
    private suspend fun pollForPeers(): Boolean {
        val startTime = System.currentTimeMillis()

        // For joiners, groupSize starts at 0 and gets resolved when a creator's announcement arrives.
        // We keep polling until groupSize is resolved AND we have enough peers.
        while (!groupSizeResolved || discoveredPeers.size < groupSize) {
            if (System.currentTimeMillis() - startTime > DISCOVERY_TIMEOUT_MS) {
                if (!groupSizeResolved) {
                    Log.e(TAG, "Discovery timeout: never received a creator announcement to learn group size")
                } else {
                    Log.e(TAG, "Discovery timeout: found ${discoveredPeers.size}/$groupSize peers")
                }
                return false
            }

            val announcements = pollSlotForAnnouncements()
            for (announcement in announcements) {
                val hex = announcement.nonceHex
                if (hex !in discoveredPeers) {
                    discoveredPeers[hex] = announcement
                    _peerCount.value = discoveredPeers.size
                    val sizeDisplay = if (groupSizeResolved) "$groupSize" else "?"
                    Log.i(TAG, "Discovered peer ${discoveredPeers.size}/$sizeDisplay " +
                            "(slot ${announcement.slotIndex}, name='${announcement.displayName}', nonce ${hex.take(8)}...)")
                }
            }

            delay(POLL_INTERVAL_MS)
        }

        Log.i(TAG, "All $groupSize peers discovered!")
        return true
    }

    /**
     * Poll the currently connected claimed slot for incoming announcement messages.
     */
    private suspend fun pollSlotForAnnouncements(): List<PeerAnnouncement> {
        return try {
            // Use receiveMessage since we're connected to our claimed slot
            val message = controller.withTransport { transport ->
                transport.receiveMessage(timeoutMs = 500)
            }

            if (message != null) {
                val announcement = parseAnnouncementPayload(message.payload)
                if (announcement != null) listOf(announcement) else emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.v(TAG, "Poll error (non-fatal): ${e.message}")
            emptyList()
        }
    }

    /**
     * Lexicographic byte array comparator.
     */
    private object ByteArrayComparator : Comparator<ByteArray> {
        override fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (cmp != 0) return cmp
            }
            return a.size - b.size
        }
    }
}

/**
 * A peer's swarm announcement data.
 */
data class PeerAnnouncement(
    val slotIndex: Int,
    val nonce: ByteArray,
    val publicKey: ByteArray,
    val isSelf: Boolean,
    var memberIndex: Int = -1,
    val displayName: String = "Anonymous",
    val isCreator: Boolean = false
) {
    /** Hex representation of the nonce (used as map key). */
    val nonceHex: String = nonce.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerAnnouncement
        return nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int = nonce.contentHashCode()
}

/**
 * Quorum violation event — emitted when K+1 announcements are detected post-sealing.
 */
data class QuorumViolation(
    val expectedSize: Int,
    val actualSize: Int,
    val extraNonce: String
)

/**
 * Discovery state machine.
 */
enum class DiscoveryState {
    IDLE,
    SELECTING_SLOT,
    CLAIMING_SLOT,
    ANNOUNCING,
    POLLING,
    DISCOVERED,
    FAILED
}
