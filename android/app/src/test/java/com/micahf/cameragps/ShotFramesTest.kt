package com.micahf.cameragps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShotFramesTest {
    private fun events(vararg shots: ShutterProtocol.Shot) = ShutterProtocol.Events(bootId = 1, shots = shots.toList())

    @Test
    fun timeIsReceivedMinusAge() {
        val shot = ShutterProtocol.Shot(seq = 0, ageMs = 9_000, contactMs = 0)
        val frames = ShotFrames.frames(events(shot), receivedAt = 100_000, fix = null)

        assertEquals(91_000L, frames.single().takenAt)
    }

    @Test
    fun noFixIsApproximateWithoutLocation() {
        val shot = ShutterProtocol.Shot(seq = 0, ageMs = 0, contactMs = 0)
        val frame = ShotFrames.frames(events(shot), receivedAt = 100_000, fix = null).single()

        assertEquals(true, frame.approximate)
        assertNull(frame.lat)
        assertNull(frame.lon)
        assertNull(frame.accuracyM)
        assertNull(frame.altM)
    }

    @Test
    fun fixWithinTwoMinutesIsExact() {
        val shot = ShutterProtocol.Shot(seq = 0, ageMs = 0, contactMs = 0)
        val takenAt = 100_000L
        val fix = ShotFrames.Fix(time = takenAt + 120_000, lat = 1.0, lon = 2.0, accuracyM = null, altM = null)
        val frame = ShotFrames.frames(events(shot), receivedAt = takenAt, fix = fix).single()

        assertEquals(false, frame.approximate)
    }

    @Test
    fun fixOverTwoMinutesAwayIsApproximate() {
        val shot = ShutterProtocol.Shot(seq = 0, ageMs = 0, contactMs = 0)
        val takenAt = 100_000L
        val after = ShotFrames.Fix(time = takenAt + 120_001, lat = 1.0, lon = 2.0, accuracyM = null, altM = null)
        val before = ShotFrames.Fix(time = takenAt - 120_001, lat = 1.0, lon = 2.0, accuracyM = null, altM = null)

        assertEquals(true, ShotFrames.frames(events(shot), receivedAt = takenAt, fix = after).single().approximate)
        assertEquals(true, ShotFrames.frames(events(shot), receivedAt = takenAt, fix = before).single().approximate)
    }

    @Test
    fun shortContactIsNotAnExposure() {
        val short = ShutterProtocol.Shot(seq = 0, ageMs = 0, contactMs = 999)
        val long = ShutterProtocol.Shot(seq = 1, ageMs = 0, contactMs = 1_000)
        val frames = ShotFrames.frames(events(short, long), receivedAt = 0, fix = null)

        assertNull(frames.single { it.deviceSeq == 0L }.exposureMs)
        assertEquals(1_000L, frames.single { it.deviceSeq == 1L }.exposureMs)
    }

    @Test
    fun carriesDeviceIds() {
        val shot = ShutterProtocol.Shot(seq = 42, ageMs = 0, contactMs = 0)
        val batch = events(shot)
        val frame = ShotFrames.frames(batch, receivedAt = 0, fix = null).single()

        assertEquals(batch.bootId, frame.deviceBootId)
        assertEquals(shot.seq, frame.deviceSeq)
    }
}
