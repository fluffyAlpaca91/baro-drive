package com.barodrive.core.engine.review

import com.barodrive.core.model.K4Compare
import com.barodrive.core.model.K4Row
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.SessionMetrics
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * K4 첫 회차와 비교 (프로토타입 js/engine/review.js `k4`·`g1Text`, 설계 6.10.5): 깜빡이 켠 거리 평균 · 급정지 비율 · 직진 핸들 흔들림.
 * 기준 = 프로필 첫 회차 값(설정 `history.firstSessionMetrics`), G1은 첫 회차 g1 › g1Baseline.
 */
internal object K4Calc {
    private const val EPS = 1e-6

    /** 퍼센트 정수(review.js `pct`: Math.round(|x| × 100)). */
    fun pct(x: Double): Long = jsRound(abs(x) * 100).roundToLong()

    private fun g1Text(better: Double): String = when {
        better >= 0.05 - EPS -> "첫날보다 ${pct(better)}% 안정"
        better <= -0.05 + EPS -> "첫날보다 ${pct(better)}% 더 흔들려요"
        else -> "첫날과 비슷해요"
    }

    /** G1 비교 기준: 첫 회차 g1(> 0), 없으면 g1Baseline(> 0). */
    fun g1Base(history: SessionHistory): Double? =
        history.firstSessionMetrics?.g1?.takeIf { it > 0 } ?: history.baselineG1?.takeIf { it > 0 }

    /** K4 (review.js `k4`). 기준이 없으면 first = true(첫 기록). 기준 없는 지표는 줄을 뺀다. 나빠지면 그대로 보인다. */
    fun k4(metrics: SessionMetrics, history: SessionHistory): K4Compare {
        val f = history.firstSessionMetrics ?: return K4Compare(first = true)
        val rows = ArrayList<K4Row>()
        val fc = f.c2Avg
        val mc = metrics.c2Avg
        if (fc != null && mc != null) {
            rows += K4Row(
                id = "c2Avg", label = "깜빡이 켠 거리 평균", from = fc, to = mc,
                text = "${jsRound(fc).roundToLong()}m → ${jsRound(mc).roundToLong()}m",
                better = if (mc > fc + EPS) true else if (mc < fc - EPS) false else null,
            )
        }
        val fTotal = f.stopTotal
        val fFail = f.stopFail
        val counts = fTotal != null && fTotal > 0 && fFail != null
        val fr = if (fTotal != null && fTotal > 0 && fFail != null) fFail.toDouble() / fTotal else f.stopFailRate
        val mTotal = metrics.stopTotal
        val mFail = metrics.stopFail
        val tr = if (mTotal != null && mTotal > 0 && mFail != null) mFail.toDouble() / mTotal else null
        if (fr != null && tr != null) {
            rows += K4Row(
                id = "stop", label = "급정지 비율", from = round3(fr), to = round3(tr),
                text = if (counts) "$fFail/$fTotal → $mFail/$mTotal" else "${pct(fr)}% → ${pct(tr)}%",
                better = if (tr < fr - EPS) true else if (tr > fr + EPS) false else null,
            )
        }
        val base = g1Base(history)
        val mg = metrics.g1
        if (base != null && mg != null) {
            val b = (base - mg) / base
            rows += K4Row(
                id = "g1", label = "직진 핸들 흔들림", from = base, to = mg, text = g1Text(b),
                better = if (b >= 0.05 - EPS) true else if (b <= -0.05 + EPS) false else null,
            )
        }
        return K4Compare(first = false, rows = rows)
    }
}
