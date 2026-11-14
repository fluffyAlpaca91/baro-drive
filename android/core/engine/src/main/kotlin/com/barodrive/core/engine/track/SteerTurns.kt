package com.barodrive.core.engine.track

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.model.SteerTurn
import com.barodrive.core.model.TrackerEvent
import com.barodrive.core.model.TurnDir
import kotlin.math.abs

/** 조향 회전의 깜빡이 창 결과 (core.js `blinkWindow`). */
internal class BlinkWindow(val blinkBefore: Boolean, val blinkSameBefore: Boolean, val blinkAtStart: Boolean, val blinkOnT: Double?)

/** 진행 중인 조향 (core.js `tr.steer` phase 'turn'). */
private class SteerPhase(val dir: TurnDir, val startT: Double, var bigSince: Double?, val navTurnId: String?) {
    var confirmed = false
    var belowSince: Double? = null
    var blink: BlinkWindow? = null
}

/**
 * 조향 회전 (core.js 추적기 '조향 회전' 절, 결정-61): abs(steer) ≥ 10°가 2초 넘게면 회전. 시작 = 5°를 넘은 순간,
 * 끝 = 3° 아래 1초. 반대로 꺾으면 새 조향. 깜빡이 창 = 시작 5초 전 ~ 1초 뒤(C5용).
 */
internal class SteerTurns(private val sh: TrackShared, private val nav: NavTurns) {
    private var st: SteerPhase? = null

    fun reset() {
        st = null
    }

    fun step(t: Double, ev: MutableList<TrackerEvent>) {
        val sv = sh.samples.last()?.steer ?: return
        val a = abs(sv)
        val sg = if (sv < 0) TurnDir.LEFT else TurnDir.RIGHT
        val cur = st
        if (cur == null) {
            if (a > Thresholds["steer.start"] + EPS) begin(sg, t, a)
            return
        }
        if (a > Thresholds["steer.start"] + EPS && sg != cur.dir) { // 반대로 꺾음 → 새 조향
            if (cur.confirmed) end(cur, t, ev)
            begin(sg, t, a)
            return
        }
        if (a >= Thresholds["steer.big"] - EPS) {
            val big = cur.bigSince ?: t.also { cur.bigSince = it }
            if (!cur.confirmed && t - big > Thresholds["steer.bigSec"] + EPS) cur.confirmed = true
        } else {
            cur.bigSince = null
        }
        if (cur.blink == null && t >= cur.startT + Thresholds["C5.after"] - EPS) cur.blink = blinkWindow(cur.dir, cur.startT)
        if (a < Thresholds["steer.end"] - EPS) {
            val below = cur.belowSince ?: t.also { cur.belowSince = it }
            if (t - below >= Thresholds["steer.endSec"] - EPS) {
                if (cur.confirmed) end(cur, below, ev) else st = null
            }
        } else {
            cur.belowSince = null
        }
    }

    /** 세션 끝: 확인된 조향이면 지금 끝낸다. */
    fun flush(t: Double, ev: MutableList<TrackerEvent>) {
        val cur = st
        if (cur != null && cur.confirmed) end(cur, t, ev)
        st = null
    }

    private fun begin(dir: TurnDir, t: Double, a: Double) {
        st = SteerPhase(dir, t, if (a >= Thresholds["steer.big"] - EPS) t else null, nav.findForSteer(dir))
    }

    private fun end(cur: SteerPhase, endT: Double, ev: MutableList<TrackerEvent>) {
        val bw = cur.blink ?: blinkWindow(cur.dir, cur.startT)
        ev += SteerTurn(
            dir = cur.dir, startT = cur.startT, endT = endT, blinkBefore = bw.blinkBefore, blinkSameBefore = bw.blinkSameBefore,
            blinkAtStart = bw.blinkAtStart, blinkOnT = bw.blinkOnT, navTurnId = cur.navTurnId,
        )
        st = null
    }

    private fun blinkWindow(dir: TurnDir, startT: Double): BlinkWindow {
        val lo = startT - Thresholds["C5.before"] - EPS
        val hi = startT + Thresholds["C5.after"] + EPS
        var any = false
        var same = false
        for (s in sh.samples) {
            if (s.t < lo || s.t > hi) continue
            val b = s.bdir ?: continue
            any = true
            if (b == dir) same = true
        }
        val s0 = sh.samples.firstOrNull { abs(it.t - startT) < EPS * 10 }
        var onT: Double? = null
        for (i in sh.blinkHist.indices.reversed()) {
            val h = sh.blinkHist[i]
            if (h.t > startT + EPS) continue
            if (h.t < startT - BLINK_LOOKBACK) break
            if (h.dir == dir) {
                onT = h.t
                break
            }
        }
        return BlinkWindow(any, same, s0 != null && s0.bdir == dir, onT)
    }

    private companion object {
        /** C4용 같은 쪽 깜빡이를 켠 시각을 거슬러 보는 시간(초, core.js 30). */
        const val BLINK_LOOKBACK = 30.0
    }
}
