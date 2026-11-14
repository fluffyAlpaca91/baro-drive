package com.barodrive.core.sim

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.Courses
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.generic.GenericRoutes
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteBuilder
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.ParkingChoice
import com.barodrive.core.sim.scenario.TemplateCourse
import com.barodrive.core.sim.scenario.TemplateOptions
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.vehicle.VehiclePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 시나리오 데이터와 시나리오 재생의 시뮬레이터 쪽 사실 (프로토타입 t2a_m1 · t3_scenarios · t5b_guide · t6_hill · t7_weather · t8_license
 * · t11_coverage의 시나리오 부분 — 판정·말은 엔진 몫이라 뺐다). 걸리는 시간(×1 실제 초)은 설계 7.2 압축으로 잰다.
 */
class ScenarioDataTest {
    private val reg = BaroSim().registry

    private fun run(target: SimTarget, skill: DriverSkill = DriverSkill.NORMAL, sim: BaroSim = BaroSim(), until: ScenarioRunner.Until = ScenarioRunner.Until.End, parking: ParkingChoice? = null) =
        ScenarioRunner.run(target, ScenarioRunner.Options(skill = skill, until = until, parking = parking), sim)

    private fun run(id: String, skill: DriverSkill = DriverSkill.NORMAL) = run(SimTarget.Scenario(id), skill)

    /** ×1에서 걸리는 실제 시간(설계 7.2 압축): 재생 중 틱마다 0.1 ÷ 압축. */
    private fun realSec(target: SimTarget, skill: DriverSkill = DriverSkill.NORMAL, parking: ParkingChoice? = null): Double {
        val sim = BaroSim()
        var real = 0.0
        run(target, skill, sim, ScenarioRunner.Until.Predicate {
            if (sim.player.state == PlayerState.PLAYING) real += 0.1 / sim.player.compression()
            sim.player.state == PlayerState.ENDED
        }, parking)
        return real
    }

    private fun realSec(id: String) = realSec(SimTarget.Scenario(id))

    private fun find(target: SimTarget) = BaroSim().player.find(target, DriverSkill.NORMAL)

    private fun templateOpts(legs: List<Leg>?, segments: List<List<Leg>>? = null) =
        TemplateOptions(DriverSkill.NORMAL, ParkingChoice(), null, null, legs, segments, null)

    /** 깜빡이를 켠 틱마다 (그때 TBT 첫 회전 id, 거리). */
    private fun blinkOn(r: ScenarioRun): List<Pair<String, Double>> = r.snapshots.zipWithNext()
        .filter { (a, b) -> a.blinker == Blinker.OFF && b.blinker != Blinker.OFF }
        .mapNotNull { (_, b) -> b.nav?.tbt?.firstOrNull()?.let { it.id to it.distance } }

    /* ================================================================ 1단계 (t2a_m1) */

    @Test
    fun stage1DataRoutesScenariosAndTemplate() {
        for (id in listOf("c1-A", "c1-B")) {
            val legs = requireNotNull(reg.route(id)).segments.single()
            assertTrue(id, legs.size > 10)
            assertTrue("$id 주차장 안", legs.any { it.nav == false })
        }
        assertNull("c1-C는 일반 경로 생성기", reg.route("c1-C"))
        for ((sid, cid) in listOf("S1-A" to "c1-A", "S1-B" to "c1-B")) {
            val sc = requireNotNull(reg.scenario(sid))
            assertTrue(sid, sc.isDefault && sc.courseId == cid && sc.mode == 1 && sc.expect.size >= 10)
        }
        assertEquals("S1-B", find(SimTarget.Course("c1-B")).def.id)
        assertEquals("template", find(SimTarget.Course("c1-C")).kind)
        val tpl = requireNotNull(reg.template(1))
        assertNull(tpl.make(TemplateCourse("x"), templateOpts(null)))
        assertNull(tpl.make(TemplateCourse("x"), templateOpts(null, listOf(listOf(Leg.Go(100.0)), listOf(Leg.Go(100.0))))))
    }

