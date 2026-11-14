package com.barodrive.core.sim.route

import com.barodrive.core.model.CautionType
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.util.JsMath.DEG
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 다리 목록 → 경로 (프로토타입 sim/route.js `BaroSim.route`, 설계 7.4).
 * TBT 종류: 각도 ≥ 150 유턴 · 100~149 급회전 · 30~99 좌·우회전 · 10~29 방향(KEEP) · 10 미만은 굽은 길(TBT 없음).
 */
object RouteBuilder {
    const val U_TURN_R = 6.0
    const val SHARP_R = 8.0
    const val TURN_R = 10.0
    const val KEEP_R = 60.0

    /** 만들 때 고를 수 있는 값(route.js `build(def, opts)`의 opts). */
    data class Options(
        val key: String? = null,
        val courseId: String? = null,
        val placeId: String? = null,
        val name: String? = null,
        val routeOption: String? = null,
        val start: Pose? = null,
        val loop: Boolean = false,
        /** n번째 회전을 놓침: 그 회전 앞에 덧붙일 직진 m. 회전 id는 't{n}r'(재탐색 뒤 경로). */
        val misses: Map<Int, Double> = emptyMap(),
        /** 원래 경로 거리 so에 끼울 회전(주입). */
        val inserts: List<RouteInsert> = emptyList(),
    )

    /** TBT 종류(`tbtType(leg)`). null = TBT 없음. */
    fun tbtType(leg: Leg.Turn): String? {
        if (leg.tbt != null) return leg.tbt
        if (leg.noTbt) return null
        val a = abs(leg.angle)
        val r = leg.dir.code
        return when {
            a >= 150 -> "TURN_UTURN"
            a >= 100 -> "TURN_SHARP_$r"
            a >= 30 -> if (leg.dir == TurnDir.LEFT) "TURN_LEFT" else "TURN_RIGHT"
            a >= 10 -> "TURN_KEEP_$r"
            else -> null
        }
    }

    /** IC 진입·진출 회전인가(`/_IC_/`). */
    fun isIC(type: String?): Boolean = type != null && type.contains("_IC_")

    fun defaultR(angle: Double): Double {
        val a = abs(angle)
        return when {
            a >= 150 -> U_TURN_R
            a >= 100 -> SHARP_R
            a >= 30 -> TURN_R
            else -> KEEP_R
        }
    }

    /** 방향 h(°)의 앞 단위 벡터. */
    fun fwd(h: Double): Pair<Double, Double> = Pair(JsMath.sin(h * DEG), -JsMath.cos(h * DEG))

    /** 방향 h(°)의 오른쪽 단위 벡터. */
    fun right(h: Double): Pair<Double, Double> = Pair(JsMath.cos(h * DEG), JsMath.sin(h * DEG))

    /** 조각 [p] 안 [u] m 자리의 자세. */
    fun samplePiece(p: Piece, u0: Double): Pose {
        var u = u0
        if (u < 0) u = 0.0
        if (u > p.len) u = p.len
        if (p.kind == Piece.Kind.LINE) {
            val (fx, fy) = fwd(p.h0)
            return Pose(p.x0 + fx * u, p.y0 + fy * u, p.h0)
        }
        val h = p.h0 + p.d * (u / p.r) / DEG
        val (rx, ry) = right(h)
        return Pose(p.cx - p.d * p.r * rx, p.cy - p.d * p.r * ry, h)
    }

    fun build(def: RouteDef, opts: Options = Options()): Route = Builder(def, opts).build()

    private class Meta(
        val seg: Int,
        val nav: Boolean,
        val limit: Int?,
        val free: Boolean,
        val road: String,
        val grade: Double = 0.0,
        val cruise: Double? = null,
        val detourOf: Int? = null,
        val noOrig: Boolean = false,
        val injected: Boolean = false,
    )

    private class Arc(
        val piece: Piece,
        val angle: Double,
        val r: Double,
        val len: Double,
        val cornerX: Double,
        val cornerY: Double,
        val hIn: Double,
        val hOut: Double,
    )

    private class Feat(val so0: Double, val so1: Double, val seg: Int)
    private class CautionFeat(val so: Double, val type: CautionType, val len: Double, val seg: Int)

    private class Builder(val def: RouteDef, val opts: Options) {
        var x = opts.start?.x ?: 0.0
        var y = opts.start?.y ?: 0.0
        var h = opts.start?.h ?: 0.0
        var s = 0.0
        var so = 0.0
        var n = 0
        var nav = true
        var limit: Int? = null
        var road = ""
        var free = false
        var grade = 0.0
        var cruise: Double? = null
        val pieces = ArrayList<Piece>()
        val turns = ArrayList<RouteTurn>()
        val waypoints = ArrayList<RouteWaypoint>()
        val segments = ArrayList<RouteSegment>()
        val schools = ArrayList<Feat>()
        val cautions = ArrayList<CautionFeat>()
        val jams = ArrayList<Feat>()
        val inserts = opts.inserts.sortedBy { it.so }
        var insIdx = 0

