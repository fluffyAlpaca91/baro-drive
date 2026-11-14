package com.barodrive.core.engine

import com.barodrive.core.model.Gear
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 등록된 검출기 전부로 짧은 주행 하나(정지 → 출발 → 멈춤 → 신호 대기 → P)를 돌린다. 자세한 규칙은 t1c 포트 테스트들. */
class CoachSessionTest {
    /** P → D(정지) → 30까지 가속 → 순항 → 감속해 정지 → 신호 대기 → P. 0.1초 간격. */
    private val drive: List<VehicleSnapshot> = buildList {
        var i = 0
        fun at(gear: Gear, speed: Double, brake: Boolean = false) = add(VehicleSnapshot(t = i++ / 10.0, gear = gear, speed = speed, brake = brake))
        repeat(10) { at(Gear.P, 0.0) } // 0.0~0.9
        repeat(20) { at(Gear.D, 0.0) } // 1.0~2.9
        for (k in 1..60) at(Gear.D, k * 0.5) // 3.0~8.9, 5km/h/s
        repeat(50) { at(Gear.D, 30.0) } // 9.0~13.9
        for (k in 59 downTo 0) at(Gear.D, k * 0.5, brake = true) // 14.0~19.9, 19.9에 정지
        repeat(30) { at(Gear.D, 0.0, brake = true) } // 20.0~22.9 (20.9에 신호 대기)
        repeat(5) { at(Gear.P, 0.0) } // 23.0~23.4
    }

    private fun run(config: SessionConfig = SessionConfig(mode = 1)): Pair<CoachSession, List<StepOutput>> {
        val session = CoachEngine().createSession(config)
        return session to drive.map(session::step)
    }

    @Test
    fun gentleStartIsPraisedOnceInStage1AndTheStopIsJudged() {
        val (session, outputs) = run()

        val said = outputs.flatMap { it.say }
        assertEquals("1단계 첫 성공 출발 칭찬(b1.ok)과 멈춘 칭찬(f1.ok)", listOf("b1.ok", "f1.ok"), said.map { it.key })
        val events = outputs.flatMap { it.events }.map { it.item to it.result.code }
        assertTrue(events.contains("B1" to "ok"))
        assertTrue(events.contains("F1" to "ok"))
        assertTrue("오류 없음 ${session.errors}", session.errors.isEmpty())
        assertTrue(outputs.any { it.waiting })
    }

    @Test
    fun finishBuildsTheRecordOnceAndStopsTheSession() {
        val (session, _) = run(SessionConfig(mode = 2, sessionNoInMode = 3))

        val record = session.finish(arrived = false)

        assertEquals(2, record.mode)
        assertEquals(2, record.stage)
        assertEquals(3, record.noInMode)
        assertEquals(23.4, record.durationSec, 1e-9)
        assertSame(record, session.finish(arrived = true))
        val after = session.step(VehicleSnapshot(t = 30.0, gear = Gear.D, speed = 0.0))
        assertTrue(after.say.isEmpty() && after.events.isEmpty())
    }

    @Test
    fun unreadableSpeedTurnsTheStopStartChecksOff() {
        val (session, outputs) = run(SessionConfig(mode = 1, caps = SignalCaps(speed = false)))

        assertTrue(outputs.all { o -> o.events.none { it.item == "B1" || it.item == "F1" } })
        assertTrue(session.info().off.any { it.id == "basic.B1" && it.reason == "caps:speed" })
    }
}