    @Test
    fun stage1TemplateAppendsParkingLotLapsAndRunsToTheEnd() {
        val out = run(SimTarget.Course("c1-C"))
        assertEquals("template", out.info.kind)
        assertEquals("TPL:c1-C", out.info.id)
        assertTrue(out.arrived)
        assertEquals(listOf("회전 앞", "중간", "도착 직전", "주차장"), out.sim.player.marks().map { it.name })
        assertEquals("주차장 3바퀴 = 180° 돌기 6번", 6, requireNotNull(out.sim.navi.path).turns.count { !it.nav })
        assertEquals("같은 코스면 같은 시나리오(시드)", out.snapshots, run(SimTarget.Course("c1-C")).snapshots)
        assertTrue(out.errors.isEmpty())
    }

    @Test
    fun stage1RealTimeAndDeterminism() {
        val b = realSec("S1-B")
        val a = realSec("S1-A")
        assertTrue("S1-B ×1 ${b}초", b > 90 && b < 140)
        assertTrue("S1-A ×1 ${a}초", a > 80 && a < 125)
        assertEquals(run("S1-B").snapshots, run("S1-B").snapshots)
    }

    /* ================================================================ 2~4단계 (t3_scenarios) */

    @Test
    fun stage2to4DataRoutesScenariosAndTemplates() {
        for (id in listOf("c2-A", "c2-B", "c3-A", "c3-C", "c4-A")) {
            val r = requireNotNull(reg.route(id)) { id }
            assertTrue(id, r.segments.single().size >= 7)
            assertTrue("$id 경로 옵션", !r.routeOption.isNullOrEmpty())
        }
        for (id in listOf("c2-C", "c3-B", "c4-B")) assertNull("${id}는 일반 경로 생성기", reg.route(id))
        for ((sid, cid, mode) in listOf(Triple("S2-B", "c2-B", 2), Triple("S2-A", "c2-A", 2), Triple("S3-A", "c3-A", 3), Triple("S3-N", "c3-C", 3), Triple("S4-A", "c4-A", 4))) {
            val sc = requireNotNull(reg.scenario(sid))
            assertTrue(sid, sc.isDefault && sc.courseId == cid && sc.mode == mode && sc.expect.size >= 8)
            assertEquals(sid, find(SimTarget.Course(cid)).def.id)
        }
        assertEquals("generated", find(SimTarget.Course("c2-C")).kind)
        assertEquals("generated", find(SimTarget.Course("c3-B")).kind)
        assertEquals("template", find(SimTarget.Course("c4-B")).kind)
        // c2-B: 경유지 2곳 · 어린이보호구역 1 · 회전 6 · 놓칠 회전(재탐색)은 5번 좌회전
        val legs = requireNotNull(reg.route("c2-B")).segments.single()
        assertEquals(2, legs.count { it is Leg.Waypoint })
        assertEquals(1, legs.count { it is Leg.Go && it.school.isNotEmpty() })
        val turns = legs.filterIsInstance<Leg.Turn>()
        assertEquals(6, turns.size)
        assertEquals(TurnPlan(miss = true), requireNotNull(reg.scenario("S2-B")).turns[5])
        assertEquals("좌회전 지점을 지나침(설계 5.3 9번)", TurnDir.LEFT, turns[4].dir)
        // c4-A: IC 진입·진출 TBT
        assertEquals(listOf("TURN_ENTER_IC_R", "TURN_EXIT_IC_R"), requireNotNull(reg.route("c4-A")).segments.single().filterIsInstance<Leg.Turn>().mapNotNull { it.tbt })
        // c2-A: 출발점(집)으로 돌아오는 고리 — 경로 끝이 처음 자리에서 20m 안
        val r = RouteBuilder.build(requireNotNull(reg.route("c2-A")))
        val end = r.sample(r.length)
        assertTrue("고리", hypot(end.x - r.start.x, end.y - r.start.y) < 20)
    }

