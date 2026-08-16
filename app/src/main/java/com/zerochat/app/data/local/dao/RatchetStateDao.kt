package com.zerochat.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zerochat.app.data.local.entity.RatchetStateEntity

@Dao
interface RatchetStateDao {
    @Query("SELECT * FROM ratchet_states WHERE peer_id = :peerId")
    suspend fun getRatchetState(peerId: String): RatchetStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRatchetState(state: RatchetStateEntity)

    @Query("DELETE FROM ratchet_states WHERE peer_id = :peerId")
    suspend fun deleteRatchetState(peerId: String)
}
