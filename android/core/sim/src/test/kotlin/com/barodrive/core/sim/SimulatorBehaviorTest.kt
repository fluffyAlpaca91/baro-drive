package com.barodrive.core.sim

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.SimTestSupport.L
import com.barodrive.core.sim.SimTestSupport.R
import com.barodrive.core.sim.SimTestSupport.endPark
import com.barodrive.core.sim.SimTestSupport.ev
import com.barodrive.core.sim.SimTestSupport.gearD
import com.barodrive.core.sim.SimTestSupport.go
import com.barodrive.core.sim.SimTestSupport.inline
import com.barodrive.core.sim.SimTestSupport.maxDrop
import com.barodrive.core.sim.SimTestSupport.run
import com.barodrive.core.sim.SimTestSupport.runId
import com.barodrive.core.sim.SimTestSupport.seconds
import com.barodrive.core.sim.SimTestSupport.st
import com.barodrive.core.sim.SimTestSupport.std
import com.barodrive.core.sim.SimTestSupport.straight
import com.barodrive.core.sim.SimTestSupport.trip
import com.barodrive.core.sim.SimTestSupport.turn
import com.barodrive.core.sim.SimTestSupport.n
import com.barodrive.core.sim.SimTestSupport.v
import com.barodrive.core.sim.generic.GenericRoutes
import com.barodrive.core.sim.player.Player
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteBuilder
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.DriveKind
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.scenario.ScenarioTemplate
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.vehicle.Vehicle
import com.barodrive.core.sim.vehicle.VehiclePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 시뮬레이터 동작 (프로토타입 tests/unit/t1b_sim.test.js — 같은 입력·같은 기댓값). */
class SimulatorBehaviorTest {
    private fun near(a: Double, b: Double, tol: Double, msg: String = "") = assertTrue("$msg — $a ≈ $b ±$tol", abs(a - b) <= tol)

    @Test
    fun legsToLengthTbtTypesAndDistances() {
        val legs = listOf(
            go(100, 30),
            turn(R, 90, 10), // TURN_RIGHT
            go(100),
            turn(L, 45, 12), // TURN_LEFT
            go(100),
            turn(R, 120, 8), // TURN_SHARP_R
            go(100),
            turn(L, 180, 6), // TURN_UTURN
            go(100),
            turn(L, 20, 60), // TURN_KEEP_L
            go(100),
            turn(R, 5, 100), // 굽은 길(TBT 없음)
            go(100),
            turn(R, 30, 150, tbt = "TURN_ENTER_IC_R", road = "북IC"),
            go(100, 100),
        )
        val route = RouteBuilder.build(RouteDef.legs(legs))
        assertEquals(listOf("TURN_RIGHT", "TURN_LEFT", "TURN_SHARP_R", "TURN_UTURN", "TURN_KEEP_L", null, "TURN_ENTER_IC_R"), route.turns.map { it.type })
        assertTrue(route.turns[6].ic)
        assertEquals("회전 다리에 길 이름을 주면 그것(IC 이름)", "북IC", route.turns[6].road)
        assertFalse("10° 아래 굽은 길은 TBT를 내지 않음", route.turns[5].nav)
        val arcs = listOf(10 to 90, 12 to 45, 8 to 120, 6 to 180, 60 to 20, 100 to 5, 150 to 30)
        val expectLen = 800 + arcs.sumOf { it.first * it.second * PI / 180 }
        near(route.length, expectLen, 1e-6, "길이")
        // 오른쪽 90° 뒤 방향은 동쪽(90°), 반지름 10이면 회전 길이 15.7m
        assertEquals(90L, Math.round(route.turns[0].hOut))
        near(route.turns[0].len, 15.708, 0.01)
        assertEquals("TURN_LEFT", RouteBuilder.tbtType(Leg.Turn(dir = L, angle = 99.0)))
        assertEquals("TURN_SHARP_L", RouteBuilder.tbtType(Leg.Turn(dir = L, angle = 100.0)))
        assertEquals("TURN_UTURN", RouteBuilder.tbtType(Leg.Turn(dir = R, angle = 150.0)))
        assertEquals("TURN_KEEP_R", RouteBuilder.tbtType(Leg.Turn(dir = R, angle = 29.0)))
        assertNull(RouteBuilder.tbtType(Leg.Turn(dir = R, angle = 9.0)))
        // 내비에 넣고 처음 자리에서: TBT 가까운 2개, 10m 내림, 제한속도
        val sim = BaroSim()
        sim.navi.setRoute(RouteDef.legs(legs), key = "test:types")
        sim.clock.tick()
        val snap = sim.snapshot()
        val nav = requireNotNull(snap.nav)
        assertEquals(NavStatus.ROUTE_DRIVE, nav.status)
        assertEquals(listOf(listOf("t1", "TURN_RIGHT", 100.0, TurnDir.RIGHT), listOf("t2", "TURN_LEFT", 210.0, TurnDir.LEFT)), nav.tbt.map { listOf(it.id, it.type, it.distance, it.dir) })
        assertEquals(30, nav.limit)
        // 원래 경로 거리 → 경로 거리(놓친 회전 앞 직진만큼 뒤로)
        val missed = RouteBuilder.build(RouteDef.legs(legs), RouteBuilder.Options(misses = mapOf(2 to 120.0)))
        near(missed.length, route.length + 120, 1e-6)
        assertEquals(150.0, JsMath.round1(missed.mapS(150.0)), 0.0)
        assertEquals(420.0, JsMath.round1(missed.mapS(300.0)), 0.0)
        assertEquals("t2r", missed.turns[1].id)
    }

