package com.offgrid.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        FileTransferEntity::class,
        RelayQueueEntity::class,
        RouteEntity::class,
        DiagnosticLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun relayQueueDao(): RelayQueueDao
    abstract fun routeDao(): RouteDao
    abstract fun diagnosticDao(): DiagnosticDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN messageType TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentJson TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offgrid_local.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
