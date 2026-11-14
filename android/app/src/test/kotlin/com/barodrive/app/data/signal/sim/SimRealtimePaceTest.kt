package com.barodrive.app.data.signal.sim

import com.barodrive.app.demo.PanelSettings
import com.barodrive.core.model.DemoPanelState
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.SimTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시연 패널 배속 '실제'(2026-11-11 drive-spec A): 패널 처음 값이 '실제'라 시나리오 재생이 실제 1초에 sim 1초로 가고,
 * ×1·×2·×4를 고르면 오늘처럼 압축 × N, [패널 설정 초기화]하면 다시 '실제'. '실제'일 때 시뮬레이터 배속은 1로 둔다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimRealtimePaceTest {
    /** 실제 [sec]초(앱 고리처럼 0.1초씩) 동안 돈 틱 수. */
    private fun SimController.ticksOver(sec: Double): Int = (0 until Math.round(sec * 10).toInt()).sumOf { advance(0.1).size }

    @Test
    fun freshPanelPlaysAScenarioInRealTime() = runTest {
        val c = SimController(FakeDemoPanelRepository(), backgroundScope)
        runCurrent()
        assertTrue("새로 깐 패널은 '실제'", c.view.value.realtime)
        assertEquals(1, c.view.value.speed)
        c.load(SimTarget.Scenario("S1-B"))
        c.toggle(null)
        assertEquals(PlayerState.PLAYING, c.view.value.player)
        val t0 = c.view.value.simT
        val n = c.ticksOver(5.0)
        assertTrue("실제 5초 → sim ${c.view.value.simT - t0}초($n 틱)", n in 49..51)
    }

    @Test
    fun pickingXnCompressesAgainAndResetGoesBackToRealTime() = runTest {
        val panel = FakeDemoPanelRepository()
        val c = SimController(panel, backgroundScope)
        runCurrent()
        c.load(SimTarget.Scenario("S1-B"))
        c.toggle(null)
        c.ticksOver(1.0)
        panel.update { it.copy(demo = it.demo.copy(speed = 4.0, realtime = false)) }
        runCurrent()
        assertFalse(c.view.value.realtime)
        assertEquals(4, c.view.value.speed)
        val fast = c.ticksOver(2.0)
        assertTrue("×4 × 압축(3·6·10) → 실제 2초에 sim ${fast / 10.0}초", fast >= 150)
        panel.reset()
        runCurrent()
        assertTrue("초기화하면 다시 '실제'", c.view.value.realtime)
        assertEquals("'실제'면 시뮬레이터 배속 1(끝 뒤 마무리 꼬리도 ×1처럼)", 1, c.view.value.speed)
        c.ticksOver(0.5) // 배율이 0.25초에 걸쳐 따라온다
        assertTrue(c.ticksOver(2.0) in 19..21)
    }

    @Test
    fun oldSavedXnWithoutTheNewFieldOpensAsRealTimeButAnExplicitXnStays() = runTest {
        // 옛 저장(배속 ×4, '실제' 칸 없음)을 읽은 값 = DemoPanelState 처음 값 realtime = true
        val old = SimController(FakeDemoPanelRepository(PanelSettings(demo = DemoPanelState(speed = 4.0))), backgroundScope)
        runCurrent()
        assertTrue(old.view.value.realtime)
        assertEquals(1, old.view.value.speed)
        val x2 = SimController(FakeDemoPanelRepository(PanelSettings(demo = DemoPanelState(speed = 2.0, realtime = false))), backgroundScope)
        runCurrent()
        assertFalse(x2.view.value.realtime)
        assertEquals(2, x2.view.value.speed)
    }
}
