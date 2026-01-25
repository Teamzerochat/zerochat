package com.zerochat.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.zerochat.app.data.local.entity.MessageEntity
import com.zerochat.app.data.local.entity.SessionEntity
import com.zerochat.app.data.local.dao.MessageDao
import com.zerochat.app.data.local.dao.SessionDao

/**
 * SQLCipher Encrypted Database
 * 
 * Security:
 * - AES-256-GCM encryption at rest
 * - Key derived from passphrase (not stored)
 * - All data encrypted including schema
 */
@Database(
    entities = [
        MessageEntity::class,
        SessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ZeroChatDatabase : RoomDatabase() {
    
    abstract fun messageDao(): MessageDao
    abstract fun sessionDao(): SessionDao
    
    companion object {
        private const val DATABASE_NAME = "zerochat.db"
        
        @Volatile
        private var INSTANCE: ZeroChatDatabase? = null
        
        /**
         * Open database with SQLCipher encryption
         * 
         * @param context Application context
         * @param dbKey Decrypted database key (from KeyManager)
         */
        fun getInstance(context: Context, dbKey: ByteArray): ZeroChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, dbKey).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context, dbKey: ByteArray): ZeroChatDatabase {
            // Load SQLCipher native library
            System.loadLibrary("sqlcipher")
            
            // SQLCipher factory with our key (single-arg constructor)
            val factory = SupportOpenHelperFactory(dbKey)
            
            return Room.databaseBuilder(
                context.applicationContext,
                ZeroChatDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
        
        /**
         * Close and clear database instance
         * Called when locking the app
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
