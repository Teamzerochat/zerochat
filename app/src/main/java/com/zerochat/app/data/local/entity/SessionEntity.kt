package com.zerochat.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Session Entity - Temporary session info
 * 
 * Note: Session data is ephemeral - not persisted across app runs
 * Only stored for current active sessions
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val sessionId: String,
    
    val peerNymAddress: String,
    val createdAt: Long,
    val lastActivityAt: Long,
    
    // Session ratchet state is stored in memory only, not here
    // This just tracks session metadata
    val isActive: Boolean = true
)
