package com.barodrive.core.engine

import kotlin.math.floor

/**
 * 시드 난수 (프로토타입 util.js `rng(seed)`: 글자를 FNV-1a 32비트로 → mulberry32). 같은 시드면 같은 수열이고 JS와 같은 수를 낸다.
 * 엔진은 이것만 쓴다(시드 없는 난수 금지, 설계 6.1). 검출기 `ctx.rng`(시드 = cfg.seed + ':' + 검출기 id)와 코스 혼잡 시드가 쓴다.
 */
class SeededRandom private constructor(private var a: Int) {
    /** 글자 시드(FNV-1a 해시가 처음 상태). */
    constructor(seed: String) : this(hash(seed).toInt())

    /** 수 시드(util.js: `Math.floor(seed) >>> 0`이 처음 상태). */
    constructor(seed: Long) : this(seed.toInt())

    /** [0, 1) 사이 수 (util.js `r()`). */
    fun next(): Double {
        a += MULBERRY_STEP
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and UINT_MASK).toDouble() / TWO_POW_32
    }

    /** [lo, hi] 정수, 양끝 포함 (util.js `r.int`). */
    fun int(lo: Int, hi: Int): Int = lo + floor(next() * (hi - lo + 1)).toInt()

    /** [lo, hi) 수 (util.js `r.range`). */
    fun range(lo: Double, hi: Double): Double = lo + next() * (hi - lo)

    /** 목록에서 하나 (util.js `r.pick`). 빈 목록이면 null. */
    fun <T> pick(list: List<T>): T? = if (list.isEmpty()) null else list[floor(next() * list.size).toInt()]

    companion object {
        /** 글자 → 부호 없는 32비트 FNV-1a (util.js `hash`). UTF-16 코드 단위마다(JS `charCodeAt`과 같음). */
        fun hash(text: String): Long {
            var h = FNV_OFFSET
            for (ch in text) {
                h = h xor ch.code
                h *= FNV_PRIME
            }
            return h.toLong() and UINT_MASK
        }

        private const val FNV_OFFSET = 0x811c9dc5.toInt()
        private const val FNV_PRIME = 0x01000193
        private const val MULBERRY_STEP = 0x6D2B79F5
        private const val UINT_MASK = 0xFFFFFFFFL
        private const val TWO_POW_32 = 4294967296.0
    }
}
