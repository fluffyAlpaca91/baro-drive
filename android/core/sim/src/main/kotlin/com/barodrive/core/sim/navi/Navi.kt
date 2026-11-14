package com.barodrive.core.sim.navi

import com.barodrive.core.model.CautionItem
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.Courses
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSegment
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.NavStatus
import com.barodrive.core.model.TbtItem
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.BaroSim
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.SimEvent
import com.barodrive.core.sim.generic.GenericRoutes
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.Piece
import com.barodrive.core.sim.route.Pose
import com.barodrive.core.sim.route.Route
import com.barodrive.core.sim.route.RouteBuilder
import com.barodrive.core.sim.route.RouteDef
import com.barodrive.core.sim.route.RouteInsert
import com.barodrive.core.sim.route.RouteTurn
import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.vehicle.Vehicle
import com.barodrive.core.sim.vehicle.VehiclePatch
import kotlin.math.max
import kotlin.math.min

/**
 * 차의 기본 내비 흉내 (프로토타입 sim/navi.js `BaroSim.navi`, 설계 7.1·7.4·7.5·6.1.2·6.12.3).
 *
 * 경로가 둘이다: [route](안내 경로 — TBT·지도 파란 선) · [path](차가 실제로 따라가는 길). 보통 같은 것이고, 회전을 놓치면
 * (시나리오 miss·주입 '재탐색') path만 그 회전 앞에 직진 120m를 더 갖고 있다가 지나쳐 30m를 가면 재탐색으로 route = path.
 * 경로가 없으면 path는 '동네 한 바퀴' 고리(내비 안내 없음, 제한속도만 알림, 설계 7.11).
 * 수치: TBT 거리 10m 내림 · 회전 음성 300·120m, IC 1km·500m(결정-71) · 음성 2.5 sim초 · 도착 = 목적지 10m 안
 * · 주의 구간은 600m 앞까지 · 구간이 바뀌면 1.5 sim초 '다시 탐색'.
 */
class Navi internal constructor(private val sim: BaroSim) {
    /** 지금 경로의 출처(navi.js `base`). */
    class Base internal constructor(
        val def: RouteDef,
        val key: String,
        val courseId: String?,
        val placeId: String?,
        val name: String,
        val routeOption: String?,
        val generated: Boolean,
    )

    private class Edits {
        val misses = LinkedHashMap<Int, Double>()
        var rerouted = HashSet<Int>()
        val inserts = ArrayList<RouteInsert>()
    }

    private class VoiceItem(val text: String, val dur: Double, val key: String?)
    private class LimitOverride(val v: Int, val untilS: Double?, val untilT: Double?)

    /** 지금 경로의 출처(null = 안내 없음). */
    var base: Base? = null
        private set
    private var edits: Edits? = null

    /** 안내 경로(null = 안내 없음). */
    var route: Route? = null
        private set

    /** 차가 따라가는 길. */
    var path: Route? = null
        private set

    /** 지금 구간(0부터). */
    var segmentIndex: Int = 0
        private set

    var arrived: Boolean = false
        private set

    /** 경로가 바뀔 때마다 +1(지도 다시 그리기). */
    var version: Int = 0
        private set

    private var passed = HashSet<String>()
    private var wpPassed = HashSet<String>()
    private var prevRemain = HashMap<String, Double>()
    private var announced = HashSet<String>()
    private var schoolPrev = false
    private var voiceQ = ArrayDeque<VoiceItem>()
    private var voiceUntil = -1.0
    private var voiceNow: VoiceItem? = null
    private var tickEvents: List<NavEvent> = emptyList()
    private var pendingEvents: List<NavEvent> = emptyList()
    private var silentEvents: List<NavEvent> = emptyList()
    private var silentBus = ArrayList<SimEvent>()
    private var searchUntil = -1.0
    private var searchKind: String? = null
    private var voicePlan: Map<Int, List<Double>> = emptyMap()
    private var virtual = ArrayList<VirtualTurn>()
    private var injCount = 0
    private var lastNav: NavSnapshot? = null
    private var navDirty = true
    private var limitSource: String? = null
    private var ovLimit: LimitOverride? = null
    private var ovSchool: OverrideSpan? = null
    private var ovJam: OverrideSpan? = null
    private var ovCautions = ArrayList<OverrideCaution>()

    /** 안내 중인가. */
    val active: Boolean get() = route != null && !arrived

    private fun now(): Double = sim.clock.simT
    private fun veh() = sim.vehicle.state
    private fun silent(): Boolean = sim.clock.isSilent()

    private fun emit(e: SimEvent) {
        if (silent()) {
            if (e !is SimEvent.NavVoice) silentBus += e
            return
        }
        sim.emit(e)
    }

    /* ---------- 경로 고르기 ---------- */

