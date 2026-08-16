package com.zerochat.app.domain.group

import android.util.Log
import com.zerochat.app.domain.transport.TransportController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group Manager — Top-Level Group Session Coordinator
 *
 * Orchestrates the full lifecycle of a group chat session:
 *
 * State Machine:
 *   IDLE → PROBING → CLAIMED → ANNOUNCING → SEALING → SEALED → ACTIVE → TERMINATED
 *
 * Lifecycle:
 * 1. User enters 6-digit code + group size K.
 * 2. GroupSlotMatrix derives 50 virtual slots.
 * 3. GroupDiscoveryManager claims a slot, broadcasts, and collects K peers.
 * 4. GroupCryptoManager derives K_group from sorted ephemeral public keys.
 * 5. Key confirmation MACs exchanged and verified.
 * 6. Group SEALED — shared code zeroized.
 * 7. GroupMessageQueue starts fan-out egress + cover traffic.
 * 8. Continuous Swarm Guard monitors for K+1 infiltration.
 *
 * Isolation: This class has ZERO coupling with 1:1 domain code.
 * It only depends on the shared TransportController API.
 */
@Singleton
class GroupManager @Inject constructor(
    private val controller: TransportController
) {
    companion object {
        private const val TAG = "GroupManager"
    }

    // Session state
    private val _state = MutableStateFlow<GroupSessionState>(GroupSessionState.Idle)
    val state: StateFlow<GroupSessionState> = _state.asStateFlow()

    // Sub-managers (created per-session, torn down on end)
    private var slotMatrix: GroupSlotMatrix? = null
    private var discoveryManager: GroupDiscoveryManager? = null
    private var cryptoManager: GroupCryptoManager? = null
    private var messageQueue: GroupMessageQueue? = null

    // Session coroutine scope
    private var sessionScope: CoroutineScope? = null
    private var sessionJob: Job? = null

    // Session metadata
    private var groupSize: Int = 0
    private var myMemberIndex: Int = -1
    private var sasWords: List<String> = emptyList()
    private var displayNameMap: Map<Int, String> = emptyMap()
    private var myDisplayName: String = ""

    /**
     * Start a new group session.
     *
     * Executes the full Phase 1-7 lifecycle asynchronously.
     * State transitions are emitted through the [state] flow.
     *
     * @param sharedSecret The 6-digit code entered by all group members
     * @param memberCount The expected number of group members (K ≤ 10), or 0 for joiners
     * @param displayName The user’s chosen display name
     * @param isCreator Whether this user is creating (true) or joining (false) the group
     */
    fun startSession(sharedSecret: String, memberCount: Int, displayName: String = "Anonymous", isCreator: Boolean = true) {
        if (_state.value != GroupSessionState.Idle &&
            _state.value !is GroupSessionState.Terminated &&
            _state.value !is GroupSessionState.SecurityViolation) {
            Log.w(TAG, "Cannot start session in state: ${_state.value}")
            return
        }

        require(sharedSecret.length >= 6) { "Shared secret must be at least 6 characters" }
        if (isCreator) {
            require(memberCount in 2..10) { "Group size must be between 2 and 10" }
        }

        groupSize = memberCount
        myDisplayName = displayName

        // Create a new coroutine scope for this session
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        sessionJob = sessionScope!!.launch {
            try {
                executeSessionLifecycle(sharedSecret, memberCount, displayName, isCreator)
            } catch (e: CancellationException) {
                Log.i(TAG, "Session cancelled")
                _state.value = GroupSessionState.Terminated("Session cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Session failed: ${e.message}", e)
                _state.value = GroupSessionState.Failed(e.message ?: "Unknown error")
                emergencyZeroize()
            }
        }
    }

    /**
     * Send a chat message to all group members.
     */
    suspend fun sendMessage(text: String): Boolean {
        if (_state.value != GroupSessionState.Active) {
            Log.w(TAG, "Cannot send message in state: ${_state.value}")
            return false
        }
        return messageQueue?.sendMessage(text) ?: false
    }

    /**
     * Get the incoming messages flow for the UI.
     */
    fun getIncomingMessages() = messageQueue?.incomingMessages

    /**
     * Get the SAS verification words.
     */
    fun getSasWords(): List<String> = sasWords

    /**
     * Get the current member count discovered so far.
     */
    fun getDiscoveredPeerCount() = discoveryManager?.peerCount

    /**
     * Get this device's member index.
     */
    fun getMyMemberIndex(): Int = myMemberIndex

    /**
     * Get the group size (K).
     */
    fun getGroupSize(): Int = groupSize

    /**
     * Get the display name map (memberIndex → displayName).
     */
    fun getDisplayNameMap(): Map<Int, String> = displayNameMap

    /**
     * Get this user's display name.
     */
    fun getMyDisplayName(): String = myDisplayName

    /**
     * Terminate the current group session.
     */
    fun terminateSession() {
        Log.i(TAG, "Terminating group session")
        _state.value = GroupSessionState.Terminated("User terminated")
        emergencyZeroize()
    }

    /**
     * Reset to idle state (after termination).
     */
    fun reset() {
        if (_state.value is GroupSessionState.Idle) return
        emergencyZeroize()
        _state.value = GroupSessionState.Idle
    }

    // --- Private Lifecycle ---

    /**
     * Execute the full group session lifecycle (Phases 1-7).
     */
    private suspend fun executeSessionLifecycle(
        sharedSecret: String,
        memberCount: Int,
        displayName: String = "Anonymous",
        isCreator: Boolean = true
    ) {
        val epoch = System.currentTimeMillis() / (24L * 60L * 60L * 1000L) // 24-hour epochs

        // --- Phase 1-2: Slot Matrix Initialization ---
        _state.value = GroupSessionState.Probing
        Log.i(TAG, "Phase 1: Initializing slot matrix (M=50, epoch=$epoch)")

        slotMatrix = GroupSlotMatrix(sharedSecret, epoch)
        cryptoManager = GroupCryptoManager()
        discoveryManager = GroupDiscoveryManager(
            controller = controller,
            slotMatrix = slotMatrix!!,
            groupSize = memberCount,
            displayName = displayName,
            isCreator = isCreator
        )

        // --- Phase 1: Generate ephemeral keys ---
        val myPublicKey = cryptoManager!!.generateEphemeralKeys()

        // --- Phase 2-4: Slot Claiming & Discovery ---
        _state.value = GroupSessionState.Claiming
        Log.i(TAG, "Phase 2-4: Claiming slot and discovering peers")

        // Reset transport for fresh session
        controller.resetForNewSession()
        controller.withTransport { it.connect("") }

        val sortedMembers = discoveryManager!!.executeDiscovery(myPublicKey)
        if (sortedMembers == null) {
            _state.value = GroupSessionState.Failed("Discovery failed")
            return
        }

        myMemberIndex = discoveryManager!!.getMyMemberIndex()
        groupSize = discoveryManager!!.getResolvedGroupSize() // Update in case joiner learned it
        displayNameMap = discoveryManager!!.getDisplayNameMap()
        _state.value = GroupSessionState.Announcing(discoveredCount = sortedMembers.size)

        // --- Phase 5-6: Sealed G-PAKE Key Agreement ---
        _state.value = GroupSessionState.Sealing
        Log.i(TAG, "Phase 5-6: Deriving group key from ${sortedMembers.size} public keys")

        val allPublicKeys = sortedMembers.map { it.publicKey }
        val keyDerived = cryptoManager!!.deriveGroupKey(allPublicKeys)
        if (!keyDerived) {
            _state.value = GroupSessionState.Failed("Key derivation failed")
            return
        }

        // Derive SAS for visual verification
        sasWords = cryptoManager!!.deriveSAS()
        Log.i(TAG, "SAS verification: ${sasWords.joinToString(" - ")}")

        // Cache peer slot point IDs before zeroizing the slot matrix
        val peerSlotPointIds = sortedMembers
            .filter { !it.isSelf }
            .map { slotMatrix!!.getSlotPointId(it.slotIndex) }

        // Zeroize the slot matrix seeds from volatile memory
        slotMatrix!!.zeroize()

        _state.value = GroupSessionState.Sealed(sasWords = sasWords)
        Log.i(TAG, "Group SEALED with ${sortedMembers.size} members")

        // --- Phase 7: Active Messaging ---
        messageQueue = GroupMessageQueue(
            controller = controller,
            cryptoManager = cryptoManager!!,
            myMemberIndex = myMemberIndex,
            memberCount = memberCount,
            peerSlotPointIds = peerSlotPointIds
        )

        messageQueue!!.start(sessionScope!!, discoveryManager!!.getClaimedPointId())

        // Start Continuous Swarm Guard
        discoveryManager!!.startSwarmGuard(sessionScope!!)

        // Monitor for quorum violations
        sessionScope!!.launch {
            discoveryManager!!.quorumViolation.collect { violation ->
                Log.e(TAG, "SECURITY VIOLATION: Quorum overflow " +
                        "(expected=${violation.expectedSize}, actual=${violation.actualSize})")
                _state.value = GroupSessionState.SecurityViolation(
                    reason = "Late participant detected. Possible imposter/race condition. " +
                            "Session terminated immediately.",
                    expectedMembers = violation.expectedSize,
                    actualMembers = violation.actualSize
                )
                emergencyZeroize()
            }
        }

        _state.value = GroupSessionState.Active
        Log.i(TAG, "Phase 7: Group chat ACTIVE (${groupSize} members, index=$myMemberIndex, name='$displayName')")
    }

    /**
     * Emergency zeroization — wipe all cryptographic material and tear down resources.
     */
    private fun emergencyZeroize() {
        Log.i(TAG, "Emergency zeroization triggered")

        messageQueue?.stop()
        messageQueue = null

        discoveryManager?.teardown()
        discoveryManager = null

        cryptoManager?.zeroize()
        cryptoManager = null

        slotMatrix?.zeroize()
        slotMatrix = null

        sessionJob?.cancel()
        sessionScope?.cancel()
        sessionScope = null
        sessionJob = null

        myMemberIndex = -1
        sasWords = emptyList()

        // Disconnect transport (shared resource — only disconnect our rendezvous)
        try {
            // Fire-and-forget cleanup; can't suspend in zeroize
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    controller.withTransport { it.disconnectAllRendezvous() }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        Log.i(TAG, "Zeroization complete")
    }
}

/**
 * Group Session State — Sealed class for UI state machine.
 *
 * Independent from the 1:1 ConnectionState to maintain isolation.
 */
sealed class GroupSessionState {
    /** No active group session. */
    object Idle : GroupSessionState()

    /** Initializing slot matrix and probing network. */
    object Probing : GroupSessionState()

    /** Claiming a virtual slot in the matrix. */
    object Claiming : GroupSessionState()

    /** Broadcasting and collecting peer announcements. */
    data class Announcing(val discoveredCount: Int) : GroupSessionState()

    /** Deriving group key via multi-party DH. */
    object Sealing : GroupSessionState()

    /** Group sealed — key derived, SAS available for verification. */
    data class Sealed(val sasWords: List<String>) : GroupSessionState()

    /** Group chat active — messages can be sent and received. */
    object Active : GroupSessionState()

    /** Session failed (non-security). */
    data class Failed(val reason: String) : GroupSessionState()

    /** Session terminated normally. */
    data class Terminated(val reason: String) : GroupSessionState()

    /**
     * SECURITY VIOLATION — Quorum overflow (K+1) detected.
     * UI must display an emergency security alert and prevent further interaction.
     */
    data class SecurityViolation(
        val reason: String,
        val expectedMembers: Int,
        val actualMembers: Int
    ) : GroupSessionState()
}