        fun addLine(len: Double, m: Meta): Piece? {
            if (!(len > 0)) return null
            val (fx, fy) = fwd(h)
            val p = Piece(
                kind = Piece.Kind.LINE, s0 = s, s1 = s + len, len = len, x0 = x, y0 = y, h0 = h, cx = 0.0, cy = 0.0, r = 0.0, d = 0,
                seg = m.seg, nav = m.nav, limit = m.limit, free = m.free, road = m.road, grade = m.grade, cruise = m.cruise,
                so0 = so, so1 = so + (if (m.noOrig) 0.0 else len), detourOf = m.detourOf, injected = m.injected,
            )
            p.x1 = x + fx * len
            p.y1 = y + fy * len
            p.h1 = h
            pieces += p
            s += len
            if (!m.noOrig) so += len
            x = p.x1
            y = p.y1
            return p
        }

        fun addArc(leg: Leg.Turn, m: Meta): Arc {
            val angle = abs(leg.angle)
            val d = if (leg.dir == TurnDir.LEFT) -1 else 1
            val r = if (leg.r != null && leg.r > 0) leg.r else defaultR(angle)
            val len = r * angle * DEG
            val (rx, ry) = right(h)
            val cx = x + d * r * rx
            val cy = y + d * r * ry
            val noOrig = m.injected
            val p = Piece(
                kind = Piece.Kind.ARC, s0 = s, s1 = s + len, len = len, x0 = x, y0 = y, h0 = h, cx = cx, cy = cy, r = r, d = d,
                seg = m.seg, nav = m.nav, limit = m.limit, free = m.free, road = m.road, grade = 0.0, cruise = null,
                so0 = so, so1 = so + (if (noOrig) 0.0 else len), detourOf = null, injected = m.injected,
            )
            val end = samplePiece(p, len)
            p.x1 = end.x
            p.y1 = end.y
            p.h1 = end.h
            pieces += p
            if (!noOrig) so += len
            // 교차점(지도 옆길용): 들어오는 길과 나가는 길이 만나는 점
            val half = min(angle, 140.0) / 2
            val k = if (angle >= 150) r else r * JsMath.tan(half * DEG)
            val (f0x, f0y) = fwd(h)
            val cornerX = x + f0x * k
            val cornerY = y + f0y * k
            s += len
            x = end.x
            y = end.y
            val hIn = h
            h = end.h
            return Arc(p, angle, r, len, cornerX, cornerY, hIn, h)
        }

        fun makeTurn(leg: Leg.Turn, a: Arc, type: String?, num: Int?, segIdx: Int, isNav: Boolean, id: String?, injected: Boolean, rerouted: Boolean, roadNext: String?): RouteTurn {
            val t = RouteTurn(
                id = id ?: (if (rerouted) "t${num}r" else "t$num"),
                n = num, seg = segIdx, type = type, dir = leg.dir, angle = a.angle, r = a.r,
                sStart = a.piece.s0, sEnd = a.piece.s1, len = a.len, nav = isNav && type != null, ic = isIC(type),
                road = roadNext ?: "", roadIn = a.piece.road, hIn = a.hIn, hOut = a.hOut, x0 = a.piece.x0, y0 = a.piece.y0,
                cornerX = a.cornerX, cornerY = a.cornerY, injected = injected, rerouted = rerouted, uturn = type == "TURN_UTURN",
            )
            turns += t
            return t
        }

        fun lookAheadRoad(legs: List<Leg>, li: Int): String? {
            for (j in li + 1 until legs.size) {
                val l = legs[j]
                if (l is Leg.Go && !l.road.isNullOrEmpty()) return l.road
                if (l is Leg.Turn) break
            }
            return null
        }