    @Test
    fun tbtDistanceShrinksAndNextTurnComesForward() {
        val out = run(inline("TBT", listOf(go(200, 30), turn(R), go(150), turn(L), go(200))))
        assertEquals(PlayerState.ENDED, out.state)
        var lastId: String? = null
        var lastD = Double.POSITIVE_INFINITY
        val order = ArrayList<String>()
        for (s in out.snapshots) {
            val first = s.nav?.tbt?.firstOrNull() ?: continue
            if (first.id != lastId) {
                order += first.id
                lastId = first.id
                lastD = Double.POSITIVE_INFINITY
            }
            assertTrue("${first.id} 거리가 늘었어요", first.distance <= lastD)
            lastD = first.distance
        }
        assertEquals(listOf("t1", "t2"), order)
        val gone = out.snapshots.indexOfFirst { it.nav?.tbt?.firstOrNull()?.id == "t2" }
        assertEquals("0m를 지나고 다음 회전으로", 0.0, out.snapshots[gone - 1].n.tbt[0].distance, 0.0)
        assertTrue(out.snapshots[gone].trip >= 200 + 15.7 - 1)
    }

    @Test
    fun navVoiceAt300And120IcAt1kmAnd500mFor2point5Seconds() {
        val out = runId("TEST-1")
        val turnStart = 420.0
        val tv = out.voices.filter { "우회전" in it.text }
        assertEquals(listOf("300미터 앞에서 우회전입니다", "잠시 후 우회전입니다"), tv.map { it.text })
        for ((v, th) in listOf(tv[0] to 300.0, tv[1] to 120.0)) {
            val snap = out.snapshots.first { abs(it.t - v.t) < 1e-6 }
            val remain = turnStart - snap.trip
            assertTrue("${th}m 음성인데 남은 거리 $remain", remain <= th && remain > th - 2)
            assertEquals(2.5, v.dur, 0.0)
            assertTrue(snap.n.voice)
            val after = out.snapshots.first { it.t > v.t + 2.55 }
            assertFalse(after.n.voice)
        }
        assertTrue(out.voices.any { it.text == "목적지에 도착했습니다. 안내를 종료합니다" })
        // IC는 1km·500m
        val ic = run(
            inline(
                "IC", listOf(go(1600, 100, "고속도로"), turn(R, 25, 220, tbt = "TURN_EXIT_IC_R", road = "동IC"), go(300, 60)), mode = 4, cruise = 90.0,
            ),
        )
        assertEquals(listOf("1킬로미터 앞에서 오른쪽 방향 동IC 진출입니다", "500미터 앞에서 오른쪽 방향 동IC 진출입니다"), ic.voices.take(2).map { it.text })
        // 시나리오가 음성 시점을 바꿈(navVoiceAt)
        val nv = run(inline("NVA", listOf(go(400, 30), turn(L), go(100)), turns = mapOf(1 to TurnPlan(navVoiceAt = listOf(300.0, 75.0)))))
        val v75 = nv.voices.first { it.text == "잠시 후 좌회전입니다" }
        val s75 = nv.snapshots.first { abs(it.t - v75.t) < 1e-6 }
        assertTrue("75m 음성: ${400 - s75.trip}", abs(400 - s75.trip - 75) < 1.5)
    }

    @Test
    fun blinkerOnAtBlinkAtAndAutoCancelAfterTurn() {
        for (at in listOf(55.0, 40.0, 25.0)) {
            val out = run(inline("BLK$at", listOf(go(300, 30), turn(R), go(300)), turns = mapOf(1 to TurnPlan(blinkAt = at))))
            val tr = out.snapshots
            val i = tr.indexOfFirst { it.blinker == Blinker.RIGHT }
            val remain = 300 - tr[i].trip
            assertTrue("blinkAt $at → $remain", abs(remain - at) <= 1)
            assertEquals("TBT(10m 내림)로는 늘 같은 칸", floor(at / 10) * 10, floor(remain / 10) * 10, 0.0)
            // 자동 해제: 10° 넘게 꺾었다가 핸들이 3° 안으로 풀리면
            val offI = tr.indices.first { it > i && tr[it].blinker == Blinker.OFF }
            val peak = tr.subList(i, offI).maxOf { it.st }
            assertTrue("꺾은 뒤에 꺼짐", peak >= 10)
            assertTrue("회전 끝 무렵 ${tr[offI].trip}", tr[offI].trip > 300 + 15.7 - 6)
            assertTrue(abs(tr[offI].st) < 3)
        }
        val keep = run(inline("KEEP", listOf(go(200, 30), turn(R), go(400)), mode = 3, turns = mapOf(1 to TurnPlan(blinkAt = 50.0, keepOn = true))))
        val k = keep.snapshots
        val arcEnd = k.indexOfFirst { it.trip > 200 + 15.7 }
        val off = k.indices.first { it > arcEnd && k[it].blinker == Blinker.OFF }
        assertTrue("keepOn이면 회전 뒤 15초 가까이 켜져 있어요", k[off].t - k[arcEnd].t > 14)
        // blink: false → 깜빡이 없이 회전(조향 회전은 그대로)
        val none = run(inline("NOBLINK", listOf(go(200, 30), turn(L), go(200)), turns = mapOf(1 to TurnPlan(blink = false))))
        assertTrue(none.snapshots.all { it.blinker == Blinker.OFF })
        assertTrue("왼쪽 10° 넘는 조향이 2초 넘게", none.snapshots.count { it.st <= -10 } >= 21)
        // 반대 깜빡이(wrong): 먼저 반대쪽, 3초 뒤 바로잡음
        val wrong = run(inline("WRONG", listOf(go(300, 30), turn(R), go(200)), mode = 3, turns = mapOf(1 to TurnPlan(blinkAt = 60.0, wrong = true))))
        val w = wrong.snapshots
        val wl = w.indexOfFirst { it.blinker != Blinker.OFF }
        assertEquals(Blinker.LEFT, w[wl].blinker)
        val wr = w.indexOfFirst { it.blinker == Blinker.RIGHT }
        assertTrue(abs(w[wr].t - w[wl].t - 3) < 0.15)
    }