    /** 대상 → 경로 출처(navi.js `resolve`). [target]이 정의면 그대로, 글자면 코스 id(없으면 장소 id). */
    fun resolve(target: Any?, key: String? = null, courseId: String? = null, placeId: String? = null, name: String? = null, routeOption: String? = null): Base {
        if (target is RouteDef) {
            require(target.usable) { "경로 정의에 다리가 없어요" }
            val c = Courses.course(courseId)
            val p = Courses.place(placeId)
            return Base(
                def = target, key = key ?: ("def:" + (courseId ?: placeId ?: "route")), courseId = courseId, placeId = placeId,
                name = name ?: target.name ?: c?.name ?: p?.name ?: "목적지",
                routeOption = routeOption ?: target.routeOption ?: c?.routeOption, generated = false,
            )
        }
        var pid = placeId
        var cid: String? = (target as? String)?.takeIf { it.isNotEmpty() } ?: courseId
        if (cid != null && sim.registry.route(cid) == null && Courses.course(cid) == null && Courses.place(cid) != null) {
            pid = cid
            cid = null
        }
        if (cid != null) {
            val c = Courses.course(cid)
            var def = sim.registry.route(cid)
            var generated = false
            if (def == null) {
                def = GenericRoutes.routeFor(GenericRoutes.Target.Course(cid, c))
                generated = true
            }
            return Base(def, "course:$cid", cid, pid, name ?: c?.name ?: def.name ?: cid, routeOption ?: def.routeOption ?: c?.routeOption, generated)
        }
        if (pid != null) {
            val p = Courses.place(pid)
            var def = sim.registry.route(pid) ?: sim.registry.route("place:$pid")
            var generated = false
            if (def == null) {
                def = GenericRoutes.routeFor(GenericRoutes.Target.Place(pid, p))
                generated = true
            }
            return Base(def, "place:$pid", null, pid, name ?: p?.name ?: def.name ?: pid, routeOption ?: def.routeOption, generated)
        }
        throw IllegalArgumentException("경로: 코스 id나 장소(placeId)를 넘겨요")
    }

    private fun buildOpts(all: Boolean): RouteBuilder.Options {
        val b = requireNotNull(base)
        val e = requireNotNull(edits)
        val misses = LinkedHashMap<Int, Double>()
        for ((n, m) in e.misses) if (all || n in e.rerouted) misses[n] = m
        return RouteBuilder.Options(
            key = b.key, courseId = b.courseId, placeId = b.placeId, name = b.name, routeOption = b.routeOption,
            misses = misses, inserts = e.inserts.toList(),
        )
    }

    private fun rebuild(keepRoute: Boolean = false) {
        val b = requireNotNull(base)
        val e = requireNotNull(edits)
        val newPath = RouteBuilder.build(b.def, buildOpts(true))
        path = newPath
        val pending = e.misses.keys.any { it !in e.rerouted }
        if (!keepRoute) route = if (pending) RouteBuilder.build(b.def, buildOpts(false)) else newPath
        version++
        navDirty = true
    }

    private fun resetTracking() {
        segmentIndex = 0
        arrived = false
        passed = HashSet()
        wpPassed = HashSet()
        prevRemain = HashMap()
        announced = HashSet()
        schoolPrev = false
        voiceQ = ArrayDeque()
        searchUntil = -1.0
        searchKind = null
        virtual = ArrayList()
        voicePlan = emptyMap()
        ovLimit = null; ovSchool = null; ovJam = null; ovCautions = ArrayList()
        navDirty = true
    }

    private fun placeCarAtStart(pth: Route) {
        val p0 = pth.sample(0.0)
        sim.vehicle.set(VehiclePatch(s = 0.0, x = p0.x, y = p0.y, heading = p0.h), Vehicle.Source.SIM)
    }

    private fun summary(): RouteSummary? {
        val r = route ?: return null
        val b = base ?: return null
        return RouteSummary(b.key, b.courseId, b.placeId, b.name, b.routeOption, r.segments.size, r.waypoints.size, JsMath.round(r.navEnd).toLong(), b.generated)
    }

    /**
     * 경로를 정한다(navi.js `setRoute`): [target] = 코스 id · 장소 id · [RouteDef]. 차는 새 경로 처음(s 0)에 놓인다(속도는 그대로).
     * → 요약, [SimEvent.Route].
     */
    fun setRoute(target: Any?, key: String? = null, courseId: String? = null, placeId: String? = null, name: String? = null, routeOption: String? = null): RouteSummary? {
        base = resolve(target, key, courseId, placeId, name, routeOption)
        edits = Edits()
        resetTracking()
        rebuild()
        placeCarAtStart(requireNotNull(path))
        val sum = summary()
        emit(SimEvent.Route(sum))
        return sum
    }

    /** 내비 장소로 목적지(navi.js `setDestination`). */
    fun setDestination(placeId: String): RouteSummary? = setRoute(null, placeId = placeId)

    private fun loopPath(start: Pose): Route =
        RouteBuilder.build(RouteDef.legs(GenericRoutes.loopLegs()), RouteBuilder.Options(key = "loop", loop = true, name = "동네 한 바퀴", start = start))

