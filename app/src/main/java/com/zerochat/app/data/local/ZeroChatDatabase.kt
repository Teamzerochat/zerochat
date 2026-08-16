package com.zerochat.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.zerochat.app.data.local.entity.MessageEntity
import com.zerochat.app.data.local.entity.RatchetStateEntity
import com.zerochat.app.data.local.entity.SessionEntity
import com.zerochat.app.data.local.entity.ZcapRatchetEntity
import com.zerochat.app.data.local.dao.MessageDao
import com.zerochat.app.data.local.dao.RatchetStateDao
import com.zerochat.app.data.local.dao.SessionDao
import com.zerochat.app.data.local.dao.ZcapRatchetDao

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
        SessionEntity::class,
        RatchetStateEntity::class,
        ZcapRatchetEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ZeroChatDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun sessionDao(): SessionDao
    abstract fun ratchetStateDao(): RatchetStateDao
    abstract fun zcapRatchetDao(): ZcapRatchetDao
    
    companion object {
        private const val DATABASE_NAME = "zerochat.db"

        @Volatile
        private var INSTANCE: ZeroChatDatabase? = null

        /**
         * Schema migration v1 → v2: adds the ZCAP ratchet state table.
         *
         * Using an explicit Migration instead of fallbackToDestructiveMigration
         * so existing messages and sessions survive the upgrade.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Legacy ratchet states table (pre-ZCAP, referenced by RatchetStateDao)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ratchet_states (
                        peer_id TEXT NOT NULL PRIMARY KEY,
                        ratchet_state BLOB NOT NULL
                    )
                    """.trimIndent()
                )
                // ZCAP ratchet state table (used by ZcapRatchetDao)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS zcap_ratchet_state (
                        peer_nym_address TEXT NOT NULL PRIMARY KEY,
                        serialized_state BLOB NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * RoomDatabase.Callback that configures SQLCipher PRAGMAs on every
         * connection open. These are session-scoped settings and must be
         * re-applied after every re-open.
         *
         * - `journal_mode = memory`   → no WAL/-shm/-journal files on disk.
         * - `cipher_memory_security = ON` → SQLCipher zeroes freed pages.
         * - `temp_store = memory`     → temp tables never touch the FS.
         * - `secure_delete = ON`      → overwrite deleted pages with zeros.
         */
        private val PRAGMA_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA journal_mode = memory")
                db.execSQL("PRAGMA cipher_memory_security = ON")
                db.execSQL("PRAGMA temp_store = memory")
                db.execSQL("PRAGMA secure_delete = ON")
            }
        }

        /**
         * Open database with SQLCipher encryption.
         *
         * @param context Application context
         * @param dbKey   Decrypted database key (from KeyManager)
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
                .addMigrations(MIGRATION_1_2)
                .addCallback(PRAGMA_CALLBACK)
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
