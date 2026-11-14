package com.barodrive.core.engine

import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.BrakeEndEvent
import com.barodrive.core.model.EngineFlags
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.GearEvent
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.RerouteEvent
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.StartEvent
import com.barodrive.core.model.SteerTurn
import com.barodrive.core.model.StraightWindow
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WaitStartEvent
import com.barodrive.core.model.WaypointEvent
import com.barodrive.core.model.ZoneEnter
import com.barodrive.core.model.ZoneExit
import com.barodrive.core.engine.api.BaroEngine
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프로토타입 t1c_engine.test.js — 공용 추적기(설계 6.2)와 검출기 끄기(7.8). */
class T1cTrackerTest {
    private fun cfg(mode: Int = 1, caps: SignalCaps = SignalCaps()) = SessionConfig(mode = mode, caps = caps)

    /** t1c `turnRoad`: 남은 거리 d0에서 1스냅숏에 1m씩 줄어든다(36km/h). [over]가 (t, d, 기본)으로 고친다. */
    private fun turnRoad(t0: Double, d0: Double, n: Int, over: (Double, Double, VehicleSnapshot) -> VehicleSnapshot = { _, _, s -> s }) =
        tl(t0, t0 + n / 10.0) { t, s ->
            val d = d0 - Math.round((t - t0) * 10)
            over(t, d, s.copy(speed = 36.0, nav = nav(tbt = if (d > 0) listOf(tbt("t1", "TURN_RIGHT", d, TurnDir.RIGHT)) else emptyList())))
        }

    @Test
    fun gearEventsStartFromP() {
        val rec = Recorder()
        drive(sess(cfg(), listOf(rec.def)), listOf(snap(t = 0.1, gear = Gear.D), snap(t = 0.2, gear = Gear.D), snap(t = 0.3, gear = Gear.N), snap(t = 0.4, gear = Gear.P)))
        assertEquals(listOf(Gear.P to Gear.D, Gear.D to Gear.N, Gear.N to Gear.P), rec.of<GearEvent>("gear").map { it.from to it.to })
        val rec2 = Recorder()
        drive(sess(cfg(caps = SignalCaps(gear = false)), listOf(rec2.def)), listOf(snap(t = 0.1, gear = Gear.D), snap(t = 0.2, gear = Gear.R)))
        assertEquals("기어를 못 읽으면 사건 없음", 0, rec2.of<GearEvent>("gear").size)
    }

    @Test
    fun startMaxGainInFiveSecondWindow() {
        fun launch(snaps: List<VehicleSnapshot>): List<StartEvent> {
            val rec = Recorder()
            drive(sess(cfg(), listOf(rec.def)), listOf(snap(t = 0.0, gear = Gear.D)) + snaps)
            return rec.of("start")
        }
        var st = launch(ramp(0.0, 13.0, 1.0) + hold(13.0, 6.0, 1.0))
        assertEquals(1, st.size)
        assertEquals(13.0, st[0].maxGain1s, 1e-9)
        assertEquals(0.1, st[0].t0, 1e-9)
        assertEquals(Gear.D, st[0].gear)
        st = launch(ramp(0.0, 11.0, 1.0) + hold(11.0, 6.0, 1.0))
        assertEquals("딱 11", 11.0, st[0].maxGain1s, 1e-9)
        st = launch(ramp(0.0, 20.0, 5.0) + hold(20.0, 2.0, 5.0))
        assertEquals("살살(1초 4km/h)", 4.0, st[0].maxGain1s, 1e-9)
        st = launch(ramp(0.0, 3.0, 1.0) + ramp(3.0, 0.0, 1.0, 1.0) + hold(0.0, 2.0, 2.0))
        assertEquals("5km/h를 넘지 않고 다시 서면 출발이 아님", 0, st.size)
        val r = snap(gear = Gear.R)
        st = launch(ramp(0.0, -6.0, 1.0, tpl = r) + hold(-6.0, 6.0, 1.0, tpl = r))
        assertEquals("후진은 출발로 보지 않음", 0, st.size)
        val rec = Recorder()
        val s = sess(cfg(), listOf(rec.def))
        drive(s, listOf(snap(t = 0.0, gear = Gear.D)) + ramp(0.0, 13.0, 1.0) + hold(13.0, 3.0, 1.0))
        assertEquals("5초가 안 지났음", 0, rec.of<StartEvent>("start").size)
        drive(s, hold(13.0, 2.0, 4.0))
        assertEquals(1, rec.of<StartEvent>("start").size)
    }

