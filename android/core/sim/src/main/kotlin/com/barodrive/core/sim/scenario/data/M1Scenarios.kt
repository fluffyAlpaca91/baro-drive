package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.Cond
import com.barodrive.core.sim.scenario.LotInfo
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.ScenarioTemplate
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.scenario.Variant
import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.util.SeedRandom
import kotlin.math.max

/**
 * 1단계 시나리오 (프로토타입 data/scenarios/m1.js, 설계 7.7 S1-B·S1-A, 8.2 c1-A·B·C, 결정-42).
 *
 * - c1-B 시민체육관 주차장: 집 → 주차장 1.5km(회전 3, 30·50 구역) + 주차장 안 5바퀴(nav: false) · S1-B.
 * - c1-A 중앙공원 공영주차장: 같은 구성, 회전 방향·주차장 바퀴 수가 다름(4바퀴, 오른쪽으로 돎) · S1-A.
 * - c1-C 강변 둔치 주차장(내 코스): 경로를 등록하지 않는다 — 일반 경로 생성기 + 아래 1단계 틀이 주차장 연습을 붙인다.
 * 주차장 안은 내비 안내가 없는 고리(180° 돌기 두 번 = 한 바퀴, 15km/h). 회전 번호(turns)는 주차장 안 회전까지 모든 회전을 센다.
 */
internal object M1Scenarios {
    private const val LOT_CRUISE = 15

    private class Lot(val laps: Int, val dir: String, val side: Double, val r: Double, val entry: Double, val exit: Double)

    /** 주차장 안 연습 다리: 들어가는 길 → (직진 · 180° 돌기 · 직진 · 180° 돌기) × 바퀴 → 세울 자리. */
    private fun lotLegs(o: Lot): List<Leg> {
        val legs = ArrayList<Leg>()
        legs += go(o.entry, nav = false, cruise = LOT_CRUISE, road = "주차장")
        repeat(o.laps) {
            legs += go(o.side, cruise = LOT_CRUISE)
            legs += turn(o.dir, angle = 180, r = o.r)
            legs += go(o.side, cruise = LOT_CRUISE)
            legs += turn(o.dir, angle = 180, r = o.r)
        }
        legs += go(o.exit, cruise = LOT_CRUISE)
        return legs
    }

    /** 주차장 안 정지(바퀴마다 첫 직진 가운데). 원래 경로 거리 목록. */
    private fun lotStops(lotStart: Double, o: Lot): List<Double> {
        val lap = 2 * o.side + 2 * Math.PI * o.r
        return List(o.laps) { i -> jr(lotStart + o.entry + i * lap + o.side / 2) }
    }

    private val LOT_B = Lot(laps = 5, dir = "L", side = 36.0, r = 6.0, entry = 26.0, exit = 16.0)
    private val LOT_A = Lot(laps = 4, dir = "R", side = 34.0, r = 6.0, entry = 24.0, exit = 16.0)
    private val LOT_T = Lot(laps = 3, dir = "L", side = 36.0, r = 6.0, entry = 26.0, exit = 16.0)

    fun register(reg: ScenarioRegistry) {
        registerB(reg)
        registerA(reg)
        reg.registerTemplate(1, template)
    }

