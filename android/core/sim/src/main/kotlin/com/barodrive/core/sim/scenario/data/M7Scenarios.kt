package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.CautionType
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.ScenarioTemplate
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.Variant
import kotlin.math.max
import kotlin.math.min

/**
 * 언덕 주행 시나리오 (프로토타입 data/scenarios/m7.js, 설계 7.7 S7-B, 8.2 c7-A·B, 6.12.2, 결정사항 E T1b-16·T6-8·T6-9).
 *
 * - c7-B 배수지 공원 언덕길 2.3km: 오르막 560m → 공원 윗길 → 내리막(내비 '내리막 주의 구간' 260m) → 끝 = 언덕 주차 자리 · S7-B
 *   (사이드브레이크 채운 채 출발 PB1 → 오르막 출발 2번 → 내리막 나눠 밟기 → 언덕 주차(내리막·연석) → P).
 *   멈춰 있는 동안의 일(오르막 [했어요]·다시 출발, 언덕 주차 상황·핸들·P)은 시간(t) 사건이다 — 서 있으면 거리가 늘지 않는다.
 * - c7-A 성당 앞 오르막길 1.2km: 시나리오는 언덕 틀(첫 직진 = 오르막, 오르막 출발 t 16·50, 내리막 나눠 밟기, 끝은 보통 P).
 */
internal object M7Scenarios {
    private val DOWNHILL = CautionType.DOWNHILL_CAUTION_AREA
    private const val GAP_PRESS = 11.0
    private const val GAP_LAUNCH = 17.0
    private const val GAP_ROLLBACK = 16.9

    /** 오르막 출발 한 번: t에 그 자리에서 서고(drop 5) → [했어요] → 다시 출발. */
    private fun hillStart(t: Double): List<ScenarioEvent> = listOf(
        ev(t = t, act = Act.STOP, drop = 5, hold = 60),
        ev(t = t + GAP_PRESS, act = Act.PRESS),
        ev(t = t + GAP_LAUNCH, act = Act.LAUNCH),
    )

    private class Downhill(val normal: List<ScenarioEvent>, val good: List<ScenarioEvent>, val poor: List<ScenarioEvent>)

    /** 내리막 나눠 밟기: 보통·능숙 4번, 서툰 2번 + 9초 계속 밟음(구간이 짧으면 계속 밟기는 뺌). */
    private fun downhillVariants(z: CautionAt): Downhill {
        val pulse = { n: Int -> ev(at = jr(z.s0 + 15), act = Act.BRAKE_PULSE, n = n) }
        val poor = mutableListOf(pulse(2))
        if (z.len >= 200) poor += ev(at = jr(z.s0 + 75), act = Act.HOLD, sec = 9)
        return Downhill(listOf(pulse(4)), listOf(pulse(4)), poor)
    }

    fun register(reg: ScenarioRegistry) {
        registerB(reg)
        reg.registerRoute(
            "c7-A",
            RouteDef.legs(
                listOf(
                    go(420, limit = 30, road = "성당 앞 오르막길", grade = 7),
                    turn("R", 90, r = 10),
                    go(260, limit = 30, road = "성당 뒷길"),
                    turn("R", 90, r = 10),
                    go(480, limit = 30, road = "성당 내리막길", grade = -7, caution = caution(120, DOWNHILL, 220)),
                ),
                routeOption = "추천", name = "성당 앞 오르막길",
            ),
        )
        reg.registerTemplate(7, template)
    }