    @Test
    fun brakeSegmentsAndEvaluation() {
        fun brakeOf(snaps: List<VehicleSnapshot>): List<BrakeEndEvent> {
            val rec = Recorder()
            drive(sess(cfg(), listOf(rec.def)), snaps)
            return rec.of("brakeEnd")
        }
        var b = brakeOf(hold(28.0, 2.0) + ramp(28.0, 0.0, 2.8, 2.0) + hold(0.0, 2.0, 4.8, brake = true))
        assertEquals(1, b.size)
        assertEquals(listOf(28.0, 0.0, 10.0, 2.8), listOf(b[0].v0, b[0].v1, b[0].maxDrop1s, b[0].dur))
        assertTrue(b[0].stopped && b[0].eval)
        assertEquals("정지 1초 뒤 끝", 5.8, b[0].t1, 1e-9)
        b = brakeOf(hold(8.0, 1.0) + ramp(8.0, 0.0, 1.0, 1.0) + hold(0.0, 2.0, 2.0, brake = true))
        assertFalse("시작 8km/h는 평가하지 않음", b[0].eval)
        b = brakeOf(hold(60.0, 1.0) + ramp(60.0, 45.0, 2.0, 1.0) + hold(45.0, 1.0, 3.0, brake = false))
        assertTrue(b[0].eval)
        assertFalse(b[0].stopped)
        assertEquals(45.0, b[0].v1, 1e-9)
        assertEquals(7.5, b[0].maxDrop1s, 1e-9)
        b = brakeOf(hold(60.0, 1.0) + ramp(60.0, 55.0, 1.0, 1.0) + hold(55.0, 1.0, 2.0, brake = false))
        assertFalse(b[0].eval)
        b = brakeOf(hold(36.0, 1.0) + ramp(36.0, 0.0, 4.0, 1.0) + hold(0.0, 2.0, 5.0, brake = true))
        assertEquals(9.0, b[0].maxDrop1s, 1e-9)
        b = brakeOf(hold(28.0, 1.0) + ramp(28.0, 0.0, 2.0, 1.0) + hold(0.0, 2.0, 3.0, brake = true))
        assertEquals(14.0, b[0].maxDrop1s, 1e-9)
    }

    @Test
    fun unreadableBrakeStartsTheSegmentWhenSpeedDrops() {
        val rec = Recorder()
        drive(sess(cfg(), listOf(rec.def)), hold(30.0, 1.0, brake = null) + ramp(30.0, 0.0, 3.0, 1.0, brake = null) + hold(0.0, 2.0, 4.0, brake = null))
        val b = rec.of<BrakeEndEvent>("brakeEnd")
        assertEquals(1, b.size)
        assertTrue(b[0].viaSpeed)
        assertEquals(30.0, b[0].v0, 1e-9)
        assertEquals(10.0, b[0].maxDrop1s, 1e-9)
        assertTrue(b[0].eval)
    }

