package com.eka.scribesdk.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.eka.scribesdk.data.local.db.dao.AudioChunkDao
import com.eka.scribesdk.data.local.db.dao.SessionDao
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity

@Database(
    entities = [SessionEntity::class, AudioChunkEntity::class],
    version = 3,
    exportSchema = false
)
internal abstract class ScribeDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun audioChunkDao(): AudioChunkDao

    companion object {
        private const val DB_NAME = "eka_scribe_db"

        @Volatile
        private var INSTANCE: ScribeDatabase? = null

        fun getInstance(context: Context): ScribeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScribeDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
