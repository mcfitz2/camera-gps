package com.micahf.cameragps.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A roll of film. The newest unfinished roll is the one in the camera. */
@Entity(tableName = "roll")
data class Roll(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val stock: String? = null,
    val iso: Int? = null,
    /** Exposures on the roll; frames past this still get logged. */
    val capacity: Int = 36,
    val loadedAt: Long,
    val finishedAt: Long? = null,
)

/**
 * One exposure. Frames from the shutter device carry its (boot id, seq) so a
 * shot delivered twice is stored once; frames added by hand have neither.
 */
@Entity(
    tableName = "frame",
    foreignKeys = [
        ForeignKey(entity = Roll::class, parentColumns = ["id"], childColumns = ["rollId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("rollId", "number"),
        Index("deviceBootId", "deviceSeq", unique = true),
    ],
)
data class Frame(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rollId: Long,
    /** Position on the roll, from 1. */
    val number: Int,
    /** When the shutter fired; null for a blank frame added by hand. */
    val takenAt: Long?,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val altM: Double? = null,
    /** Neighbourhood or town from reverse geocoding, e.g. "Wicker Park". */
    val place: String? = null,
    /** The location was taken well after the shot, so may be off. */
    val approximate: Boolean = false,
    /** Exposure length for long (bulb) exposures. */
    val exposureMs: Long? = null,
    val deviceBootId: Long? = null,
    val deviceSeq: Long? = null,
    val note: String? = null,
)

/** A roll with its frame count, for lists. */
data class RollSummary(
    val id: Long,
    val name: String,
    val stock: String?,
    val iso: Int?,
    val capacity: Int,
    val loadedAt: Long,
    val finishedAt: Long?,
    @ColumnInfo(name = "frames") val frames: Int,
)