    @Test
    fun steerAfterStartsTurningEarlyAndArcUsesAtanWheelbaseOverRadius() {
        val out = run(inline("C4", listOf(go(300, 30), turn(L, r = 10), go(300)), mode = 2, cruise = 28.0, turns = mapOf(1 to TurnPlan(blinkAt = 45.0, steerAfter = 1.0))))
        val tr = out.snapshots
        val b = tr.indexOfFirst { it.blinker == Blinker.LEFT }
        val st = tr.indexOfFirst { it.st < -5 }
        val gap = tr[st].t - tr[b].t
        assertTrue("깜빡이 → 꺾기 ${gap}초", gap >= 1.0 && gap <= 1.3)
        assertTrue("회전 앞에서 먼저 꺾음", tr[st].trip < 300 - 20)
        val inArc = tr.filter { it.trip > 302 && it.trip < 313 }
        val delta = atan(2.72 / 10) / PI * 180
        assertTrue("회전 중 δ = $delta", inArc.any { abs(it.st + delta) < 0.01 })
        // 큰 조향(10°↑)은 회전 구간에서만 — 먼저 꺾는 동안은 7°
        val early = tr.filter { it.trip < 299 && it.st < -5 }
        assertTrue(early.isNotEmpty() && early.all { it.st >= -7.01 })
    }

    @Test
    fun stopEventMaxDropPerSecondEqualsDropAndHoldsBrake() {
        for (drop in listOf(6.0, 10.0, 13.0)) {
            val out = run(straight("STOP$drop", events = listOf(gearD(), ev(at = 400.0, act = Act.STOP, drop = drop, hold = 6.0), endPark())))
            val tr = out.snapshots
            val stopI = tr.indexOfFirst { it.trip > 300 && it.v == 0.0 }
            val brakeI = tr.indices.first { it > 50 && tr[it].brake == true && tr[it].v > 0 }
            val md = maxDrop(tr, brakeI, stopI + 5)
            assertTrue("drop $drop → $md", abs(md - drop) <= 0.5)
            assertTrue("정지 자리 ${tr[stopI].trip}", abs(tr[stopI].trip - 400) < 1.5)
            assertTrue("정지해 있는 동안 브레이크를 밟고 있어요", tr.subList(stopI, stopI + 55).all { it.v == 0.0 && it.brake == true })
        }
        // 실력: 서툰 +3(11 아래는 10.9까지) · 능숙 5 이하(11 넘는 급제동은 그대로)
        fun sk(skill: DriverSkill, drop: Double): Double {
            val out = run(straight("SK", events = listOf(gearD(), ev(at = 300.0, act = Act.STOP, drop = drop, hold = 3.0), endPark())), skill)
            val stopI = out.snapshots.indexOfFirst { it.trip > 200 && it.v == 0.0 }
            return maxDrop(out.snapshots, stopI - 80, stopI + 3)
        }
        assertTrue(abs(sk(DriverSkill.POOR, 6.0) - 9) <= 0.5)
        assertTrue("서툰이라도 급정지가 위험 회피 급제동(11)으로 바뀌지 않음", abs(sk(DriverSkill.POOR, 9.0) - 10.9) <= 0.5)
        assertTrue(sk(DriverSkill.GOOD, 9.0) <= 5.5)
        assertTrue(abs(sk(DriverSkill.GOOD, 13.0) - 13) <= 0.5)
    }