    @Test
    fun signalWaitStartsAfterOneSecondAndBrakeEndComesFirst() {
        val rec = Recorder()
        val s = sess(cfg(), listOf(rec.def))
        drive(s, hold(20.0, 1.0) + ramp(20.0, 0.0, 2.0, 1.0) + hold(0.0, 0.9, 3.0, brake = true))
        assertEquals("0.9초는 아직", 0, rec.of<WaitStartEvent>("waitStart").size)
        drive(s, hold(0.0, 0.2, 3.9, brake = true))
        assertEquals(1, rec.of<WaitStartEvent>("waitStart").size)
        assertEquals(4.0, rec.of<WaitStartEvent>("waitStart")[0].t, 1e-9)
        assertEquals(listOf("brakeEnd", "waitStart"), rec.log.map { it.first }.filter { it == "brakeEnd" || it == "waitStart" })
        assertTrue(s.waiting)
        drive(s, ramp(0.0, 5.0, 1.0, 4.1))
        assertEquals(1, rec.log.count { it.first == "waitEnd" })
        assertFalse(s.waiting)
        val recN = Recorder()
        drive(sess(cfg(), listOf(recN.def)), hold(0.0, 1.5, tpl = snap(gear = Gear.N)))
        assertEquals("N도 신호 대기", 1, recN.of<WaitStartEvent>("waitStart").size)
        for (g in listOf(Gear.R, Gear.P)) {
            val r = Recorder()
            drive(sess(cfg(), listOf(r.def)), hold(0.0, 3.0, tpl = snap(gear = g)))
            assertEquals("$g 는 신호 대기가 아님", 0, r.of<WaitStartEvent>("waitStart").size)
        }
        val rg = Recorder()
        drive(sess(cfg(caps = SignalCaps(gear = false)), listOf(rg.def)), hold(0.0, 1.5))
        assertEquals("기어를 못 읽으면 D로 봄", 1, rg.of<WaitStartEvent>("waitStart").size)
    }

    @Test
    fun turnApproachInWindowAndBlinkOnDistance() {
        val rec = Recorder()
        drive(sess(cfg(), listOf(rec.def)), turnRoad(0.0, 200.0, 199) { _, d, s -> s.copy(blinker = if (d <= 55) Blinker.RIGHT else Blinker.OFF) })
        val ap = rec.of<TurnApproach>("turnApproach")
        assertTrue(ap.size > 100)
        assertEquals("150m부터", 150.0, ap[0].distance, 1e-9)
        assertTrue(ap[0].first)
        assertFalse(ap[1].first)
        assertTrue(ap.all { it.distance <= 150 })
        val at55 = ap.first { it.blinkOnDist != null }
        assertEquals(55.0, at55.distance, 1e-9)
        assertEquals(55.0, at55.blinkOnDist)
        assertTrue(at55.blinkOk)
        assertEquals(TurnDir.RIGHT, at55.blinkDir)
        assertEquals("처음 켠 거리를 그대로", 55.0, ap.last().blinkOnDist)
        assertNull("C1 때(70m)는 깜빡이 꺼짐", ap.first { it.distance == 70.0 }.blinkDir)
        val rec2 = Recorder()
        drive(sess(cfg(), listOf(rec2.def)), turnRoad(0.0, 200.0, 199) { _, _, s -> s.copy(blinker = Blinker.RIGHT) })
        assertEquals("창 밖에서 켰으면 150으로 자름", 150.0, rec2.of<TurnApproach>("turnApproach")[0].blinkOnDist)
        val rec3 = Recorder()
        drive(sess(cfg(), listOf(rec3.def)), tl(0.0, 10.0) { t, s ->
            s.copy(speed = 90.0, nav = nav(tbt = listOf(tbt("ic1", "TURN_ENTER_IC_R", 330 - Math.round(t * 10) * 2.5, TurnDir.RIGHT))))
        })
        val icAp = rec3.of<TurnApproach>("turnApproach")
        assertTrue(icAp.isNotEmpty())
        assertTrue(icAp[0].distance <= 300 && icAp[0].distance > 290)
        assertTrue(icAp[0].ic)
        assertEquals(300.0, icAp[0].window, 0.0)
    }

