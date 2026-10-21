package com.aliedu.kern.core.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates the v1 -> v2 migration (PRD 14.2): existing messages survive and gain a nullable
 * reasoning column, and the agent task-list table + index are created. The migration SQL is written
 * to match Room's generated schema (`schemas/.../2.json`).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    private val v1Messages =
        "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
            "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, " +
            "`providerMessageId` TEXT, `createdAt` INTEGER NOT NULL, `seq` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"

    @Test
    fun `migration 1 to 2 adds reasoning and the todos table`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `sessions` (`id` TEXT NOT NULL, " +
                                "`workspaceId` TEXT, `providerProfileId` TEXT, `title` TEXT NOT NULL, " +
                                "`status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                                "`updatedAt` INTEGER NOT NULL, `lastEventAt` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`id`))"
                        )
                        db.execSQL(v1Messages)
                        db.execSQL(
                            "INSERT INTO sessions VALUES ('s1', NULL, NULL, 't', 'IDLE', 0, 0, 0)"
                        )
                        db.execSQL(
                            "INSERT INTO messages VALUES ('m1', 's1', 'ASSISTANT', 'hi', " +
                                "'COMPLETE', NULL, 0, 0)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        val db = helper.writableDatabase
        assertThat(columnNames(db, "messages")).doesNotContain("reasoning")

        KernDatabase.MIGRATION_1_2.migrate(db)

        // The existing row survives and the new column is nullable.
        db.query("SELECT content, reasoning FROM messages WHERE id = 'm1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("hi")
            assertThat(c.isNull(1)).isTrue()
        }
        assertThat(columnNames(db, "todos")).containsExactly(
            "id", "sessionId", "content", "status", "orderIndex", "createdAt", "updatedAt"
        )
        db.query("PRAGMA index_list(`todos`)").use { c ->
            val names = buildList { while (c.moveToNext()) add(c.getString(1)) }
            assertThat(names).contains("index_todos_sessionId")
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 2 to 3 adds the nullable realPath column`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)

        val v2Workspaces =
            "CREATE TABLE IF NOT EXISTS `workspaces` (`id` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `uriString` TEXT, " +
                "`localPath` TEXT, `grantState` TEXT NOT NULL, `lastOpenedAt` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v2Workspaces)
                        db.execSQL(
                            "INSERT INTO workspaces VALUES ('w1', 'Proj', 'SELECTED_FOLDER', " +
                                "'content://tree/abc', NULL, 'VALID', 0, 0)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        val db = helper.writableDatabase
        assertThat(columnNames(db, "workspaces")).doesNotContain("realPath")

        KernDatabase.MIGRATION_2_3.migrate(db)

        // The existing row survives and the new column is nullable.
        assertThat(columnNames(db, "workspaces")).contains("realPath")
        db.query("SELECT displayName, realPath FROM workspaces WHERE id = 'w1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Proj")
            assertThat(c.isNull(1)).isTrue()
        }

        helper.close()
        context.deleteDatabase(dbName)
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
}