    @Test
    fun launchMaxGainPerSecondAfter5kmh() {
        fun gainOf(gain: Double?, skill: DriverSkill = DriverSkill.NORMAL): Double {
            val events = listOfNotNull(gearD(), gain?.let { ev(t = 1.0, act = Act.LAUNCH, gain = it) }, endPark())
            val tr = run(straight("LAUNCH", events = events), skill, until = seconds(30)).snapshots
            val i5 = tr.indexOfFirst { it.v >= 5 }
            var g = 0.0
            for (i in i5 until i5 + 50) g = max(g, tr[min(tr.size - 1, i + 10)].v - tr[i].v)
            return g
        }
        assertTrue("급출발 13", abs(gainOf(13.0) - 13) <= 0.5)
        assertTrue("보통 출발 4.7(1.3m/s²)", abs(gainOf(null) - 4.7) <= 0.5)
        assertTrue("서툰 +4", abs(gainOf(null, DriverSkill.POOR) - 8.7) <= 0.5)
        assertTrue("능숙 6 이하", gainOf(13.0, DriverSkill.GOOD) <= 6.01)
    }

    @Test
    fun wobbleStdOf10SecondStraightWindowMatchesSkill() {
        for ((skill, w) in listOf(DriverSkill.POOR to 1.25, DriverSkill.NORMAL to 0.95, DriverSkill.GOOD to 0.6)) {
            val tr = run(straight("WOB", cruise = 40.0), skill, until = seconds(90)).snapshots.filter { it.v > 15 }
            for (k in 0 until 4) {
                val win = tr.subList(40 + k * 73, 40 + k * 73 + 100).map { it.st }
                val sd = std(win)
                assertTrue("$skill 창 $k: $sd / $w", abs(sd - w) <= w * 0.15)
            }
        }
        // 시나리오가 wobble을 직접 줄 수도
        val win = run(straight("WOB2", cruise = 40.0, wobble = 2.0), until = seconds(60)).snapshots.filter { it.v > 15 }.subList(50, 150).map { it.st }
        assertTrue(abs(std(win) - 2.0) <= 0.3)
    }

    @Test
    fun sameSeedSameResultDifferentSkillDifferentResult() {
        val a = runId("TEST-1")
        val b = runId("TEST-1")
        assertEquals(a.snapshots, b.snapshots)
        assertEquals(a.voices, b.voices)
        val c = runId("TEST-1", DriverSkill.POOR)
        assertNotEquals(a.snapshots, c.snapshots)
        // 운전자 시드를 바꾸면 흔들림이 바뀜
        val d = ScenarioRunner.run(SimTarget.Scenario("TEST-1"), ScenarioRunner.Options(seed = "other"), SimTestSupport.sim())
        assertNotEquals(a.snapshots.map { it.steer }, d.snapshots.map { it.steer })
        // 일반 경로 생성기도 시드(이름)가 같으면 같은 길
        assertEquals(GenericRoutes.legs("c2-C:시장 옆 골목", 2800.0), GenericRoutes.legs("c2-C:시장 옆 골목", 2800.0))
        assertNotEquals(GenericRoutes.legs("c2-C:시장 옆 골목", 2800.0), GenericRoutes.legs("mart:마트", 2800.0))
    }

    @Test
    fun compressionAndSpeedEventWindowsSteadyAndManualRealTime() {
        val sim = SimTestSupport.sim()
        val p = sim.player
        val c = sim.clock
        p.load(SimTarget.Scenario("TEST-1"))
        p.play()
        assertEquals("출발 직후는 사건 구간", 3.0, p.compression(), 0.0)
        p.runUntil { sim.vehicle.state.s > 100 && sim.vehicle.state.speed >= 45 }
        assertEquals("회전이 300m 넘게 남은 순항", 10.0, p.compression(), 0.0)
        p.runUntil { sim.vehicle.state.s > 290 }
        assertEquals("회전 150m 안", 3.0, p.compression(), 0.0)
        // 실제 1초 → sim 압축 × 배속 (배율은 0.25초에 걸쳐 따라가므로 처음 프레임에 맞춰 둠)
        p.runUntil(maxSimSec = 1.0) { sim.vehicle.state.s > 120 }
        c.setSpeed(2)
        val t0 = c.simT
        var n = 0
        repeat(20) { n += c.advance(0.05) }
        val comp = p.compression()
        assertTrue("실제 1초 ×$comp ×2 → sim ${c.simT - t0}", abs(c.simT - t0 - comp * 2) <= 0.25)
        assertEquals(Math.round((c.simT - t0) * 10).toInt(), n)
        c.setSpeed(4)
        assertEquals(p.compression() * 4, c.targetRate(), 0.0)
        c.setSpeed(3)
        assertEquals("배속은 1·2·4만", 1, c.speed)
        // 일시정지면 시계가 서고, 손 조작(재생 중 아님)이면 ×1
        p.pause()
        assertEquals(0, c.advance(0.2))
        c.paused = false
        assertEquals("멈춘 채 손 조작은 ×1", 1.0, c.targetRate(), 0.0)
        // 아무 일 없으면(P · 0km/h) 시계가 선다
        sim.reset()
        repeat(30) { c.advance(0.2) }
        assertEquals(0, c.advance(0.2))
        // 파사드: advance가 돈 틱마다 스냅숏을 준다
        sim.load(SimTarget.Scenario("TEST-1"))
        sim.play()
        val snaps = sim.advance(0.2)
        assertTrue(snaps.isNotEmpty() && snaps.zipWithNext().all { (a, b) -> abs(b.t - a.t - 0.1) < 1e-9 })
    }