        fun build(): Route {
            def.segments.forEachIndexed { segIdx, legs ->
                val segS0 = s
                var navEnd: Double? = null
                var wpIndex = 0
                for ((li, leg) in legs.withIndex()) {
                    leg.nav?.let { nav = it }
                    when (leg) {
                        is Leg.Go -> {
                            val len = max(0.0, if (leg.len.isNaN()) 0.0 else leg.len)
                            if (leg.noLimit) limit = null
                            else if (leg.limit != null) limit = leg.limit
                            else if (limit == null && nav) limit = 50
                            if (leg.road != null) road = leg.road
                            free = leg.free
                            grade = leg.grade
                            cruise = leg.cruise
                            val legSo = so
                            var done = 0.0
                            while (insIdx < inserts.size && inserts[insIdx].so < legSo + len && inserts[insIdx].so >= legSo) {
                                val ins = inserts[insIdx++]
                                val cut = max(0.0, min(len, ins.so - legSo)) - done
                                addLine(cut, Meta(segIdx, nav, limit, free, road, grade, cruise))
                                done += cut
                                val il = ins.leg ?: Leg.Turn(dir = TurnDir.RIGHT, angle = 90.0)
                                val type = tbtType(il)
                                val a = addArc(il, Meta(segIdx, nav, limit, free, road, injected = true))
                                val t = makeTurn(
                                    il, a, type, null, segIdx, nav, id = ins.id ?: "i${turns.size + 1}", injected = true, rerouted = false,
                                    roadNext = il.road?.takeIf { it.isNotEmpty() } ?: road,
                                )
                                a.piece.turn = t
                            }
                            addLine(len - done, Meta(segIdx, nav, limit, free, road, grade, cruise))
                            for (rg in leg.school) schools += Feat(legSo + rg.a, legSo + rg.b, segIdx)
                            for (c in leg.cautions) cautions += CautionFeat(legSo + c.at, c.type, c.len, segIdx)
                            for (rg in leg.jams) jams += Feat(legSo + rg.a, legSo + rg.b, segIdx)
                            if (nav) navEnd = s
                        }
                        is Leg.Turn -> {
                            n++
                            val type = tbtType(leg)
                            val extra = opts.misses[n] ?: 0.0
                            if (extra > 0) addLine(extra, Meta(segIdx, nav, limit, free, road, detourOf = n, noOrig = true))
                            val a = addArc(leg, Meta(segIdx, nav, limit, free, road))
                            val nextRoad = leg.road?.takeIf { it.isNotEmpty() } ?: lookAheadRoad(legs, li) ?: road
                            val t = makeTurn(leg, a, type, n, segIdx, nav, id = null, injected = false, rerouted = extra > 0, roadNext = nextRoad)
                            a.piece.turn = t
                            if (nav) navEnd = s
                        }
                        is Leg.Waypoint -> {
                            if (nav) {
                                wpIndex++
                                waypoints += RouteWaypoint(wpIndex, s, so, segIdx, leg.name?.takeIf { it.isNotEmpty() } ?: "경유지 $wpIndex")
                            }
                        }
                    }
                }
                segments += RouteSegment(segIdx, segS0, s, navEnd ?: s, def.segmentNames.getOrNull(segIdx))
            }
            check(pieces.isNotEmpty()) { "경로: 길이가 0이에요" }
            val length = s

            // 제한속도 구간(내비 안내 + free)
            val zones = ArrayList<Zone>()
            for (p in pieces) {
                val lim = if ((p.nav || p.free) && p.limit != null) p.limit else null
                val last = zones.lastOrNull()
                if (last != null && last.limit == lim && last.seg == p.seg && abs(last.s1 - p.s0) < 1e-6) last.s1 = p.s1
                else zones += Zone("z${zones.size + 1}", p.s0, p.s1, lim, p.seg)
            }
            val route = Route(
                key = opts.key, courseId = opts.courseId, placeId = opts.placeId,
                name = opts.name ?: def.name, routeOption = opts.routeOption ?: def.routeOption,
                loop = opts.loop, length = length, navEnd = segments.lastOrNull()?.navEnd ?: length,
                pieces = pieces, turns = turns, zones = zones,
                schools = emptyList(), cautions = emptyList(), jams = emptyList(),
                waypoints = waypoints, segments = segments, def = def, misses = opts.misses, inserts = inserts,
            )
            return route.withFeatures(
                schools.mapIndexed { i, z -> SpanFeature("sz${i + 1}", route.mapS(z.so0), route.mapS(z.so1), z.seg) },
                cautions.mapIndexed { i, c -> CautionFeature("ca${i + 1}", route.mapS(c.so), c.type, c.len, c.seg) },
                jams.mapIndexed { i, j -> SpanFeature("jm${i + 1}", route.mapS(j.so0), route.mapS(j.so1), j.seg) },
            )
        }
    }

    private fun Route.withFeatures(schools: List<SpanFeature>, cautions: List<CautionFeature>, jams: List<SpanFeature>) = Route(
        key, courseId, placeId, name, routeOption, loop, length, navEnd, pieces, turns, zones, schools, cautions, jams,
        waypoints, segments, def, misses, inserts,
    )
}
