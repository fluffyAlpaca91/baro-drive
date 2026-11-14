package com.barodrive.core.engine.review

import com.barodrive.core.model.EventData
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * 회차 뒤 계산(리뷰·점수·도움 줄여 가기·면허·단계)이 같이 쓰는 작은 도구. 프로토타입 JS의 수·글자 규칙을 그대로 따른다
 * (js/core/util.js `fmtKm`·`fmtMin`·`josa`, JS `Math.round`·`toFixed`·`Number()`·참거짓).
 */

/** JS `Math.round`: .5는 +∞ 쪽으로(Kotlin `round`는 짝수 쪽). 부동소수 경계(0.49999999999999994)도 JS와 같다. */
internal fun jsRound(x: Double): Double {
    val f = floor(x)
    return if (x - f >= 0.5) f + 1 else f
}

/** review.js `round3` = Math.round(x × 1000) / 1000. */
internal fun round3(x: Double): Double = jsRound(x * 1000) / 1000

/** score.js `r2` = Math.round(x × 100) / 100. */
internal fun round2(x: Double): Double = jsRound(x * 100) / 100

/** JS 비교 함수의 `a − b` 부호(같으면 0 — 다음 기준으로). */
internal fun sign(d: Double): Int = if (d > 0) 1 else if (d < 0) -1 else 0

/** JS `Number(x).toFixed(1)`: 정확한 이진 값을 반올림(.5는 위로). */
internal fun toFixed1(x: Double): String = BigDecimal(x).setScale(1, RoundingMode.HALF_UP).toPlainString()

/** 정수 값이면 소수점 없이(JS `String(28)` = '28'), 아니면 그대로. */
internal fun jsNum(x: Double): String =
    if (x == floor(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString() else x.toString()

/** util.js `fmtKm`: 미터 → '2.6km'(늘 소수 한 자리). */
internal fun fmtKm(m: Double): String = toFixed1(maxOf(0.0, m) / 1000) + "km"

/** util.js `fmtMin`: 초 → '45초' · '7분' · '1시간 5분'. */
internal fun fmtMin(sec: Double): String {
    val s = maxOf(0.0, jsRound(sec)).toLong()
    if (s < 60) return "${s}초"
    val m = jsRound(s / 60.0).toLong()
    if (m < 60) return "${m}분"
    val h = m / 60
    val mm = m % 60
    return if (mm != 0L) "${h}시간 ${mm}분" else "${h}시간"
}

/**
 * JS `Number(값)`: 없음(undefined) → null(NaN) · JSON null → 0 · 참거짓 → 1/0 · 글자 → 수(빈 글자 0, 못 읽으면 null) · 그 밖 null.
 * 메모(`rec.notes`)처럼 모양이 정해지지 않은 값을 읽을 때 쓴다.
 */
internal fun jsNumber(e: JsonElement?): Double? = when (e) {
    null -> null
    JsonNull -> 0.0
    is JsonPrimitive -> when {
        e.isString -> e.content.trim().let { t -> if (t.isEmpty()) 0.0 else t.toDoubleOrNull()?.takeIf { it.isFinite() } }
        e.booleanOrNull != null -> if (e.booleanOrNull == true) 1.0 else 0.0
        else -> e.doubleOrNull?.takeIf { it.isFinite() }
    }
    else -> null
}

/** JS 참거짓(`!!값`): 없음·null·false·0·NaN·'' → false. */
internal fun jsTruthy(e: JsonElement?): Boolean = when (e) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        e.isString -> e.content.isNotEmpty()
        e.booleanOrNull != null -> e.booleanOrNull == true
        else -> e.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
    }
    else -> true
}

/** 판정 data 칸의 JS 참거짓(`!!d.ic` · `d.school`). */
internal fun EventData.truthy(key: String): Boolean = jsTruthy(this[key])

/**
 * 판정 data 칸의 JS `String(값)`: 글자·수·참거짓은 그 글자, 칸이 없으면 'undefined'(license.js가 구역 id를 이렇게 비교한다).
 * 숫자는 JS처럼 정수면 소수점 없이.
 */
internal fun EventData.jsString(key: String): String {
    val e = this[key] ?: return "undefined"
    if (e !is JsonPrimitive || e is JsonNull) return if (e is JsonNull) "null" else e.toString()
    if (e.isString) return e.content
    return e.doubleOrNull?.let(::jsNum) ?: e.content
}

/**
 * 조사 고르기 (util.js `josa`, 설계 6.6): josa('출발', '은/는') → '출발은' · josa('핸들', '을/를') → '핸들을'.
 * 짝은 '으로/로'(기본) '이/가' '을/를' '은/는' '과/와' '아/야' '이에요/예요' '이랑/랑'. 숫자는 읽는 소리로(3 → 삼, 10 → 십).
 */
internal object Josa {
    private val PAIRS = listOf(
        "으로" to "로", "이" to "가", "을" to "를", "은" to "는", "과" to "와", "아" to "야", "이에요" to "예요", "이랑" to "랑",
    )

    /** 받침 번호(0 = 없음, 8 = ㄹ). 숫자 읽기: 영(ㅇ) 일(ㄹ) 이 삼(ㅁ) 사 오 육(ㄱ) 칠(ㄹ) 팔(ㄹ) 구. */
    private val DIGIT_JONG = intArrayOf(21, 8, 0, 16, 0, 0, 1, 8, 8, 0)

    private const val TRAIL = "\"'”’)]}>.!?,…·"

    /** 낱말 + 조사. */
    fun josa(word: String?, pair: String = "으로/로"): String = (word ?: "") + pick(word, pair)

    /** 조사만. */
    fun pick(word: String?, pair: String = "으로/로"): String {
        val (a, b) = findPair(pair)
        val j = lastJong(word)
        if (a == "으로") return if (j == 0 || j == 8) b else a
        return if (j == 0) b else a
    }

    private fun findPair(pair: String): Pair<String, String> {
        val p = pair.ifEmpty { "으로/로" }
        if ('/' in p) {
            val s = p.split('/')
            return s[0] to s[1]
        }
        return PAIRS.firstOrNull { it.first == p || it.second == p } ?: (p to p)
    }

    private fun lastJong(word: String?): Int {
        val s = word ?: ""
        var i = s.length - 1
        while (i >= 0 && (isJsSpace(s[i]) || s[i] in TRAIL)) i--
        if (i < 0) return 0
        val ch = s[i]
        if (ch.code in 0xAC00..0xD7A3) return (ch.code - 0xAC00) % 28
        if (ch in '0'..'9') {
            var j = i
            while (j >= 0 && s[j] in '0'..'9') j--
            val digits = s.substring(j + 1, i + 1).replace(Regex("^0+(?=\\d)"), "")
            if (digits == "0") return 21
            return when (digits.length - digits.trimEnd('0').length) {
                0 -> DIGIT_JONG[ch - '0']
                1 -> 17 // 십(ㅂ)
                2 -> 1 // 백(ㄱ)
                else -> 4 // 천·만(ㄴ)
            }
        }
        return when (ch) {
            'l', 'L' -> 8
            'm', 'M' -> 16
            'n', 'N' -> 4
            else -> 0
        }
    }

    /** JS 정규식 `\s`가 잡는 글자. */
    private fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }
}
