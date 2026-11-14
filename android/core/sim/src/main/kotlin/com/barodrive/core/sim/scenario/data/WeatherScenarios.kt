package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.CautionType
import com.barodrive.core.model.Gear
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.TurnPlan

/**
 * 날씨 시나리오 (프로토타입 data/scenarios/weather.js, 설계 7.7 S5-R·S2-S, 8.2 장소 '회사' 4.6km, 5.6·6.8 날씨 보정).
 *
 * - S5-R 자율 · 집 → 회사(비): 경로 'place:company'. 출발 1분 뒤 와이퍼 → 30초 뒤 비 오는 날 안내 · 중앙로 신호(요령 카드) · 1초 9km/h 출발
 *   · 시청로 43km/h(E1.wx) · 미끄러운 길 · 1초 7km/h 정지 · 회사 앞길 신호 → 회사 입구(도착) → 주차장 → P.
 * - S2-S 2단계 · 초등학교 뒤 골목(눈): m2의 c2-B 경로를 그대로 쓴다(코스 기본은 S2-B). 바깥 −2°C, 순항 22km/h,
 *   1초 7km/h 정지 · 1초 10km/h 출발 · 어린이보호구역 27km/h(E2: 30 × 0.8 = 24).
 */
internal object WeatherScenarios {
    fun register(reg: ScenarioRegistry) {
        registerR(reg)
        registerS(reg)
    }

