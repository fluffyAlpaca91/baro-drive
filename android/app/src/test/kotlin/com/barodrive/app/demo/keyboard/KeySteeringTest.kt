package com.barodrive.app.demo.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 키보드 핸들 규칙(drive-spec B '핸들'): 끝까지 1.5초 · 둘 다 누르면 그대로 · 5km/h 넘으면 같은 빠르기로 가운데 · 그 이하는 각을 지킴. */
class KeySteeringTest {
    /** [sec]초를 [dt]초씩. */
    private fun run(start: Double, left: Boolean, right: Boolean, speed: Double, sec: Double, dt: Double = 0.05): Double {
        var a = start
        repeat(Math.round(sec / dt).toInt()) { a = KeySteering.next(a, left, right, speed, dt) }
        return a
    }

    @Test
    fun fullLockInAboutOneAndAHalfSecondsLeftNegative() {
        assertEquals("패널 막대 범위", 35.0, KeySteering.FULL_LOCK, 0.0)
        assertEquals(35.0 / 1.5, KeySteering.RATE, 1e-9)
        assertEquals("반쯤", -17.5, run(0.0, left = true, right = false, speed = 0.0, sec = 0.75), 1e-6)
        assertEquals("왼쪽 끝은 음수", -35.0, run(0.0, left = true, right = false, speed = 0.0, sec = 1.5), 1e-6)
        assertTrue("1.5초 전에는 아직", run(0.0, left = true, right = false, speed = 0.0, sec = 1.4) > -35.0)
        assertEquals("끝에서 멈춤", -35.0, run(0.0, left = true, right = false, speed = 30.0, sec = 3.0), 0.0)
        assertEquals("오른쪽 끝", 35.0, run(0.0, left = false, right = true, speed = 30.0, sec = 2.0), 0.0)
        assertEquals("오른쪽 끝에서 ←를 1.5초 → 가운데(같은 빠르기)", 0.0, run(35.0, left = true, right = false, speed = 0.0, sec = 1.5), 1e-6)
    }

    @Test
    fun bothKeysDoNothing() {
        assertEquals(12.0, run(12.0, left = true, right = true, speed = 40.0, sec = 1.0), 0.0)
        assertEquals(-7.0, run(-7.0, left = true, right = true, speed = 0.0, sec = 1.0), 0.0)
    }

    @Test
    fun releasedWheelSelfCentersAboveFiveKmhAtTheSameRate() {
        assertEquals("끝에서 가운데까지 1.5초", 0.0, run(-35.0, left = false, right = false, speed = 20.0, sec = 1.5), 1e-6)
        assertEquals("반쯤", 17.5, run(35.0, left = false, right = false, speed = 20.0, sec = 0.75), 1e-6)
        assertEquals("가운데를 넘지 않음", 0.0, run(3.0, left = false, right = false, speed = 20.0, sec = 1.0), 0.0)
        assertEquals("후진도 크기로", 0.0, run(20.0, left = false, right = false, speed = -10.0, sec = 1.0), 0.0)
        assertTrue(KeySteering.selfCenters(5.1))
    }

    @Test
    fun releasedWheelKeepsItsAngleAtFiveKmhOrBelow() {
        assertEquals("주차", -20.0, run(-20.0, left = false, right = false, speed = 0.0, sec = 2.0), 0.0)
        assertEquals("딱 5km/h도", 20.0, run(20.0, left = false, right = false, speed = 5.0, sec = 2.0), 0.0)
        assertEquals(-12.0, run(-12.0, left = false, right = false, speed = -4.0, sec = 2.0), 0.0)
    }
}
