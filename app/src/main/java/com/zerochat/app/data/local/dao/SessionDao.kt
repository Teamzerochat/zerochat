package com.zerochat.app.data.local.dao

import androidx.room.*
import com.zerochat.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    
    @Query("SELECT * FROM sessions WHERE isActive = 1")
    fun getActiveSessions(): Flow<List<SessionEntity>>
    
    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): SessionEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)
    
    @Update
    suspend fun update(session: SessionEntity)
    
    @Query("UPDATE sessions SET isActive = 0 WHERE sessionId = :sessionId")
    suspend fun deactivateSession(sessionId: String)
    
    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