    /** 안내 끝(경로 없음): 차는 그 자리에서 동네 한 바퀴. [quiet]면 [SimEvent.Route]를 보내지 않는다. */
    fun clear(quiet: Boolean = false) {
        val v = veh()
        val hadRoute = route != null
        base = null
        edits = null
        route = null
        resetTracking()
        path = loopPath(Pose(v.x, v.y, v.heading))
        sim.vehicle.set(VehiclePatch(s = 0.0), Vehicle.Source.SIM)
        version++
        if (hadRoute && !quiet) emit(SimEvent.Route(null))
    }

    /* ---------- 움직이기 ---------- */

    /** 차를 path 따라 [ds] m 움직인 자리(vehicle.js move가 부름). [blocked] = 길 끝·길 처음에서 막힘. */
    class Advance(val s: Double, val x: Double, val y: Double, val h: Double, val blocked: Boolean)

    internal fun advance(s0: Double, ds: Double): Advance {
        val pth = path ?: loopPath(Pose(veh().x, veh().y, veh().heading)).also { path = it }
        var s1 = s0 + ds
        var blocked = false
        if (pth.loop) s1 = pth.wrap(s1)
        else if (s1 > pth.length) {
            s1 = pth.length
            blocked = true
        } else if (s1 < 0) {
            s1 = 0.0
            blocked = true
        }
        val p = pth.sample(s1)
        return Advance(s1, p.x, p.y, p.h, blocked)
    }

    /* ---------- 음성 ---------- */

    /** 내비 음성(겹치면 차례로) → [SimEvent.NavVoice]. */
    fun voice(text: String?, dur: Double? = null, key: String? = null) {
        if (text.isNullOrEmpty()) return
        val item = VoiceItem(text, if (dur != null && dur > 0) dur else VOICE_DUR, key)
        if (voiceUntil >= 0 && now() < voiceUntil) {
            voiceQ.addLast(item)
            return
        }
        startVoice(item)
    }

    private fun startVoice(item: VoiceItem) {
        val t = now()
        voiceUntil = t + item.dur
        voiceNow = item
        navDirty = true
        emit(SimEvent.NavVoice(NavVoice(item.text, item.dur, t, item.key)))
    }

    private fun turnPhrase(type: String?, dir: TurnDir, road: String): String {
        val rd = if (road.isNotEmpty()) "$road " else ""
        return when (type) {
            "TURN_RIGHT" -> "우회전"
            "TURN_LEFT" -> "좌회전"
            "TURN_SHARP_R" -> "오른쪽 급회전"
            "TURN_SHARP_L" -> "왼쪽 급회전"
            "TURN_UTURN" -> "유턴"
            "TURN_KEEP_R" -> "오른쪽 방향"
            "TURN_KEEP_L" -> "왼쪽 방향"
            "TURN_ENTER_IC_R" -> "오른쪽 방향 ${rd}진입"
            "TURN_ENTER_IC_L" -> "왼쪽 방향 ${rd}진입"
            "TURN_EXIT_IC_R" -> "오른쪽 방향 ${rd}진출"
            "TURN_EXIT_IC_L" -> "왼쪽 방향 ${rd}진출"
            else -> if (dir == TurnDir.LEFT) "좌회전" else "우회전"
        }
    }

    /** 회전 음성 문장(navi.js `turnLine`): 150m 안이면 '잠시 후 …', 1km 넘으면 킬로미터. */
    fun turnLine(ref: TurnRef, th: Double): String = turnLine(ref.type, ref.dir, ref.road, th)

    private fun turnLine(type: String?, dir: TurnDir, road: String, th: Double): String {
        val ph = turnPhrase(type, dir, road)
        if (th <= 150) return "잠시 후 ${ph}입니다"
        val d = if (th >= 1000) (if (th % 1000 == 0.0) JsMath.str(th / 1000) else JsMath.fixed1(th / 1000)) + "킬로미터" else JsMath.str(th) + "미터"
        return "$d 앞에서 ${ph}입니다"
    }

    private fun thresholds(tr: RouteTurn): List<Double> {
        val plan = tr.n?.let { voicePlan[it] }
        if (!plan.isNullOrEmpty()) return plan.sortedDescending()
        return if (tr.ic) listOf(1000.0, 500.0) else listOf(300.0, 120.0)
    }

    /* ---------- 사건 ---------- */

    private fun event(e: NavEvent) {
        when {
            silent() -> silentEvents = silentEvents + e
            sim.clock.inTick -> tickEvents = tickEvents + e
            else -> pendingEvents = pendingEvents + e
        }
        navDirty = true
    }

    /** 재탐색: 그 회전을 지난 것으로 하고 경로를 다시 찾음(이벤트 + 음성). */
    fun reroute(turnId: String): Boolean {
        val r = route ?: return false
        val tr = r.turns.firstOrNull { it.id == turnId } ?: return false
        return reroute(tr)
    }