    /* ================================================================ c1-B · S1-B */
    private fun registerB(reg: ScenarioRegistry) {
        val road = listOf(
            go(240, limit = 30, road = "은행나무길"),
            turn("R", 90, r = 10), // 1 체육관로로 우회전
            go(380, limit = 50, road = "체육관로"), // 신호 정지(급정지)
            go(180, limit = 30, road = "체육관로"),
            turn("L", 90, r = 12), // 2 공원로로 좌회전
            go(420, limit = 50, road = "공원로"),
            turn("R", 90, r = 10), // 3 체육관길로 우회전
            go(240, limit = 30, road = "체육관길"), // 30 구역 · 끝 = 주차장 입구(도착)
        )
        val legs = road + lotLegs(LOT_B)
        val m = Layout(legs, ArcFormula.PI_180)
        val stops = lotStops(m.navEnd, LOT_B)
        val nRoad = m.turns.count { it.nav } // 3 — 주차장 회전은 4번부터
        reg.registerRoute("c1-B", RouteDef.legs(legs, routeOption = "편안한 길", name = "시민체육관 주차장"))
        reg.registerScenario(
            ScenarioDef(
                id = "S1-B", title = "1단계 · 시민체육관 주차장", mode = 1, courseId = "c1-B", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0),
                cruise = 40.0,
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 55.0), // 70m에서 꺼져 있음 → 사전 안내, 55m에서 켬 → 칭찬
                    2 to TurnPlan(blinkAt = 35.0), // 30m대 → '거의'
                    3 to TurnPlan(blinkAt = 45.0, steerAfter = 1.0), // 켜자마자 꺾음 → 다음 신호 대기에 카드
                    nRoad + 5 to TurnPlan(blink = false), // 주차장 3바퀴째 첫 돌기: 깜빡이 없이
                ),
                events = listOf(
                    ev(t = 0, act = Act.BELT, v = false), // 벨트 풀고 시작(능숙은 끔)
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    ev(t = 5.5, act = Act.BELT, v = true, cond = Cond.BELT_OFF), // 5초 뒤 맴(설계 5.3 3번)
                    mark("회전 앞", at = 130),
                    mark("신호 대기", at = 512), // 멈추기 바로 앞(1~2초 뒤 신호 대기 카드)
                    ev(at = 520, act = Act.STOP, drop = 10, hold = 14), // 신호 정지: 1초에 10km/h → 급정지 카드
                    mark("중간", at = 580),
                    ev(at = 1330, act = Act.OVER, speed = 38, dur = 6), // 30 구역 38km/h 6초 → 제한속도
                    mark("도착 직전", at = jr(m.navEnd - 150)),
                    mark("주차장", at = jr(m.navEnd + 5)),
                    ev(at = stops[0], act = Act.STOP, drop = 5, hold = 4),
                    ev(at = stops[1], act = Act.STOP, drop = 5, hold = 3),
                    ev(at = stops[2], act = Act.STOP, drop = 5, hold = 3),
                    ev(at = stops[3], act = Act.STOP, drop = 5, hold = 4),
                    ev(at = stops[3], act = Act.LAUNCH, gain = 13), // 4바퀴째 출발을 확 → 다음 신호 대기에 카드
                    ev(at = stops[4], act = Act.STOP, drop = 5, hold = 4),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.POOR to Variant(
                        turns = mapOf(nRoad + 9 to TurnPlan(blink = false)),
                        events = listOf(
                            ev(at = 520, act = Act.LAUNCH, gain = 9), // 서툰 +4 → 13: 급출발
                            ev(at = stops[0], act = Act.LAUNCH, gain = 9),
                            ev(at = stops[1], act = Act.LAUNCH, gain = 9),
                        ),
                    ),
                ),
                expect = listOf(
                    "A1", "B1.ok", "B1.fail", "C1", "C2.ok", "C2.almost", "C4.ok", "C4.fail", "C5.ok", "C5.fail", "F1.ok", "F1.fail",
                    "E1.ok", "E1.fail", "G1", "PB2.fail",
                ),
            ),
        )
    }

    /* ================================================================ c1-A · S1-A */
    private fun registerA(reg: ScenarioRegistry) {
        val road = listOf(
            go(260, limit = 30, road = "은행나무길"),
            turn("L", 90, r = 12), // 1 중앙로로 좌회전
            go(440, limit = 50, road = "중앙로"), // 신호 정지(부드럽게)
            turn("L", 90, r = 12), // 2 공원앞길로 좌회전
            go(340, limit = 30, road = "공원앞길"),
            turn("R", 90, r = 10), // 3 공영주차장 길로 우회전
            go(300, limit = 30, road = "공원로"), // 끝 = 주차장 입구(도착)
        )
        val legs = road + lotLegs(LOT_A)
        val m = Layout(legs, ArcFormula.PI_180)
        val stops = lotStops(m.navEnd, LOT_A)
        val nRoad = m.turns.count { it.nav }
        reg.registerRoute("c1-A", RouteDef.legs(legs, routeOption = "편안한 길", name = "중앙공원 공영주차장"))
        reg.registerScenario(
            ScenarioDef(
                id = "S1-A", title = "1단계 · 중앙공원 공영주차장", mode = 1, courseId = "c1-A", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0),
                cruise = 40.0,
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 60.0),
                    2 to TurnPlan(blinkAt = 25.0), // 20m대 → '늦음'
                    3 to TurnPlan(blinkAt = 50.0),
                    nRoad + 2 to TurnPlan(steerAfter = 1.0), // 주차장 2바퀴째 첫 돌기: 켜자마자 꺾음
                    nRoad + 5 to TurnPlan(blink = false), // 3바퀴째 둘째 돌기: 깜빡이 없이
                ),
                events = listOf(
                    ev(t = 0, act = Act.BELT, v = false),
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    ev(t = 5.5, act = Act.BELT, v = true, cond = Cond.BELT_OFF),
                    mark("회전 앞", at = 150),
                    mark("신호 대기", at = 552),
                    ev(at = 560, act = Act.STOP, drop = 6, hold = 12), // 신호 정지(부드럽게)
                    mark("중간", at = 620),
                    mark("도착 직전", at = jr(m.navEnd - 150)),
                    mark("주차장", at = jr(m.navEnd + 5)),
                    ev(at = stops[0], act = Act.STOP, drop = 5, hold = 4),
                    ev(at = stops[1], act = Act.STOP, drop = 5, hold = 3),
                    ev(at = stops[2], act = Act.STOP, drop = 5, hold = 4),
                    ev(at = stops[3], act = Act.STOP, drop = 5, hold = 4),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.POOR to Variant(
                        events = listOf( // 서툰: 급출발 3번(9 + 4 = 13)
                            ev(at = 560, act = Act.LAUNCH, gain = 9),
                            ev(at = stops[1], act = Act.LAUNCH, gain = 9),
                            ev(at = stops[2], act = Act.LAUNCH, gain = 9),
                        ),
                    ),
                ),
                expect = listOf("A1", "B1.ok", "C1", "C2.ok", "C2.late", "C4.ok", "C4.fail", "C5.ok", "C5.fail", "F1.ok", "E1.ok", "G1", "PB2.fail"),
            ),
        )
    }

    /* ================================================================ 1단계 틀 (생성 코스용)
     * 받은 다리(일반 생성 경로) 뒤에 주차장 연습 3바퀴(nav: false)를 붙이고, 회전 절반쯤 앞 신호 정지·장면('회전 앞'·'중간'·
     * '도착 직전'·'주차장')·주차장 정지를 만든다. 구간이 여럿이면 null(기본 틀). */
    private val template = ScenarioTemplate { course, opts ->
        val road = opts.legs?.takeIf { it.isNotEmpty() } ?: return@ScenarioTemplate null
        val id = course.id.ifEmpty { "c1" }
        val rng = SeedRandom.of("stage1:$id")
        val legs = road + lotLegs(LOT_T)
        val m = Layout(legs, ArcFormula.PI_180)
        val navTurns = m.turns.filter { it.nav }
        val events = ArrayList<ScenarioEvent>()
        events += ev(t = 0.5, act = Act.GEAR, v = Gear.D)
        // 회전 절반쯤 앞에서 신호 정지(생성 경로의 기본 틀과 같은 모양, 설계 7.4)
        var first: Double? = null
        navTurns.forEachIndexed { i, t ->
            if (t.s0 < 160) return@forEachIndexed
            if (rng() < (if (i == 0) 0.75 else 0.45)) {
                val at = jr(t.s0 - 12)
                events += ev(at = at, act = Act.STOP, drop = 5, hold = rng.int(6, 12))
                if (first == null) first = at
            }
        }
        if (navTurns.isNotEmpty()) events += mark("회전 앞", at = max(10.0, jr(navTurns[0].s0 - 110)))
        events += mark("중간", at = jr(first?.let { it + 40 } ?: (m.navEnd / 2)))
        events += mark("도착 직전", at = max(0.0, jr(m.navEnd - 150)))
        events += mark("주차장", at = jr(m.navEnd + 5))
        val stops = lotStops(m.navEnd, LOT_T)
        for (s in stops) events += ev(at = s, act = Act.STOP, drop = 5, hold = rng.int(3, 5))
        events += endPark()
        val nRoad = navTurns.size // 주차장 회전은 nRoad + 1번부터(2개씩 한 바퀴)
        ScenarioDef(
            id = "TPL:$id",
            title = "1단계 · " + (course.name ?: id) + " · 주차장 연습",
            mode = 1, courseId = course.id,
            start = StartState(gear = Gear.P, speed = 0.0),
            cruise = 32.0,
            route = RouteDef.legs(legs),
            turns = mapOf(nRoad + 3 to TurnPlan(steerAfter = 1.0)), // 주차장 2바퀴째: 켜자마자 꺾음(능숙은 3.5초로 늘림)
            events = events,
            variants = mapOf(
                DriverSkill.POOR to Variant( // 서툰: 급출발 두 번(9 + 4 = 13), 한 번 깜빡이 없이
                    turns = mapOf(nRoad + 4 to TurnPlan(blink = false)),
                    events = listOf(ev(at = first ?: stops[0], act = Act.LAUNCH, gain = 9), ev(at = stops[1], act = Act.LAUNCH, gain = 9)),
                ),
            ),
            expect = listOf("B1.ok", "C2", "C4.ok", "C4.fail", "C5.ok", "F1.ok", "E1", "G1"),
            generated = true,
            lot = LotInfo(LOT_T.laps, JsMath.round1(m.navEnd)),
        )
    }
}