    @Test
    fun s2bNormalStorySimSide() {
        val out = run("S2-B")
        assertTrue(out.arrived)
        assertEquals(listOf("t5"), out.reroutes)
        assertTrue(out.voices.any { it.text == "경로를 다시 찾았습니다" })
        assertEquals(2, out.waypoints.count { it.kind == "waypoint" })
        assertEquals(listOf("회전 앞", "신호 대기", "30 구역", "어린이보호구역", "급제동", "재탐색", "도착 직전"), out.sim.player.marks().map { it.name })
        // 깜빡이를 켠 거리(TBT 10m 내림) = 설계 5.3 '깜빡이 5/6'(t1 50 · t2 40 · t3 30 · t4 50 · 다시 찾은 t5r 50 · t6 50)
        assertEquals(listOf("t1" to 50.0, "t2" to 40.0, "t3" to 30.0, "t4" to 50.0, "t5r" to 50.0, "t6" to 50.0), blinkOn(out))
        // 능숙: 벨트를 풀지 않고 재탐색은 그대로
        val good = run("S2-B", DriverSkill.GOOD)
        assertTrue(good.snapshots.all { it.beltDriver == true })
        assertEquals(listOf("t5"), good.reroutes)
    }

    @Test
    fun s4aIcVoicesAndGeneratedCoursesRunToArrival() {
        val vt = run("S4-A").voices.map { it.text }
        assertTrue(vt.joinToString(" / "), "500미터 앞에서 오른쪽 방향 북IC 진입입니다" in vt)
        assertTrue("1킬로미터 앞에서 오른쪽 방향 동IC 진출입니다" in vt && "500미터 앞에서 오른쪽 방향 동IC 진출입니다" in vt)
        for (cid in listOf("c2-C", "c3-B", "c4-B")) {
            for (skill in listOf(DriverSkill.NORMAL, DriverSkill.GOOD)) {
                val out = run(SimTarget.Course(cid), skill)
                assertTrue("$cid $skill", out.arrived)
                assertEquals(cid, out.info.courseId)
                assertTrue(out.errors.isEmpty())
            }
        }
        val tpl = run(SimTarget.Course("c4-B"), DriverSkill.GOOD)
        assertEquals("TPL:c4-B", tpl.info.id)
        val offs = requireNotNull(tpl.sim.player.def).events.count { it.act == Act.BLINKER }
        assertTrue("IC 진입·합류·진출", offs >= 3)
        val fn = requireNotNull(reg.template(4))
        assertNull(fn.make(TemplateCourse("x"), templateOpts(null)))
        assertNull(fn.make(TemplateCourse("x"), templateOpts(null, listOf(listOf(Leg.Go(100.0)), listOf(Leg.Go(100.0))))))
    }

    @Test
    fun s3aJamWindowLastsOverAMinute() {
        val out = run("S3-A")
        val jam = out.snapshots.filter { it.nav?.congested == true }
        assertTrue(jam.isNotEmpty())
        assertTrue("정체 구간 ${jam.first().t}~${jam.last().t}", jam.last().t - jam.first().t > 60)
        // 반대 깜빡이: 2번 좌회전 90m 앞에서 오른쪽 → 3초 뒤 왼쪽
        assertEquals(listOf("t1" to 60.0, "t2" to 90.0, "t3" to 50.0, "t4" to 60.0), blinkOn(out))
    }

    @Test
    fun stage2to4RealTimeAndDeterminism() {
        val want = mapOf("S2-B" to (100.0 to 150.0), "S2-A" to (80.0 to 125.0), "S3-A" to (90.0 to 135.0), "S3-N" to (70.0 to 110.0), "S4-A" to (75.0 to 120.0))
        for ((id, range) in want) {
            val sec = realSec(id)
            assertTrue("$id ×1 ${sec}초", sec > range.first && sec < range.second)
        }
        assertEquals(run("S3-A").snapshots, run("S3-A").snapshots)
    }

