package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.scenario.ScenarioRegistry
import com.barodrive.core.sim.scenario.ScenarioTemplate
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.scenario.TurnPlan
import com.barodrive.core.sim.scenario.Variant
import com.barodrive.core.sim.util.SeedRandom
import kotlin.math.abs
import kotlin.math.max

/**
 * 운전면허 연습 시나리오 (프로토타입 data/scenarios/m8.js, 설계 7.7 S8-C, 8.2 c8-A~D, 6.12.3 구간 이어 붙이기, 결정-62·63).
 *
 * 시험장 주변 코스 A~D: 모두 3구간(구간마다 경유지 0~1곳), 고속도로 없음, 시험장로에서 나가 시험장로로 돌아온다.
 * 구간이 끝나면 내비가 '경유지에 도착했습니다… 다음 구간 안내를 시작합니다'와 함께 다음 구간을 다시 찾는다. 회전 번호는 구간을 넘어 이어서 센다.
 * S8-C(c8-C 기본): 사이드브레이크 채운 채 D(PB1) → 사전 안내·칭찬 → 급정지 → 늦은 깜빡이 → 켜자마자 꺾기 → 어린이보호구역 36km/h(E2)
 * → 조수석이 빔(M2, 동승자 카드) → 깜빡이 없이 유턴 → 58km/h(E1) → 도착 → P.
 * 면허 틀: 시나리오가 없는 모드 8 코스(c8-A·B·D)에 — 회전 앞 신호 정지(시드)·장면, 보통: 급정지·늦은 깜빡이·켜자마자 꺾기 1번씩.
 */
internal object M8Scenarios {
    private const val CRUISE = 45.0
    private const val ROUTE_OPTION = "추천"

    /** 회전 하나(구간 [seg]). */
    private class T8(val n: Int, val seg: Int, val s0: Double, val s1: Double, val dir: TurnDir, val uturn: Boolean, val nav: Boolean)
    private class Seg8(val index: Int, val s0: Double, val s1: Double, val navEnd: Double)
    private class Measure(val len: Double, val navEnd: Double, val turns: List<T8>, val segments: List<Seg8>)

    /** 거리 재기(route.js와 같은 규칙, 원호 = r · a · π / 180). */
    private fun measure(segs: List<List<Leg>>): Measure {
        var s = 0.0
        var n = 0
        var nav = true
        val turns = ArrayList<T8>()
        val segments = ArrayList<Seg8>()
        segs.forEachIndexed { si, legs ->
            val s0 = s
            var navEnd = s
            for (l in legs) {
                l.nav?.let { nav = it }
                when (l) {
                    is Leg.Go -> {
                        s += max(0.0, l.len)
                        if (nav) navEnd = s
                    }
                    is Leg.Turn -> {
                        n++
                        val a = abs(l.angle)
                        val r = if (l.r != null && l.r > 0) l.r else defaultR(a)
                        val len = ArcFormula.PI_180.len(r, a)
                        turns += T8(n, si, s, s + len, l.dir, a >= 150, nav && a >= 10)
                        s += len
                        if (nav) navEnd = s
                    }
                    is Leg.Waypoint -> Unit
                }
            }
            segments += Seg8(si, s0, s, navEnd)
        }
        return Measure(s, segments.lastOrNull()?.navEnd ?: s, turns, segments)
    }

