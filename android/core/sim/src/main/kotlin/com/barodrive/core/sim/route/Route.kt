package com.barodrive.core.sim.route

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.TurnDir

/**
 * 만든 경로 (프로토타입 sim/route.js `build()`가 돌려주는 Route, 설계 7.4). 좌표: x 동쪽 +, y 남쪽 +, heading 북 0°·시계 방향 +.
 * 경로 거리 s(m)로 위치·제한속도·조각을 찾는다. [mapS]는 원래 경로 거리(놓친 회전 앞 직진·끼운 회전을 빼고 잰 값) → 이 경로 거리.
 */
class Route internal constructor(
    val key: String?,
    val courseId: String?,
    val placeId: String?,
    val name: String?,
    val routeOption: String?,
    val loop: Boolean,
    val length: Double,
    /** 마지막 구간 목적지 s. */
    val navEnd: Double,
    val pieces: List<Piece>,
    val turns: List<RouteTurn>,
    val zones: List<Zone>,
    val schools: List<SpanFeature>,
    val cautions: List<CautionFeature>,
    val jams: List<SpanFeature>,
    val waypoints: List<RouteWaypoint>,
    val segments: List<RouteSegment>,
    /** 원래 정의(다시 만들 때 쓴다). */
    val def: RouteDef,
    /** 만들 때 준 놓친 회전·끼운 회전. */
    val misses: Map<Int, Double>,
    val inserts: List<RouteInsert>,
) {
    /** 처음 자리. */
    val start: Pose get() = Pose(pieces[0].x0, pieces[0].y0, pieces[0].h0)

    private val origPieces: List<Piece> = pieces.filter { it.so1 > it.so0 }

    /** 고리면 [0, length)로 감는다. */
    fun wrap(sv: Double): Double {
        if (!loop || !(length > 0)) return sv
        return ((sv % length) + length) % length
    }

    private fun pieceIndex(sv: Double): Int {
        var lo = 0
        var hi = pieces.size - 1
        if (sv <= pieces[0].s0) return 0
        if (sv >= pieces[hi].s1) return hi
        while (lo < hi) {
            val mid = (lo + hi + 1) shr 1
            if (pieces[mid].s0 <= sv) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun pieceAt(sv: Double): Piece = pieces[pieceIndex(wrap(sv))]

    /** 경로 거리 [sv]의 자세와 조각. */
    fun sample(sv: Double): Sample {
        val v = wrap(sv)
        val p = pieces[pieceIndex(v)]
        val r = RouteBuilder.samplePiece(p, v - p.s0)
        return Sample(r.x, r.y, r.h, p)
    }

    /** 원래 경로 거리 → 이 경로 거리. */
    fun mapS(sOrig: Double): Double {
        val v = if (sOrig.isNaN()) 0.0 else sOrig
        if (origPieces.isEmpty()) return v
        var lo = 0
        var hi = origPieces.size - 1
        if (v >= origPieces[hi].so1) return origPieces[hi].s1 + (v - origPieces[hi].so1)
        if (v <= origPieces[0].so0) return origPieces[0].s0 + (v - origPieces[0].so0)
        while (lo < hi) {
            val mid = (lo + hi) shr 1
            if (origPieces[mid].so1 <= v) lo = mid + 1 else hi = mid
        }
        val p = origPieces[lo]
        return p.s0 + (v - p.so0)
    }

    /** 그 자리 제한속도(안내 구간 + free 다리). */
    fun limitAt(sv: Double): Int? {
        val v = wrap(sv)
        for (z in zones) if (v >= z.s0 && v < z.s1) return z.limit
        return zones.lastOrNull()?.limit
    }
}

/** 자세: 자리(m)와 방향(°). */
data class Pose(val x: Double, val y: Double, val h: Double)

/** [Route.sample] 결과. */
data class Sample(val x: Double, val y: Double, val h: Double, val piece: Piece)

/** 경로 조각(직선 · 원호). [so0]·[so1]은 원래 경로 거리, [detourOf]는 놓친 회전 앞에 끼운 직진, [injected]는 끼운 회전. */
class Piece internal constructor(
    val kind: Kind,
    val s0: Double,
    val s1: Double,
    val len: Double,
    val x0: Double,
    val y0: Double,
    val h0: Double,
    val cx: Double,
    val cy: Double,
    val r: Double,
    /** +1 오른쪽 · −1 왼쪽(원호). */
    val d: Int,
    val seg: Int,
    val nav: Boolean,
    val limit: Int?,
    val free: Boolean,
    val road: String,
    val grade: Double,
    /** 이 조각 순항(직진 다리만 — 원호·돌아가는 직진은 null). */
    val cruise: Double?,
    val so0: Double,
    val so1: Double,
    val detourOf: Int?,
    val injected: Boolean,
) {
    var x1: Double = 0.0
        internal set
    var y1: Double = 0.0
        internal set
    var h1: Double = 0.0
        internal set

    /** 이 원호가 그리는 회전. */
    var turn: RouteTurn? = null
        internal set

    enum class Kind { LINE, ARC }
}

/** 경로 위 회전 하나(route.js `makeTurn`). [nav] = TBT를 내는 안내 회전, [n] = 경로의 회전 번호(끼운 회전은 null). */
class RouteTurn internal constructor(
    val id: String,
    val n: Int?,
    val seg: Int,
    val type: String?,
    val dir: TurnDir,
    val angle: Double,
    val r: Double,
    val sStart: Double,
    val sEnd: Double,
    val len: Double,
    val nav: Boolean,
    val ic: Boolean,
    val road: String,
    val roadIn: String,
    val hIn: Double,
    val hOut: Double,
    val x0: Double,
    val y0: Double,
    val cornerX: Double,
    val cornerY: Double,
    val injected: Boolean,
    val rerouted: Boolean,
    val uturn: Boolean,
) {
    /** 이 회전에서 켤 깜빡이. */
    val blinker: Blinker get() = if (dir == TurnDir.LEFT) Blinker.LEFT else Blinker.RIGHT

    override fun toString(): String = "Turn($id $type $sStart)"
}

/** 같은 제한속도가 이어지는 구간. */
class Zone internal constructor(val id: String, val s0: Double, s1: Double, val limit: Int?, val seg: Int) {
    var s1: Double = s1
        internal set
}

/** 어린이보호구역·정체 구간(경로 거리). */
data class SpanFeature(val id: String, val s0: Double, val s1: Double, val seg: Int)

/** 주의 구간(경로 거리 [s]에서 [len] m). */
data class CautionFeature(val id: String, val s: Double, val type: CautionType, val len: Double, val seg: Int)

/** 경유지(구간마다 1부터). */
data class RouteWaypoint(val index: Int, val s: Double, val so: Double, val seg: Int, val name: String)

/** 구간. [navEnd] = 이 구간 목적지(마지막 안내 다리 끝). */
data class RouteSegment(val index: Int, val s0: Double, val s1: Double, val navEnd: Double, val name: String?)

/** 원래 경로 거리 [so]에 끼울 회전([leg] 없으면 오른쪽 90°). */
data class RouteInsert(val so: Double, val leg: Leg.Turn?, val id: String?)
