package com.barodrive.core.sim.generic

import com.barodrive.core.model.Course
import com.barodrive.core.model.Courses
import com.barodrive.core.model.Gear
import com.barodrive.core.model.Place
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.navi.Navi
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.RouteBuilder
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.route.Span
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.EventValue
import com.barodrive.core.sim.scenario.ScenarioDef
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.scenario.StartState
import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.util.SeedRandom
import kotlin.math.max
import kotlin.math.min

/**
 * 일반 경로 생성기 (프로토타입 sim/generic.js `BaroSim.generic`, 설계 7.4·8.2 '생성' 코스·7.11 동네 한 바퀴).
 *
 * - 시내: 직진 200~600m와 좌우 회전을 섞고(진행 방향이 뒤로 돌지 않게 계단 모양) 제한속도 30·50·60을 섞는다. 처음·끝은 30.
 * - 고속도로(모드 4 코스): 시내 → IC 진입(110m 기준 TBT) → 진입로 60 → 본선 100 → IC 진출 → 시내.
 * 시드(코스·장소 id + 이름)가 같으면 늘 같은 길.
 */
object GenericRoutes {
    val ROADS: List<String> = listOf(
        "은행나무길", "느티나무로", "시청로", "강변로", "장미길", "학교길", "중앙로", "공원로", "새말길", "푸른길",
        "문화로", "샘터길", "햇살로", "솔밭길", "큰골길", "버들길", "한빛로", "연꽃길", "배울길", "시장길",
    )

    /** 모드별 기본 순항 km/h. */
    val CRUISE: Map<Int, Double> = mapOf(1 to 25.0, 2 to 28.0, 3 to 50.0, 4 to 95.0, 5 to 45.0, 6 to 12.0, 7 to 25.0, 8 to 32.0)

    fun cruiseFor(mode: Int?): Double = CRUISE[mode] ?: 40.0

    /** 경로를 만들 대상: 코스(데이터가 없을 수도 — 시험 코스) · 장소. */
    sealed interface Target {
        val id: String

        data class Course(override val id: String, val data: com.barodrive.core.model.Course?) : Target
        data class Place(override val id: String, val data: com.barodrive.core.model.Place?) : Target
    }

    /**
     * 코스·장소 데이터에서 거리(m) 읽기(generic.js `distOf`): distM·distanceM·lengthM·meters(m) → km·distKm·distanceKm·lengthKm
     * → dist·distance·length(200 아래 수는 km, 글자 '2.6km') → info·meta·sub·summary 글자 속 'km'. 없으면 null.
     */
    fun distOf(o: Map<String, Any?>?): Double? {
        if (o == null) return null
        fun num(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: Double.NaN
            else -> Double.NaN
        }
        for (k in listOf("distM", "distanceM", "lengthM", "meters")) if (num(o[k]) > 0) return num(o[k])
        for (k in listOf("km", "distKm", "distanceKm", "lengthKm")) if (num(o[k]) > 0) return num(o[k]) * 1000
        for (k in listOf("dist", "distance", "length")) {
            val v = o[k]
            if (v is Number && v.toDouble() > 0) return if (v.toDouble() < 200) v.toDouble() * 1000 else v.toDouble()
            if (v is String) {
                val m = Regex("([\\d.]+)\\s*(km|m)?", RegexOption.IGNORE_CASE).find(v)
                if (m != null) {
                    val n = m.groupValues[1].toDoubleOrNull() ?: Double.NaN
                    return if (m.groupValues[2].lowercase() == "m") n else n * 1000
                }
            }
        }
        val t = listOf("info", "meta", "sub", "summary").firstNotNullOfOrNull { o[it] as? String }
        if (t != null) {
            val m = Regex("([\\d.]+)\\s*km", RegexOption.IGNORE_CASE).find(t)
            if (m != null) return (m.groupValues[1].toDoubleOrNull() ?: Double.NaN) * 1000
        }
        return null
    }