    private fun reroute(tr: RouteTurn): Boolean {
        if (route == null) return false
        passed += tr.id
        val e = edits
        val n = tr.n
        if (n != null && e != null && (e.misses[n] ?: 0.0) != 0.0) {
            e.rerouted.add(n)
            rebuild()
        }
        event(NavEvent.Reroute(tr.id))
        voice("경로를 다시 찾았습니다", key = "nav.reroute")
        searchUntil = now() + REROUTE_SHOW
        searchKind = "reroute"
        emit(SimEvent.Reroute(tr.id))
        version++
        return true
    }

    /** 도착(이벤트 + 음성). 이미 도착했거나 안내가 없으면 false. [forced] = 주입·시나리오 '도착'. */
    fun arrive(forced: Boolean = false): Boolean {
        if (route == null || arrived) return false
        arrived = true
        event(NavEvent.Arrived)
        voice("목적지에 도착했습니다. 안내를 종료합니다", key = "nav.arrived")
        emit(SimEvent.Arrived(base?.courseId, base?.placeId, forced))
        sim.clock.keepAlive(6.0)
        version++
        navDirty = true
        return true
    }

    private fun segmentEnd() {
        val r = requireNotNull(route)
        val done = segmentIndex
        segmentIndex++
        event(NavEvent.WaypointArrived(done + 1, done + 1, "segment"))
        voice("경유지에 도착했습니다. 다음 구간 안내를 시작합니다", dur = 3.5, key = "nav.segment")
        searchUntil = now() + SEARCH_SEC
        searchKind = "segment"
        emit(SimEvent.Waypoint(done + 1, done + 1, "segment", next = segmentIndex + 1, count = r.segments.size))
        version++
    }

    private fun missedOnPath(tr: RouteTurn): Boolean {
        val e = edits ?: return false
        val n = tr.n ?: return false
        if ((e.misses[n] ?: 0.0) == 0.0 || n in e.rerouted) return false
        return path !== route
    }

    /* ---------- 틱 ---------- */

    private fun guideTick(s: Double) {
        val r = requireNotNull(route)
        val seg = r.segments.getOrNull(segmentIndex) ?: return
        for (w in r.waypoints) {
            val k = "${w.seg}:${w.index}"
            if (w.seg != segmentIndex || k in wpPassed || s < w.s - 5) continue
            wpPassed += k
            event(NavEvent.WaypointArrived(w.index, segmentIndex + 1, null))
            voice("경유지에 도착했습니다", key = "nav.waypoint")
            emit(SimEvent.Waypoint(w.index, segmentIndex + 1, "waypoint", name = w.name))
        }
        for (tr in r.turns) {
            if (tr.seg != segmentIndex || !tr.nav || tr.id in passed) continue
            if (missedOnPath(tr)) {
                if (s >= tr.sStart + REROUTE_AFTER) {
                    reroute(tr)
                    return
                }
            } else if (s > tr.sEnd + 0.01) {
                passed += tr.id
                continue
            }
            val remain = tr.sStart - s
            val prev = prevRemain[tr.id]
            prevRemain[tr.id] = remain
            if (prev == null || searchKind == "segment") continue
            for (th in thresholds(tr)) {
                val key = tr.id + "@" + JsMath.str(th)
                if (key in announced) continue
                if (prev > th && remain <= th) {
                    announced += key
                    voice(turnLine(tr.type, tr.dir, tr.road, th), key = "nav.turn" + JsMath.str(th))
                }
            }
        }
        if (s >= seg.navEnd - ARRIVE_M) {
            if (segmentIndex < r.segments.size - 1) segmentEnd() else arrive()
        }
    }

    private fun schoolNow(s: Double): Boolean {
        val o = ovSchool
        if (o != null && s >= o.s0 && s <= o.s1) return true
        val r = route
        if (r == null || arrived) return false
        return r.schools.any { it.seg == segmentIndex && s >= it.s0 && s < it.s1 }
    }

    private fun jamNow(s: Double): Boolean {
        val o = ovJam
        if (o != null && s >= o.s0 && s <= o.s1) return true
        val r = route
        if (r == null || arrived) return false
        return r.jams.any { it.seg == segmentIndex && s >= it.s0 && s < it.s1 }
    }

    private fun overrideTick(s: Double, t: Double) {
        ovLimit?.let { l -> if ((l.untilS != null && s >= l.untilS) || (l.untilT != null && t >= l.untilT)) ovLimit = null }
        ovSchool?.let { if (s > it.s1) ovSchool = null }
        ovJam?.let { if (s > it.s1) ovJam = null }
        if (ovCautions.isNotEmpty()) ovCautions = ArrayList(ovCautions.filter { s <= it.s + it.len + 30 })
        if (virtual.isNotEmpty()) {
            val pth = path
            virtual = ArrayList(virtual.filter { v ->
                if (pth == null || pth.turns.none { it === v.turn }) return@filter false
                val d = distTo(v.turn, s)
                if (d <= 0) v.entered = true
                !(v.entered && (d > 50 || d < -v.turn.len - 0.01))
            })
        }
        val inSchool = schoolNow(s)
        if (inSchool && !schoolPrev) voice("어린이보호구역입니다", key = "nav.school")
        schoolPrev = inSchool
    }