    /* ================================================================ 실제 주차 (t5b_guide 시나리오 부분) */

    @Test
    fun s6pFollowsTheParkingChoiceAndParkingCoursesUseIt() {
        val sim = BaroSim()
        sim.chooseParking("back", "L")
        val sc = requireNotNull(sim.registry.scenario("S6-P", sim.parkingChoice))
        assertTrue(sc.mode == 6 && sc.courseId == null)
        assertEquals(ParkingChoice("back", "L"), sc.parking)
        assertTrue(sc.title.orEmpty().contains("후방(후진) · 왼쪽 칸"))
        sim.chooseParking("front", "R")
        for (cid in listOf("c6-A", "c6-B")) {
            val f = sim.player.find(SimTarget.Course(cid), DriverSkill.NORMAL)
            assertEquals(cid, "template", f.kind)
            assertEquals("S6-P", f.def.id)
            assertNull("$cid 경로 없음", f.def.courseId)
            assertEquals(ParkingChoice("front", "R"), f.def.parking)
        }
        sim.reset()
        val info = sim.load(SimTarget.Course("c6-A"))
        assertEquals("S6-P", info.id)
        assertNull(info.routeKey)
        assertEquals(NavStatus.INACTIVE, sim.snapshot().nav?.status)
        assertEquals("1단계,2단계,3단계,4단계,5단계", info.marks.joinToString(","))
        sim.chooseParking("parallel", "L")
        assertEquals(8, requireNotNull(sim.registry.scenario("S6-P", sim.parkingChoice)).events.count { it.mark != null })
        // ×1 실제 길이: 평행 > 후방 > 전방, 평행 20~60초
        val real = listOf("parallel", "back", "front").associateWith { realSec(SimTarget.Scenario("S6-P"), parking = ParkingChoice(it, "R")) }
        assertTrue(real.toString(), real.getValue("parallel") > real.getValue("back") && real.getValue("back") > real.getValue("front"))
        assertTrue("평행 ${real["parallel"]}", real.getValue("parallel") in 20.0..60.0)
        // 장면을 지날 때 그 단계 입력([다음 단계])은 1단계만 시나리오가 누른다(보통)
        val out = run(SimTarget.Scenario("S6-P"), parking = ParkingChoice("parallel", "R"))
        assertEquals(listOf<SessionInput>(SessionInput.PkNext(1)), out.inputs.map { it.second })
        assertEquals(PlayerState.ENDED, out.state)
    }

    /* ================================================================ 언덕 (t6_hill 시나리오 부분 · t11 T11-8) */

    @Test
    fun hillDataRoutesScenarioAndTemplate() {
        for (id in listOf("c7-A", "c7-B")) {
            val legs = requireNotNull(reg.route(id)).segments.single()
            assertTrue(id, legs.size >= 5)
            assertTrue("$id 내리막 주의 구간", legs.any { it is Leg.Go && it.cautions.any { c -> c.type == CautionType.DOWNHILL_CAUTION_AREA } })
        }
        val sc = requireNotNull(reg.scenario("S7-B"))
        assertTrue(sc.isDefault && sc.mode == 7 && sc.courseId == "c7-B")
        assertEquals("S7-B", find(SimTarget.Course("c7-B")).def.id)
        assertEquals("template", find(SimTarget.Course("c7-A")).kind)
        // 장면 목록: 시간·거리 장면이 섞이면 시나리오에 적은 순서 그대로(T11-8)
        val sim = BaroSim()
        sim.load(SimTarget.Scenario("S7-B"))
        assertEquals(sc.events.mapNotNull { it.mark }, sim.marks().map { it.name })
        assertEquals("오르막 출발", sim.marks().first().name)
    }