    private fun distOf(c: Course?): Double? = c?.km?.takeIf { it > 0 }?.let { it * 1000 }
    private fun distOf(p: Place?): Double? = p?.km?.takeIf { it > 0 }?.let { it * 1000 }

    /** 코스 모드(없거나 0 이하면 null). */
    fun modeOf(c: Course?): Int? = c?.mode?.takeIf { it > 0 }

    private fun pickLimit(r: SeedRandom, i: Int, n: Int): Int {
        if (i == 0 || i == n - 1) return 30
        val x = r()
        return if (x < 0.2) 30 else if (x < 0.75) 50 else 60
    }

    private fun cityLegs(r: SeedRandom, target: Double, school: Boolean): List<Leg> {
        val legs = ArrayList<Leg>()
        val roads = ROADS.toMutableList()
        val road = {
            val i = r.int(0, roads.size - 1)
            if (i in roads.indices) roads.removeAt(i) else "동네길"
        }
        var total = 0.0
        var rel = 0
        val first = min(260.0, max(120.0, JsMath.round(target * 0.08)))
        legs += Leg.Go(len = first, limit = 30, road = road())
        total += first
        val plan = ArrayList<Double>()
        while (total < target - 250) {
            val remain = target - total
            var len = r.int(200, 600).toDouble()
            if (remain - len < 220) len = max(180.0, remain - 40)
            plan += len
            total += len + 16
        }
        plan.forEachIndexed { i, len ->
            val dir = when (rel) {
                90 -> TurnDir.LEFT
                -90 -> TurnDir.RIGHT
                else -> if (r() < 0.5) TurnDir.LEFT else TurnDir.RIGHT
            }
            rel += if (dir == TurnDir.RIGHT) 90 else -90
            legs += Leg.Turn(dir = dir, angle = 90.0, r = if (r() < 0.5) 10.0 else 12.0)
            val limit = pickLimit(r, i + 1, plan.size + 1)
            legs += Leg.Go(len = JsMath.round(len), limit = limit, road = road())
        }
        val last = legs.last()
        if (last is Leg.Go) legs[legs.size - 1] = last.copy(limit = 30)
        if (school && legs.size > 4 && r() < 0.5) {
            val gi = legs.indexOfFirst { it is Leg.Go && it.limit == 30 && it.len >= 220 }
            if (gi >= 0) {
                val g = legs[gi] as Leg.Go
                legs[gi] = g.copy(school = listOf(Span(60.0, min(g.len - 40, 220.0))))
            }
        }
        return legs
    }

    private fun highwayLegs(r: SeedRandom, target: Double, from: String?, to: String?): List<Leg> {
        val inName = from ?: "북IC"
        val outName = to ?: "동IC"
        val main = max(1500.0, target - 2200)
        return listOf(
            Leg.Go(len = 320.0, limit = 50, road = ROADS[r.int(0, ROADS.size - 1)]),
            Leg.Turn(dir = TurnDir.RIGHT, angle = 90.0, r = 12.0),
            Leg.Go(len = 380.0, limit = 60, road = "$inName 가는 길"),
            Leg.Turn(dir = TurnDir.RIGHT, angle = 30.0, r = 140.0, tbt = "TURN_ENTER_IC_R", road = inName),
            Leg.Go(len = 420.0, limit = 60, road = "$inName 진입로"),
            Leg.Turn(dir = TurnDir.LEFT, angle = 25.0, r = 320.0, noTbt = true),
            Leg.Go(len = JsMath.round(main), limit = 100, road = "고속도로"),
            Leg.Turn(dir = TurnDir.RIGHT, angle = 25.0, r = 220.0, tbt = "TURN_EXIT_IC_R", road = outName),
            Leg.Go(len = 450.0, limit = 60, road = "$outName 나들목"),
            Leg.Turn(dir = TurnDir.RIGHT, angle = 90.0, r = 12.0),
            Leg.Go(len = 300.0, limit = 50, road = ROADS[r.int(0, ROADS.size - 1)]),
        )
    }

