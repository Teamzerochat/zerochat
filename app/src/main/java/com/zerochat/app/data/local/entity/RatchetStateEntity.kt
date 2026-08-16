package com.zerochat.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores the serialized Double Ratchet state for each peer.
 *
 * This entity was previously referenced as `RatchetStateEntity` by
 * `RatchetStateDao`. It is superseded by `ZcapRatchetEntity` (table:
 * `zcap_ratchet_state`) which is the canonical ZCAP entity. This class
 * is kept to satisfy `RatchetStateDao`'s existing references and will
 * be merged into `ZcapRatchetEntity` in a future migration.
 */
@Entity(tableName = "ratchet_states")
data class RatchetStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_id")
    val peerId: String,

    @ColumnInfo(name = "ratchet_state", typeAffinity = ColumnInfo.BLOB)
    val ratchetState: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RatchetStateEntity) return false
        return peerId == other.peerId && ratchetState.contentEquals(other.ratchetState)
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + ratchetState.contentHashCode()
        return result
    }
}
