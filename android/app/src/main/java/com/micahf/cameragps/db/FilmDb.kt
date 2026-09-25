package com.micahf.cameragps.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** Film rolls and their frames. */
@Database(
    entities = [Roll::class, Frame::class, Stock::class],
    version = 3,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = FilmDb.AddStocks::class),
    ],
)
abstract class FilmDb : RoomDatabase() {
    abstract fun film(): FilmDao

    /** Fills the new stock list with the defaults and the stocks rolls already use. */
    class AddStocks : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            FilmStocks.insertDefaults(db)
            db.execSQL(
                """INSERT OR IGNORE INTO stock (name, iso)
                   SELECT stock, MAX(iso) FROM roll WHERE stock IS NOT NULL AND stock != '' GROUP BY stock""",
            )
        }
    }

    companion object {
        @Volatile private var instance: FilmDb? = null

        fun get(context: Context): FilmDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FilmDb::class.java, "film.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = FilmStocks.insertDefaults(db)
                })
                .build()
                .also { instance = it }
        }
    }
}
