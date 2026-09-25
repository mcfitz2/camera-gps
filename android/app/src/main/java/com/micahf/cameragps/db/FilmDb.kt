package com.micahf.cameragps.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Film rolls and their frames. */
@Database(
    entities = [Roll::class, Frame::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class FilmDb : RoomDatabase() {
    abstract fun film(): FilmDao

    companion object {
        @Volatile private var instance: FilmDb? = null

        fun get(context: Context): FilmDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FilmDb::class.java, "film.db")
                .build()
                .also { instance = it }
        }
    }
}
