package com.ntq.training.momentbox.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationEntity::class,
        JournalEntryEntity::class,
        JournalAttachmentEntity::class,
        JournalTagEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(JournalTypeConverters::class)
abstract class AppDatabase : RoomDatabase(){
    abstract fun notificationDao(): NotificationDao
    abstract fun journalDao(): JournalDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN owner_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notifications ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            }
        }
        // Milestone M3: Bổ sung 3 bảng nhật ký, đính kèm và nhãn
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `journal_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `mood` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `is_pinned` INTEGER NOT NULL DEFAULT 0,
                        `word_count` INTEGER NOT NULL DEFAULT 0,
                        `photo_count` INTEGER NOT NULL DEFAULT 0,
                        `audio_count` INTEGER NOT NULL DEFAULT 0,
                        `deleted_at` INTEGER
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_entries_created_at` ON `journal_entries` (`created_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_entries_mood` ON `journal_entries` (`mood`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_entries_deleted_at` ON `journal_entries` (`deleted_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_entries_is_pinned` ON `journal_entries` (`is_pinned`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `journal_attachments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entry_id` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `local_path` TEXT NOT NULL,
                        `duration_ms` INTEGER NOT NULL DEFAULT 0,
                        `waveform` TEXT NOT NULL DEFAULT '[]',
                        `sort_order` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (`entry_id`) REFERENCES `journal_entries` (`id`) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_attachments_entry_id` ON `journal_attachments` (`entry_id`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `journal_tags` (
                        `entry_id` INTEGER NOT NULL,
                        `tag` TEXT NOT NULL,
                        PRIMARY KEY(`entry_id`, `tag`),
                        FOREIGN KEY (`entry_id`) REFERENCES `journal_entries` (`id`) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_tags_entry_id` ON `journal_tags` (`entry_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_tags_tag` ON `journal_tags` (`tag`)")
            }
        }
        private const val DATABASE_NAME = "momentbox.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
