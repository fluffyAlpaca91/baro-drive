package com.barodrive.core.sim

import com.barodrive.core.sim.vehicle.VehiclePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 속도(시연 패널 배속 '실제', 2026-11-11 drive-spec A): [SimClock.realtime]이면 재생 중에도 실제 1초 = sim 1초(압축 3·6·10도 배속도 없음).
 * 시뮬레이터 처음 값은 꺼짐이라 헤드리스·골든 실행은 그대로이고, 끄면 다시 압축 × 배속이다. 손 조작(×1)과 시나리오 끝 마무리는 그대로.
 */
class RealtimePaceTest {
    /** 실제 [realSec]초를 0.05초씩 넘긴 동안 돈 틱 수. */
    private fun SimClock.ticksOver(realSec: Double): Int {
        var n = 0
        repeat(Math.round(realSec / 0.05).toInt()) { n += advance(0.05) }
        return n
    }

    @Test
    fun realtimeIsOffByDefaultSoPlaybackStaysCompressed() {
        val sim = SimTestSupport.sim()
        assertFalse("시뮬레이터 처음 값은 꺼짐(골든·헤드리스 그대로)", sim.clock.realtime)
        sim.player.load(SimTarget.Scenario("TEST-1"))
        sim.play()
        assertEquals("출발 직후 사건 구간 ×3", 3.0, sim.player.compression(), 0.0)
        assertEquals(3.0, sim.clock.targetRate(), 0.0)
        assertTrue("실제 1초에 sim 3초쯤", sim.clock.ticksOver(1.0) in 29..31)
    }

    @Test
    fun realtimePlaysOneSimSecondPerRealSecondInEveryWindowAndAtEverySpeed() {
        val sim = SimTestSupport.sim()
        val c = sim.clock
        assertTrue(sim.setRealtime(true))
        sim.setSpeed(4)
        sim.player.load(SimTarget.Scenario("TEST-1"))
        sim.play()
        assertEquals("사건 구간(압축 3)", 3.0, sim.player.compression(), 0.0)
        assertEquals("배속 ×4라도 실제 속도면 1", 1.0, c.targetRate(), 0.0)
        val t0 = c.simT
        val n = c.ticksOver(1.0)
        assertTrue("실제 1초 → sim ${c.simT - t0}초($n 틱)", n in 9..11)
        // 순항(압축 10)에서도 1:1
        sim.player.runUntil { sim.vehicle.state.s > 100 && sim.vehicle.state.speed >= 45 }
        assertEquals("회전이 300m 넘게 남은 순항", 10.0, sim.player.compression(), 0.0)
        assertEquals(1.0, c.targetRate(), 0.0)
        assertTrue(c.ticksOver(2.0) in 19..21)
        assertEquals("재생 중 그대로", PlayerState.PLAYING, sim.player.state)
    }

    @Test
    fun switchingOffRestoresCompressionTimesSpeedAndManualStaysRealTime() {
        val sim = SimTestSupport.sim()
        val c = sim.clock
        sim.setRealtime(true)
        sim.player.load(SimTarget.Scenario("TEST-1"))
        sim.play()
        assertEquals(1.0, c.targetRate(), 0.0)
        assertFalse(sim.setRealtime(false))
        sim.setSpeed(2)
        assertEquals("끄면 압축 × 배속", sim.player.compression() * 2, c.targetRate(), 0.0)
        // 손 조작은 실제 속도와 상관없이 ×1(재생 중 핸들 → 일시정지, 시계는 손 조작으로)
        sim.setRealtime(true)
        sim.set(VehiclePatch(steer = 10.0))
        assertEquals(PlayerState.PAUSED, sim.player.state)
        assertEquals(1.0, c.targetRate(), 0.0)
        sim.setRealtime(false)
        assertEquals(1.0, c.targetRate(), 0.0)
        // 처음으로 돌리면 시뮬레이터 처음 값(꺼짐) — 앱 패널이 자기 설정을 다시 넣는다
        sim.setRealtime(true)
        sim.reset()
        assertFalse(c.realtime)
        assertEquals(1, c.speed)
    }

    @Test
    fun scenarioEndTailIsUnchanged() {
        val sim = SimTestSupport.sim()
        sim.setRealtime(true)
        sim.player.load(SimTarget.Scenario("TEST-1"))
        sim.player.runUntil { sim.player.state == PlayerState.ENDED }
        assertTrue("끝나면 마무리 꼬리(keepAlive 5초)", sim.clock.keepLeft() > 0)
        assertEquals("재생 밖(끝 뒤 마무리)은 바꾸지 않음 — ×6 × 배속", 6.0, sim.clock.targetRate(), 0.0)
    }
}