    @Test
    fun runUntilEndMarkAndNumberAndScenarioEvents() {
        val sim = SimTestSupport.sim()
        val p = sim.player
        val states = ArrayList<String>()
        var ff = 0
        sim.addListener { e ->
            if (e is SimEvent.Scenario) states += e.state.code + (e.mark?.let { ":$it" } ?: "")
            if (e is SimEvent.Tick && e.ff) ff++
        }
        val info = p.load(SimTarget.Scenario("TEST-1"), DriverSkill.NORMAL)
        assertEquals("TEST-1", info.id)
        assertEquals(listOf("회전 앞", "30 구역", "도착 직전"), info.marks)
        val r1 = p.runUntilMark("30 구역")
        assertEquals("cond", r1.reason)
        assertEquals(PlayerState.PLAYING, p.state)
        assertEquals(50, p.runFor(5.0).ticks)
        val r = p.runUntilEnd()
        assertEquals("cond", r.reason)
        assertEquals(PlayerState.ENDED, p.state)
        assertEquals(Gear.P, sim.vehicle.state.gear)
        assertTrue(sim.navi.arrived)
        assertTrue(ff > 500)
        assertEquals(listOf("loaded", "playing"), states.take(2))
        assertTrue("playing:도착 직전" in states)
        assertEquals("ended:도착 직전", states.last())
        assertThrows(IllegalArgumentException::class.java) { p.runUntilMark("없는 장면") }
    }

    @Test
    fun capsTurnSnapshotValuesIntoNullAndReverseSpeedIntoMagnitude() {
        val sim = BaroSim()
        val s0 = sim.snapshot()
        assertNotNull(s0.nav)
        assertEquals("야간 기본이라 하향등도 켬", true, s0.lowBeam)
        sim.setCaps(SignalCaps(speed = false, gear = false, blinker = false, steer = false, nav = false, passenger = false, beltDriver = false, handbrake = false, wiper = false, outsideTemp = false))
        val s1 = sim.snapshot()
        listOf(s1.speed, s1.gear, s1.blinker, s1.steer, s1.nav, s1.passenger, s1.beltDriver, s1.handbrake, s1.wiper, s1.outsideTemp).forEach { assertNull(it) }
        assertNotNull("트립 거리는 늘 읽힘(차속 대안)", s1.tripDist)
        assertNotNull(s1.brake)
        sim.reset()
        sim.vehicle.set(VehiclePatch(gear = Gear.R, speed = -4.0), Vehicle.Source.SIM)
        assertEquals(-4.0, sim.snapshot().v, 0.0)
        sim.setCaps(SignalCaps(reverseSpeed = false))
        assertEquals(4.0, sim.snapshot().v, 0.0)
    }

    @Test
    fun missedTurnReroutesAfter30mWithEventVoiceAndNewTurnId() {
        val out = run(
            inline(
                "MISS", listOf(go(250, 30), turn(R), go(200), turn(L), go(250)), mode = 2, turns = mapOf(2 to TurnPlan(miss = true)),
                events = listOf(gearD(), ev(at = 600.0, act = Act.STOP, drop = 5.0, hold = 2.0), endPark()),
            ),
        )
        assertEquals(listOf("t2"), out.reroutes)
        assertTrue(out.voices.any { it.text == "경로를 다시 찾았습니다" })
        val tr = out.snapshots
        val ri = tr.indexOfFirst { s -> s.n.events.any { it is NavEvent.Reroute } }
        assertTrue(ri > 0)
        assertEquals(listOf<NavEvent>(NavEvent.Reroute("t2")), tr[ri].n.events)
        assertTrue("사건은 그 틱에 한 번만", tr.withIndex().all { (k, s) -> k == ri || s.n.events.none { it is NavEvent.Reroute } })
        assertEquals("재탐색 전까지 놓친 회전(0m)", "t2", tr[ri - 1].n.tbt[0].id)
        assertEquals("재탐색 뒤 새 회전", "t2r", tr[ri].n.tbt[0].id)
        val t2Start = 250 + 15.708 + 200
        assertTrue(abs(tr[ri].trip - (t2Start + 30)) < 1.5)
        // 놓친 회전에서는 깜빡이·핸들 없이 직진
        val near = tr.filter { it.trip > t2Start - 60 && it.trip < t2Start + 25 }
        assertTrue(near.all { it.blinker == Blinker.OFF && abs(it.st) < 5 })
        // 'at' 정지는 원래 경로 거리: 놓친 회전 뒤라 120m(돌아가는 길)만큼 뒤에서 섬
        val stopI = tr.indexOfFirst { it.trip > 300 && it.v == 0.0 && it.gear == Gear.D }
        assertTrue("정지 ${tr[stopI].trip}", abs(tr[stopI].trip - (600 + 120)) < 1.5)
        assertEquals(PlayerState.ENDED, out.state)
    }

