package com.micahf.cameragps.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Creates [name] as Room would have at schema [version], from the exported schema. */
    private fun createAt(version: Int, name: String): SQLiteDatabase {
        val schema = File("schemas/com.micahf.cameragps.db.FilmDb/$version.json")
        val database = JSONObject(schema.readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = database.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    private fun openRoom(name: String): FilmDb =
        Room.databaseBuilder(context, FilmDb::class.java, name).allowMainThreadQueries().build()

    @Test
    fun migratesFromVersion1KeepingRollsAndFrames() = runBlocking {
        val raw = createAt(1, "v1.db")
        raw.execSQL(
            "INSERT INTO roll (id, name, stock, iso, capacity, loadedAt) " +
                "VALUES (1, 'Old roll', 'Zz Test Stock', 250, 36, 100)",
        )
        raw.execSQL("INSERT INTO frame (rollId, number, takenAt, approximate) VALUES (1, 1, 1000, 0)")
        raw.close()

        val room = openRoom("v1.db")
        try {
            assertEquals("Old roll", room.film().rollNow(1)!!.name)
            assertEquals(null, room.film().framesNow(1).single().place)

            val stocks = room.film().stocks().first()
            val stock = stocks.single { it.name == "Zz Test Stock" }
            assertEquals(250, stock.iso)
            assertEquals(FilmStocks.defaults.size + 1, stocks.size)
        } finally {
            room.close()
        }
    }

    @Test
    fun migratesFromVersion2KeepingPlaces() = runBlocking {
        val raw = createAt(2, "v2.db")
        raw.execSQL("INSERT INTO roll (id, name, capacity, loadedAt) VALUES (1, 'Old roll', 36, 100)")
        raw.execSQL(
            "INSERT INTO frame (rollId, number, takenAt, place, approximate) VALUES (1, 1, 1000, 'Loop', 0)",
        )
        raw.close()

        val room = openRoom("v2.db")
        try {
            assertEquals("Loop", room.film().framesNow(1).single().place)
        } finally {
            room.close()
        }
    }

    @Test
    fun stockFromOldRollsKeepsHighestIso() = runBlocking {
        val raw = createAt(2, "v2.db")
        raw.execSQL(
            "INSERT INTO roll (id, name, stock, iso, capacity, loadedAt) " +
                "VALUES (1, 'Roll A', 'Zz Dup', 100, 36, 100)",
        )
        raw.execSQL(
            "INSERT INTO roll (id, name, stock, iso, capacity, loadedAt) " +
                "VALUES (2, 'Roll B', 'Zz Dup', 400, 36, 200)",
        )
        raw.close()

        val room = openRoom("v2.db")
        try {
            val stocks = room.film().stocks().first().filter { it.name == "Zz Dup" }
            assertTrue(stocks.size == 1)
            assertEquals(400, stocks.single().iso)
        } finally {
            room.close()
        }
    }
}