    @Test
    fun s7bEverySkillStopsAtTheParkingSpotBeforeSituationSteerAndPark() {
        for (skill in DriverSkill.entries) {
            val out = run(SimTarget.Scenario("S7-B"), skill)
            val sitTick = out.inputs.last { it.second is SessionInput.HillSituation }.first
            val tr = out.snapshots
            val sit = tr[sitTick - 1]
            val pTick = tr.indexOfLast { it.gear != Gear.P } + 2 // P가 된 틱
            val stopIdx = (0 until sitTick).last { abs(tr[it].speed ?: 0.0) >= 0.5 } + 1 // 선 틱
            assertTrue("$skill: 선 뒤 1초 넘게 지나서 상황", sit.t - tr[stopIdx].t >= 1)
            assertTrue("$skill: P까지 서 있음", tr.subList(stopIdx, pTick - 1).all { abs(it.speed ?: 0.0) < 0.5 })
            val steerTick = tr.indexOfFirst { it.t > sit.t && (it.steer ?: 0.0) > 8 } + 1
            assertTrue("$skill: 바퀴를 돌린 뒤 P", steerTick in (sitTick + 1) until pTick)
            assertEquals(listOf<SessionInput>(SessionInput.HillPress, SessionInput.HillPress, SessionInput.HillPress, SessionInput.HillSituation(com.barodrive.core.model.HillSlope.DOWN)), out.inputs.map { it.second })
        }
        val real = realSec("S7-B")
        assertTrue("×1 ${real}초(설계 7.7 2분)", real > 60 && real < 150)
    }

    @Test
    fun hillTemplateOnHandWrittenAndGeneratedRoutes() {
        val out = run(SimTarget.Course("c7-A"))
        assertTrue(out.arrived)
        assertEquals(listOf("오르막 출발", "두 번째 오르막", "내리막", "도착 직전"), out.sim.marks().map { it.name })
        val poor = run(SimTarget.Course("c7-A"), DriverSkill.POOR)
        assertTrue("서툰: 1번째 오르막에서 뒤로 밀림", poor.snapshots.any { (it.speed ?: 0.0) < -1 && it.gear == Gear.D })
        // 생성 경로(첫 직진이 짧고 내리막 구간이 없음) → 첫 직진을 300m로 늘리고 긴 직진에 내리막 주의 구간을 넣음
        val legs = GenericRoutes.legs("t6-gen", 1700.0, mode = 7)
        assertTrue((legs[0] as Leg.Go).len < 300 && legs.none { it is Leg.Go && it.cautions.isNotEmpty() })
        val def = requireNotNull(requireNotNull(reg.template(7)).make(TemplateCourse("t6-gen", "시험 언덕", 7), templateOpts(legs)))
        val dl = requireNotNull(def.route).segments.single()
        assertEquals(300.0, (dl[0] as Leg.Go).len, 0.0)
        assertTrue(dl.any { it is Leg.Go && it.cautions.any { c -> c.type == CautionType.DOWNHILL_CAUTION_AREA } })
        val gen = run(SimTarget.Inline(def.copy(id = "T6-GEN", courseId = null)))
        assertTrue(gen.arrived)
        assertNull("구간 여럿이면 기본 틀", requireNotNull(reg.template(7)).make(TemplateCourse("x"), templateOpts(null, listOf(listOf(Leg.Go(100.0)), listOf(Leg.Go(100.0))))))
    }

    /* ================================================================ 날씨 (t7_weather 시나리오 부분) */