    private fun freeTail(s: Double) {
        // 손 조작으로 경로 끝을 넘어가려 하면 길을 더 이어 준다(안내는 이미 끝남)
        val pth = path ?: return
        val b = base ?: return
        if (pth.loop) return
        if (sim.player.state == PlayerState.PLAYING) return
        if (veh().speed <= 0.5 || pth.length - s > 150) return
        val segs = b.def.segments.map { it.toList() }.toMutableList()
        segs[segs.size - 1] = segs.last() + GenericRoutes.freeTail(b.key + ":" + version)
        base = Base(RouteDef(segs, b.def.routeOption, b.def.name), b.key, b.courseId, b.placeId, b.name, b.routeOption, b.generated)
        rebuild(keepRoute = true)
    }

    /** 틱 단계 'navi'(40). */
    internal fun tick() {
        tickEvents = pendingEvents
        pendingEvents = emptyList()
        val t = now()
        val s = veh().s
        if (voiceUntil >= 0 && t >= voiceUntil - 1e-9) {
            voiceUntil = -1.0
            voiceNow = null
        }
        if (voiceUntil < 0 && voiceQ.isNotEmpty()) startVoice(voiceQ.removeFirst())
        if (searchUntil >= 0 && t >= searchUntil) {
            searchUntil = -1.0
            searchKind = null
        }
        if (route != null && !arrived) guideTick(s)
        overrideTick(s, t)
        freeTail(s)
        lastNav = compute(s, t)
        navDirty = false
    }

    /* ---------- nav 값 ---------- */

    /** 회전 시작까지 남은 거리(고리면 한 바퀴를 감아서: (−회전 길이, 고리 길이 − 회전 길이]). */
    fun distTo(tr: RouteTurn, s: Double = veh().s): Double {
        var d = tr.sStart - s
        val pth = path
        if (pth != null && pth.loop && pth.length > 0 && pth.turns.any { it === tr }) {
            val l = pth.length
            while (d < -tr.len - 0.01) d += l
            while (d > l - tr.len - 0.01) d -= l
        }
        return d
    }

    private fun turnItem(e: Upcoming): TbtItem {
        val r = e.ref
        return TbtItem(
            id = r.id, type = r.type ?: "", distance = JsMath.round10(max(0.0, e.remain)), dir = r.dir,
            blinker = r.blinker, ic = r.ic, road = r.road,
        )
    }

    private fun upcomingAt(s: Double): List<Upcoming> {
        val out = ArrayList<Upcoming>()
        val r = route
        if (r != null && !arrived && searchKind != "segment") {
            for (tr in r.turns) {
                if (tr.seg != segmentIndex || !tr.nav || tr.id in passed) continue
                if (!missedOnPath(tr) && s > tr.sEnd) continue
                out += Upcoming(TurnRef(tr), tr.sStart - s)
            }
        }
        for (v in virtual) out += Upcoming(TurnRef(v.turn, v), distTo(v.turn, s))
        out.sortBy { it.remain }
        return out
    }

    /** 다가오는 안내 회전(가까운 순). */
    fun upcoming(): List<Upcoming> = upcomingAt(veh().s)

    /** 안내 중 다음 회전(정확한 남은 m). */
    fun nextTurn(): Upcoming? = upcomingAt(veh().s).firstOrNull()

    private fun limitNow(s: Double): Pair<Int?, String?> {
        var lim: Int? = null
        var src: String? = null
        val o = ovLimit
        val r = route
        val pth = path
        if (o != null) {
            lim = o.v
            src = "override"
        } else if (r != null && !arrived) {
            lim = r.limitAt(s)
            src = if (lim != null) "route" else null
        } else if (pth != null) {
            val p = pth.pieceAt(s)
            if (p.free && p.limit != null) {
                lim = p.limit
                src = "free"
            }
        }
        if (schoolNow(s)) {
            lim = if (lim == null) 30 else min(lim, 30)
            src = src ?: "school"
        }
        return Pair(lim, src)
    }