    @Test
    fun blinkerLeftOnFromThePreviousTurnDoesNotCountAndWrongSideIsRecorded() {
        val rec = Recorder()
        drive(sess(cfg(), listOf(rec.def)), tl(0.0, 30.0) { t, s ->
            val list = if (t < 10) listOf(tbt("a", "TURN_RIGHT", Math.round((10 - t) * 10).toDouble(), TurnDir.RIGHT))
            else listOf(tbt("b", "TURN_RIGHT", Math.round((30 - t) * 10).toDouble(), TurnDir.RIGHT))
            val on = (t >= 5 && t < 22) || t >= 25
            s.copy(speed = 36.0, blinker = if (on) Blinker.RIGHT else Blinker.OFF, nav = nav(tbt = list))
        })
        val bAp = rec.of<TurnApproach>("turnApproach").filter { it.id == "b" }
        assertNull("회전 a 때 켠 깜빡이는 회전 b에 세지 않음", bAp[0].blinkOnDist)
        assertFalse(bAp[0].blinkOk)
        assertEquals("지금 깜빡이 상태는 그대로 알려 줌", TurnDir.RIGHT, bAp[0].blinkDir)
        assertEquals("끄고 다시 켠 25초(50m)", 50.0, bAp.first { it.blinkOnDist != null }.blinkOnDist)
        val rec2 = Recorder()
        drive(sess(cfg(), listOf(rec2.def)), turnRoad(0.0, 160.0, 159) { _, d, s -> s.copy(blinker = if (d <= 80) Blinker.LEFT else Blinker.OFF) })
        val w = rec2.of<TurnApproach>("turnApproach").first { it.wrongOnDist != null }
        assertEquals(80.0, w.wrongOnDist)
        assertTrue(w.blinkWrong)
        assertNull(w.blinkOnDist)
    }

    @Test
    fun turnPassedAfterSteeringEnds() {
        val rec = Recorder()
        drive(sess(cfg(), listOf(rec.def)), turnRoad(0.0, 100.0, 160) { t, d, s ->
            val steer = when {
                t > 10.0 && t <= 11.0 -> (t - 10) * 20
                t > 11.0 && t <= 12.5 -> 20.0
                t > 12.5 && t <= 13.5 -> 20 - (t - 12.5) * 20
                else -> 0.0
            }
            s.copy(blinker = if (d <= 45) Blinker.RIGHT else Blinker.OFF, steer = Math.round(steer * 100) / 100.0)
        })
        val p = rec.of<TurnPassed>("turnPassed")
        assertEquals(1, p.size)
        assertEquals("t1", p[0].id)
        assertEquals(10.0, p[0].reachedT)
        assertEquals("5°를 넘은 첫 순간(10.3초에 6°)", 10.3, p[0].steerStartT)
        assertEquals(45.0, p[0].blinkOnDist)
        assertEquals(5.5, p[0].blinkOnT)
        assertFalse(p[0].missed)
        assertEquals("3° 아래로 내려간 순간", 13.4, p[0].steerEndT)
        assertTrue("같은 회전이 조향 회전으로도 잡힘", rec.log.any { it.first == "steerTurn" })
        assertEquals("조향 회전이 내비 회전과 이어짐", "t1", rec.of<SteerTurn>("steerTurn")[0].navTurnId)
        val rec2 = Recorder()
        drive(sess(cfg(), listOf(rec2.def)), turnRoad(0.0, 30.0, 130))
        val p2 = rec2.of<TurnPassed>("turnPassed")
        assertEquals(1, p2.size)
        assertNull(p2[0].steerStartT)
        assertEquals(3.0, p2[0].reachedT)
        assertEquals(109, drive(sess(cfg(), listOf(Recorder().def)), turnRoad(0.0, 30.0, 109)).steps.size)
    }

