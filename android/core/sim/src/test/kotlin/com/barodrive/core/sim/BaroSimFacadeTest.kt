package com.barodrive.core.sim

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.player.Player
import com.barodrive.core.sim.vehicle.VehiclePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 파사드를 앱처럼 쓰기: 실제 시간 고리([BaroSim.advance])·자동 재생·세션 시작 때 시계 0([SimEvent.Gear] 받는 자리에서 [BaroSim.resetClock])
 * ·마무리 동안 시계 붙잡기·시나리오 입력·주입·진행 막대. 시연 패널(SimController)이 이 순서로 부르면 된다.
 */
class BaroSimFacadeTest {
    /** 앱 고리 흉내: 실제 [realSec]초를 [frame]초씩 넘기며 스냅숏을 모은다. */
    private fun BaroSim.loop(realSec: Double, frame: Double = 0.1, out: MutableList<VehicleSnapshot> = ArrayList()): MutableList<VehicleSnapshot> {
        var t = 0.0
        while (t < realSec - 1e-9) {
            out += advance(frame)
            t += frame
        }
        return out
    }

    @Test
    fun appLoopAutoplaysCompressesAndStartsTheSessionClockAtDeparture() {
        val sim = BaroSim()
        var sessionStarted = false
        val inputs = ArrayList<SessionInput>()
        sim.inputSink = { inputs += it }
        sim.addListener { e ->
            // 앱 세션 연결: 기어가 P를 벗어나면 세션 시작 · sim 시계 0(그 틱 스냅숏의 t가 0)
            if (e is SimEvent.Gear && e.from == Gear.P && e.to != Gear.P && !sessionStarted) {
                sessionStarted = true
                sim.resetClock(0.0)
            }
        }
        val info = sim.load(SimTarget.Scenario("S9-R"), DriverSkill.NORMAL, autoplay = true, autoplayDelay = 1.0)
        assertEquals(PlayerState.LOADED, info.state)
        val before = sim.loop(0.9)
        assertTrue("자동 재생 1초 전에는 서 있음(P · 시계 멈춤)", before.isEmpty())
        val snaps = sim.loop(30.0)
        assertEquals(PlayerState.PLAYING, sim.player.state)
        assertTrue(sessionStarted)
        val start = snaps.indexOfFirst { it.gear == Gear.D }
        assertEquals("세션 시작 틱에 t 0", 0.0, snaps[start].t, 0.0)
        assertTrue("그 뒤 0.1초 간격", snaps.drop(start).zipWithNext().all { (a, b) -> abs(b.t - a.t - 0.1) < 1e-9 })
        // 압축: 실제 30초 동안 sim은 3~10배 — 사건 구간(×3)보다 많이, 순항(×10)보다 적게
        val simSec = snaps.size / 10.0
        assertTrue("sim ${simSec}초", simSec > 30 * 3 - 1 && simSec < 30 * 10)
        // 배속 ×2면 같은 실제 시간에 두 배쯤
        sim.setSpeed(2)
        val fast = sim.loop(5.0)
        sim.setSpeed(1)
        val slow = sim.loop(5.0)
        assertTrue("×2 ${fast.size} vs ×1 ${slow.size}", fast.size > slow.size * 1.4)
        // 끝까지: 시나리오 질문(ask)이 inputSink로
        sim.loop(400.0)
        assertEquals(PlayerState.ENDED, sim.player.state)
        assertEquals(listOf<SessionInput>(SessionInput.DriverAsk), inputs)
        assertTrue(sim.errors.isEmpty())
    }

    @Test
    fun parkedCarStopsTheClockUnlessHeldForTheSessionFinish() {
        val sim = BaroSim()
        assertTrue("P · 0km/h면 시계가 선다", sim.loop(2.0).isEmpty())
        sim.clock.hold("finish", true) // 앱 세션 마무리(P 표본 3초)를 잴 동안
        val held = sim.loop(1.0)
        assertTrue("붙잡으면 ×1로 돈다 ${held.size}", held.size in 9..11)
        sim.clock.hold("finish", false)
        sim.loop(5.0)
        assertTrue(sim.loop(1.0).isEmpty())
        // 손 조작은 ×1 실제 시간
        sim.shift(Gear.D)
        sim.setTargetSpeed(30.0)
        assertTrue(sim.loop(1.0).size in 9..11)
    }

    @Test
    fun panelControlsPauseSkipInjectCapsAndProgress() {
        val sim = BaroSim()
        sim.load(SimTarget.Course("c2-B"))
        assertEquals("S2-B", sim.info().id)
        assertTrue(sim.play())
        sim.step(60.0)
        val p = sim.progress()
        assertTrue(p.ratio > 0 && p.ratio < 1)
        assertEquals(sim.marks().map { it.name }, p.marks.map { it.name })
        assertNotNull(sim.navState().destName)
        // 다음 장면
        val r = requireNotNull(sim.skip(Player.Skip.NEXT))
        assertTrue(r.mark != null)
        val afterSkip = sim.tick()
        assertTrue(afterSkip.skip)
        // 주입: 패널 글을 돌려준다
        val msg = sim.inject("hardStop")
        assertTrue(msg, msg == "1초에 9km/h씩 줄여 멈춰요" || msg == "10km/h 넘게 달릴 때 눌러요")
        assertTrue(sim.inject("rain").startsWith("와이퍼 켬"))
        // 재생 중 손 조작 → 일시정지
        sim.set(VehiclePatch(speed = 10.0))
        assertEquals(PlayerState.PAUSED, sim.player.state)
        assertEquals("toggle → 다시 재생", "playing", sim.toggle())
        // 못 읽는 신호
        sim.setCaps(SignalCaps(steer = false, nav = false))
        val s = sim.snapshot()
        assertNull(s.steer)
        assertNull(s.nav)
        // 패널 주간/야간·차종(재생을 멈추지 않음)
        sim.setEnvironment(night = false, model = "grandeur")
        assertEquals(PlayerState.PLAYING, sim.player.state)
        assertEquals("grandeur", sim.snapshot().model)
        assertEquals(33.0, sim.snapshot().maxSteer)
        // 끝으로 → 도착
        sim.skip(Player.Skip.END)
        sim.player.runUntilEnd()
        assertEquals(PlayerState.ENDED, sim.player.state)
        assertTrue(sim.navi.arrived)
        assertFalse(sim.errors.isNotEmpty())
    }

    @Test
    fun virtualParkingPracticeFromTheFacade() {
        val sim = BaroSim()
        sim.setEnvironment(model = "tucson")
        val practice = sim.parkingPractice("back", "L")
        assertEquals("tucson", practice.car.id)
        val st = sim.parking.runPlan(practice, sim.parking.plan("back", "L", "tucson"))
        assertEquals("done", st.status)
    }
}