    private fun cautionsNow(s: Double): List<CautionItem> {
        val out = ArrayList<CautionItem>()
        val r = route
        if (r != null && !arrived) {
            for (c in r.cautions) {
                if (c.seg != segmentIndex) continue
                val remain = c.s - s
                if (remain <= CAUTION_RANGE && remain >= -c.len) out += CautionItem(c.id, c.type, JsMath.round10(max(0.0, remain)))
            }
            for (z in r.schools) {
                if (z.seg != segmentIndex || s >= z.s1) continue
                val remain = z.s0 - s
                if (remain <= CAUTION_RANGE) out += CautionItem(z.id, CautionType.SCHOOL_ZONE, JsMath.round10(max(0.0, remain)))
            }
        }
        val os = ovSchool
        if (os != null && s < os.s1) {
            val remain = os.s0 - s
            if (remain <= CAUTION_RANGE) out += CautionItem(os.id ?: "", CautionType.SCHOOL_ZONE, JsMath.round10(max(0.0, remain)))
        }
        for (c in ovCautions) {
            val remain = c.s - s
            if (remain <= CAUTION_RANGE && remain >= -c.len) out += CautionItem(c.id, c.type, JsMath.round10(max(0.0, remain)))
        }
        out.sortBy { it.remain }
        return out
    }

    private fun compute(s: Double, t: Double): NavSnapshot {
        val r = route
        val guiding = r != null && !arrived
        val ups = upcomingAt(s).take(2)
        val (lim, src) = limitNow(s)
        limitSource = src
        return NavSnapshot(
            status = if (guiding || virtual.isNotEmpty()) NavStatus.ROUTE_DRIVE else NavStatus.INACTIVE,
            tbt = ups.map(::turnItem),
            limit = lim,
            cautions = cautionsNow(s),
            inSchoolZone = schoolNow(s),
            congested = jamNow(s),
            voice = voiceUntil >= 0 && t < voiceUntil,
            heading = JsMath.round1(veh().heading),
            events = tickEvents.toList(),
            remain = if (guiding && r != null) max(0.0, JsMath.round(r.segments[segmentIndex].navEnd - s)) else null,
            segment = r?.let { NavSegment(min(segmentIndex, it.segments.size - 1) + 1, it.segments.size) },
        )
    }

    /** 스냅숏 nav(navi.js `navFields`). 틱 밖에서 값이 바뀌었으면 다시 계산한다. */
    fun navFields(): NavSnapshot {
        val cached = lastNav
        if (cached == null || (navDirty && !sim.clock.inTick)) {
            val n = compute(veh().s, now())
            lastNav = n
            navDirty = false
            return n
        }
        return cached
    }

    /* ---------- 남은 거리·시간 ---------- */

    private fun pieceSec(p: Piece, from: Double, to: Double): Double {
        val len = max(0.0, min(p.s1, to) - max(p.s0, from))
        if (!(len > 0)) return 0.0
        var v = if (p.limit != null) min(p.limit.toDouble(), 80.0) * 0.8 else 30.0
        if (p.kind == Piece.Kind.ARC) v = min(v, 18.0)
        val r = route
        if (r != null && r.jams.any { p.s0 < it.s1 && p.s1 > it.s0 }) v = min(v, 15.0)
        return len / (max(5.0, v) / 3.6)
    }

    private fun remainSec(fromS: Double, toS: Double): Double {
        val r = route ?: return 0.0
        val b = base
        // 코스·장소 데이터에 예상 시간(min)이 있으면 그 값을 남은 거리 비율로(CR-1 카드의 '7분'과 처음이 같게)
        val min = when {
            b?.courseId != null -> Courses.course(b.courseId)?.min
            b?.placeId != null -> Courses.place(b.placeId)?.min
            else -> null
        }
        if (min != null && min > 0 && r.segments.size == 1 && r.navEnd > 0) {
            return min * 60.0 * max(0.0, toS - fromS) / r.navEnd
        }
        var sec = 0.0
        for (p in r.pieces) {
            if (p.s1 <= fromS || p.s0 >= toS) continue
            sec += pieceSec(p, fromS, toS)
        }
        return sec
    }

    /** 화면용 내비 상태(navi.js `state`). */
    fun state(): NavState {
        val s = veh().s
        val t = now()
        val n = navFields()
        val r = route
        val guiding = r != null && !arrived
        val seg = r?.segments?.get(min(segmentIndex, r.segments.size - 1))
        val remainM = if (guiding && seg != null) max(0.0, seg.navEnd - s) else 0.0
        val totalRemainM = if (guiding && r != null) max(0.0, r.navEnd - s) else 0.0
        val b = base
        return NavState(
            nav = n,
            guiding = guiding,
            arrived = arrived,
            searching = if (searchUntil >= 0 && t < searchUntil) searchKind else null,
            remainM = JsMath.round(remainM).toLong(),
            remainSec = if (guiding && seg != null) JsMath.round(remainSec(s, seg.navEnd)).toLong() else 0L,
            totalRemainM = JsMath.round(totalRemainM).toLong(),
            destName = b?.name,
            routeOption = b?.routeOption,
            courseId = b?.courseId,
            placeId = b?.placeId,
            key = b?.key,
            waypoints = r?.waypoints?.filter { it.seg == segmentIndex }?.map {
                NavWaypointState(it.index, it.name, max(0.0, JsMath.round(it.s - s)).toLong(), "${it.seg}:${it.index}" in wpPassed)
            } ?: emptyList(),
            voiceText = if (n.voice) voiceNow?.text else null,
            limitSource = limitSource,
            loop = path?.loop == true,
        )
    }

