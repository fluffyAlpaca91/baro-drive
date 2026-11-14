package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.Gear
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.TurnPlan

/**
 * 규칙 확인 시나리오 (프로토타입 data/scenarios/rules.js, 설계 7.7 S9-R, 6.5.2 말하기 규칙, 6.5.5 K8).
 *
 * S9-R 1단계 · 규칙 확인 — 짧은 시간에 말 후보를 많이 만들어 말 줄이기 규칙(간격·상한·비율·침묵·내비 음성·50m 안)이 막는 말을 보게 한다.
 * 코스 데이터에 없는 시험용 길이라 경로를 시나리오 안에 둔다(내비 경로 키 'scenario:S9-R'). 코스 추천에는 나오지 않는다.
 */
internal object RulesScenarios {
    private val LEGS: List<Leg> = listOf(
        go(320, limit = 50, road = "규칙확인로"),
        turn("R", 90, r = 10), // 1
        go(170, limit = 50, road = "느티나무길"),
        turn("L", 90, r = 10), // 2
        go(50, limit = 50, road = "짧은 골목"),
        turn("R", 90, r = 10), // 3
        go(300, limit = 50, road = "시장길"),
        turn("R", 90, r = 10), // 4
        go(460, limit = 30, road = "학교 앞길"),
        turn("L", 90, r = 10), // 5
        go(600, limit = 50, road = "강변로"),
        turn("R", 90, r = 10), // 6
        go(160, limit = 30, road = "도착길"),
    )

    fun register(reg: ScenarioRegistry) {
        // 다리 목록을 따라 회전 지점(원래 경로 거리 m) — rules.js turnsAt(원호 = π · r · 각 / 180)
        var s = 0.0
        val t = ArrayList<Double>()
        for (l in LEGS) {
            when (l) {
                is Leg.Go -> s += l.len
                is Leg.Turn -> {
                    t += s
                    s += ArcFormula.PI_FIRST.len(l.r ?: 10.0, l.angle)
                }
                is Leg.Waypoint -> Unit
            }
        }
        val len = s
        reg.registerScenario(
            ScenarioDef(
                id = "S9-R", title = "규칙 확인 · 말 줄이기 규칙", mode = 1,
                route = RouteDef.legs(LEGS, routeOption = "편안한 길"), destName = "규칙 확인 코스",
                start = StartState(gear = Gear.P, speed = 0.0),
                cruise = 34.0,
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 35.0), // 거의(30~39m) — C1과 짝이라 바로 말함(교정 1)
                    2 to TurnPlan(blinkAt = 55.0),
                    3 to TurnPlan(blinkAt = 55.0),
                    4 to TurnPlan(blinkAt = 47.0, navVoiceAt = listOf(80.0)), // 내비 음성(80m)이 C1(TBT '70')과 겹쳐 → 50m 안
                    5 to TurnPlan(blinkAt = 55.0),
                    6 to TurnPlan(blinkAt = 75.0), // 일찍 켬(C1 없음) → 칭찬이 상한에 막혀 회전 전에 만료
                ),
                events = listOf(
                    ev(t = 0, act = Act.LAUNCH, gain = 13), // 급출발 → 첫 신호 대기 카드(교정)
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    mark("회전 4개", at = jr(t[0] - 120)),
                    mark("신호 대기", at = jr(t[0] + 70)),
                    ev(at = jr(t[0] + 80), act = Act.STOP, drop = 5, hold = 16), // 오래 섬: 카드 문장이 간격 → 비율에 막힘
                    ev(at = jr(t[3] - 62), act = Act.NAV_VOICE, text = "전방에 과속 단속 카메라가 있습니다"), // 내비 음성이 이어져 4번 회전 칭찬이 만료
                    ev(at = jr(t[3] - 44), act = Act.NAV_VOICE, text = "어린이 통학 차량을 조심하세요"),
                    ev(at = jr(t[3] - 26), act = Act.NAV_VOICE, text = "잠시 후 우회전입니다"),
                    ev(at = jr(t[3] - 9), act = Act.NAV_VOICE, text = "우회전입니다"),
                    mark("30 구역", at = jr(t[3] + 120)),
                    ev(at = jr(t[3] + 140), act = Act.OVER, speed = 38, dur = 6), // 제한속도 30 → E1(안전)
                    mark("급제동", at = jr(t[4] - 170)),
                    ev(at = jr(t[4] - 160), act = Act.STOP, drop = 13, hold = 3), // 위험 회피 급제동(F2) → 30초 침묵
                    mark("운전자 질문", at = jr(t[4] + 150)),
                    ev(at = jr(t[4] + 160), act = Act.ASK), // K8: 간격 예외, 상한에는 셈
                    mark("도착 직전", at = jr(len - 150)),
                    endPark(),
                ),
                expect = listOf("B1.fail", "C1", "C2.ok", "C2.almost", "F1.ok", "E1.fail", "F2", "K8", "PB2.fail"),
            ),
        )
    }
}
