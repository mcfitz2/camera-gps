package com.micahf.cameragps

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightTest {
    private val u = UUID.randomUUID()

    @Test
    fun completesFromMatchingCallback() = runBlocking {
        val op = InFlight(OpKind.READ, u)
        assertTrue(op.offer(OpKind.READ, u, 0, byteArrayOf(1)))
        val (status, value) = op.result.await()
        assertEquals(0, status)
        assertArrayEquals(byteArrayOf(1), value)
    }

    @Test
    fun ignoresOtherKind() {
        val op = InFlight(OpKind.READ, u)
        assertFalse(op.offer(OpKind.WRITE, u, 0))
        assertFalse(op.result.isCompleted)
    }

    @Test
    fun ignoresOtherCharacteristic() {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        val op = InFlight(OpKind.WRITE, u1)
        assertFalse(op.offer(OpKind.WRITE, u2, 0))
    }

    @Test
    fun completesOnce() = runBlocking {
        val op = InFlight(OpKind.WRITE, u)
        assertTrue(op.offer(OpKind.WRITE, u, 0, byteArrayOf(1)))
        assertFalse(op.offer(OpKind.WRITE, u, 1, byteArrayOf(2)))
        val (status, value) = op.result.await()
        assertEquals(0, status)
        assertArrayEquals(byteArrayOf(1), value)
    }
}
