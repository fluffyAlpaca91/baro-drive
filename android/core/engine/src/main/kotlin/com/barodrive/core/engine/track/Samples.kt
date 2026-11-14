package com.barodrive.core.engine.track

import com.barodrive.core.engine.EPS
import com.barodrive.core.model.Gear
import com.barodrive.core.model.TurnDir

/** 추적기 표본 하나 (core.js createTracker `step`의 `s`). 못 읽는 값은 null. */
internal class Sample(
    val t: Double,
    /** 속도 km/h(차속을 못 읽으면 트립 거리로 어림한 값). */
    val v: Double?,
    val steer: Double?,
    /** 지금 깜빡이 쪽. */
    val bdir: TurnDir?,
    val brake: Boolean?,
    val gear: Gear?,
    val heading: Double?,
    val trip: Double?,
)

/** 깜빡이가 바뀐 순간 { t, dir } (core.js `tr.blinkHist`). */
internal class BlinkMark(val t: Double, val dir: TurnDir?)

/** 표본 줄 (core.js `samples`·`at`·`trim`). 오래된 표본은 40초 넘으면 버린다. */
internal class SampleBuffer : Iterable<Sample> {
    private val list = ArrayList<Sample>()

    val isEmpty: Boolean get() = list.isEmpty()

    fun last(): Sample? = list.lastOrNull()

    fun first(): Sample? = list.firstOrNull()

    fun add(s: Sample) {
        list += s
    }

    fun clear() = list.clear()

    override fun iterator(): Iterator<Sample> = list.iterator()

    /** T 시각의 값(사이면 선으로 이음). 기록보다 앞이면 첫 값 (core.js `at(T, f)`). */
    fun at(time: Double, field: (Sample) -> Double?): Double? {
        for (i in list.indices.reversed()) {
            val s = list[i]
            if (s.t <= time + EPS) {
                val v = field(s)
                val n = list.getOrNull(i + 1)
                val nv = n?.let(field)
                if (n != null && n.t > time + EPS && v != null && nv != null && n.t > s.t) return v + (nv - v) * (time - s.t) / (n.t - s.t)
                return v
            }
        }
        return list.firstOrNull()?.let(field)
    }

    /** 표본이 200개 넘고 맨 앞이 40초보다 오래됐으면 40초 안 것만 남긴다 (core.js `trim`). */
    fun trim(t: Double) {
        val head = list.firstOrNull() ?: return
        if (list.size > TRIM_MIN && head.t < t - KEEP_SEC) {
            var k = 0
            while (k < list.size && list[k].t < t - KEEP_SEC) k++
            list.subList(0, k).clear()
        }
    }

    private companion object {
        const val TRIM_MIN = 200
        const val KEEP_SEC = 40.0
    }
}
