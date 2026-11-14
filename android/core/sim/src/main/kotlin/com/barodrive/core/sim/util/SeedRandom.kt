package com.barodrive.core.sim.util

import kotlin.math.floor

/**
 * 시드 난수 (프로토타입 core/util.js `Baro.util.rng`·`hash`). 같은 시드면 같은 수열 — 시계·시드 없는 난수는 쓰지 않는다.
 *
 * - 글자 시드: FNV-1a 32비트(UTF-16 code unit마다 — Kotlin [Char]와 같음) → mulberry32.
 * - 수 시드: `floor(seed) >>> 0` → mulberry32.
 * 골든 manifest `rngCheck`의 `hash`·`first5`와 같은 값을 낸다.
 */
class SeedRandom private constructor(seed: Int) {
    private var a: Int = seed

    /** [0, 1) (mulberry32). */
    fun next(): Double {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFF_FFFFL).toDouble() / 4294967296.0
    }

    operator fun invoke(): Double = next()

    /** lo..hi 양끝 포함(`r.int`). */
    fun int(lo: Int, hi: Int): Int = lo + floor(next() * (hi - lo + 1)).toInt()

    /** [lo, hi) (`r.range`). */
    fun range(lo: Double, hi: Double): Double = lo + next() * (hi - lo)

    /** 배열에서 하나(`r.pick`). */
    fun <T> pick(list: List<T>): T = list[floor(next() * list.size).toInt()]

    companion object {
        /** 글자 → 32비트 FNV-1a(`Baro.util.hash`), 부호 없는 값. */
        fun hash(str: String): Long {
            var h = 0x811c9dc5.toInt()
            for (c in str) {
                h = h xor c.code
                h *= 0x01000193
            }
            return h.toLong() and 0xFFFF_FFFFL
        }

        /** 글자 시드. */
        fun of(seed: String): SeedRandom = SeedRandom(hash(seed).toInt())

        /** 수 시드(`Math.floor(seed) >>> 0`). */
        fun of(seed: Double): SeedRandom = SeedRandom(floor(seed).toLong().toInt())
    }
}
