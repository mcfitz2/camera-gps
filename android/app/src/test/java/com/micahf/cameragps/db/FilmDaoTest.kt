package com.micahf.cameragps.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FilmDaoTest {
    private lateinit var db: FilmDb
    private lateinit var dao: FilmDao

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FilmDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.film()
    }

    @After
    fun close() = db.close()

    private fun shot(seq: Long, takenAt: Long, bootId: Long = 1) =
        Frame(rollId = 0, number = 0, takenAt = takenAt, deviceBootId = bootId, deviceSeq = seq)

    @Test
    fun addShotsStartsAnUntitledRollWhenNoneIsLoaded() = runBlocking {
        val (roll, added) = dao.addShots(listOf(shot(0, 2_000), shot(1, 1_000)), "Untitled", now = 10)

        assertEquals("Untitled", roll.name)
        assertEquals(10L, roll.loadedAt)
        assertEquals(listOf(1, 2), added.map { it.number })
        assertEquals(1_000L, added.single { it.number == 1 }.takenAt)
    }

    @Test
    fun addShotsAppendsToTheLoadedRoll() = runBlocking {
        val rollId = dao.loadRoll(Roll(name = "A", loadedAt = 1))
        dao.addShots(listOf(shot(0, 100), shot(1, 200)), "Untitled", now = 5)
        dao.addShots(listOf(shot(2, 300)), "Untitled", now = 6)

        val frames = dao.framesNow(rollId)
        assertEquals(listOf(1, 2, 3), frames.map { it.number })
        assertTrue(frames.all { it.rollId == rollId })
    }

    @Test
    fun addShotsSkipsShotsAlreadyStored() = runBlocking {
        dao.addShots(listOf(shot(0, 100), shot(1, 200)), "Untitled", now = 5)
        val (_, added) = dao.addShots(listOf(shot(1, 200), shot(2, 300)), "Untitled", now = 5)

        assertEquals(1, added.size)
        assertEquals(2L, added.single().deviceSeq)
        assertEquals(3, added.single().number)

        val (_, addedOtherBoot) = dao.addShots(listOf(shot(0, 100, bootId = 2)), "Untitled", now = 5)
        assertEquals(1, addedOtherBoot.size)
    }

    @Test
    fun loadRollFinishesTheRollInTheCamera() = runBlocking {
        val a = dao.loadRoll(Roll(name = "A", loadedAt = 1))
        dao.loadRoll(Roll(name = "B", loadedAt = 5))

        assertEquals(5L, dao.rollNow(a)!!.finishedAt)
        assertEquals("B", dao.activeRoll()!!.name)
    }

    @Test
    fun insertBlankMovesLaterFramesUp() = runBlocking {
        val rollId = dao.loadRoll(Roll(name = "A", loadedAt = 1))
        dao.addShots(listOf(shot(0, 100), shot(1, 200), shot(2, 300)), "Untitled", now = 1)
        val oldNumberTwoId = dao.framesNow(rollId).single { it.number == 2 }.id

        dao.insertBlank(rollId, 2)

        val frames = dao.framesNow(rollId)
        assertEquals(listOf(1, 2, 3, 4), frames.map { it.number })
        assertEquals(null, frames.single { it.number == 2 }.takenAt)
        assertEquals(3, frames.single { it.id == oldNumberTwoId }.number)
    }

    @Test
    fun deleteMovesLaterFramesDown() = runBlocking {
        val rollId = dao.loadRoll(Roll(name = "A", loadedAt = 1))
        dao.addShots(listOf(shot(0, 100), shot(1, 200), shot(2, 300)), "Untitled", now = 1)
        val oldNumberThreeId = dao.framesNow(rollId).single { it.number == 3 }.id
        val toDelete = dao.framesNow(rollId).single { it.number == 2 }

        dao.delete(toDelete)

        val frames = dao.framesNow(rollId)
        assertEquals(listOf(1, 2), frames.map { it.number })
        assertEquals(2, frames.single { it.id == oldNumberThreeId }.number)
    }

    @Test
    fun addStockIgnoresNameInAnyCase() = runBlocking {
        val id = dao.addStock(Stock(name = "Zz Film", iso = 100))
        assertTrue(id > 0)

        val duplicateId = dao.addStock(Stock(name = "zz film", iso = 200))
        assertEquals(-1L, duplicateId)
    }

    @Test
    fun restoreDefaultStocksPutsBackDeletedDefaults() = runBlocking {
        dao.restoreDefaultStocks()
        val toDelete = dao.stocks().first().first { it.name == FilmStocks.defaults.first().name }
        dao.deleteStock(toDelete)

        dao.restoreDefaultStocks()

        assertEquals(FilmStocks.defaults.size, dao.stocks().first().size)
    }
}