    /* ================================================================ 시험장 주변 코스 A~D (3구간씩) */
    private val ROUTES: Map<String, List<List<Leg>>> = linkedMapOf(
        "c8-A" to listOf(
            listOf( // 1구간: 시험장로 → 햇살로(경유지 햇살로 사거리) → 강변로
                go(260, limit = 30, road = "시험장로"),
                turn("L", 90, r = 12), // 1
                go(480, limit = 50, road = "햇살로"),
                wp("햇살로 사거리"),
                go(300, limit = 50),
                turn("R", 90, r = 10), // 2
                go(640, limit = 60, road = "강변로"),
            ),
            listOf( // 2구간: 강변로 → 중앙로 → 새말길(어린이보호구역)
                go(300, limit = 60, road = "강변로"),
                turn("R", 90, r = 10), // 3
                go(520, limit = 50, road = "중앙로"),
                turn("L", 90, r = 12), // 4
                go(860, limit = 30, road = "새말길", school = span(120, 330)),
            ),
            listOf( // 3구간: 새말길 → 공원로 유턴 → 시험장로
                go(380, limit = 30, road = "새말길"),
                turn("R", 90, r = 10), // 5
                go(560, limit = 50, road = "공원로"),
                turn("L", 180, r = 6), // 6 유턴
                go(420, limit = 50, road = "공원로"),
                turn("R", 90, r = 10), // 7
                go(300, limit = 30, road = "시험장로"),
            ),
        ),
        "c8-B" to listOf(
            listOf( // 1구간: 시험장로 → 시청로 → 문화로(경유지 문화로 사거리)
                go(240, limit = 30, road = "시험장로"),
                turn("R", 90, r = 10), // 1
                go(620, limit = 50, road = "시청로"),
                turn("R", 90, r = 10), // 2
                go(380, limit = 50, road = "문화로"),
                wp("문화로 사거리"),
                go(380, limit = 50),
            ),
            listOf( // 2구간: 문화로 → 강변로 → 장미길
                go(260, limit = 50, road = "문화로"),
                turn("L", 90, r = 12), // 3
                go(540, limit = 60, road = "강변로"),
                turn("L", 90, r = 12), // 4
                go(780, limit = 30, road = "장미길"),
            ),
            listOf( // 3구간: 장미길 → 버들길 → 시험장로
                go(420, limit = 30, road = "장미길"),
                turn("R", 90, r = 10), // 5
                go(380, limit = 50, road = "버들길"),
                turn("L", 90, r = 12), // 6
                go(680, limit = 30, road = "시험장로"),
            ),
        ),
        "c8-C" to listOf(
            listOf( // 1구간: 시험장로 → 시청로(경유지 시청 앞 사거리) → 강변로 → 문화로
                go(220, limit = 30, road = "시험장로"),
                turn("R", 90, r = 10), // 1
                go(540, limit = 50, road = "시청로"),
                wp("시청 앞 사거리"),
                go(360, limit = 60, road = "강변로"),
                turn("L", 90, r = 12), // 2
                go(600, limit = 50, road = "문화로"),
            ),
            listOf( // 2구간: 문화로 → 학교길(어린이보호구역) → 공원로
                go(280, limit = 50, road = "문화로"),
                turn("R", 90, r = 10), // 3
                go(460, limit = 30, road = "학교길", school = span(70, 300)),
                turn("L", 90, r = 12), // 4
                go(960, limit = 50, road = "공원로"),
            ),
            listOf( // 3구간: 공원로 유턴(경유지 공원 앞) → 시험장로
                go(400, limit = 50, road = "공원로"),
                turn("L", 180, r = 6), // 5 유턴
                go(300, limit = 50, road = "공원로"),
                wp("공원 앞"),
                go(300, limit = 50),
                turn("R", 90, r = 10), // 6
                go(760, limit = 30, road = "시험장로"),
            ),
        ),
        "c8-D" to listOf(
            listOf( // 1구간: 시험장로 → 한빛로 → 강변로
                go(300, limit = 30, road = "시험장로"),
                turn("L", 90, r = 12), // 1
                go(460, limit = 50, road = "한빛로"),
                turn("R", 90, r = 10), // 2
                go(840, limit = 60, road = "강변로"),
            ),
            listOf( // 2구간: 강변로 → 느티나무로(경유지 느티나무로 사거리) → 배울길(어린이보호구역)
                go(300, limit = 60, road = "강변로"),
                turn("R", 90, r = 10), // 3
                go(260, limit = 50, road = "느티나무로"),
                wp("느티나무로 사거리"),
                go(260, limit = 50),
                turn("L", 90, r = 12), // 4
                go(820, limit = 30, road = "배울길", school = span(100, 320)),
            ),
            listOf( // 3구간: 배울길 → 솔밭길 유턴 → 시험장로
                go(300, limit = 30, road = "배울길"),
                turn("R", 90, r = 10), // 5
                go(500, limit = 50, road = "솔밭길"),
                turn("L", 180, r = 6), // 6 유턴
                go(300, limit = 50, road = "솔밭길"),
                turn("R", 90, r = 10), // 7
                go(440, limit = 30, road = "시험장로"),
            ),
        ),
    )
    private val NAMES = mapOf("c8-A" to "시험장 주변 A 코스", "c8-B" to "시험장 주변 B 코스", "c8-C" to "시험장 주변 C 코스", "c8-D" to "시험장 주변 D 코스")

    fun register(reg: ScenarioRegistry) {
        for ((id, segs) in ROUTES) reg.registerRoute(id, RouteDef(segs, ROUTE_OPTION, NAMES[id]))
        registerC(reg)
        reg.registerTemplate(8, template)
    }

