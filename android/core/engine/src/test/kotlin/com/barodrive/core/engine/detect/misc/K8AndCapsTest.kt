package com.barodrive.core.engine.detect.misc

import com.barodrive.core.engine.CoachEngine
import com.barodrive.core.engine.Drive
import com.barodrive.core.engine.SnapSeq
import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.det
import com.barodrive.core.engine.drive
import com.barodrive.core.engine.nav
import com.barodrive.core.engine.of
import com.barodrive.core.engine.r1
import com.barodrive.core.engine.review.round2
import com.barodrive.core.engine.said
import com.barodrive.core.engine.snap
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로토타입 t10_rules.test.js(K8 운전자 질문, 설계 6.5.5)·t10_caps.test.js(신호 지원, 설계 7.8)의 엔진 쪽. */
class K8AndCapsTest {
    private fun turn(t: Double, d: Double?, okAt: Double = 40.0) = K8Turn(t, "t1", d, okAt)
    private fun stop(t: Double, drop: Double) = K8Stop(t, drop, 7.5)

    @Test
    fun k8PicksTheLatestTurnOrStopWithinThirtySeconds() {
        assertEquals(K8Answer("turn", "k8.turn.ok", mapOf("N" to 55.0), "t1", 55.0, null, 10.0), chooseK8Answer(100.0, turn(90.0, 55.0), null))
        assertEquals("거의(30~39m)도 늦음 쪽", "k8.turn.late", chooseK8Answer(100.0, turn(90.0, 35.0), null).key)
        assertEquals("안 켬", "k8.turn.late", chooseK8Answer(100.0, turn(90.0, null), null).key)
        assertEquals("IC는 110m", "k8.turn.late", chooseK8Answer(100.0, turn(90.0, 105.0, 110.0), null).key)
        assertEquals("k8.stop.fix", chooseK8Answer(100.0, null, stop(95.0, 9.0)).key)
        assertEquals("k8.stop.ok", chooseK8Answer(100.0, null, stop(95.0, 5.0)).key)
        assertEquals("더 최근 것", "stop", chooseK8Answer(100.0, turn(80.0, 55.0), stop(95.0, 5.0)).kind)
        assertEquals("turn", chooseK8Answer(100.0, turn(98.0, 55.0), stop(95.0, 9.0)).kind)
        assertEquals("30초가 지남", "k8.none", chooseK8Answer(100.0, turn(69.9, 55.0), stop(60.0, 5.0)).key)
        assertEquals("딱 30초는 안", "turn", chooseK8Answer(100.0, turn(70.0, 55.0), null).kind)
        assertEquals("k8.none", chooseK8Answer(100.0, null, null).key)
    }

    @Test
    fun k8StopAnswersAndGapException() {
        fun run(rate: Double, caps: SignalCaps = SignalCaps(), pre: Boolean = false): Drive {
            val s = CoachEngine().createSession(SessionConfig(mode = 2, caps = caps), listOf(det("k8.answer")), Lines.book)
            val list = ArrayList<Any>()
            var t = 0.0
            fun push(v: Double, brake: Boolean) {
                t = Math.round((t + 0.1) * 10) / 10.0
                list += snap(t = t, gear = Gear.D, speed = v, brake = brake, tripDist = t * 8)
            }
            repeat(20) { push(30.0, false) }
            val n = Math.round(30 / rate * 10).toInt()
            for (i in 1..n) push(maxOf(0.0, 30 - rate * i / 10), true)
            repeat(20) { push(0.0, true) }
            if (pre) list += SessionInput.DriverAsk
            list += SessionInput.DriverAsk
            return drive(s, list)
        }
        val fix = run(10.0)
        assertTrue(said(fix, "k8.stop.fix"))
        assertEquals(listOf("stop"), fix.events.of("K8").map { it.data.kind })
        assertTrue(said(run(5.0), "k8.stop.ok"))
        assertTrue("세기를 모름", said(run(10.0, SignalCaps(speed = false)), "k8.none"))
        val twice = run(5.0, pre = true)
        assertEquals("말하는 중이면 다음 답은 기다림", 1, twice.say.count { it.item == "K8" })
        assertEquals(2, twice.events.of("K8").size)
    }

    @Test
    fun itemsOffPerSignalMatchTheTable() {
        fun off(k: SignalKey) = BaroEngine.itemsOffByCaps(SignalCaps().with(k, false)).sorted()
        assertEquals(listOf("B1", "E1", "E2", "F1", "F2", "HL2", "PK2"), off(SignalKey.SPEED))
        assertEquals(listOf("C1", "C2", "C3", "C4", "C5", "C6"), off(SignalKey.BLINKER))
        assertEquals(listOf("C4", "C5", "G1", "HL3", "PK3"), off(SignalKey.STEER))
        assertEquals(listOf("C1", "C2", "C3", "E1", "E2"), off(SignalKey.NAV))
        assertEquals(listOf("A1"), off(SignalKey.BELT_DRIVER))
        assertEquals(listOf("HL2"), off(SignalKey.REVERSE_SPEED))
        for (k in listOf(SignalKey.GEAR, SignalKey.PASSENGER, SignalKey.HANDBRAKE, SignalKey.WIPER, SignalKey.OUTSIDE_TEMP, SignalKey.TRIP)) {
            assertEquals("$k 는 항목을 끄지 않고 대안으로", emptyList<String>(), off(k))
        }
    }