    @Test
    fun segmentsEndWithWaypointEventNextSegmentVoiceAndLastSegmentArrives() {
        val def = ScenarioDef(
            id = "SEG", mode = 8, cruise = 35.0,
            route = RouteDef(
                listOf(
                    listOf(go(200, 30), turn(R), go(150)),
                    listOf(go(150, 50), Leg.Waypoint(), turn(L), go(200)),
                    listOf(go(250, 30)),
                ),
            ),
            events = listOf(gearD(), endPark()),
        )
        val out = run(def)
        val segs = out.waypoints.filter { it.kind == "segment" }
        assertEquals(listOf(listOf(1, 2, 3), listOf(2, 3, 3)), segs.map { listOf(it.segment, it.next, it.count) })
        assertEquals(1, out.waypoints.count { it.kind == "waypoint" })
        assertEquals(2, out.voices.count { "다음 구간 안내를 시작합니다" in it.text })
        assertEquals(listOf(1, 2, 3), out.snapshots.mapNotNull { it.nav?.segment?.index }.distinct())
        // 구간마다 그 구간 회전만 TBT에(다음 구간 회전은 그 구간에 들어서야)
        assertEquals(listOf("t1"), out.snapshots[1].n.tbt.map { it.id })
        assertTrue(out.arrived)
        assertEquals(PlayerState.ENDED, out.state)
    }

    @Test
    fun manualThrottleBrakeParkAndManualTouchPausesPlayback() {
        val sim = SimTestSupport.sim()
        val d = sim.driver
        val v = sim.vehicle
        val c = sim.clock
        sim.set(VehiclePatch(gear = Gear.D))
        sim.pressPedal("throttle", true)
        c.step(4.0)
        sim.pressPedal("throttle", false)
        assertTrue("4초 가속 ${v.state.speed}", abs(v.state.speed - 4 * 4.7) < 0.6)
        c.step(2.0)
        assertTrue("떼면 그 속도 유지(손 조작 목표)", abs(v.state.speed - 18.8) < 0.6)
        d.manual.setTarget(40.0)
        c.step(10.0)
        assertEquals(40.0, v.state.speed, 0.0)
        sim.pressPedal("brake", true)
        c.step(2.0)
        assertTrue(v.state.brake && v.state.speed < 30)
        sim.pressPedal("brake", false)
        sim.lockBrake(true)
        c.step(10.0)
        assertEquals(0.0, v.state.speed, 0.0)
        sim.lockBrake(false)
        assertTrue("고리 길을 따라 움직임", v.state.s > 50)
        // 달리는 중 패널 P → 먼저 세우고 P
        d.manual.setTarget(30.0)
        c.step(10.0)
        assertEquals("later", sim.shift(Gear.P))
        c.step(8.0)
        assertEquals(Gear.P, v.state.gear)
        assertEquals(0.0, v.state.speed, 0.0)
        // 미뤄 둔 기어는 그 사이 바로 바꾼 기어가 취소한다(달리며 R → 다시 D면 멈춘 뒤에도 D)
        assertEquals("now", sim.shift(Gear.D))
        d.manual.setTarget(30.0)
        c.step(10.0)
        assertEquals("later", sim.shift(Gear.R))
        assertEquals("now", sim.shift(Gear.D))
        c.step(15.0)
        assertEquals("R을 고를 때 목표 0 → 섬", 0.0, v.state.speed, 0.0)
        assertEquals("나중에 고른 D가 이김 — 멈춘 뒤 R로 바뀌지 않음", Gear.D, v.state.gear)
        // 재생 중 손 조작 → 일시정지(시계는 손 조작으로 계속)
        val p = sim.player
        p.load(SimTarget.Scenario("TEST-1"))
        p.runUntil { sim.vehicle.state.speed > 20 }
        val got = ArrayList<String>()
        sim.addListener { if (it is SimEvent.Manual) got += it.key }
        sim.set(VehiclePatch(passenger = true))
        assertEquals("조수석·벨트 같은 신호는 재생을 멈추지 않음", PlayerState.PLAYING, p.state)
        sim.set(VehiclePatch(steer = 10.0))
        assertEquals(PlayerState.PAUSED, p.state)
        assertEquals(listOf("steer"), got)
        assertFalse(c.paused)
        assertEquals(1.0, c.targetRate(), 0.0)
    }

    @Test
    fun gearEventAfterStateIsCompleteAndParkStopsTheCar() {
        val sim = BaroSim()
        val seen = ArrayList<List<Any?>>()
        sim.addListener { e -> if (e is SimEvent.Gear) seen += listOf(e.from, e.to, sim.snapshot().gear) }
        sim.vehicle.set(VehiclePatch(gear = Gear.D, speed = 20.0), Vehicle.Source.SIM)
        sim.vehicle.set(VehiclePatch(gear = Gear.P), Vehicle.Source.SIM)
        assertEquals(listOf(listOf(Gear.P, Gear.D, Gear.D), listOf(Gear.D, Gear.P, Gear.P)), seen)
        assertEquals(0.0, sim.vehicle.state.speed, 0.0)
        assertEquals("같은 값은 바뀐 것 없음", emptyList<String>(), sim.vehicle.set(VehiclePatch(gear = Gear.P)))
    }

