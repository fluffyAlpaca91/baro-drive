package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.ScenarioTemplate
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.scenario.Variant
import com.barodrive.core.sim.util.JsMath

/**
 * 4단계 시나리오 (프로토타입 data/scenarios/m4.js, 설계 7.7 S4-A, 8.2 c4-A·B, 6.3.1 C2(IC 110m), 6.5.2 규칙 5(합류 침묵), 결정-69).
 *
 * - c4-A 북IC → 동IC 한 구간: 시내 → 북IC 진입(TURN_ENTER_IC_R) → 진입로 60 → 고속도로 100 8.8km → 동IC 진출 → 나들목 → 시내(약 11.3km) · S4-A.
 *   완만한 IC 길은 핸들이 10° 넘게 돌지 않아 차가 깜빡이를 저절로 끄지 않으므로 운전자가 직접 끈다(사건).
 * - c4-B 남IC → 서IC: 경로를 등록하지 않는다 — 일반 경로 생성기(고속도로 다리) + 4단계 틀
 *   (기본 틀에 '완만한 회전 뒤 깜빡이 끄기'만 더함, 결정사항 E T3-5).
 */
internal object M4Scenarios {
    private const val WHEELBASE = 2.72 // 아반떼 — 핸들이 10° 넘게 도는 회전인지 가늠하는 데만 쓴다
    private const val AUTO_CANCEL = 10.0 // 차가 깜빡이를 저절로 끄는 조향

    fun register(reg: ScenarioRegistry) {
        registerA(reg)
        reg.registerTemplate(4, template)
    }

    private fun gentle(t: TurnAt): Boolean = JsMath.atan(WHEELBASE / t.r) / JsMath.DEG < AUTO_CANCEL

    /* ================================================================ c4-A · S4-A */
    private fun registerA(reg: ScenarioRegistry) {
        val legs = listOf(
            go(300, limit = 50, road = "은행나무길", key = "F0"),
            turn("R", 90, r = 12), // 1 북IC 가는 길로 우회전
            go(600, limit = 60, road = "북IC 가는 길", key = "F1"), // 신호 정지
            turn("R", 30, r = 140, tbt = "TURN_ENTER_IC_R", road = "북IC"), // 2 북IC 진입(110m 기준)
            go(450, limit = 60, road = "북IC 진입로", key = "F2"), // 합류(20초 침묵) · 68km/h
            go(8800, limit = 100, road = "고속도로", key = "F3"),
            turn("R", 25, r = 220, tbt = "TURN_EXIT_IC_R", road = "동IC"), // 3 동IC 진출
            go(500, limit = 60, road = "동IC 나들목", key = "F4"), // 나들목 끝 신호 — 급정지
            turn("R", 90, r = 12), // 4 동부로로 우회전
            go(450, limit = 50, road = "동부로", key = "F5"), // 도착
        )
        val la = Layout(legs, ArcFormula.D2R)
        reg.registerRoute("c4-A", RouteDef.legs(legs, routeOption = "고속도로 우선", name = "북IC → 동IC 한 구간"))
        reg.registerScenario(
            ScenarioDef(
                id = "S4-A", title = "4단계 · 북IC → 동IC", mode = 4, courseId = "c4-A", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0),
                cruise = 95.0,
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 55.0),
                    2 to TurnPlan(blinkAt = 130.0), // 북IC: 130m(110m 넘음 — 성공)
                    3 to TurnPlan(blinkAt = 105.0), // 동IC: 105m(TBT 100m — 거의)
                ),
                events = listOf(
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    mark("신호 대기", at = la.at("F1", 292)),
                    ev(at = la.at("F1", 300), act = Act.STOP, drop = 5, hold = 10),
                    mark("IC 진입", at = la.at("F1", 450)),
                    mark("합류", at = la.at("F2", 0)),
                    ev(at = la.at("F2", 5), act = Act.OVER, speed = 68, dur = 3), // 합류하려고 60 구역 68km/h → E1 — 합류 침묵 동안 줄여서 막힘
                    ev(at = la.at("F2", 25), act = Act.BLINKER, v = "OFF"), // 진입로에 들어서면 깜빡이를 끔(완만한 길이라 저절로 안 꺼짐)
                    mark("IC 진출", at = la.at("F3", 8800 - 1100)),
                    ev(at = la.at("F4", 25), act = Act.BLINKER, v = "OFF"), // 나들목에 들어서면 깜빡이를 끔
                    ev(at = la.at("F4", 470), act = Act.STOP, drop = 9, hold = 10), // 나들목 끝 신호에서 갑자기 브레이크(급정지)
                    mark("도착 직전", at = jr(la.navEnd - 150)),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.GOOD to Variant(turns = mapOf(3 to TurnPlan(blinkAt = 140.0))), // 능숙: 동IC도 미리(110m 넘게)
                ),
                expect = listOf("B1.ok", "C2.ok", "C2.almost", "C4.ok", "C5.ok", "E1.ok", "E1.fail", "F1.ok", "F1.fail", "G1"),
            ),
        )
    }

    /* ================================================================ 4단계 틀(생성 코스: c4-B 등)
     * 기본 틀 시나리오에 '완만한 회전 뒤 깜빡이 끄기'를 더한다. 내비 회전(IC 진입·진출)은 회전이 끝나고 25m 뒤,
     * 내비 안내가 없는 굽은 길(합류)은 굽은 길 가운데에서 끈다. 구간이 여럿이거나 다리가 없으면 null(기본 틀로). */
    private val template = ScenarioTemplate { course, opts ->
        val road = opts.legs?.takeIf { it.isNotEmpty() } ?: return@ScenarioTemplate null
        val gen = opts.generic ?: return@ScenarioTemplate null
        val id = course.id.takeIf { it.isNotEmpty() } ?: return@ScenarioTemplate null
        val base = gen(id, 4)
        val l = Layout(road, ArcFormula.D2R)
        val events = base.events.toMutableList()
        for (tr in l.turns) {
            if (!gentle(tr)) continue
            val navTurn = !tr.leg.noTbt && (tr.leg.tbt != null || tr.angle >= 10)
            val at = if (navTurn) tr.s1 + 25 else (tr.s0 + tr.s1) / 2
            events += ev(at = jr(at), act = Act.BLINKER, v = "OFF")
        }
        base.copy(
            id = "TPL:$id",
            title = "4단계 · " + (course.name ?: id) + " · 고속도로",
            mode = 4, courseId = id, events = events, expect = listOf("C2", "E1", "F1", "G1"), generated = true, template = 4,
        )
    }
}
