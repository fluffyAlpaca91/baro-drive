package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.scenario.Variant

/**
 * 3단계 시나리오 (프로토타입 data/scenarios/m3.js, 설계 7.7 S3-A·S3-N, 8.2 c3-A·B·C, 6.5.2 규칙 6(혼잡), 6.3.1 C3·C6·J1).
 *
 * - c3-A 시청 앞 큰길: 왕복 4차로 50·60 구역, 회전 4, 역전로에 정체 구간 하나(3번 회전까지 이어짐, 약 6.0km) · S3-A
 *   (2번 좌회전 90m 앞 반대 깜빡이 C3 → 3초 뒤 바로잡음 · 정체 안 급정지 · 3번 회전 뒤 깜빡이 15초 켜 둠 C6).
 * - c3-C 강변대로: 60 구간 3.4km, 회전 3(약 5.4km) · S3-N(밤, 출발 30초 동안 하향등 끔 J1 · 68km/h E1).
 * - c3-B 역 앞 사거리: 경로를 등록하지 않는다 — 일반 경로 생성기 + 기본 틀.
 */
internal object M3Scenarios {
    fun register(reg: ScenarioRegistry) {
        registerA(reg)
        registerN(reg)
    }

    /* ================================================================ c3-A · S3-A */
    private fun registerA(reg: ScenarioRegistry) {
        val jam = span(500, 1360) // 역전로 500m부터 3번 회전을 지나 강변로 140m까지
        val legs = listOf(
            go(500, limit = 50, road = "시청로", key = "A0"),
            turn("R", 90, r = 12), // 1 중앙대로로 우회전
            go(1500, limit = 60, road = "중앙대로", key = "A1"), // 신호 정지 1
            turn("L", 90, r = 12), // 2 역전로로 좌회전 — 반대쪽 깜빡이(C3)
            go(1200, limit = 50, road = "역전로", jam = jam, key = "A2"), // 정체 구간 · 신호 정지 2(급정지)
            turn("R", 90, r = 12), // 3 강변로로 우회전 — 깜빡이 켠 채(C6, 정체 안)
            go(1800, limit = 60, road = "강변로", key = "A3"),
            turn("L", 90, r = 12), // 4 시청 뒷길로 좌회전
            go(900, limit = 50, road = "시청 뒷길", key = "A4"), // 신호 정지 3 · 도착
        )
        val la = Layout(legs, ArcFormula.D2R)
        reg.registerRoute("c3-A", RouteDef.legs(legs, routeOption = "추천", name = "시청 앞 큰길"))
        reg.registerScenario(
            ScenarioDef(
                id = "S3-A", title = "3단계 · 시청 앞 큰길", mode = 3, courseId = "c3-A", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0),
                cruise = 60.0,
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 60.0),
                    2 to TurnPlan(blinkAt = 90.0, wrong = true), // 반대쪽 깜빡이 → 3초 뒤 바로잡음
                    3 to TurnPlan(blinkAt = 55.0, keepOn = true), // 돈 뒤 15초 켜 둠 → 10초 넘는 순간 C6
                    4 to TurnPlan(blinkAt = 60.0),
                ),
                events = listOf(
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    mark("회전 앞", at = la.at("A0", 390)),
                    ev(at = la.at("A1", 750), act = Act.STOP, drop = 6, hold = 12), // 신호 앞 정지 1(부드럽게)
                    mark("반대 깜빡이", at = la.at("A1", 1350)),
                    mark("정체 구간", at = la.at("A2", 500)),
                    ev(at = la.at("A2", 900), act = Act.STOP, drop = 8, hold = 10), // 정체 안 신호 앞 정지 2(25km/h에서 급정지)
                    mark("깜빡이 켠 채", at = la.at("A3", 0)),
                    ev(at = la.at("A4", 500), act = Act.STOP, drop = 6, hold = 12), // 신호 앞 정지 3
                    mark("도착 직전", at = jr(la.navEnd - 150)),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.POOR to Variant(events = listOf(ev(at = la.at("A1", 750), act = Act.LAUNCH, gain = 9))), // 서툰 +4 → 13: 급출발
                ),
                expect = listOf("B1.ok", "C2.ok", "C3.fail", "C4.ok", "C5.ok", "C6.fail", "F1.ok", "F1.fail", "E1.ok", "G1"),
            ),
        )
    }

    /* ================================================================ c3-C · S3-N (밤) */
    private fun registerN(reg: ScenarioRegistry) {
        val legs = listOf(
            go(600, limit = 50, road = "강변북로 입구", key = "N0"),
            turn("R", 90, r = 12), // 1 강변대로로 우회전
            go(3400, limit = 60, road = "강변대로", key = "N1"), // 60 구간 길게 · 68km/h 6초 · 신호 정지
            turn("L", 90, r = 12), // 2 다리 아래길로 좌회전
            go(1000, limit = 50, road = "다리 아래길", key = "N2"), // 신호 정지
            turn("R", 90, r = 10), // 3 강변마을길로 우회전
            go(400, limit = 30, road = "강변마을길", key = "N3"), // 도착
        )
        val ln = Layout(legs, ArcFormula.D2R)
        reg.registerRoute("c3-C", RouteDef.legs(legs, routeOption = "추천", name = "강변대로"))
        reg.registerScenario(
            ScenarioDef(
                id = "S3-N", title = "3단계 · 강변대로(밤)", mode = 3, courseId = "c3-C", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0, night = true, lowBeam = true),
                cruise = 60.0,
                turns = mapOf(1 to TurnPlan(blinkAt = 55.0), 2 to TurnPlan(blinkAt = 50.0), 3 to TurnPlan(blinkAt = 45.0)),
                events = listOf(
                    ev(t = 0, act = Act.LAMP, v = false), // 밤인데 하향등을 끈 채 출발(능숙은 끔)
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    ev(t = 32, act = Act.LAMP, v = true), // 출발 30초 뒤 켬
                    mark("강변대로", at = ln.at("N1", 0)),
                    ev(at = ln.at("N1", 1800), act = Act.OVER, speed = 68, dur = 6), // 60 구간 68km/h 6초 → 제한속도
                    ev(at = ln.at("N1", 2900), act = Act.STOP, drop = 6, hold = 12), // 강변대로 끝 신호
                    ev(at = ln.at("N2", 500), act = Act.STOP, drop = 6, hold = 12),
                    mark("도착 직전", at = jr(ln.navEnd - 150)),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.POOR to Variant(events = listOf(ev(at = ln.at("N2", 500), act = Act.LAUNCH, gain = 9))), // 서툰 +4 → 13: 급출발
                ),
                expect = listOf("B1.ok", "C2.ok", "C4.ok", "C5.ok", "E1.ok", "E1.fail", "F1.ok", "G1", "J1.fail"),
            ),
        )
    }
}