    /* ================================================================ 회사 · S5-R (자율, 비) */
    private fun registerR(reg: ScenarioRegistry) {
        val legs = listOf(
            go(300, limit = 30, cruise = 28, road = "은행나무길", key = "R0"),
            turn("R", 90, r = 10), // 1 중앙로로 우회전
            go(350, limit = 50, cruise = 46, road = "중앙로", key = "R1"), // 와이퍼 켬(출발 1분 뒤)
            go(1350, limit = 50, cruise = 38, road = "중앙로", key = "R1b"), // 비 → 천천히 · 신호(요령 카드) · 출발 9km/h
            turn("L", 90, r = 12), // 2 시청로로 좌회전
            go(1400, limit = 50, cruise = 38, road = "시청로", key = "R2", caution = caution(1000, CautionType.SLIPPERY_ROAD_ALERT, 120)), // 43km/h(E1.wx) · 미끄러운 길 · 신호 7km/h
            turn("R", 90, r = 10), // 3 회사 앞길로 우회전
            go(1090, limit = 30, cruise = 22, road = "회사 앞길", key = "R3"), // 신호(요령 카드) · 끝 = 회사 입구(내비 도착)
            go(40, nav = false, cruise = 10, road = "회사 주차장", key = "R4"), // 주차장 안(내비 없음) → P
        )
        val lr = Layout(legs, ArcFormula.D2R)
        reg.registerRoute("place:company", RouteDef.legs(legs, routeOption = "추천", name = "회사"))
        reg.registerScenario(
            ScenarioDef(
                id = "S5-R", title = "자율 · 집 → 회사(비)", mode = 5, placeId = "company", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0, wiper = false),
                cruise = 46.0,
                turns = mapOf(1 to TurnPlan(blinkAt = 50.0), 2 to TurnPlan(blinkAt = 55.0), 3 to TurnPlan(blinkAt = 50.0)),
                events = listOf(
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    mark("회전 앞", at = jr(lr.turns[0].s0 - 110)),
                    mark("와이퍼", t = 59),
                    ev(t = 60, act = Act.WIPER, v = true), // 출발 1분 뒤 와이퍼 → 30 sim초 뒤 비 오는 날 안내
                    mark("신호 대기", at = lr.at("R1b", 942)),
                    ev(at = lr.at("R1b", 950), act = Act.STOP, drop = 5, hold = 12), // 부드럽게(비 오는 날 F1 6 아래) → 빗길 요령 카드
                    ev(at = lr.at("R1b", 950), act = Act.LAUNCH, gain = 9), // 1초 9km/h 출발 → 비 오는 날 B1 8 기준 넘음
                    mark("빗길 과속", at = lr.at("R2", 250)),
                    ev(at = lr.at("R2", 300), act = Act.OVER, speed = 43, dur = 6), // 50 구역 43km/h → E1(낮춘 기준 40)
                    mark("미끄러운 길", at = lr.at("R2", 640)),
                    ev(at = lr.at("R2", 1320), act = Act.STOP, drop = 7, hold = 10), // 1초 7km/h 정지 → 비 오는 날 F1 6 기준 넘음
                    ev(at = lr.at("R3", 600), act = Act.STOP, drop = 5, hold = 9), // 회사 앞길 신호(요령 카드)
                    mark("도착 직전", at = jr(lr.navEnd - 150)),
                    mark("회사 주차장", at = jr(lr.navEnd + 5)),
                    endPark(),
                ),
                expect = listOf("J3", "B1.ok", "B1.fail", "C2.ok", "F1.ok", "F1.fail", "E1.fail", "G1"),
            ),
        )
    }

    /* ================================================================ c2-B · S2-S (2단계, 눈) — 다리 길이는 m2의 c2-B와 같다 */
    private fun registerS(reg: ScenarioRegistry) {
        val legs = listOf(
            go(260, key = "B0"),
            turn("R", 90, r = 10),
            go(430, key = "B1"),
            turn("L", 90, r = 12),
            go(470, key = "B2"),
            turn("R", 90, r = 10),
            go(450, key = "B3"), // 어린이보호구역 70~330m
            turn("L", 90, r = 12),
            go(430, key = "B4"),
            turn("L", 90, r = 12),
            go(360, key = "B5"),
            turn("R", 90, r = 10),
            go(330, key = "B6"),
        )
        val lb = Layout(legs, ArcFormula.D2R)
        reg.registerScenario(
            ScenarioDef(
                id = "S2-S", title = "2단계 · 초등학교 뒤 골목(눈)", mode = 2, courseId = "c2-B",
                start = StartState(gear = Gear.P, speed = 0.0, outsideTemp = -2.0), // 바깥 온도 −2°C(설계 7.7)
                cruise = 22.0, // 눈 오는 날 30 구역 기준 24 아래
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 55.0), 2 to TurnPlan(blinkAt = 50.0), 3 to TurnPlan(blinkAt = 55.0),
                    4 to TurnPlan(blinkAt = 50.0), 5 to TurnPlan(blinkAt = 50.0), 6 to TurnPlan(blinkAt = 55.0),
                ),
                events = listOf(
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    mark("회전 앞", at = lb.at("B0", 150)),
                    mark("신호 대기", at = lb.at("B1", 222)),
                    ev(at = lb.at("B1", 230), act = Act.STOP, drop = 7, hold = 14), // 1초 7km/h → 눈 오는 날 F1 6 기준 넘음
                    ev(at = lb.at("B1", 230), act = Act.LAUNCH, gain = 10), // 1초 10km/h 출발 → 눈 오는 날 B1 8 기준 넘음
                    ev(at = lb.at("B2", 170), act = Act.STOP, drop = 5, hold = 10), // 부드럽게 → 정지 칭찬 · 출발 교정 카드 + 요령
                    mark("어린이보호구역", at = lb.at("B3", 30)),
                    ev(at = lb.at("B3", 62), act = Act.OVER, speed = 27, dur = 8), // 구역 바로 앞에서 27km/h로 → E2(30 × 0.8 = 24)
                    ev(at = lb.at("B3", 230), act = Act.STOP, drop = 5, hold = 8), // 횡단보도 신호(요령 카드)
                    ev(at = lb.at("B5", 200), act = Act.STOP, drop = 5, hold = 8), // 공원길 신호(요령 카드)
                    mark("도착 직전", at = jr(lb.navEnd - 150)),
                    endPark(),
                ),
                expect = listOf("J3", "B1.ok", "B1.fail", "C2.ok", "F1.ok", "F1.fail", "E2", "G1"),
            ),
        )
    }
}