    /* ================================================================ c7-B · S7-B 배수지 공원 언덕길 */
    private fun registerB(reg: ScenarioRegistry) {
        val legs = listOf(
            go(380, limit = 30, road = "은행나무길"),
            turn("R", 90, r = 10), // 1 배수지길로 우회전
            go(520, limit = 50, road = "배수지길"), // 여기까지 언덕까지 0.9km
            turn("L", 90, r = 12), // 2 언덕길로 좌회전
            go(560, limit = 30, road = "배수지 공원 언덕길", grade = 8), // 오르막 출발 2번
            turn("R", 90, r = 10), // 3 공원 윗길
            go(200, limit = 30, road = "공원 윗길"),
            turn("R", 90, r = 10), // 4 내리막길
            go(520, limit = 30, road = "배수지 공원 내리막길", grade = -8, caution = caution(90, DOWNHILL, 260)), // 끝 = 언덕 주차 자리(도착)
        )
        val mb = Layout(legs, ArcFormula.PI_180)
        val zb = mb.cautions[0]
        val t1 = 138.0 // 오르막 출발 시각(언덕길에 들어선 뒤 ~10초 · 1번째 시도 끝나고 ~20초)
        val t2 = 178.0
        val parkAt = jr(mb.navEnd - 6) // 언덕 주차 자리(내비 도착 = 10m 안)
        val tp = 333.5 // 언덕 주차 자리에 서는 시각(세 실력 333.0~333.8)
        val db = downhillVariants(zb)
        reg.registerRoute("c7-B", RouteDef.legs(legs, routeOption = "추천", name = "배수지 공원 언덕길"))
        reg.registerScenario(
            ScenarioDef(
                id = "S7-B", title = "언덕 주행 · 배수지 공원 언덕길", mode = 7, courseId = "c7-B", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0, handbrake = true, passenger = true),
                cruise = 30.0,
                events = listOf(
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D), // 사이드브레이크 채운 채 D → 2초 넘음(PB1)
                    ev(t = 6, act = Act.HANDBRAKE, v = false), // 6초에 풂(pb1.on을 말할 동안 채워져 있게)
                    mark("오르막 출발", t = t1 + 12),
                ) + hillStart(t1) + listOf(
                    mark("두 번째 오르막", t = t2 + 12),
                ) + hillStart(t2) + listOf(
                    mark("내리막", at = jr(zb.s0 + 40)), // 300m 앞에서 내리막 감속 1단계 → 구간에 들어서면 2단계(나눠 밟기)
                    ev(at = jr(zb.s1 + 25), act = Act.PRESS), // 내리막 3단계 [했어요] → 끝
                    mark("도착 직전", at = jr(mb.navEnd - 150)),
                    ev(at = parkAt - 6, act = Act.BLINKER, v = "RIGHT"), // 길가로 붙이며 오른쪽 깜빡이
                    ev(at = parkAt, act = Act.STOP, drop = 3, hold = 60),
                    ev(t = tp + 2.5, act = Act.HILL_SITUATION, v = "down"), // 내리막·연석 있음 → 앞바퀴 연석 쪽(오른쪽)
                    ev(t = tp + 5.5, act = Act.STEER, v = 20, dur = 60), // 오른쪽으로 20° → 2단계 넘김(8°)
                    mark("언덕 주차", t = tp + 9.5),
                    ev(t = tp + 13.5, act = Act.PARK), // P → HL3·PB2 → 회차 끝
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.NORMAL to Variant(events = db.normal),
                    DriverSkill.GOOD to Variant(events = db.good),
                    DriverSkill.POOR to Variant(events = listOf(ev(t = t1 + GAP_ROLLBACK, act = Act.ROLLBACK, speed = 2, dur = 1)) + db.poor),
                ),
                expect = listOf("PB1", "B1.ok", "F1.ok", "C2", "E1.ok", "G1", "HL2.ok", "HL1.ok", "HL3.ok", "PB2.fail"),
            ),
        )
    }

    /* ================================================================ 언덕 틀 (생성 코스용)
     * 첫 직진 = 오르막(300m 이상): 출발해 ~10초 뒤(t 16) 1번째 시도, t 50에 2번째. 내리막 주의 구간이 없으면 첫 직진 뒤
     * 가장 긴 직진(300m 이상)에 넣는다. 언덕 주차는 넣지 않는다(도착 시각을 모름, 결정사항 E T6-9). 구간이 여럿이면 null(기본 틀). */
    private const val TPL_T1 = 16.0
    private const val TPL_T2 = 50.0
    private const val TPL_CRUISE = 25.0

    private val template = ScenarioTemplate { course, opts ->
        val road = opts.legs?.takeIf { it.isNotEmpty() } ?: return@ScenarioTemplate null
        val legs = road.toMutableList()
        val firstIdx = legs.indexOfFirst { it is Leg.Go }
        if (firstIdx < 0) return@ScenarioTemplate null
        val firstGo = legs[firstIdx] as Leg.Go
        if (!(firstGo.len >= 300)) legs[firstIdx] = firstGo.copy(len = 300.0) // 오르막 출발 두 번이 첫 직진 안에 들어가게
        var m = Layout(legs, ArcFormula.PI_180)
        var zone = m.cautions.firstOrNull { it.type == DOWNHILL && it.len >= 120 }
        if (zone == null) { // 내리막 주의 구간이 없으면 첫 직진 뒤 가장 긴 직진(300m 이상)에
            val cand = m.goes.filter { it.i != firstIdx && it.len >= 300 }.sortedByDescending { it.len }.firstOrNull()
            if (cand != null) {
                val leg = legs[cand.i] as Leg.Go
                val len = min(240.0, jr(cand.len - 140))
                legs[cand.i] = leg.copy(cautions = leg.cautions + caution(80, DOWNHILL, len))
                m = Layout(legs, ArcFormula.PI_180)
                zone = m.cautions.firstOrNull { it.type == DOWNHILL }
            }
        }
        val events = ArrayList<ScenarioEvent>()
        events += ev(t = 0.5, act = Act.GEAR, v = Gear.D)
        events += mark("오르막 출발", t = TPL_T1 + 12)
        events += hillStart(TPL_T1)
        events += mark("두 번째 오르막", t = TPL_T2 + 12)
        events += hillStart(TPL_T2)
        var normal = emptyList<ScenarioEvent>()
        var good = emptyList<ScenarioEvent>()
        var poor = listOf(ev(t = TPL_T1 + GAP_ROLLBACK, act = Act.ROLLBACK, speed = 2, dur = 1))
        if (zone != null) {
            val dv = downhillVariants(zone)
            normal = dv.normal
            good = dv.good
            poor = poor + dv.poor
            events += mark("내리막", at = jr(zone.s0 + 40))
            events += ev(at = jr(zone.s1 + 25), act = Act.PRESS)
        }
        events += mark("도착 직전", at = max(0.0, jr(m.navEnd - 150)))
        events += endPark()
        ScenarioDef(
            id = "TPL:${course.id}",
            title = "언덕 주행 · " + (course.name ?: course.id.ifEmpty { "언덕" }) + " · 언덕 틀",
            mode = 7, courseId = course.id.ifEmpty { null },
            start = StartState(gear = Gear.P, speed = 0.0),
            cruise = TPL_CRUISE,
            route = RouteDef.legs(legs),
            events = events,
            variants = mapOf(DriverSkill.NORMAL to Variant(events = normal), DriverSkill.GOOD to Variant(events = good), DriverSkill.POOR to Variant(events = poor)),
            expect = if (zone != null) listOf("HL2.ok", "HL1.ok", "PB2") else listOf("HL2.ok", "PB2"),
            generated = true,
        )
    }
}
