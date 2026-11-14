package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.Cond
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.scenario.Variant

/**
 * 2단계 시나리오 (프로토타입 data/scenarios/m2.js, 설계 7.7 S2-B·S2-A, 8.2 c2-A·B·C, 5.3 별지 B 12단계).
 *
 * - c2-B 초등학교 뒤 골목: 30 구역 골목 6회전(오·왼·오·왼·왼·오), 어린이보호구역 1, 경유지 2곳(약 3.1km) · S2-B(설계 5.3 줄거리:
 *   벨트·사전 안내·55m 칭찬·급정지 카드·켜자마자 꺾음 카드·30 구역 38km/h·급제동 F2·5번 좌회전 지나침 → 재탐색·도착 → P).
 * - c2-A 우리 동네 한 바퀴(내 코스): 집에서 나가 5회전 뒤 집으로 돌아오는 고리 · S2-A(칭찬 위주).
 * - c2-C 시장 옆 골목: 경로를 등록하지 않는다 — 일반 경로 생성기 + 기본 틀.
 * 거리(at)는 원래 경로 기준 m.
 */
internal object M2Scenarios {
    fun register(reg: ScenarioRegistry) {
        registerB(reg)
        registerA(reg)
    }

    /* ================================================================ c2-B · S2-B (설계 5.3) */
    private fun registerB(reg: ScenarioRegistry) {
        val legs = listOf(
            go(260, limit = 30, road = "은행나무길", key = "B0"),
            turn("R", 90, r = 10), // 1 학교앞길로 우회전(사전 안내 + 55m 칭찬)
            go(430, limit = 30, road = "학교앞길", key = "B1"), // 신호 정지(급정지 카드)
            turn("L", 90, r = 12), // 2 초등학교길로 좌회전(켜고 1초 만에 꺾음)
            go(470, limit = 30, road = "초등학교길", key = "B2"), // 신호 대기(켜자마자 꺾음 카드) → 38km/h
            turn("R", 90, r = 10), // 3 학교뒷길로 우회전(35m — 거의)
            go(450, limit = 30, road = "학교뒷길", school = span(70, 330), key = "B3"),
            wp("초등학교 뒤"), // 경유지 1
            turn("L", 90, r = 12), // 4 놀이터길로 좌회전
            go(430, limit = 30, road = "놀이터길", key = "B4"), // 위험 회피 급제동(F2)
            turn("L", 90, r = 12, road = "공원길"), // 5 공원길로 좌회전 — 지나침 → 재탐색
            go(360, limit = 30, road = "공원길", key = "B5"),
            wp("공원 앞"), // 경유지 2
            turn("R", 90, r = 10), // 6 은행나무길로 우회전
            go(330, limit = 30, road = "은행나무길", key = "B6"), // 도착
        )
        val lb = Layout(legs, ArcFormula.D2R)
        reg.registerRoute("c2-B", RouteDef.legs(legs, routeOption = "편안한 길", name = "초등학교 뒤 골목"))
        reg.registerScenario(
            ScenarioDef(
                id = "S2-B", title = "2단계 · 초등학교 뒤 골목", mode = 2, courseId = "c2-B", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0),
                cruise = 28.0,
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 55.0), // 70m에서 꺼져 있음 → 사전 안내, 55m에서 켬 → 칭찬
                    2 to TurnPlan(blinkAt = 45.0, steerAfter = 1.0), // 켜자마자 꺾음 → 다음 신호 대기에 카드
                    3 to TurnPlan(blinkAt = 35.0), // 30m대 → '거의'(깜빡이 5/6의 하나)
                    4 to TurnPlan(blinkAt = 50.0),
                    5 to TurnPlan(miss = true), // 좌회전 지점을 지나침 → 재탐색(그 회전은 기록에서 뺌)
                    6 to TurnPlan(blinkAt = 55.0),
                ),
                events = listOf(
                    ev(t = 0, act = Act.BELT, v = false), // 벨트 풀고 시작(능숙은 끔)
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    ev(t = 5.5, act = Act.BELT, v = true, cond = Cond.BELT_OFF), // 5초 뒤 맴(설계 5.3 3번)
                    mark("회전 앞", at = lb.at("B0", 150)),
                    mark("신호 대기", at = lb.at("B1", 222)),
                    ev(at = lb.at("B1", 230), act = Act.STOP, drop = 10, hold = 14), // 28km/h에서 1초에 10km/h → 급정지 카드
                    ev(at = lb.at("B2", 170), act = Act.STOP, drop = 5, hold = 10), // 신호 대기: 2번 회전 '켜자마자 꺾음' 카드
                    mark("30 구역", at = lb.at("B2", 230)),
                    ev(at = lb.at("B2", 240), act = Act.OVER, speed = 38, dur = 6), // 30 구역 38km/h 6초 → 제한속도(이 구역에서 1번)
                    mark("어린이보호구역", at = lb.at("B3", 40)),
                    ev(at = lb.at("B3", 230), act = Act.STOP, drop = 5, hold = 8), // 어린이보호구역 안 횡단보도 신호
                    mark("급제동", at = lb.at("B4", 150)),
                    ev(at = lb.at("B4", 170), act = Act.STOP, drop = 20, hold = 4), // 28km/h에서 1.4초 만에 정지 → 위험 회피 급제동(F2)
                    mark("재탐색", at = lb.at("B4", 280)),
                    ev(at = lb.at("B5", 200), act = Act.STOP, drop = 5, hold = 8),
                    mark("도착 직전", at = jr(lb.navEnd - 150)),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.POOR to Variant(
                        events = listOf(
                            ev(at = lb.at("B3", 50), act = Act.OVER, speed = 36, dur = 8), // 어린이보호구역에 36km/h로 들어감 → E2
                            ev(at = lb.at("B1", 230), act = Act.LAUNCH, gain = 9), // 서툰 +4 → 13: 급출발
                            ev(at = lb.at("B3", 230), act = Act.LAUNCH, gain = 9),
                        ),
                    ),
                ),
                expect = listOf(
                    "A1", "B1.ok", "C1", "C2.ok", "C2.almost", "C4.ok", "C4.fail", "C5.ok", "F1.ok", "F1.fail", "F2", "E1.ok", "E1.fail",
                    "G1", "PB2.fail",
                ),
            ),
        )
    }

    /* ================================================================ c2-A · S2-A (칭찬 위주)
     * 고리 모양: 북 → (오) 동 → (오) 남 → (오) 서 → (왼) 남 → (오) 서 — 마지막 다리가 출발점(집)에서 끝나도록 길이를 맞췄다. */
    private fun registerA(reg: ScenarioRegistry) {
        val a = 760
        val b = 700
        val c = 420
        val d = 320
        val legs = listOf(
            go(a, limit = 30, road = "은행나무길", key = "A0"), // 30 구역 1
            turn("R", 90, r = 10), // 1
            go(b, limit = 50, road = "느티나무로", key = "A1"), // 50
            turn("R", 90, r = 10), // 2
            go(c, limit = 30, road = "장미길", key = "A2"), // 30 구역 2
            turn("R", 90, r = 10), // 3
            go(d, limit = 50, road = "중앙로", key = "A3"), // 50
            turn("L", 90, r = 10), // 4
            go(a - c - 30, limit = 30, road = "샘터길", key = "A4"), // 30 구역 3
            turn("R", 90, r = 10), // 5
            go(b - d - 10, limit = 30, road = "은행나무길", key = "A5"), // 30 구역 3(이어짐) · 집 도착
        )
        val la = Layout(legs, ArcFormula.D2R)
        reg.registerRoute("c2-A", RouteDef.legs(legs, routeOption = "편안한 길", name = "우리 동네 한 바퀴"))
        reg.registerScenario(
            ScenarioDef(
                id = "S2-A", title = "2단계 · 우리 동네 한 바퀴", mode = 2, courseId = "c2-A", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0),
                cruise = 40.0,
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 80.0), // 70m 전에 이미 켬 → 사전 안내 없이 칭찬(TBT는 10m 내림)
                    2 to TurnPlan(blinkAt = 60.0), // 70m 사전 안내 → 60m 칭찬(같은 회전 짝)
                    3 to TurnPlan(blinkAt = 80.0),
                    4 to TurnPlan(blinkAt = 65.0),
                    5 to TurnPlan(blinkAt = 80.0),
                ),
                events = listOf(
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    mark("회전 앞", at = la.at("A0", a - 110)),
                    mark("신호 대기", at = la.at("A1", 292)),
                    ev(at = la.at("A1", 300), act = Act.STOP, drop = 5, hold = 10), // 1번 회전 칭찬 카드
                    ev(at = la.at("A2", 120), act = Act.STOP, drop = 8, hold = 8), // 조금 급하게 섬 → 교정 카드
                    mark("중간", at = la.at("A2", 200)),
                    ev(at = la.at("A2", 330), act = Act.STOP, drop = 5, hold = 8), // 부드럽게 섬 → 칭찬(실패 다음 첫 성공, 결정-30)
                    ev(at = la.at("A4", 160), act = Act.STOP, drop = 5, hold = 8),
                    mark("도착 직전", at = jr(la.navEnd - 150)),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.POOR to Variant(
                        events = listOf(
                            ev(at = la.at("A1", 300), act = Act.LAUNCH, gain = 9), // 서툰 +4 → 13: 급출발
                            ev(at = la.at("A4", 160), act = Act.LAUNCH, gain = 9),
                        ),
                    ),
                ),
                expect = listOf("B1.ok", "C1", "C2.ok", "C4.ok", "C5.ok", "F1.ok", "F1.fail", "E1.ok", "G1", "PB2.fail"),
            ),
        )
    }
}