    @Test
    fun allInjectionsRegisteredAndChangeSignalsDuringPlaybackAndManualDriving() {
        val ids = BaroSim().injections.map { it.id }
        for (id in listOf(
            "beltStart", "handbrakeStart", "passenger", "hardLaunch", "hardStop", "hazardBrake", "turnAhead", "blink55", "blink25",
            "quickSteer", "steerNoBlink", "wrongBlink", "blinkStays", "speed30", "schoolSpeed", "wobble", "rain", "slippery", "downhill", "jam",
            "naviVoice", "reroute", "rollback", "nightNoLamp", "arrive",
        )) assertTrue("주입 $id", id in ids)
        for (x in BaroSim().injections) assertTrue("${x.id} 이름·묶음", x.label.isNotEmpty() && x.group.isNotEmpty())
        // 재생 중: 급정지 → 1초 9km/h, 위험 회피 → 14, 30 구역 과속 → 38km/h + 제한 30
        val out = run(
            straight("INJ", cruise = 40.0), until = seconds(80),
            injections = listOf(
                ScenarioRunner.Injection(15.0, "hardStop"), ScenarioRunner.Injection(30.0, "speed30"),
                ScenarioRunner.Injection(45.0, "hazardBrake"), ScenarioRunner.Injection(60.0, "rain"),
            ),
        )
        val tr = out.snapshots
        val at = { t: Double -> tr.indexOfFirst { it.t >= t } }
        assertTrue(abs(maxDrop(tr, at(15.0), at(22.0)) - 9) <= 0.5)
        assertTrue(tr.subList(at(32.0), at(40.0)).any { it.v >= 37.9 && it.n.limit == 30 })
        assertTrue(abs(maxDrop(tr, at(45.0), at(52.0)) - 14) <= 0.5)
        assertEquals(true, tr.last().wiper)
        // 손 운전(경로 없음)에서 다 눌러도 오류 없음
        val sim = BaroSim()
        sim.set(VehiclePatch(gear = Gear.D))
        sim.driver.manual.setTarget(30.0)
        sim.clock.step(12.0)
        for (x in sim.injections) {
            val msg = sim.inject(x.id)
            assertTrue("${x.id} → 글", msg.isNotEmpty())
            sim.clock.step(3.0)
        }
        assertTrue(sim.errors.toString(), sim.errors.isEmpty())
    }

    @Test
    fun injectedTurnAheadAndBlink55BindToThatTurn() {
        val sim = SimTestSupport.sim()
        val p = sim.player
        p.load(SimTarget.Inline(inline("LONG", listOf(go(1200, 30), turn(R), go(300)))))
        p.runUntil { sim.vehicle.state.s > 200 }
        sim.inject("turnAhead")
        val n = requireNotNull(sim.navi.nextTurn())
        assertTrue("100m 앞 회전 ${n.remain}", abs(n.remain - 100) < 12)
        assertTrue(n.ref.injected)
        sim.inject("blink55")
        val id = n.ref.id
        var blinkRemain: Double? = null
        p.runUntil(maxSimSec = 120.0) {
            if (blinkRemain == null && sim.snapshot().blinker == Blinker.RIGHT) {
                blinkRemain = sim.navi.distTo(requireNotNull(sim.navi.path).turns.first { it.id == id })
            }
            sim.navi.isPassed(id)
        }
        val br = requireNotNull(blinkRemain)
        assertTrue("55m에서 켬: $br", abs(br - 55) <= 1.5)
        p.runUntilEnd()
        assertEquals("뒤 경로가 돌아도 끝까지 감", PlayerState.ENDED, p.state)
    }

    @Test
    fun skipNextJumpsToMarkSilentlyAndSkipEndGoesTo60mBeforeTheEnd() {
        val sim = SimTestSupport.sim()
        val p = sim.player
        p.load(SimTarget.Scenario("TEST-1"))
        var ticks = 0
        sim.addListener { if (it is SimEvent.Tick) ticks++ }
        val r = requireNotNull(sim.skip(Player.Skip.NEXT))
        assertEquals("회전 앞", r.mark)
        assertEquals("건너뛰는 동안 sim:tick 없음", 0, ticks)
        assertEquals("불러온 채였으면 멈춘 채로", PlayerState.PAUSED, p.state)
        assertTrue(sim.vehicle.state.s >= 280)
        sim.tick()
        val sn = sim.snapshot()
        assertTrue("다음 틱 스냅숏에 skip", sn.skip && sn.skipInfo?.from?.s == 0.0 && (sn.skipInfo?.to?.s ?: 0.0) >= 280)
        sim.tick()
        assertFalse("한 틱만", sim.snapshot().skip)
        p.play()
        sim.skip(Player.Skip.END)
        assertTrue(sim.vehicle.state.s >= requireNotNull(sim.navi.path).length - 61)
        assertEquals(PlayerState.PLAYING, p.state)
        p.runUntilEnd()
        assertEquals(PlayerState.ENDED, p.state)
        assertNull(sim.skip(Player.Skip.NEXT))
    }

