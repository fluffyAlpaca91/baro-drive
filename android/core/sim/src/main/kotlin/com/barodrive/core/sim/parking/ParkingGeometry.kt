package com.barodrive.core.sim.parking

import com.barodrive.core.sim.util.JsMath
import kotlin.math.abs

/** 점(m, x 오른쪽 · y 아래). */
data class Pt(val x: Double, val y: Double)

/** 차 자세: 뒷바퀴 축 가운데 [x]·[y], 차 앞 방향 [h](라디안, 0 = 오른쪽, π/2 = 아래). */
data class ParkPose(val x: Double, val y: Double, val h: Double)

/** 바퀴 하나(그림용): 자리와 방향(앞바퀴는 조향각만큼). */
data class Wheel(val id: String, val front: Boolean, val x: Double, val y: Double, val h: Double)

/**
 * 가상 주차 기하 (프로토타입 sim/parking.js 기하 도우미): 차 사각형·바퀴, 볼록 다각형 겹침(SAT), 겹친 곳, 자전거 모델 원호 적분.
 */
object ParkingGeometry {
    /** 차 자세 [p]에서 (앞 [f], 오른쪽 [r]) m 떨어진 점. */
    fun local(p: ParkPose, f: Double, r: Double): Pt {
        val c = JsMath.cos(p.h)
        val s = JsMath.sin(p.h)
        return Pt(p.x + f * c - r * s, p.y + f * s + r * c)
    }

    /** 차 네 모서리: 앞 왼 · 앞 오 · 뒤 오 · 뒤 왼. */
    fun corners(c: ParkCar, p: ParkPose): List<Pt> {
        val f = c.wheelbase + c.front
        val b = -c.rear
        val w = c.width / 2
        return listOf(local(p, f, -w), local(p, f, w), local(p, b, w), local(p, b, -w))
    }

    /** 바퀴 넷(앞 왼·앞 오·뒤 왼·뒤 오). */
    fun wheels(c: ParkCar, p: ParkPose, steerDeg: Double): List<Wheel> {
        val t = c.width / 2 - 0.2
        val d = steerDeg * JsMath.DEG
        val fl = local(p, c.wheelbase, -t)
        val fr = local(p, c.wheelbase, t)
        val rl = local(p, 0.0, -t)
        val rr = local(p, 0.0, t)
        return listOf(
            Wheel("fl", true, fl.x, fl.y, p.h + d),
            Wheel("fr", true, fr.x, fr.y, p.h + d),
            Wheel("rl", false, rl.x, rl.y, p.h),
            Wheel("rr", false, rr.x, rr.y, p.h),
        )
    }

    private fun proj(poly: List<Pt>, ax: Pt): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (q in poly) {
            val v = q.x * ax.x + q.y * ax.y
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return Pair(lo, hi)
    }

    /** 볼록 다각형 겹침(SAT). 변이 닿기만 하면 겹침 아님. */
    fun overlaps(a: List<Pt>, b: List<Pt>): Boolean {
        for (poly in listOf(a, b)) {
            for (i in poly.indices) {
                val p = poly[i]
                val q = poly[(i + 1) % poly.size]
                val ax = Pt(-(q.y - p.y), q.x - p.x)
                val pa = proj(a, ax)
                val pb = proj(b, ax)
                if (pa.second <= pb.first + 1e-9 || pb.second <= pa.first + 1e-9) return false
            }
        }
        return true
    }

    private fun inPoly(poly: List<Pt>, q: Pt): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val cr = (b.x - a.x) * (q.y - a.y) - (b.y - a.y) * (q.x - a.x)
            if (abs(cr) < 1e-12) continue
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun segX(p1: Pt, p2: Pt, p3: Pt, p4: Pt): Pt? {
        val d = (p2.x - p1.x) * (p4.y - p3.y) - (p2.y - p1.y) * (p4.x - p3.x)
        if (abs(d) < 1e-12) return null
        val t = ((p3.x - p1.x) * (p4.y - p3.y) - (p3.y - p1.y) * (p4.x - p3.x)) / d
        val u = ((p3.x - p1.x) * (p2.y - p1.y) - (p3.y - p1.y) * (p2.x - p1.x)) / d
        if (t < 0 || t > 1 || u < 0 || u > 1) return null
        return Pt(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y))
    }

    /** 겹친 곳의 가운데쯤(붉은 표시 자리). */
    fun contact(a: List<Pt>, b: List<Pt>): Pt? {
        val pts = ArrayList<Pt>()
        for (q in a) if (inPoly(b, q)) pts += q
        for (q in b) if (inPoly(a, q)) pts += q
        for (i in a.indices) for (j in b.indices) {
            segX(a[i], a[(i + 1) % a.size], b[j], b[(j + 1) % b.size])?.let { pts += it }
        }
        if (pts.isEmpty()) return null
        val n = pts.size
        return Pt(pts.fold(0.0) { s, q -> s + q.x } / n, pts.fold(0.0) { s, q -> s + q.y } / n)
    }

    /** 자전거 모델 원호 적분: 뒷바퀴 축이 [ds] m 나아감(뒤로는 −). */
    fun advance(c: ParkCar, p: ParkPose, ds: Double, steerDeg: Double): ParkPose {
        val d = steerDeg * JsMath.DEG
        if (abs(d) < 1e-6) return ParkPose(p.x + ds * JsMath.cos(p.h), p.y + ds * JsMath.sin(p.h), p.h)
        val r = c.wheelbase / JsMath.tan(d)
        val h2 = p.h + ds / r
        return ParkPose(p.x + r * (JsMath.sin(h2) - JsMath.sin(p.h)), p.y - r * (JsMath.cos(h2) - JsMath.cos(p.h)), h2)
    }

    /** 각도 차 (−π, π]. */
    fun angDiff(a: Double, b: Double): Double {
        var d = (a - b) % (2 * Math.PI)
        if (d > Math.PI) d -= 2 * Math.PI
        if (d <= -Math.PI) d += 2 * Math.PI
        return d
    }

    fun rect(x0: Double, y0: Double, x1: Double, y1: Double): List<Pt> = listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1))
}
