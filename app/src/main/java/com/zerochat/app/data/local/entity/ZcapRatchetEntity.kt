package com.zerochat.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores the serialized Double Ratchet state for each peer.
 *
 * The serialized state blob is produced by the Rust `zcap_serialize_state`
 * function and must be zeroized from memory immediately after writing to DB.
 * The DB itself is encrypted by SQLCipher (AES-256-GCM), so the blob is
 * encrypted at rest.
 */
@Entity(tableName = "zcap_ratchet_state")
data class ZcapRatchetEntity(
    /** Nym address of the remote peer — used as the natural primary key. */
    @PrimaryKey
    @ColumnInfo(name = "peer_nym_address")
    val peerNymAddress: String,

    /**
     * Serialized, Zeroize-backed ratchet state returned by Rust.
     * Treat as opaque bytes — do not log or expose in UI.
     */
    @ColumnInfo(name = "serialized_state", typeAffinity = ColumnInfo.BLOB)
    val serializedState: ByteArray
) {
    // ByteArray equality requires manual override to avoid identity comparison.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ZcapRatchetEntity) return false
        return peerNymAddress == other.peerNymAddress &&
               serializedState.contentEquals(other.serializedState)
    }

    override fun hashCode(): Int {
        var result = peerNymAddress.hashCode()
        result = 31 * result + serializedState.contentHashCode()
        return result
    }
}
