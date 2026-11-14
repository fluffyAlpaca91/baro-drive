package com.barodrive.app.data.signal.sim

import com.barodrive.core.model.Gear
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.vehicle.VehiclePatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시뮬레이터 신호 출처: 세워 둔 차는 시계가 서서 틱이 없어도, 모으기 시작하면 지금 상태 하나를 먼저 낸다
 * (프로토타입 PR-1은 `BaroSim.snapshot()`을 바로 읽어 처음부터 '맸어요 · 풀려 있어요 · 비어 있어요'가 보인다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimSignalSourceTest {
    @Test
    fun parkedCarGivesItsCurrentStateWhenCollectionStarts() = runTest {
        val controller = SimController(FakeDemoPanelRepository(), backgroundScope)
        val source = SimSignalSource(controller, StandardTestDispatcher(testScheduler))

        val first = source.snapshots.first()

        assertEquals(Gear.P, first.gear)
        assertEquals("운전석 벨트(시뮬레이터 처음 값)", true, first.beltDriver)
        assertEquals("사이드브레이크", false, first.handbrake)
        assertEquals("조수석", false, first.passenger)
        assertEquals("와이퍼", false, first.wiper)
        assertEquals("지금 상태를 읽어도 시계는 서 있다", 0, controller.advance(0.1).size)
    }

    @Test
    fun resetWhileParkedGivesTheFreshStateAlthoughTheClockStands() = runTest {
        val controller = SimController(FakeDemoPanelRepository(), backgroundScope)
        val source = SimSignalSource(controller, StandardTestDispatcher(testScheduler))
        val got = ArrayList<VehicleSnapshot>()
        backgroundScope.launch { source.snapshots.collect { got += it } }
        runCurrent()

        // 지난 회차 끝처럼 사이드브레이크를 채운다(손 조작 → 몇 초 틱).
        controller.set(VehiclePatch(handbrake = true))
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(true, got.last().handbrake)
        val before = got.size
        assertEquals("서 있는 차는 이제 시계가 선다", 0, controller.advance(0.1).size)

        // 데모 상태 적용 = 처음으로: 틱이 없어도 처음 상태가 한 번 나간다.
        controller.resetAll()
        advanceTimeBy(300)
        runCurrent()
        assertTrue("처음으로 돌린 뒤 스냅숏 ${got.size - before}개", got.size > before)
        assertEquals("사이드브레이크가 풀린 처음 상태", false, got.last().handbrake)
    }
}