    /** 다리 목록(generic.js `legs(seed, distM, { mode, highway, from, to })`). */
    fun legs(seed: String, distM: Double?, mode: Int? = null, highway: Boolean = false, from: String? = null, to: String? = null, school: Boolean = true): List<Leg> {
        val r = SeedRandom.of("route:$seed")
        val target = max(600.0, if (distM != null && distM != 0.0 && !distM.isNaN()) distM else 2000.0)
        if (highway || mode == 4) return highwayLegs(r, target, from, to)
        return cityLegs(r, target, school)
    }

    /** 코스·장소 경로(generic.js `routeFor`). 시드 = id + 이름. 면허 코스(segments ≥ 2)는 거리가 비슷한 구간으로 자른다. */
    fun routeFor(t: Target): RouteDef {
        val course = (t as? Target.Course)?.data
        val place = (t as? Target.Place)?.data
        val name = course?.name ?: place?.name ?: t.id
        val mode = if (t is Target.Place) 5 else modeOf(course)
        var dist = if (t is Target.Place) distOf(place) else distOf(course)
        if (dist == null || dist == 0.0) dist = if (t is Target.Place) 2500.0 else 2000.0
        if (t is Target.Place && (dist < 800 || place?.here == true)) dist = 1500.0 // '집'(지금 위치)은 동네를 한 바퀴
        var from: String? = null
        var to: String? = null
        val m = Regex("([가-힣A-Za-z]+IC)\\s*(?:→|->|에서)\\s*([가-힣A-Za-z]+IC)").find(name)
        if (m != null) {
            from = m.groupValues[1]
            to = m.groupValues[2]
        }
        val lg = legs(t.id + ":" + name, dist, mode = mode, from = from, to = to)
        val routeOption = course?.routeOption ?: when (mode) {
            4 -> "고속도로 우선"
            3, 7, 8 -> "추천"
            else -> "편안한 길"
        }
        val segs = course?.segments
        val nSeg = if (segs != null && segs >= 2) min(4, segs) else 1
        if (nSeg > 1) return RouteDef(splitSegments(lg, nSeg), routeOption, name, generated = true)
        return RouteDef.legs(lg, routeOption, name, generated = true)
    }

    /**
     * 다리 목록을 거리가 비슷한 [n]구간으로 자른다(면허 코스: 구간마다 내비가 다음 구간을 다시 찾음, 설계 6.12.3).
     * 자르는 자리는 직진 다리 가운데(회전에 붙지 않게).
     */
    fun splitSegments(lg: List<Leg>, n: Int): List<List<Leg>> {
        val legLen = { l: Leg -> if (l is Leg.Go) l.len else 16.0 }
        val total = lg.fold(0.0) { a, l -> a + legLen(l) }
        val starts = ArrayList<Double>()
        var acc = 0.0
        for (l in lg) {
            starts += acc
            acc += legLen(l)
        }
        data class Cut(val i: Int, val at: Double)
        val cuts = ArrayList<Cut>()
        var from = 0
        for (k in 1 until n) {
            val goal = total * k / n
            var pick: Cut? = null
            for (i in from until lg.size) {
                val l = lg[i]
                if (l !is Leg.Go || l.len < 90) continue
                val a = starts[i]
                val b = a + l.len
                if (b - 40 < goal) continue
                val at = max(40.0, min(l.len - 40, goal - a))
                pick = Cut(i, JsMath.round(at))
                break
            }
            if (pick == null) break
            cuts += pick
            from = pick.i + 1
        }
        val out = ArrayList<MutableList<Leg>>()
        out.add(ArrayList())
        lg.forEachIndexed { i, l ->
            val c = cuts.firstOrNull { it.i == i }
            if (c == null || l !is Leg.Go) {
                out.last().add(l)
                return@forEachIndexed
            }
            out.last().add(l.copy(len = c.at, school = emptyList()))
            out.add(mutableListOf(l.copy(len = l.len - c.at, school = emptyList())))
        }
        return out.filter { it.isNotEmpty() }
    }