    @Test
    fun detectorsTurnOffWithTheirSignalsAndE3WakesOnlyWithoutSpeed() {
        fun info(caps: SignalCaps) = CoachEngine().createSession(SessionConfig(mode = 1, caps = caps)).info()
        val full = info(SignalCaps())
        assertTrue("basic.E1" in full.detectors && "fallback.E3" in full.detectors)
        val noSpeed = info(SignalCaps(speed = false))
        for (id in listOf("basic.B1", "basic.F1", "basic.F2", "basic.E1", "basic.E2")) assertTrue(id, noSpeed.off.any { it.id == id && it.reason == "caps:speed" })
        val noBlink = info(SignalCaps(blinker = false))
        for (id in listOf("basic.C1", "basic.C2", "basic.C4", "basic.C5", "safety3.C3", "safety3.C6")) assertTrue(id, noBlink.off.any { it.id == id && it.reason == "caps:blinker" })
        assertTrue("내비도 못 읽으면 E3도 꺼짐", info(SignalCaps(nav = false)).off.any { it.id == "fallback.E3" && it.reason == "caps:nav" })
        assertTrue(info(SignalCaps(steer = false)).off.any { it.id == "basic.G1" })
        val s = CoachEngine().createSession(SessionConfig(mode = 1, caps = SignalCaps(handbrake = false, passenger = false)), emptyList())
        s.step(snap(handbrake = true, passenger = true))
        assertTrue(s.errors.isEmpty())
    }

    /** t10_caps `seq`: 0.1초 표본, 트립 거리는 속도를 쌓은 값. */
    private class TripSeq {
        val list = ArrayList<VehicleSnapshot>()
        private var t = 0.0
        private var trip = 0.0

        fun add(sec: Double, fix: (VehicleSnapshot) -> VehicleSnapshot): TripSeq {
            repeat(Math.round(sec * 10).toInt()) {
                t = r1(t + 0.1)
                val s = fix(snap(t = t, gear = Gear.D, speed = 0.0))
                trip += abs(s.speed ?: 0.0) / 3.6 * 0.1
                list += s.copy(tripDist = round2(trip))
            }
            return this
        }
    }

    private fun nv(limit: Int = 30): NavSnapshot = nav(limit = limit)

    @Test
    fun e3ReportsNaviSpeedingWhenSpeedIsUnreadable() {
        class R(val out: Drive, val rec: com.barodrive.core.model.SessionRecord)
        fun run(caps: SignalCaps, kmh: Double): R {
            val s = CoachEngine().createSession(SessionConfig(mode = 1, caps = caps), listOf(det("fallback.E3"), det("basic.E1")), Lines.book)
            val b = TripSeq().add(2.0) { it.copy(speed = 25.0, nav = nv()) }.add(6.0) { it.copy(speed = kmh, nav = nv()) }
                .add(2.0) { it.copy(speed = 25.0, nav = nv(50)) }.add(6.0) { it.copy(speed = 60.0, nav = nv(50)) }
            val out = drive(s, b.list)
            return R(out, s.finish(arrived = true))
        }
        val blind = run(SignalCaps(speed = false), 40.0)
        val e3 = blind.out.events.of("E3")
        assertEquals(listOf(Triple(EventResult.FAIL, 30.0, "navi"), Triple(EventResult.FAIL, 50.0, "navi")), e3.map { Triple(it.result, it.data.limit, it.data.via) })
        assertTrue((e3[0].data.peak ?: 0.0) >= 38 && (e3[0].data.overSec ?: 0.0) > 3)
        assertEquals("말하지 않음(리포트만)", 0, blind.out.say.size)
        assertEquals("E1은 꺼짐", 0, blind.out.events.of("E1").size)
        assertEquals(listOf(e3Text(2)), blind.rec.tips.filter { it.kind == "E3" }.map { it.text })
        assertEquals("내비가 과속을 알려 준 구간 2곳 · 차속을 못 읽어 내비 경고로만 셌어요", blind.rec.tips[0].text)
        assertEquals("제한 안이면 없음", 0, run(SignalCaps(speed = false), 29.0).out.events.of("E3").count { it.data.limit == 30.0 })
        val seen = run(SignalCaps(), 40.0)
        assertEquals("차속을 읽으면 E3는 자고 E1이 본다", 0, seen.out.events.of("E3").size)
        assertTrue(seen.out.events.any { it.item == "E1" && it.result == EventResult.FAIL })
    }

    @Test
    fun pb2AsksAtTheFirstParkInParkingAndHillModesOnly() {
        fun run(mode: Int, extra: Boolean = false): Drive {
            val s = CoachEngine().createSession(SessionConfig(mode = mode, caps = SignalCaps(handbrake = false)), listOf(det("basic.PB2")), Lines.book)
            val b = TripSeq().add(3.0) { it.copy(gear = if (mode == 6) Gear.R else Gear.D, speed = 4.0) }.add(1.0) { it.copy(gear = Gear.D, speed = 0.0) }
                .add(4.0) { it.copy(gear = Gear.P, speed = 0.0) }
            if (extra) b.add(1.0) { it.copy(gear = Gear.D, speed = 3.0) }.add(4.0) { it.copy(gear = Gear.P, speed = 0.0) }
            return drive(s, b.list)
        }
        val m6 = run(6, extra = true)
        assertEquals("한 번만", listOf(EventResult.INFO to true), m6.events.of("PB2").map { it.result to it.data.unreadable })
        assertTrue(said(m6, "pb2"))
        val m7 = run(7)
        assertEquals(1, m7.events.of("PB2").size)
        assertTrue(said(m7, "pb2.hill"))
        assertEquals("1단계: 도착 전 P에서는 묻지 않음", 0, run(1).events.of("PB2").size)
    }
}
