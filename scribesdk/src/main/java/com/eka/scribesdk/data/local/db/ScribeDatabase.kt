package com.eka.scribesdk.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.eka.scribesdk.data.local.db.dao.AudioChunkDao
import com.eka.scribesdk.data.local.db.dao.SessionDao
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity

@Database(
    entities = [SessionEntity::class, AudioChunkEntity::class],
    version = 4,
    exportSchema = false
)
internal abstract class ScribeDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun audioChunkDao(): AudioChunkDao

    companion object {
        private const val DB_NAME = "eka_scribe_db"

        @Volatile
        private var INSTANCE: ScribeDatabase? = null

        /** Adds api_flow; keeps pending sessions/chunks instead of wiping the DB on upgrade. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scribe_session_table ADD COLUMN api_flow TEXT")
            }
        }

        fun getInstance(context: Context): ScribeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScribeDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