    @Test
    fun weatherScenariosCompanyRouteAndSnowOnC2B() {
        val sim = BaroSim()
        val info = sim.load(SimTarget.Place("company"))
        assertEquals("S5-R", info.id)
        assertEquals(5, info.mode)
        assertTrue("길이 ${info.lengthM}", (info.lengthM ?: 0) in 4501..4699)
        assertEquals("place:company", sim.navState().key)
        assertTrue(requireNotNull(reg.scenario("S5-R")).isDefault)
        val def = requireNotNull(reg.scenario("S2-S"))
        assertEquals("c2-B", def.courseId)
        assertFalse("코스 기본은 S2-B(이건 패널에서 고름)", def.isDefault)
        assertEquals(-2.0, requireNotNull(def.start?.outsideTemp), 0.0)
        // T3 c2-B 경로 그대로: 학교앞길·초등학교길·학교뒷길·공원길 신호, 어린이보호구역 바로 앞 27km/h
        val goes = ArrayList<Pair<Double, Leg.Go>>()
        var s = 0.0
        for (l in requireNotNull(reg.route("c2-B")).segments.single()) {
            if (l is Leg.Go) {
                goes += s to l
                s += l.len
            } else if (l is Leg.Turn) s += (l.r ?: 10.0) * l.angle * Math.PI / 180
        }
        assertEquals(7, goes.size)
        val at = { i: Int, off: Int -> Math.round(goes[i].first + off).toDouble() }
        assertEquals(listOf(at(1, 230), at(2, 170), at(3, 230), at(5, 200)), def.events.filter { it.act == Act.STOP }.map { it.at })
        assertEquals(listOf(com.barodrive.core.sim.route.Span(70.0, 330.0)), goes[3].second.school)
        val over = def.events.first { it.act == Act.OVER }
        assertTrue("구역 바로 앞에서 속도를 올림", requireNotNull(over.at).let { it >= at(3, 55) && it < at(3, 70) })
        val snow = run("S2-S")
        assertTrue(snow.arrived)
        assertTrue(snow.snapshots.all { it.outsideTemp == -2.0 })
    }

    /* ================================================================ 운전면허 (t8_license 시나리오 부분) */

    @Test
    fun licenseRoutesHaveThreeSegmentsAndMatchCourseLength() {
        for (c in Courses.testCourses) {
            val def = requireNotNull(reg.route(c.id)) { c.id }
            assertEquals(c.id, 3, def.segments.size)
            assertEquals("추천", def.routeOption)
            val r = RouteBuilder.build(def)
            assertEquals(3, r.segments.size)
            for (i in 0 until 3) assertTrue("${c.id} 구간 ${i + 1}", r.waypoints.count { it.seg == i } <= 2)
            assertTrue("${c.id} IC 없음", r.turns.none { it.ic })
            assertTrue(c.id, r.turns.all { it.nav })
            assertTrue("${c.id} 길이 ${r.navEnd} / ${c.km}km", abs(r.navEnd - c.km * 1000) / (c.km * 1000) < 0.02)
            assertTrue("${c.id} 60 넘는 구역 없음", r.pieces.all { (it.limit ?: 0) <= 60 })
            assertTrue("${c.id} 회전 ${r.turns.size}", r.turns.size >= 6)
        }
        assertEquals("S8-C", find(SimTarget.Course("c8-C")).def.id)
        assertEquals("template", find(SimTarget.Course("c8-A")).kind)
        val sc = requireNotNull(reg.scenario("S8-C"))
        assertTrue(sc.mode == 8 && sc.courseId == "c8-C" && sc.isDefault && sc.start?.passenger == true)
        assertEquals("S8-C 회전 6(설계 7.7)", 6, RouteBuilder.build(requireNotNull(reg.route("c8-C"))).turns.count { it.nav })
    }