    /* ---------- 회전 놓치기·끼우기 ---------- */

    /** 시나리오가 불러올 때: 이 번호 회전들을 놓친다(값 = 덧붙일 직진 m, 40 이하면 120). */
    fun setMisses(map: Map<Int, Double?>): Boolean {
        if (base == null) return false
        val e = requireNotNull(edits)
        e.misses.clear()
        for ((k, v) in map) e.misses[k] = if (v != null && v > 40) v else MISS_EXTRA
        e.rerouted = HashSet()
        rebuild()
        return true
    }

    /** 다음 회전(또는 [n]번 · id [id])을 놓치게 path를 바꾼다(시나리오 reroute·주입 '재탐색'). → 놓칠 회전 | null. */
    fun missTurn(n: Int? = null, id: String? = null): RouteTurn? {
        val r = route ?: return null
        if (base == null) return null
        val s = veh().s
        val tr: RouteTurn? = when {
            n != null || id != null -> r.turns.firstOrNull { (n != null && it.n == n) || (id != null && it.id == id) }
            else -> upcomingAt(s).firstOrNull { it.ref.n != null && it.ref.virtual == null && it.remain > 25 }?.ref?.turn
        }
        if (tr == null || tr.n == null || tr.sStart - s < 20) return null
        requireNotNull(edits).misses[tr.n] = MISS_EXTRA
        rebuild(keepRoute = true)
        return tr
    }

    /** 시나리오가 불러올 때: 회전 번호 → 음성 거리들(navVoiceAt). */
    fun setVoicePlan(map: Map<Int, List<Double>>) {
        voicePlan = map.toMap()
    }

    /**
     * 다가오는 회전(TBT)을 넣는다(navi.js `injectTurn`). 안내 중이면 길에 회전을 끼우고(뒤 경로가 돈다) — 70~260m 앞에 이미 회전이 있으면
     * 그것에 묶는다([force]면 늘 끼움) —, 아니면 고리의 다음 모퉁이에 가상 TBT를 붙인다. → 결과 | null(넣을 자리가 없음).
     */
    fun injectTurn(distance: Double = 100.0, dir: TurnDir = TurnDir.RIGHT, type: String? = null, angle: Double? = null, r: Double? = null, force: Boolean = false): InjectedTurn? {
        val s = veh().s
        val dist = max(40.0, if (distance > 0) distance else 100.0)
        val b = base
        if (route != null && !arrived && b != null) {
            val ne = upcomingAt(s).firstOrNull()
            if (ne != null && ne.remain >= 70 && ne.remain <= 260 && !force) return InjectedTurn(ne.ref, bound = true)
            val pth = requireNotNull(path)
            // 끼울 자리: 원래 거리, 다른 회전에서 30m 넘게 떨어진 직진 위
            var at = s + dist
            val nearTurn = { sv: Double -> pth.turns.any { sv > it.sStart - 35 && sv < it.sEnd + 35 } }
            var guard = 0
            while (guard++ < 200) {
                val p = pth.pieceAt(at)
                if (at >= pth.length - 40) return null
                if (p.kind == Piece.Kind.LINE && p.detourOf == null && !p.injected && !nearTurn(at) && p.so1 > p.so0) break
                at += 10
            }
            val p = pth.pieceAt(at)
            val so = p.so0 + (at - p.s0)
            val id = "i" + (++injCount)
            val t = type.orEmpty()
            val ang = if (angle != null && angle != 0.0) angle else when {
                t == "TURN_UTURN" -> 180.0
                t.contains("SHARP") -> 120.0
                t.contains("KEEP") -> 20.0
                else -> 90.0
            }
            val leg = Leg.Turn(dir = dir, angle = ang, tbt = type?.takeIf { it.isNotEmpty() }, r = r ?: (if (t.contains("IC")) 150.0 else null))
            requireNotNull(edits).inserts += RouteInsert(so, leg, id)
            val before = pth.length
            rebuild()
            val newPath = requireNotNull(path)
            val tr = newPath.turns.firstOrNull { it.id == id }
            sim.player.onPathEdit(tr?.sStart ?: at, newPath.length - before)
            return tr?.let { InjectedTurn(TurnRef(it), bound = false) }
        }
        // 고리(안내 없음)·안내 끝: 다음 모퉁이에 TBT를 붙인다
        val pth = path ?: return null
        val c = pth.turns.map { it to distTo(it, s) }
            .filter { (tr, d) -> d >= 60 && virtual.none { it.turn === tr } }
            .sortedBy { it.second }
            .firstOrNull() ?: return null
        val id = "v" + (++injCount)
        val vType = type ?: (if (c.first.dir == TurnDir.LEFT) "TURN_LEFT" else "TURN_RIGHT")
        val vDir = when {
            vType.endsWith("_L") || vType.contains("LEFT") -> TurnDir.LEFT
            vType.endsWith("_R") || vType.contains("RIGHT") -> TurnDir.RIGHT
            else -> c.first.dir
        }
        val v = VirtualTurn(id, c.first, vType, vDir)
        virtual.add(v)
        navDirty = true
        return InjectedTurn(TurnRef(c.first, v), bound = true, virtual = true)
    }

