package com.micahf.cameragps

import com.micahf.cameragps.db.Frame
import kotlin.math.abs

/** Turns a batch of logger shots into frames, before they're numbered onto a roll. */
object ShotFrames {
    /** A fix further than this from the shot is marked approximate. */
    const val APPROXIMATE_AFTER_MS = 2 * 60_000L
    /** Shorter contact times are shutter timing noise, not exposure lengths worth noting. */
    const val MIN_EXPOSURE_MS = 1_000L

    /** The parts of a location fix a frame records; plain values so this stays testable. */
    data class Fix(val time: Long, val lat: Double, val lon: Double, val accuracyM: Float?, val altM: Double?)

    fun frames(events: ShutterProtocol.Events, receivedAt: Long, fix: Fix?): List<Frame> =
        events.shots.map { shot ->
            val takenAt = receivedAt - shot.ageMs
            Frame(
                rollId = 0,
                number = 0,
                takenAt = takenAt,
                lat = fix?.lat,
                lon = fix?.lon,
                accuracyM = fix?.accuracyM,
                altM = fix?.altM,
                // Nothing tracks the phone between shots, so a late fix may be somewhere else.
                approximate = fix == null || abs(fix.time - takenAt) > APPROXIMATE_AFTER_MS,
                // The contact closes for the whole exposure, but only long ones are measured well.
                exposureMs = shot.contactMs.takeIf { it >= MIN_EXPOSURE_MS },
                deviceBootId = events.bootId,
                deviceSeq = shot.seq,
            )
        }
}
