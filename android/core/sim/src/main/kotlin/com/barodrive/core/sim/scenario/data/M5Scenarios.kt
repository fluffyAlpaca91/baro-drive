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
 * 자율 모드 시나리오 (프로토타입 data/scenarios/m5.js, 설계 7.7 S5-M — 집 → 마트, 목업 s07 점수 86 재현, 8.2 장소 '마트' 12.4km).
 *
 * 경로 'place:mart'(내비 장소 '마트'): 은행나무길 30 → 중앙로 50 → 시청로 60 → 공원로 50 → 강변로 80 → 마트길 50 → 마트 앞길 30
 * → 마트 주차장(내비 없음). 보통: 회전 2 '늦음' · 회전 3·5 '거의' · 회전 4 켜자마자 꺾음 · 시청로 신호 뒤 급출발 1번 · 직진 흔들림이
 * 기준보다 20% 큼 · 시청로 위험 회피 급제동 1번 → 100 − 14 = 86점.
 */
internal object M5Scenarios {
    fun register(reg: ScenarioRegistry) {
        val legs = listOf(
            go(300, limit = 30, cruise = 28, road = "은행나무길"), // 0
            turn("R", 90, r = 10), // 1 회전 1 중앙로로 우회전(50m 전 켬 · 성공)
            go(900, limit = 50, cruise = 46, road = "중앙로"), // 2 신호 정지(부드럽게)
            turn("L", 90, r = 12), // 3 회전 2 시청로로 좌회전(25m 전 · 늦음)
            go(3400, limit = 60, cruise = 56, road = "시청로"), // 4 신호 뒤 급출발 · 위험 회피 급제동
            turn("R", 90, r = 10), // 5 회전 3 공원로로 우회전(35m 전 · 거의)
            go(1800, limit = 50, cruise = 46, road = "공원로"), // 6 신호 정지(부드럽게)
            turn("R", 90, r = 10), // 7 회전 4 강변로로 우회전(켜고 1초 만에 꺾음)
            go(4000, limit = 80, cruise = 74, road = "강변로"), // 8
            turn("L", 90, r = 12), // 9 회전 5 마트길로 좌회전(33m 전 · 거의)
            go(1500, limit = 50, cruise = 46, road = "마트길"), // 10 신호 정지(부드럽게)
            turn("R", 90, r = 10), // 11 회전 6 마트 앞길로 우회전(50m 전 · 성공)
            go(400, limit = 30, cruise = 28, road = "마트 앞길"), // 12 끝 = 마트 입구(내비 도착)
            go(40, nav = false, cruise = 10, road = "마트 주차장"), // 13 주차장 안(내비 없음) → P
        )
        val m = Layout(legs, ArcFormula.PI_180)
        fun at(i: Int, d: Number) = jr(m.start(i) + d.toDouble())
        reg.registerRoute("place:mart", RouteDef.legs(legs, routeOption = "추천", name = "마트"))
        reg.registerScenario(
            ScenarioDef(
                id = "S5-M", title = "자율 · 집 → 마트", mode = 5, placeId = "mart", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0),
                cruise = 56.0,
                turns = mapOf(
                    2 to TurnPlan(blinkAt = 25.0), // 20m대 → '늦음'
                    3 to TurnPlan(blinkAt = 35.0), // 30m대 → '거의'
                    4 to TurnPlan(blinkAt = 45.0, steerAfter = 1.0), // 40m(성공)에 켜고 1초 만에 꺾음 → 켜자마자 꺾음
                    5 to TurnPlan(blinkAt = 33.0), // 30m대 → '거의'
                ),
                events = listOf(
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    mark("회전 앞", at = jr(m.turns[0].s0 - 110)),
                    mark("신호 대기", at = at(2, 592)),
                    ev(at = at(2, 600), act = Act.STOP, drop = 5, hold = 10), // 중앙로 신호(부드럽게)
                    mark("급출발", at = at(4, 492)),
                    ev(at = at(4, 500), act = Act.STOP, drop = 5, hold = 12), // 시청로 신호
                    ev(at = at(4, 500), act = Act.LAUNCH, gain = 13), // 그 신호에서 확 출발 → 급출발 1번
                    mark("급제동", at = at(4, 2190)),
                    ev(at = at(4, 2200), act = Act.STOP, drop = 13, hold = 6), // 위험 회피 급제동(1초 13km/h) → 감점 없음, 30초 침묵
                    mark("중간", at = at(6, 300)),
                    ev(at = at(6, 900), act = Act.STOP, drop = 6, hold = 8), // 공원로 신호(부드럽게)
                    mark("강변로", at = at(8, 200)),
                    ev(at = at(10, 600), act = Act.STOP, drop = 5, hold = 9), // 마트길 신호(부드럽게)
                    mark("도착 직전", at = jr(m.navEnd - 150)),
                    mark("마트 주차장", at = jr(m.navEnd + 5)),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.NORMAL to Variant(wobble = 0.95), // 직진 흔들림 큼: 잰 값 약 0.96°(기준 0.8°보다 20%) → −4
                ),
                expect = listOf("B1.ok", "B1.fail", "C2.ok", "C2.late", "C2.almost", "C4.ok", "C4.fail", "C5.ok", "F1.ok", "F2", "E1.ok", "G1"),
            ),
        )
    }
}
