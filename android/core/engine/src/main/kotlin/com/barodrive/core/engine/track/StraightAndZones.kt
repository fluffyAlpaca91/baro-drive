package com.barodrive.core.engine.track

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.review.round2
import com.barodrive.core.engine.review.round3
import com.barodrive.core.model.StraightWindow
import com.barodrive.core.model.TrackerEvent
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.ZoneEnter
import com.barodrive.core.model.ZoneExit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 재는 직진 창 (core.js `newWin`). */
private class Win(val t0: Double, steer: Double, val useHeading: Boolean, var lastH: Double?) {
    var n = 1
    var sum = steer
    var sq = steer * steer
    var hu = 0.0
    var hMin = 0.0
    var hMax = 0.0
}

/**
 * 직진 창 (core.js `straightStep`, G1용): 10초 동안 속도 > 15이고 heading 변화 < 15°(내비를 못 읽으면 abs(steer) < 10°)면
 * steer 표준편차를 낸다.
 */
internal class StraightWatch(private val sh: TrackShared) {
    private var w: Win? = null

    fun reset() {
        w = null
    }

    fun step(t: Double, ev: MutableList<TrackerEvent>) {
        val s = sh.samples.last() ?: return
        val v = s.v
        val steer = s.steer
        if (v == null || !(v > Thresholds["straight.v"] + EPS) || steer == null) {
            w = null
            return
        }
        val heading = s.heading
        val cur = w
        if (cur == null || cur.useHeading != (heading != null)) {
            w = if (heading != null || abs(steer) < Thresholds["steer.big"] - EPS) Win(t, steer, heading != null, heading) else null
            return
        }
        if (cur.useHeading && heading != null) {
            val dh = ((heading - (cur.lastH ?: heading) + 540) % 360) - 180
            cur.lastH = heading
            cur.hu += dh
            cur.hMin = min(cur.hMin, cur.hu)
            cur.hMax = max(cur.hMax, cur.hu)
            if (cur.hMax - cur.hMin >= Thresholds["straight.heading"] - EPS) {
                w = Win(t, steer, true, heading)
                return
            }
        } else if (abs(steer) >= Thresholds["steer.big"] - EPS) {
            w = null
            return
        }
        cur.n++
        cur.sum += steer
        cur.sq += steer * steer
        if (t - cur.t0 >= Thresholds["straight.sec"] - EPS) {
            val mean = cur.sum / cur.n
            val std = sqrt(max(0.0, cur.sq / cur.n - mean * mean))
            ev += StraightWindow(t0 = cur.t0, t1 = t, n = cur.n, std = round3(std), mean = round3(mean))
            w = null
        }
    }
}

/** 지금 구역 (core.js `tr.zone`). */
private class Zone(val id: String, val limit: Int, val school: Boolean, val t0: Double)

/**
 * 구역 (core.js `zoneStep`): 제한속도(어린이보호구역이면 없을 때 30)와 어린이보호구역 여부가 같은 구간. 바뀌면 zoneExit → zoneEnter.
 */
internal class ZoneWatch {
    private var zone: Zone? = null
    private var seq = 0

    fun step(snap: VehicleSnapshot, t: Double, ev: MutableList<TrackerEvent>) {
        val nav = snap.nav
        val school = nav?.inSchoolZone == true
        val limit = nav?.limit?.takeIf { it > 0 } ?: if (school) SCHOOL_LIMIT else null
        val z = zone
        if (z != null && limit != null && z.limit == limit && z.school == school) return
        if (z == null && limit == null) return
        if (z != null) {
            ev += exitOf(z, t)
            zone = null
        }
        if (limit != null) {
            val nz = Zone("z${++seq}", limit, school, t)
            zone = nz
            ev += ZoneEnter(id = nz.id, limit = limit.toDouble(), school = school, t = t)
        }
    }

    fun flush(t: Double, ev: MutableList<TrackerEvent>) {
        val z = zone ?: return
        ev += exitOf(z, t)
        zone = null
    }

    private fun exitOf(z: Zone, t: Double) = ZoneExit(id = z.id, limit = z.limit.toDouble(), school = z.school, t = t, dur = round2(t - z.t0))

    private companion object {
        const val SCHOOL_LIMIT = 30
    }
}
