package com.zerochat.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zerochat.app.data.local.entity.ZcapRatchetEntity

/**
 * DAO for [ZcapRatchetEntity].
 *
 * All writes use REPLACE so the ratchet state is always kept at the
 * most-recently-advanced version. The caller (ZcapStateManager) is
 * responsible for zeroizing the in-memory ByteArray after the insert.
 */
@Dao
interface ZcapRatchetDao {

    /**
     * Insert or replace the ratchet state for a peer.
     *
     * Call this immediately after a successful `zcap_send` or
     * `zcap_fetch_messages` to persist the advanced ratchet state.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: ZcapRatchetEntity)

    /**
     * Retrieve the current ratchet state for a peer, or null if no state
     * has been stored yet (first-send path).
     */
    @Query("SELECT * FROM zcap_ratchet_state WHERE peer_nym_address = :peerAddress LIMIT 1")
    suspend fun getRatchetState(peerAddress: String): ZcapRatchetEntity?

    /**
     * Delete the ratchet state for a peer — called on session termination
     * or panic-wipe. Complement with DB-level `secure_delete = ON`.
     */
    @Query("DELETE FROM zcap_ratchet_state WHERE peer_nym_address = :peerAddress")
    suspend fun deleteRatchetState(peerAddress: String)

    /**
     * Wipe all ratchet states — called during full app lock/wipe.
     */
    @Query("DELETE FROM zcap_ratchet_state")
    suspend fun deleteAll()
}
