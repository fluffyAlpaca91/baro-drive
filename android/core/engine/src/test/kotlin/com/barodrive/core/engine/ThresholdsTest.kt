package com.barodrive.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdsTest {
    @Test
    fun spotChecksMatchPrototypeTable() {
        assertEquals(5.0, Thresholds["start.v"], 0.0)
        assertEquals(40.0, Thresholds["C2.ok"], 0.0)
        assertEquals(0.5, Thresholds["stop.v"], 0.0)
        assertEquals(1.0, Thresholds["wait.sec"], 0.0)
        assertEquals(20.0, Thresholds["gap"], 0.0)
        assertEquals(600.0, Thresholds["cap.window"], 0.0)
        assertEquals(6.0, Thresholds["cap.n"], 0.0)
        assertEquals(7.5, Thresholds["F1.drop"], 0.0)
        assertEquals(-0.5, Thresholds["HL2.v"], 0.0)
        assertEquals(0.8, Thresholds["E.factor.wx"], 0.0)
    }

    @Test
    fun holdsTheWholePrototypeTable() {
        // core.js TH에는 키가 83개 있다(2026-11-11 기준).
        assertEquals(83, Thresholds.keys.size)
    }

    @Test
    fun unknownKeyFailsLoudly() {
        val error = assertThrows(IllegalArgumentException::class.java) { Thresholds["start.speed"] }

        assertTrue(error.message.orEmpty().contains("start.speed"))
    }
}