    @Test
    fun rerouteMarksTheTurnMissedAndItsJudgmentsExcluded() {
        val rec = Recorder()
        val judge = DetectorDef(id = "t1c.judge", items = listOf("C2")) { ctx ->
            ctx.on<TurnApproach> { a -> if (a.first) ctx.emit("C2", EventResult.LATE, EventData(turnId = a.id, distance = 20.0)) }
            ctx.on<TurnPassed> { p -> ctx.emit("C4", EventResult.OK, EventData(turnId = p.id, extra = kotlinx.serialization.json.JsonObject(mapOf("missed" to kotlinx.serialization.json.JsonPrimitive(p.missed))))) }
            DetectorInstance.NONE
        }
        val t2 = listOf(tbt("t2", "TURN_LEFT", 300.0, TurnDir.LEFT))
        val snaps = turnRoad(0.0, 60.0, 80) { t, _, s ->
            when {
                abs(t - 6.0) < 1e-9 -> s.copy(nav = nav(tbt = t2, events = listOf(NavEvent.Reroute("t1"))))
                t > 6 -> s.copy(nav = nav(tbt = t2))
                else -> s
            }
        }
        val s = sess(cfg(mode = 2), listOf(rec.def, judge))
        drive(s, snaps)
        val rr = rec.of<RerouteEvent>("reroute")
        assertEquals(1, rr.size)
        assertEquals("t1", rr[0].turnId)
        assertTrue(rec.of<TurnPassed>("turnPassed").first { it.id == "t1" }.missed)
        val out = s.finish(arrived = true)
        val c2 = out.events.filter { it.item == "C2" }
        assertEquals(1, c2.size)
        assertEquals(EventResult.EXCLUDED, c2[0].result)
        assertEquals(EventResult.LATE, c2[0].data.was)
        assertEquals("reroute", c2[0].data.excluded)
        assertEquals(EventResult.EXCLUDED, out.events.first { it.item == "C4" }.result)
    }

    @Test
    fun steerTurnNeedsMoreThanTwoSecondsOverTenDegrees() {
        fun steerSnaps(bigSec: Double) = tl(0.0, 20.0) { t, s ->
            val st = when {
                t >= 2 && t < 2.5 -> 6.0
                t >= 2.5 && t <= 2.5 + bigSec + 1e-9 -> 15.0
                t > 2.5 + bigSec && t < 2.5 + bigSec + 0.5 -> 4.0
                else -> 0.0
            }
            s.copy(steer = st, speed = 15.0)
        }
        val r20 = Recorder()
        drive(sess(cfg(), listOf(r20.def)), steerSnaps(2.0))
        assertEquals("딱 2.0초는 회전이 아님", 0, r20.of<SteerTurn>("steerTurn").size)
        val r21 = Recorder()
        drive(sess(cfg(), listOf(r21.def)), steerSnaps(2.1))
        val st = r21.of<SteerTurn>("steerTurn")
        assertEquals(1, st.size)
        assertEquals(TurnDir.RIGHT, st[0].dir)
        assertEquals(2.0, st[0].startT, 1e-9)
        assertEquals("4°(3 넘음)를 지나 3° 아래로 내려간 순간", 5.1, st[0].endT, 1e-9)
        val rl = Recorder()
        drive(sess(cfg(), listOf(rl.def)), tl(0.0, 10.0) { t, s -> s.copy(steer = if (t >= 1 && t <= 4) -15.0 else 0.0, speed = 15.0) })
        assertEquals(TurnDir.LEFT, rl.of<SteerTurn>("steerTurn")[0].dir)
        assertEquals(1.0, rl.of<SteerTurn>("steerTurn")[0].startT, 1e-9)
        assertEquals(4.1, rl.of<SteerTurn>("steerTurn")[0].endT, 1e-9)
    }

    @Test
    fun steerTurnBlinkWindowFiveSecondsBeforeToOneAfter() {
        fun run(blink: (Double) -> Boolean, side: Blinker = Blinker.RIGHT): SteerTurn {
            val r = Recorder()
            drive(sess(cfg(), listOf(r.def)), tl(0.0, 20.0) { t, s ->
                s.copy(steer = if (t >= 10 && t <= 13) 15.0 else 0.0, speed = 15.0, blinker = if (blink(t)) side else Blinker.OFF)
            })
            return r.of<SteerTurn>("steerTurn")[0]
        }
        var st = run({ it >= 5.0 && it < 5.5 })
        assertTrue(st.blinkBefore)
        assertTrue(st.blinkSameBefore)
        assertFalse(st.blinkAtStart)
        st = run({ it >= 4.5 && it < 4.9 })
        assertFalse("창 밖이면 깜빡이 없이 회전", st.blinkBefore)
        assertTrue(run({ it >= 10.9 && it < 11.5 }).blinkBefore)
        assertFalse(run({ it >= 11.2 && it < 12 }).blinkBefore)
        st = run({ it >= 7 && it < 14 })
        assertTrue(st.blinkAtStart)
        assertEquals("C4용: 같은 쪽 깜빡이를 켠 시각", 7.0, st.blinkOnT)
        st = run({ it >= 8 && it < 14 }, Blinker.LEFT)
        assertTrue("어느 쪽이든 깜빡이가 켜졌으면", st.blinkBefore)
        assertFalse(st.blinkSameBefore)
    }