    /* ================================================================ S8-C 시험장 주변 C 코스 (설계 7.7) */
    private fun registerC(reg: ScenarioRegistry) {
        val mc = measure(ROUTES.getValue("c8-C"))
        val t = { n: Int -> mc.turns[n - 1] } // n번째 회전(1부터)
        val seg = { i: Int -> mc.segments[i - 1] } // i구간(1부터)
        val schoolC = t(3).s1 + 70 // 학교길 어린이보호구역 시작
        val calm1 = jr(t(1).s1 + 300) // 시청로 신호 대기(부드럽게) — 1번 회전 칭찬 카드
        val hard = jr(t(2).s0 - 14) // 2번 회전 앞 신호 정지: 1초에 9km/h → 급정지 카드
        val school = jr(t(4).s0 - 14) // 4번 회전 앞 신호 대기 — 3번 회전 '켜자마자 꺾음' 카드
        val alone = jr(t(4).s1 + 600) // 공원로 신호 대기 — 조수석 비움 뒤 첫 신호 대기 → 면허 동승자 카드
        val uturn = jr(t(5).s0 - 14) // 유턴 앞 신호 대기
        val last = jr(t(6).s0 - 14) // 6번 회전 앞 신호 대기 — 깜빡이 없이 유턴 카드
        val seatEmpty = jr(t(4).s1 + 150)
        val pn = listOf(DriverSkill.POOR, DriverSkill.NORMAL)
        reg.registerScenario(
            ScenarioDef(
                id = "S8-C", title = "운전면허 연습 · 시험장 주변 C 코스", mode = 8, courseId = "c8-C", isDefault = true,
                start = StartState(gear = Gear.P, speed = 0.0, passenger = true),
                cruise = CRUISE,
                turns = mapOf(
                    1 to TurnPlan(blinkAt = 55.0), // 70m에서 꺼져 있음 → 사전 안내(C1), 55m에서 켬 → 칭찬
                    2 to TurnPlan(blinkAt = 25.0), // 30m 안 → '늦음'
                    3 to TurnPlan(blinkAt = 45.0, steerAfter = 1.0), // 켜자마자 꺾음 → 다음 신호 대기 카드
                    4 to TurnPlan(blinkAt = 36.0), // 30m대 → '거의'
                    5 to TurnPlan(blink = false), // 깜빡이 없이 유턴 → 다음 신호 대기 카드
                    6 to TurnPlan(blinkAt = 60.0),
                ),
                events = listOf(
                    ev(t = 0, act = Act.HANDBRAKE, v = true, skills = pn), // 사이드브레이크 채운 채 출발 시도(PB1)
                    ev(t = 0.5, act = Act.GEAR, v = Gear.D),
                    ev(t = 3.4, act = Act.HANDBRAKE, v = false, skills = pn),
                    mark("회전 앞", at = jr(t(1).s0 - 110)),
                    ev(at = calm1, act = Act.STOP, drop = 5, hold = 8),
                    mark("신호 대기", at = hard - 8),
                    ev(at = hard, act = Act.STOP, drop = 9, hold = 10),
                    mark("구간 2", at = jr(seg(2).s0 + 5)),
                    ev(at = schoolC + 2, act = Act.OVER, speed = 36, dur = 5), // 어린이보호구역 들어서자마자 36km/h → E2
                    ev(at = school, act = Act.STOP, drop = 5, hold = 8),
                    mark("중간", at = jr(t(4).s1 + 100)),
                    ev(at = seatEmpty, act = Act.SEAT_EMPTY, skills = pn), // 2구간에서 조수석이 빔 → 면허 동승자 카드
                    mark("동승자 카드", at = alone - 8),
                    ev(at = alone, act = Act.STOP, drop = 5, hold = 10),
                    mark("구간 3", at = jr(seg(3).s0 + 5)),
                    ev(at = uturn, act = Act.STOP, drop = 5, hold = 10),
                    ev(at = jr(t(5).s1 + 150), act = Act.OVER, speed = 58, dur = 6), // 50 구역 58km/h → E1
                    ev(at = last, act = Act.STOP, drop = 5, hold = 8),
                    mark("도착 직전", at = jr(mc.navEnd - 150)),
                    endPark(),
                ),
                variants = mapOf(
                    DriverSkill.POOR to Variant(
                        events = listOf( // 서툰: 급출발 2번(9 + 4 = 13)
                            ev(at = hard, act = Act.LAUNCH, gain = 9),
                            ev(at = alone, act = Act.LAUNCH, gain = 9),
                        ),
                    ),
                ),
                expect = listOf(
                    "PB1", "B1.ok", "C1", "C2.ok", "C2.almost", "C2.late", "C2.fail", "C4.ok", "C4.fail", "C5.ok", "C5.fail",
                    "F1.ok", "F1.fail", "E1.ok", "E1.fail", "E2", "G1", "PB2.fail", "M2.fail",
                ),
            ),
        )
    }

