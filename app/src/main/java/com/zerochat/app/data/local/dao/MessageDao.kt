package com.zerochat.app.data.local.dao

import androidx.room.*
import com.zerochat.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<MessageEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long
    
    @Delete
    suspend fun delete(message: MessageEntity)
    
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteSessionMessages(sessionId: String)
    
    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