    /** 고리 위 회전에 붙인 가상 TBT. */
    fun virtualFor(pathTurn: RouteTurn): VirtualTurn? = virtual.firstOrNull { it.turn === pathTurn }

    /* ---------- 손 바꾸기(시연 패널 '내비'·주입·시나리오) ---------- */

    /** 제한속도를 [v]로(앞으로 [meters] m, 또는 [sec]초). null이면 경로 값으로. */
    fun overrideLimit(v: Int?, meters: Double? = null, sec: Double? = null) {
        if (v == null) {
            ovLimit = null
            navDirty = true
            return
        }
        val s = veh().s
        val m = meters ?: 300.0
        ovLimit = LimitOverride(v, if (m.isFinite()) s + m else null, if (sec != null && sec != 0.0) now() + sec else null)
        navDirty = true
    }

    /** 어린이보호구역을 켬([startIn] m 뒤부터 [meters] m) · 끔. */
    fun overrideSchool(on: Boolean, meters: Double? = null, startIn: Double? = null) {
        if (!on) {
            ovSchool = null
            navDirty = true
            return
        }
        val s = veh().s
        val st = startIn ?: 0.0
        val m = meters ?: 250.0
        ovSchool = OverrideSpan("szx" + (++injCount), s + st, s + st + m)
        navDirty = true
    }

    /** 정체를 켬(지금부터 [meters] m) · 끔. */
    fun overrideJam(on: Boolean, meters: Double? = null) {
        if (!on) {
            ovJam = null
            navDirty = true
            return
        }
        val s = veh().s
        ovJam = OverrideSpan(null, s, s + (meters ?: 400.0))
        navDirty = true
    }

    /** 주의 구간을 [ahead] m 앞에 넣음. */
    fun overrideCaution(type: CautionType = CautionType.SLIPPERY_ROAD_ALERT, ahead: Double? = null, len: Double? = null): OverrideCaution {
        val s = veh().s
        val c = OverrideCaution("cax" + (++injCount), s + (ahead ?: 300.0), type, if (len != null && len != 0.0) len else 0.0)
        ovCautions.add(c)
        navDirty = true
        return c
    }

    /** 손 바꾸기 지우기: null이면 모두, 아니면 'limit'·'school'·'jam'·'cautions'. */
    fun overrideClear(kind: String? = null) {
        if (kind == null || kind == "limit") ovLimit = null
        if (kind == null || kind == "school") ovSchool = null
        if (kind == null || kind == "jam") ovJam = null
        if (kind == null || kind == "cautions") ovCautions = ArrayList()
        navDirty = true
    }

    /** 지금 손 바꾸기 값. */
    val overrides: NavOverrides
        get() = NavOverrides(ovLimit?.v, ovLimit?.untilS, ovLimit?.untilT, ovSchool, ovJam, ovCautions.toList())

    fun schoolNow(): Boolean = schoolNow(veh().s)

    fun jamNow(): Boolean = jamNow(veh().s)

    fun isPassed(id: String): Boolean = id in passed

    /** 건너뛰기(silent) 동안 모은 버스 사건을 차례로 보낸다(음성은 버림). */
    internal fun flushSilent(): Int {
        val q = silentBus
        silentBus = ArrayList()
        pendingEvents = pendingEvents + silentEvents
        silentEvents = emptyList()
        navDirty = true
        for (e in q) sim.emit(e)
        return q.size
    }

    /** 같은 경로로 다시 시작(시나리오를 다시 불러올 때): 차를 처음에 두고 안내 상태를 처음으로. [SimEvent.Route]는 안 보냄. */
    internal fun restart(): Boolean {
        if (base == null) return false
        edits = Edits()
        resetTracking()
        rebuild()
        placeCarAtStart(requireNotNull(path))
        return true
    }

    internal fun reset() {
        base = null
        edits = null
        route = null
        resetTracking()
        voiceUntil = -1.0
        voiceNow = null
        tickEvents = emptyList()
        pendingEvents = emptyList()
        silentEvents = emptyList()
        silentBus = ArrayList()
        injCount = 0
        path = loopPath(Pose(0.0, 0.0, 0.0))
        version++
        lastNav = null
    }

    private companion object {
        const val ARRIVE_M = 10.0
        const val VOICE_DUR = 2.5
        const val REROUTE_AFTER = 30.0
        const val MISS_EXTRA = 120.0
        const val CAUTION_RANGE = 600.0
        const val SEARCH_SEC = 1.5
        const val REROUTE_SHOW = 1.2
    }
}