    /* ================================================================ 면허 틀 (생성 코스·시나리오가 없는 모드 8 코스) */
    private val template = ScenarioTemplate { course, opts ->
        val segs = opts.segments?.takeIf { it.isNotEmpty() } ?: opts.legs?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
        if (segs == null || segs.none { it.isNotEmpty() }) return@ScenarioTemplate null
        val id = course.id.ifEmpty { opts.courseId ?: "c8" }
        val name = course.name ?: id
        val rng = SeedRandom.of("license:$id")
        val m = measure(segs)
        val nav = m.turns.filter { it.nav }
        val events = ArrayList<ScenarioEvent>()
        events += ev(t = 0.5, act = Act.GEAR, v = Gear.D)
        // 회전 앞 신호 정지(구간 첫 회전은 70%, 나머지 50%) — 앞 회전과 160m 넘게 떨어진 회전만
        val stops = ArrayList<Double>()
        var far: Pair<T8, Double>? = null // 앞 회전에서 가장 먼 회전(정지가 하나도 안 뽑히면 거기)
        nav.forEachIndexed { i, tr ->
            val gap = tr.s0 - (if (i > 0) nav[i - 1].s1 else 0.0)
            val f = far
            if (f == null || gap > f.second) far = Pair(tr, gap)
            if (gap < 160) return@forEachIndexed
            val first = i == 0 || nav[i - 1].seg != tr.seg
            if (rng() < (if (first) 0.7 else 0.5)) stops += jr(tr.s0 - 14)
        }
        val f = far
        if (stops.isEmpty() && f != null && f.second >= 40) stops += jr(f.first.s0 - 14)
        val hard = if (stops.isNotEmpty()) stops[rng.int(0, stops.size - 1)] else null // 보통: 급정지 1번
        for (at in stops) events += ev(at = at, act = Act.STOP, drop = if (at == hard) 9 else 5, hold = rng.int(8, 14))
        // 보통: 깜빡이 늦음 1번 · 켜자마자 꺾기 1번(첫 회전·유턴 말고)
        val turns = LinkedHashMap<Int, TurnPlan>()
        val pool = nav.drop(1).filter { !it.uturn }
        val late = if (pool.isNotEmpty()) pool[rng.int(0, pool.size - 1)] else null
        if (late != null) turns[late.n] = TurnPlan(blinkAt = 25.0)
        val pool2 = pool.filter { it !== late }
        val quick = if (pool2.isNotEmpty()) pool2[rng.int(0, pool2.size - 1)] else null
        if (quick != null) turns[quick.n] = TurnPlan(blinkAt = 45.0, steerAfter = 1.0)
        // 장면
        if (nav.isNotEmpty()) events += mark("회전 앞", at = max(10.0, jr(nav[0].s0 - 110)))
        m.segments.drop(1).forEachIndexed { i, sg -> events += mark("구간 ${i + 2}", at = jr(sg.s0 + 5)) }
        events += mark("중간", at = jr(m.navEnd / 2))
        events += mark("도착 직전", at = max(0.0, jr(m.navEnd - 150)))
        events += endPark()
        // 서툰: 급출발 두 번(9 + 4 = 13) · 깜빡이 없이 한 번
        val poorTurns = LinkedHashMap<Int, TurnPlan>()
        val noBlink = pool2.filter { it !== quick }
        if (noBlink.isNotEmpty()) poorTurns[noBlink[rng.int(0, noBlink.size - 1)].n] = TurnPlan(blink = false)
        ScenarioDef(
            id = "TPL:$id",
            title = "운전면허 연습 · $name",
            mode = 8, courseId = course.id.ifEmpty { opts.courseId },
            start = StartState(gear = Gear.P, speed = 0.0, passenger = true),
            cruise = CRUISE,
            turns = turns,
            events = events,
            variants = mapOf(
                DriverSkill.POOR to Variant(turns = poorTurns, events = stops.take(2).map { ev(at = it, act = Act.LAUNCH, gain = 9) }),
            ),
            expect = listOf("B1.ok", "C1", "C2.ok", "C2.late", "C4.ok", "C4.fail", "C5.ok", "F1.ok", "F1.fail", "E1.ok", "G1", "PB2.fail"),
            generated = true,
        )
    }
}