    @Test
    fun loadOrderCourseDefaultThenModeTemplateThenGenericAndPlaceScenarios() {
        val sim = BaroSim()
        val p = sim.player
        val reg = sim.registry
        reg.registerRoute("TT-1", RouteDef.legs(listOf(go(300, 30), turn(R), go(300))))
        reg.registerScenario(ScenarioDef(id = "TT-A", mode = 2, courseId = "TT-1"))
        reg.registerScenario(ScenarioDef(id = "TT-B", mode = 2, courseId = "TT-1", isDefault = true, events = listOf(gearD())))
        assertEquals("TT-B", p.load(SimTarget.Course("TT-1")).id)
        // 모드 틀: 시나리오가 없는 코스(c9-Z = 모드 9)
        var gotLegs = false
        reg.registerTemplate(9, ScenarioTemplate { course, opts ->
            gotLegs = opts.legs != null
            ScenarioDef(id = "TPL-9", mode = 9, courseId = course.id, cruise = 20.0, events = listOf(gearD(), endPark()))
        })
        val t = p.load(SimTarget.Course("c9-Z"))
        assertEquals("TPL-9", t.id)
        assertEquals("template", t.kind)
        assertTrue("틀에 코스 다리 목록을 넘김", gotLegs)
        // 기본 틀(일반 생성): 어떤 코스든 끝까지 돈다
        val g = ScenarioRunner.run(SimTarget.Course("c3-Z"))
        assertEquals("generated", g.info.kind)
        assertEquals(PlayerState.ENDED, g.state)
        assertTrue(g.arrived)
        // 장소: 그 장소 시나리오(없으면 생성)
        reg.registerScenario(ScenarioDef(id = "PL-1", mode = 5, placeId = "zz-place", route = RouteDef.legs(listOf(go(200, 30))), events = listOf(gearD(), endPark())))
        assertEquals("PL-1", p.load(SimTarget.Place("zz-place")).id)
        assertEquals(PlayerState.ENDED, ScenarioRunner.run(SimTarget.Place("nowhere")).state)
        assertThrows(IllegalArgumentException::class.java) { p.load(SimTarget.Scenario("없는-시나리오")) }
    }

    @Test
    fun genericRoutesMatchDistanceNeverTurnBackAndHighwayHasIcs() {
        for ((seed, d) in listOf("a" to 2600.0, "b" to 4100.0, "c" to 1400.0, "d" to 6200.0)) {
            val route = RouteBuilder.build(RouteDef.legs(GenericRoutes.legs(seed, d)))
            assertTrue("$seed: ${route.length} / $d", abs(route.length - d) < d * 0.15 + 120)
            var rel = 0.0
            for (tr in route.turns) {
                rel += if (tr.dir == TurnDir.RIGHT) tr.angle else -tr.angle
                assertTrue("진행 방향이 뒤로", abs(rel) <= 90)
            }
        }
        val hw = RouteBuilder.build(RouteDef.legs(GenericRoutes.legs("c4-B", 14200.0, mode = 4, from = "남IC", to = "서IC")))
        val types = hw.turns.filter { it.nav }.map { it.type }
        assertTrue("TURN_ENTER_IC_R" in types && "TURN_EXIT_IC_R" in types)
        assertTrue(hw.zones.any { it.limit == 100 })
        assertEquals(2600.0, requireNotNull(GenericRoutes.distOf(mapOf("distance" to "2.6km"))), 0.0)
        assertEquals(4100.0, requireNotNull(GenericRoutes.distOf(mapOf("km" to 4.1))), 1e-9)
        assertEquals(12400.0, requireNotNull(GenericRoutes.distOf(mapOf("dist" to 12.4))), 1e-9)
    }

    @Test
    fun eventsOnlyScenarioCreepsWithBrakeReleasedReverseIsNegativeAndEnds() {
        val def = ScenarioDef(
            id = "EVT", mode = 6,
            events = listOf(
                ev(t = 1.0, act = Act.GEAR, v = Gear.R), ev(t = 2.0, act = Act.STEER, v = -30), ev(t = 3.0, act = Act.BRAKE, v = false),
                ev(t = 6.0, act = Act.OVER, speed = -9.0, dur = 1.5), ev(t = 10.0, act = Act.BRAKE, v = true), ev(t = 12.0, act = Act.STEER, v = 0),
                ev(t = 14.0, act = Act.GEAR, v = Gear.P), ScenarioEvent(end = true),
            ),
        )
        val out = run(def, until = seconds(40))
        val tr = out.snapshots
        val at = { t: Double -> tr.first { it.t >= t } }
        assertEquals("브레이크를 밟고 있음", 0.0, at(2.5).v, 0.0)
        assertTrue("후진 기어 천천히 ${at(5.5).v}", at(5.5).v <= -3.9 && at(5.5).v >= -4.01)
        assertTrue("후진 9km/h(PK2)", tr.minOf { it.v } <= -8.9)
        assertTrue(abs(at(5.0).st + 30) < 0.01)
        assertEquals(0.0, at(13.0).v, 0.0)
        assertEquals(PlayerState.ENDED, out.state)
        assertEquals(DriveKind.EVENTS, out.sim.player.current?.drive)
    }

    @Test
    fun runnerWithoutEngineRunsTheSimulatorOnly() {
        val out = ScenarioRunner.run(SimTarget.Scenario("TEST-1"), sim = SimTestSupport.sim())
        assertEquals(PlayerState.ENDED, out.state)
        assertTrue(out.arrived)
        assertTrue(out.errors.isEmpty())
        assertEquals("세션은 기어가 P를 벗어난 틱에", Gear.D, out.snapshotAt(requireNotNull(out.sessionStartTick)).gear)
        assertEquals("그 틱에 시계 0", 0.0, out.snapshotAt(requireNotNull(out.sessionStartTick)).t, 0.0)
    }
}
