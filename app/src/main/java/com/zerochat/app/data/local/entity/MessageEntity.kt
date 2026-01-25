package com.zerochat.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Message Entity - Stored in encrypted SQLCipher database
 * 
 * Note: Messages are ephemeral - cleared on session end
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val sessionId: String,
    val content: String,  // Already decrypted by app layer
    val timestamp: Long,
    val isOutgoing: Boolean,
    val status: MessageStatus = MessageStatus.SENT
)

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,  // Note: We don't actually track this for privacy
    FAILED
}