    @Test
    fun straightWindowsMeasureSteerStd() {
        val r = Recorder()
        drive(sess(cfg(), listOf(r.def)), tl(0.0, 25.0) { t, s ->
            s.copy(speed = 40.0, steer = if (Math.round(t * 10) % 2 != 0L) 1.0 else -1.0, nav = NAV0.copy(heading = 90 + (t % 3)))
        })
        val w = r.of<StraightWindow>("straightWindow")
        assertEquals(2, w.size)
        assertTrue("std ${w[0].std}", abs(w[0].std - 1) < 0.01)
        assertTrue(w[0].t1 - w[0].t0 >= 10 - 1e-9)
        val slow = Recorder()
        drive(sess(cfg(), listOf(slow.def)), tl(0.0, 15.0) { _, s -> s.copy(speed = 15.0, steer = 0.5) })
        assertEquals("속도 15는 직진 창 아님(> 15)", 0, slow.of<StraightWindow>("straightWindow").size)
        val curve = Recorder()
        drive(sess(cfg(), listOf(curve.def)), tl(0.0, 15.0) { t, s -> s.copy(speed = 40.0, steer = 3.0, nav = NAV0.copy(heading = (t * 3) % 360)) })
        assertEquals("10초에 heading이 15° 넘게 바뀌면 아님", 0, curve.of<StraightWindow>("straightWindow").size)
        val wrap = Recorder()
        drive(sess(cfg(), listOf(wrap.def)), tl(0.0, 11.0) { t, s -> s.copy(speed = 40.0, steer = 0.0, nav = NAV0.copy(heading = (355 + t) % 360)) })
        assertEquals("0°를 넘어가도 변화량으로 셈", 1, wrap.of<StraightWindow>("straightWindow").size)
        val noNav = Recorder()
        drive(sess(cfg(caps = SignalCaps(nav = false)), listOf(noNav.def)), tl(0.0, 11.0) { _, s -> s.copy(speed = 40.0, steer = 0.4, nav = NAV0.copy(heading = 10.0)) })
        assertEquals("내비를 못 읽으면 핸들로만", 1, noNav.of<StraightWindow>("straightWindow").size)
    }

    @Test
    fun zonesChangeWithLimitOrSchoolZone() {
        val r = Recorder()
        drive(sess(cfg(), listOf(r.def)), tl(0.0, 10.0) { t, s -> s.copy(nav = NAV0.copy(limit = if (t < 3) 50 else 30, inSchoolZone = t >= 6 && t < 8)) })
        val enter = r.of<ZoneEnter>("zoneEnter")
        val exit = r.of<ZoneExit>("zoneExit")
        assertEquals(listOf(50.0 to false, 30.0 to false, 30.0 to true, 30.0 to false), enter.map { it.limit to it.school })
        assertEquals(4, enter.map { it.id }.toSet().size)
        assertEquals(enter.take(3).map { it.id }, exit.map { it.id })
        val none = Recorder()
        drive(sess(cfg(), listOf(none.def)), tl(0.0, 3.0) { _, s -> s.copy(nav = null) })
        assertEquals("내비를 못 읽으면 회전·구역 없음", 0, none.log.count { it.first == "zoneEnter" || it.first == "turnApproach" })
    }

    @Test
    fun arrivedAndWaypointEvents() {
        val r = Recorder()
        val s = sess(cfg(), listOf(r.def))
        drive(s, tl(0.0, 2.0) { t, sn ->
            when (t) {
                1.0 -> sn.copy(nav = NAV0.copy(events = listOf(NavEvent.WaypointArrived(index = 1, segment = 1, kind = "segment"))))
                2.0 -> sn.copy(nav = NAV0.copy(events = listOf(NavEvent.Arrived)))
                else -> sn
            }
        })
        assertEquals(listOf(Triple(1, 1, "segment")), r.of<WaypointEvent>("waypoint").map { Triple(it.index, it.segment, it.kind) })
        assertEquals(1, r.log.count { it.first == "arrived" })
        assertTrue(s.arrived)
        assertTrue("finish에 arrived를 안 주면 내비 도착을 씀", s.finish().arrived)
    }

