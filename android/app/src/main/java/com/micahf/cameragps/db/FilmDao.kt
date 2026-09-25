package com.micahf.cameragps.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FilmDao {
    @Query(
        """SELECT roll.*, (SELECT COUNT(*) FROM frame WHERE frame.rollId = roll.id) AS frames
           FROM roll ORDER BY loadedAt DESC, id DESC""",
    )
    abstract fun rolls(): Flow<List<RollSummary>>

    @Query("SELECT * FROM roll WHERE id = :id")
    abstract fun roll(id: Long): Flow<Roll?>

    @Query("SELECT * FROM frame WHERE rollId = :rollId ORDER BY number")
    abstract fun frames(rollId: Long): Flow<List<Frame>>

    /** Film stocks used before, most recent first, for suggestions. */
    @Query(
        """SELECT stock FROM roll WHERE stock IS NOT NULL AND stock != ''
           GROUP BY stock ORDER BY MAX(loadedAt) DESC LIMIT 6""",
    )
    abstract fun recentStocks(): Flow<List<String>>

    @Query("UPDATE frame SET place = :place WHERE id IN (:ids)")
    abstract suspend fun setPlace(ids: List<Long>, place: String)

    @Query("SELECT * FROM frame WHERE rollId = :rollId ORDER BY number")
    abstract suspend fun framesNow(rollId: Long): List<Frame>

    @Query("SELECT * FROM roll WHERE id = :id")
    abstract suspend fun rollNow(id: Long): Roll?

    /** The roll in the camera: the newest one not yet finished. */
    @Query("SELECT * FROM roll WHERE finishedAt IS NULL ORDER BY loadedAt DESC, id DESC LIMIT 1")
    abstract suspend fun activeRoll(): Roll?

    @Insert
    abstract suspend fun insert(roll: Roll): Long

    @Update
    abstract suspend fun update(roll: Roll)

    /** Deletes the roll and its frames. */
    @Delete
    abstract suspend fun delete(roll: Roll)

    @Insert
    abstract suspend fun insert(frame: Frame): Long

    @Update
    abstract suspend fun update(frame: Frame)

    @Delete
    protected abstract suspend fun deleteRow(frame: Frame)

    @Query("SELECT COUNT(*) FROM frame WHERE rollId = :rollId")
    abstract suspend fun frameCount(rollId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM frame WHERE deviceBootId = :bootId AND deviceSeq = :seq)")
    protected abstract suspend fun hasShot(bootId: Long, seq: Long): Boolean

    @Query("SELECT COALESCE(MAX(number), 0) FROM frame WHERE rollId = :rollId")
    protected abstract suspend fun lastNumber(rollId: Long): Int

    @Query("UPDATE frame SET number = number + :by WHERE rollId = :rollId AND number >= :from")
    protected abstract suspend fun shift(rollId: Long, from: Int, by: Int)

    /** Takes the roll out of the camera; new shots start an untitled roll. */
    @Query("UPDATE roll SET finishedAt = :at WHERE id = :id")
    abstract suspend fun finish(id: Long, at: Long)

    @Query("UPDATE roll SET finishedAt = :at WHERE finishedAt IS NULL")
    protected abstract suspend fun finishAll(at: Long)

    /**
     * Stores shots from the device as the next frames of the roll in the
     * camera, skipping ones already stored. Starts a roll if none is loaded.
     *
     * @return the roll and the frames added.
     */
    @Transaction
    open suspend fun addShots(shots: List<Frame>, untitledName: String, now: Long): Pair<Roll, List<Frame>> {
        val roll = activeRoll() ?: Roll(name = untitledName, loadedAt = now).let { it.copy(id = insert(it)) }
        var number = lastNumber(roll.id)
        val added = shots
            .filter { hasShot(it.deviceBootId!!, it.deviceSeq!!).not() }
            .sortedBy { it.takenAt }
            .map { shot ->
                val frame = shot.copy(rollId = roll.id, number = ++number)
                frame.copy(id = insert(frame))
            }
        return roll to added
    }

    /** Finishes the loaded roll (if any) and loads [roll]. */
    @Transaction
    open suspend fun loadRoll(roll: Roll): Long {
        finishAll(roll.loadedAt)
        return insert(roll)
    }

    /** Inserts an empty frame at [number], moving later frames up one. */
    @Transaction
    open suspend fun insertBlank(rollId: Long, number: Int) {
        shift(rollId, number, 1)
        insert(Frame(rollId = rollId, number = number, takenAt = null))
    }

    /** Deletes a frame, moving later frames down one. */
    @Transaction
    open suspend fun delete(frame: Frame) {
        deleteRow(frame)
        shift(frame.rollId, frame.number + 1, -1)
    }
}
