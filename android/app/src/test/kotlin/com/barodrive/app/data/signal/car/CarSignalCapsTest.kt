package com.barodrive.app.data.signal.car

import com.barodrive.core.engine.api.BaroEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 진짜 차 caps와 속도 권한(설계 7.8): 거절하면 속도가 꺼지고, 엔진이 속도가 필요한 항목을 신호 지원 규칙대로 끈다. */
class CarSignalCapsTest {
    private val speedItems = listOf("B1", "F1", "F2", "E1", "E2", "PK2", "HL2")

    @Test
    fun deniedSpeedTurnsOffItemsThatNeedSpeed() {
        val caps = CarSignalSource.capsFor(speedGranted = false)
        assertFalse(caps.speed)
        assertFalse("뒤로 가는 속도도 같은 속성이라 못 읽는다", caps.reverseSpeed)
        val off = BaroEngine.itemsOffByCaps(caps)
        for (id in speedItems) assertTrue("$id 꺼짐", id in off)
    }

    @Test
    fun grantedSpeedKeepsThoseItems() {
        val caps = CarSignalSource.capsFor(speedGranted = true)
        assertTrue(caps.speed && caps.reverseSpeed)
        val off = BaroEngine.itemsOffByCaps(caps)
        for (id in speedItems) assertFalse("$id 켜짐", id in off)
    }

    @Test
    fun otherSignalsDoNotDependOnThePermission() {
        val denied = CarSignalSource.capsFor(speedGranted = false)
        val granted = CarSignalSource.capsFor(speedGranted = true)
        assertEquals(granted.copy(speed = false, reverseSpeed = false), denied)
        assertTrue("차에서 읽는 신호", granted.gear && granted.blinker && granted.steer && granted.nav)
        assertFalse("아직 null로 내는 신호", granted.passenger || granted.beltDriver || granted.handbrake || granted.wiper || granted.outsideTemp)
    }
}
