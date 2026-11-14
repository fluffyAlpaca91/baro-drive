package com.barodrive.core.sim.util

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 프로토타입(JS)과 같은 수를 내기 위한 계산 도우미 (prototype/tools/golden/README.md 9.2).
 *
 * - 반올림은 JS `Math.round`처럼 반을 +∞ 쪽으로(Java [Math.round]와 같음, `kotlin.math.round`는 짝수 쪽이라 다르다).
 * - 삼각함수는 [StrictMath](fdlibm) — V8도 fdlibm을 옮겨 써서 마지막 자리까지 같다.
 * - `hypot`은 V8 방식(가장 큰 값으로 나눈 Kahan 합)으로 — fdlibm `hypot`과 마지막 자리가 다를 수 있다.
 */
object JsMath {
    const val DEG: Double = Math.PI / 180

    /** JS `Math.round`. */
    fun round(x: Double): Double = if (x.isNaN() || x.isInfinite()) x else Math.round(x).toDouble()

    /** `Math.round(x * 10) / 10`. */
    fun round1(x: Double): Double = round(x * 10) / 10

    /** `Math.round(x * 100) / 100`. */
    fun round2(x: Double): Double = round(x * 100) / 100

    /** `Math.round(x * 1000) / 1000`. */
    fun round3(x: Double): Double = round(x * 1000) / 1000

    /** 10m 내림(`Math.floor(m / 10) * 10`, TBT 거리). */
    fun round10(m: Double): Double = floor(m / 10) * 10

    /** JS `x || 0`: 0·−0·NaN이면 0. */
    fun orZero(x: Double): Double = if (x == 0.0 || x.isNaN()) 0.0 else x

    fun clamp(v: Double, lo: Double, hi: Double): Double = minOf(hi, maxOf(lo, v))

    /** JS가 수를 글자로 쓰는 모양(정수면 '300', 아니면 '75.5'). */
    fun str(x: Double): String = if (x.isFinite() && x == floor(x) && abs(x) < 1e15) x.toLong().toString() else x.toString()

    /** JS `x.toFixed(1)`. */
    fun fixed1(x: Double): String = String.format(java.util.Locale.ROOT, "%.1f", x)

    fun sin(x: Double): Double = StrictMath.sin(x)
    fun cos(x: Double): Double = StrictMath.cos(x)
    fun tan(x: Double): Double = StrictMath.tan(x)
    fun atan(x: Double): Double = StrictMath.atan(x)

    /** V8 `Math.hypot`(Kahan 합). */
    fun hypot(vararg xs: Double): Double {
        if (xs.isEmpty()) return 0.0
        var max = 0.0
        var nan = false
        for (v in xs) {
            if (v.isNaN()) nan = true else if (abs(v) > max) max = abs(v)
        }
        if (max == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
        if (nan) return Double.NaN
        if (max == 0.0) return 0.0
        var sum = 0.0
        var compensation = 0.0
        for (v in xs) {
            val n = abs(v) / max
            val summand = n * n - compensation
            val preliminary = sum + summand
            compensation = (preliminary - sum) - summand
            sum = preliminary
        }
        return sqrt(sum) * max
    }
}