    @Test
    fun s8cSegmentsInOrderAndArrival() {
        val out = run("S8-C")
        assertEquals(listOf(listOf(1, 2, 3), listOf(2, 3, 3)), out.waypoints.filter { it.kind == "segment" }.map { listOf(it.segment, it.next, it.count) })
        assertEquals("구간 1·3에 경유지 1곳씩", listOf("waypoint", "segment", "segment", "waypoint"), out.waypoints.map { it.kind })
        assertTrue("마지막 구간 도착 = 세션 도착", out.arrived)
        val segEnds = out.voices.filter { "다음 구간 안내를 시작합니다" in it.text }
        val arrive = out.voices.first { "목적지에 도착했습니다" in it.text }
        assertEquals(2, segEnds.size)
        assertTrue("구간 1 끝 → 구간 2 끝 → 도착", segEnds[0].t < segEnds[1].t && segEnds[1].t < arrive.t)
        assertEquals(listOf("회전 앞", "신호 대기", "구간 2", "중간", "동승자 카드", "구간 3", "도착 직전"), out.sim.marks().map { it.name })
        assertTrue("코스 데이터 5.3km ${out.snapshots.last().tripDist}", abs(requireNotNull(out.snapshots.last().tripDist) - 5300) < 160)
        assertTrue("2구간에서 조수석이 빔", out.snapshots.any { it.passenger == false && it.nav?.segment?.index == 2 })
        val good = run("S8-C", DriverSkill.GOOD)
        assertTrue("능숙: 조수석·사이드브레이크 사건 없음", good.snapshots.all { it.passenger == true && it.handbrake != true || it.gear == Gear.P })
        val real = realSec("S8-C")
        assertTrue("S8-C ×1 ${real}초", real > 100 && real < 175)
    }

    @Test
    fun licenseTemplateRunsCoursesABDThroughThreeSegments() {
        for (id in listOf("c8-A", "c8-B", "c8-D")) {
            val out = run(SimTarget.Course(id))
            assertEquals(id, listOf("template", "TPL:$id"), listOf(out.info.kind, out.info.id))
            assertEquals(id, listOf(1, 2), out.waypoints.filter { it.kind == "segment" }.map { it.segment })
            assertTrue(id, out.arrived)
            val marks = out.sim.marks().map { it.name }
            assertEquals(id, listOf("구간 2", "구간 3"), marks.filter { it.startsWith("구간") })
            assertTrue(id, marks.indexOf("도착 직전") > 0)
            if (id == "c8-A") assertEquals("같은 코스면 같은 시나리오", out.snapshots, run(SimTarget.Course(id)).snapshots)
        }
        val tpl = requireNotNull(reg.template(8))
        assertNull(tpl.make(TemplateCourse("x"), templateOpts(null)))
        // 생성 경로(일반 경로 생성기의 3구간)에도 쓴다
        val c8z = com.barodrive.core.model.Course(id = "c8-Z", mode = 8, letter = "Z", name = "연습 코스", km = 4.2, min = 15, congestion = 0, routeOption = "추천", straightKm = 1.0, segments = 3)
        val gen = GenericRoutes.routeFor(GenericRoutes.Target.Course("c8-Z", c8z))
        val made = requireNotNull(tpl.make(TemplateCourse("c8-Z", "연습 코스"), templateOpts(null, gen.segments)))
        assertEquals(8, made.mode)
        assertTrue(made.events.any { it.mark == "구간 3" })
    }

    /* ================================================================ 시연 패널 손 조작 (t11 T11-3) */

    @Test
    fun panelBrakeStaysPressedAcrossTicksAndReleases() {
        val sim = BaroSim()
        sim.set(VehiclePatch(gear = Gear.D))
        sim.set(VehiclePatch(brake = true))
        assertTrue("브레이크 고정이 같이 켜짐", sim.driver.manual.brakeLock)
        repeat(20) { sim.tick() }
        assertTrue("틱이 지나도 밟은 채", sim.vehicle.state.brake)
        sim.set(VehiclePatch(brake = false))
        assertFalse("풀면 고정도 풀림", sim.driver.manual.brakeLock)
    }

    /* ================================================================ 목록 */

    @Test
    fun listIsSortedByModeThenIdAndCoversEveryPrototypeScenario() {
        val list = BaroSim().list()
        assertEquals(
            listOf("S1-A", "S1-B", "S9-R", "S2-A", "S2-B", "S2-S", "S3-A", "S3-N", "S4-A", "S5-M", "S5-R", "S6-P", "S7-B", "S8-C"),
            list.map { it.id },
        )
        assertTrue(list.first { it.id == "S6-P" }.title.startsWith("주차 연습 · 실제 주차 안내(평행 · 오른쪽 칸)"))
    }
}