    /** 동네 한 바퀴(고리, 내비 안내 없음·제한속도는 알림). */
    fun loopLegs(): List<Leg> = listOf(
        Leg.Go(len = 250.0, limit = 30, road = "동네길", nav = false, free = true),
        Leg.Turn(dir = TurnDir.RIGHT, angle = 90.0, r = 10.0),
        Leg.Go(len = 190.0, limit = 30, road = "학교앞길", free = true),
        Leg.Turn(dir = TurnDir.RIGHT, angle = 90.0, r = 10.0),
        Leg.Go(len = 250.0, limit = 50, road = "중앙로", free = true),
        Leg.Turn(dir = TurnDir.RIGHT, angle = 90.0, r = 12.0),
        Leg.Go(len = 190.0, limit = 30, road = "샘터길", free = true),
        Leg.Turn(dir = TurnDir.RIGHT, angle = 90.0, r = 10.0),
    )

    /** 경로 끝에 덧붙일 길(손 조작으로 끝을 넘어갈 때). */
    fun freeTail(seed: String): List<Leg> {
        val r = SeedRandom.of("tail:$seed")
        val d1 = if (r() < 0.5) TurnDir.LEFT else TurnDir.RIGHT
        val l1 = r.int(220, 360).toDouble()
        val l2 = r.int(260, 420).toDouble()
        val l3 = r.int(300, 500).toDouble()
        return listOf(
            Leg.Go(len = l1, limit = 30, road = "동네길", nav = false, free = true),
            Leg.Turn(dir = d1, angle = 90.0, r = 10.0, nav = false),
            Leg.Go(len = l2, limit = 30, road = "동네길", nav = false, free = true),
            Leg.Turn(dir = d1.opposite, angle = 90.0, r = 10.0, nav = false),
            Leg.Go(len = l3, limit = 30, road = "동네길", nav = false, free = true),
        )
    }

    /**
     * 기본 틀 시나리오(generic.js `scenario`): 출발 → 경로(회전 앞 신호 대기 정지 몇 번) → 도착 → P. 모드 틀이 없을 때 재생기가 쓴다.
     * 대상은 코스 id 또는 장소 id 하나. [navi]로 그 코스·장소 경로를 찾는다(등록 경로 → 생성).
     */
    fun scenario(navi: Navi, courseId: String?, placeId: String?, mode: Int? = null): ScenarioDef {
        val data = Courses.course(courseId)
        val resolved = navi.resolve(courseId, courseId = courseId, placeId = placeId)
        val route = RouteBuilder.build(resolved.def)
        val m = mode?.takeIf { it != 0 } ?: (if (placeId != null) 5 else modeOf(data) ?: 1)
        val key = courseId ?: placeId ?: "route"
        val r = SeedRandom.of("scenario:$key")
        val events = ArrayList<ScenarioEvent>()
        events += ScenarioEvent(t = 0.5, act = Act.GEAR, v = EventValue.of(Gear.D))
        var stops = 0
        for (tr in route.turns) {
            if (!tr.nav || tr.ic || tr.sStart < 150 || tr.r > 30) continue
            if (r() < (if (stops == 0) 0.7 else 0.4)) {
                val at = JsMath.round(tr.sStart - 9)
                events += ScenarioEvent(at = at, act = Act.STOP, drop = 5.0, hold = r.int(6, 14).toDouble())
                stops++
            }
        }
        val navEnd = route.navEnd
        if (navEnd > 600) events += ScenarioEvent(mark = "중간", at = JsMath.round(navEnd / 2))
        events += ScenarioEvent(mark = "도착 직전", at = max(0.0, JsMath.round(navEnd - 150)))
        events += ScenarioEvent(end = true, act = Act.PARK)
        return ScenarioDef(
            id = "GEN:$key",
            title = (resolved.name.takeIf { it.isNotEmpty() } ?: key) + " · 자동 생성",
            mode = m,
            courseId = courseId,
            placeId = placeId,
            cruise = cruiseFor(m),
            start = StartState(gear = Gear.P),
            events = events,
            generated = true,
        )
    }
}
