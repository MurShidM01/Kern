package com.aliedu.kern.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aliedu.kern.core.data.local.dao.CredentialRecordDao
import com.aliedu.kern.core.data.local.dao.LocalPreferenceDao
import com.aliedu.kern.core.data.local.dao.MessageDao
import com.aliedu.kern.core.data.local.dao.PendingPatchDao
import com.aliedu.kern.core.data.local.dao.ProviderProfileDao
import com.aliedu.kern.core.data.local.dao.SessionDao
import com.aliedu.kern.core.data.local.dao.TodoDao
import com.aliedu.kern.core.data.local.dao.ToolEventDao
import com.aliedu.kern.core.data.local.dao.WorkspaceDao
import com.aliedu.kern.core.data.local.entity.CredentialRecordEntity
import com.aliedu.kern.core.data.local.entity.LocalPreferenceEntity
import com.aliedu.kern.core.data.local.entity.MessageEntity
import com.aliedu.kern.core.data.local.entity.PendingPatchEntity
import com.aliedu.kern.core.data.local.entity.ProviderProfileEntity
import com.aliedu.kern.core.data.local.entity.SessionEntity
import com.aliedu.kern.core.data.local.entity.TodoEntity
import com.aliedu.kern.core.data.local.entity.ToolEventEntity
import com.aliedu.kern.core.data.local.entity.WorkspaceEntity

@Database(
    entities = [
        WorkspaceEntity::class,
        ProviderProfileEntity::class,
        CredentialRecordEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        ToolEventEntity::class,
        PendingPatchEntity::class,
        LocalPreferenceEntity::class,
        TodoEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class KernDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun providerProfileDao(): ProviderProfileDao
    abstract fun credentialRecordDao(): CredentialRecordDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun toolEventDao(): ToolEventDao
    abstract fun pendingPatchDao(): PendingPatchDao
    abstract fun localPreferenceDao(): LocalPreferenceDao
    abstract fun todoDao(): TodoDao

    companion object {
        const val DATABASE_NAME = "kern.db"

        /** v2 adds the per-message reasoning column and the agent task-list table (FR-018). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `todos` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_todos_sessionId` ON `todos` (`sessionId`)"
                )
            }
        }

        /**
         * v3 records the real `/storage/emulated/0/...` path of a folder workspace, so Files, Editor,
         * Agent tools and the Alpine terminal can share one tree once all-files access is granted.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workspaces` ADD COLUMN `realPath` TEXT")
            }
        }

        /** v4 keeps the line counts of file-editing tools so their rows can show +N / -M. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tool_events` ADD COLUMN `linesAdded` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `tool_events` ADD COLUMN `linesRemoved` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 keeps the diff a file-editing tool applied, so the transcript can show the change it
         * made instead of the tool-call JSON. Old rows keep a null diff and fall back to that JSON.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tool_events` ADD COLUMN `diff` TEXT")
            }
        }
    }
}