    @Test
    fun detectorsAreOffForCapsModeOrMissingItems() {
        val made = ArrayList<String>()
        fun mk(id: String, items: List<String> = emptyList(), needs: List<com.barodrive.core.model.SignalKey> = emptyList(), modes: List<Int>? = null) =
            DetectorDef(id = id, items = items, needs = needs, modes = modes) { made += id; DetectorInstance.NONE }
        val dets = listOf(
            mk("c2", listOf("C2"), listOf(com.barodrive.core.model.SignalKey.BLINKER, com.barodrive.core.model.SignalKey.NAV)),
            mk("b1", listOf("B1"), listOf(com.barodrive.core.model.SignalKey.SPEED)), mk("pk", listOf("PK1"), modes = listOf(6)), mk("any"),
        )
        val s = sess(cfg(caps = SignalCaps(blinker = false)), dets)
        assertEquals(listOf("b1", "any"), made)
        assertEquals(listOf("c2" to "caps:blinker", "pk" to "mode"), s.info().off.map { it.id to it.reason })
        made.clear()
        sess(cfg(mode = 6), dets)
        assertEquals("X인 항목도 판정은 하니 검출기는 켬", listOf("c2", "b1", "pk", "any"), made)
        made.clear()
        val s3 = sess(BaroEngine.buildConfig(1, null, flags = EngineFlags(extraSafety = false)), listOf(mk("s3", listOf("C3", "C6", "J1")), mk("j1", listOf("J1"))))
        assertEquals(emptyList<String>(), made)
        assertEquals(listOf("items", "items"), s3.info().off.map { it.reason })
    }

    @Test
    fun unreadableSignalsAreNullInTheSnapshotDetectorsSee() {
        var seen: VehicleSnapshot? = null
        val d = DetectorDef(id = "t1c.reg", modes = listOf(1)) {
            object : DetectorInstance {
                override fun step(snap: VehicleSnapshot) {
                    seen = snap
                }
            }
        }
        val s = sess(cfg(caps = SignalCaps(speed = false, blinker = false, nav = false, trip = false)), listOf(d))
        s.step(snap(t = 0.1, gear = Gear.D, speed = 30.0, blinker = Blinker.LEFT, tripDist = 120.0, nav = NAV0.copy(limit = 50)))
        val sn = checkNotNull(seen)
        assertNull(sn.speed)
        assertNull(sn.blinker)
        assertNull(sn.nav)
        assertNull("trip을 못 읽으면 트립 거리도 null(core.js normSnap)", sn.tripDist)
        assertEquals(Gear.D, sn.gear)
    }

    @Test
    fun throwingDetectorIsLoggedAndTheSessionGoesOn() {
        val bad = DetectorDef(id = "t1c.bad") { ctx ->
            ctx.on<GearEvent> { error("일부러") }
            object : DetectorInstance {
                override fun step(snap: VehicleSnapshot) = error("또")
            }
        }
        val ok = script(listOf(sayAction(0.2, "t1c.info", "A1", com.barodrive.core.model.Category.INFO)))
        val s = sess(cfg(), listOf(bad, ok))
        val out = drive(s, tl(0.0, 0.5))
        assertTrue("다른 검출기는 그대로", said(out, "t1c.info"))
        assertTrue(s.errors.toString(), s.errors.any { it.contains("t1c.bad · GEAR") })
        assertTrue(s.errors.any { it.contains("t1c.bad · step") })
        val boom = sess(cfg(), listOf(DetectorDef(id = "t1c.boom") { error("만들기") }))
        assertTrue(boom.errors.any { it.contains("t1c.boom 만들기 오류") })
        assertEquals(listOf("t1c.boom" to "error"), boom.info().off.map { it.id to it.reason })
    }
}
