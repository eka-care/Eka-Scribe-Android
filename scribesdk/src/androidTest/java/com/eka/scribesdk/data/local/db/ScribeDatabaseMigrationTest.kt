package com.eka.scribesdk.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the v3 -> v4 upgrade (adds scribe_session_table.api_flow) keeps unfinished
 * sessions/chunks instead of hitting fallbackToDestructiveMigration().
 *
 * The v3 schema below is the exact DDL Room generated on `main` (exportSchema = false there,
 * so there is no JSON to feed MigrationTestHelper). Room itself validates the migrated tables
 * against the v4 entities when the DB is opened, so a wrong migration fails here.
 *
 * Run: ./gradlew :scribesdk:connectedDebugAndroidTest \
 *        -Pandroid.testInstrumentationRunnerArguments.class=com.eka.scribesdk.data.local.db.ScribeDatabaseMigrationTest
 */
@RunWith(AndroidJUnit4::class)
class ScribeDatabaseMigrationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration_test_db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Creates the database exactly as the v3 (main branch) app would have left it. */
    private fun createV3DatabaseWithPendingSession() {
        val path = context.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `scribe_session_table` (" +
                    "`session_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                    "`chunk_count` INTEGER NOT NULL, `mode` TEXT, `owner_id` TEXT, " +
                    "`metadata` TEXT, `upload_stage` TEXT NOT NULL, `session_metadata` TEXT, " +
                    "`folder_name` TEXT, `bid` TEXT, PRIMARY KEY(`session_id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `scribe_audio_chunk_table` (" +
                    "`chunk_id` TEXT NOT NULL, `session_id` TEXT NOT NULL, " +
                    "`chunk_index` INTEGER NOT NULL, `file_path` TEXT NOT NULL, " +
                    "`file_name` TEXT NOT NULL, `start_time_ms` INTEGER NOT NULL, " +
                    "`end_time_ms` INTEGER NOT NULL, `duration_ms` INTEGER NOT NULL, " +
                    "`upload_state` TEXT NOT NULL, `retry_count` INTEGER NOT NULL, " +
                    "`quality_score` REAL, `created_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`chunk_id`), " +
                    "FOREIGN KEY(`session_id`) REFERENCES `scribe_session_table`(`session_id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scribe_audio_chunk_table_session_id` " +
                    "ON `scribe_audio_chunk_table` (`session_id`)"
        )
        db.execSQL(
            "INSERT INTO scribe_session_table (session_id, created_at, updated_at, state, " +
                    "chunk_count, upload_stage, bid) " +
                    "VALUES ('legacy-session-1', 1000, 2000, 'RECORDING', 2, 'STOP', 'bid-1')"
        )
        db.execSQL(
            "INSERT INTO scribe_audio_chunk_table (chunk_id, session_id, chunk_index, file_path, " +
                    "file_name, start_time_ms, end_time_ms, duration_ms, upload_state, retry_count, " +
                    "created_at) VALUES ('legacy-session-1_0', 'legacy-session-1', 0, '/tmp/1.m4a', " +
                    "'1.m4a', 0, 20000, 20000, 'PENDING', 0, 1000)"
        )
        db.version = 3
        db.close()
    }

    private fun openV4() = Room.databaseBuilder(context, ScribeDatabase::class.java, dbName)
        .addMigrations(ScribeDatabase.MIGRATION_3_4)
        // Deliberately NOT adding fallbackToDestructiveMigration(): a bad migration must fail
        // loudly here instead of silently wiping the data.
        .build()

    @Test
    fun migrate3To4_keepsPendingSessionAndChunks_withNullApiFlow() = runBlocking {
        createV3DatabaseWithPendingSession()

        val db = openV4()
        try {
            val session = db.sessionDao().getById("legacy-session-1")
            assertNotNull("pending session must survive the upgrade", session)
            assertEquals("STOP", session!!.uploadStage)
            assertEquals("bid-1", session.bid)
            assertNull("legacy sessions must keep api_flow = null (S3 flow)", session.apiFlow)

            val chunks = db.audioChunkDao().getBySession("legacy-session-1")
            assertEquals(1, chunks.size)
            assertEquals("PENDING", chunks[0].uploadState)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate3To4_newSessionCanStoreApiFlow() = runBlocking {
        createV3DatabaseWithPendingSession()

        val db = openV4()
        try {
            val old = db.sessionDao().getById("legacy-session-1")!!
            db.sessionDao().insert(
                old.copy(sessionId = "a-new-session", apiFlow = "sessions_v1")
            )
            assertEquals("sessions_v1", db.sessionDao().getById("a-new-session")!!.apiFlow)
            assertNull(db.sessionDao().getById("legacy-session-1")!!.apiFlow)
        } finally {
            db.close()
        }
    }
}
