package com.barodrive.core.engine.content

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/*
 * 대사 그리기의 작은 규칙들 (프로토타입 core.js `applyHonorific`·`fmtVar`·`VAR_RE`·`normDir`, 설계 6.6·6.6.1).
 * 정규식의 공백은 JS `\s`와 같은 글자로 직접 적는다 — JVM의 `\s`는 ASCII 공백만, 안드로이드(ICU)의 `\s`는 유니코드
 * 공백까지 잡아서 둘이 다르기 때문이다.
 */

/** JS 정규식 `\s`가 잡는 글자(문자 클래스 안에 넣는 꼴). */
private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

/** 문장 맨 앞 또는 마침표·느낌표·물음표 + 공백 뒤의 `{호칭}` (core.js applyHonorific 첫째 식). */
private val HON_LEAD = Regex("(^|[.!?][$WS]+)\\{[$WS]*호칭[$WS]*\\}")

/** 그 밖 자리의 `{호칭}`과 바로 앞 공백. */
private val HON_ANY = Regex("[$WS]*\\{[$WS]*호칭[$WS]*\\}")

/** 호칭이 없을 때: 맨 앞 `{호칭}`은 뒤 쉼표·공백과 함께 지운다. */
private val HON_LEAD_DROP = Regex("(^|[.!?][$WS]+)\\{[$WS]*호칭[$WS]*\\}[$WS]*,?[$WS]*")

/** 호칭이 없을 때: 그 밖 자리의 `{호칭}`은 바로 앞 공백·쉼표와 함께 지운다. */
private val HON_ANY_DROP = Regex("[$WS,]*\\{[$WS]*호칭[$WS]*\\}")

/** 변수 자리 `{이름}` (core.js `VAR_RE`). */
internal val VAR_RE = Regex("\\{[$WS]*([^{}$WS]+)[$WS]*\\}")

/** 주차 단계 문장의 `{dir}` 자리 (parking.js `steps`). */
internal val DIR_RE = Regex("\\{[$WS]*dir[$WS]*\\}")

/** JS `String.prototype.trim`처럼 앞뒤 공백을 뺀다. */
internal fun String.jsTrim(): String = trim { it.isJsSpace() }

private fun Char.isJsSpace(): Boolean = when (this) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
    else -> this in ' '..' '
}

/**
 * `{호칭}`·`{ 호칭}` 채우기 (core.js `applyHonorific`, 설계 6.6.1). 호칭이 있으면 문장 맨 앞은 그 자리에, 그 밖(느낌표·쉼표 앞)은
 * 공백 하나 뒤에 넣는다. 없으면 맨 앞은 뒤 쉼표·공백과 함께, 그 밖은 바로 앞 공백·쉼표와 함께 지운다.
 * 넣는 글은 글자 그대로 넣는다(`$`를 특별히 보지 않음).
 */
internal fun applyHonorific(text: String, honorific: String): String {
    if (!text.contains("호칭")) return text
    if (honorific.isNotEmpty()) {
        val lead = HON_LEAD.replace(text) { it.groupValues[1] + honorific }
        return HON_ANY.replace(lead) { " $honorific" }
    }
    val lead = HON_LEAD_DROP.replace(text) { it.groupValues[1] }
    return HON_ANY_DROP.replace(lead) { "" }
}

/**
 * 변수 값을 글자로 (core.js `fmtVar`). `{N}`은 10m 단위로 내림(0 아래는 0), `{dir}`은 'L'·'LEFT'·'R'·'RIGHT' → 왼쪽·오른쪽,
 * `{wx}`는 'rain'·'snow'·'severe' → 글자, 다른 숫자는 정수면 그대로 · 아니면 소수 한 자리(JS 숫자 글자와 같게).
 * 나머지는 `toString()`.
 */
internal fun formatVar(name: String, value: Any): String {
    val num = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    if (name == "N" && num != null) return jsNumber(max(0.0, floor(num / 10) * 10))
    if (name == "dir") normDir(value)?.let { return Words.dirName(it) }
    if (name == "wx") Words.wx[value.toString()]?.let { return it }
    if (num != null) return if (num == floor(num)) jsNumber(num) else jsNumber(jsRound1(num))
    return value.toString()
}

/** 변수가 빠졌다고 보는 값 (core.js: undefined · null · 유한하지 않은 숫자). */
internal fun isMissingVar(value: Any?): Boolean =
    value == null || (value is Double && !value.isFinite()) || (value is Float && !value.isFinite())

/** 'L'·'LEFT' → 'L', 'R'·'RIGHT' → 'R', 그 밖 null (core.js `normDir`). 열거형 LEFT·RIGHT도 이름으로 받는다. */
internal fun normDir(value: Any?): String? = when (value?.toString()?.uppercase()) {
    "L", "LEFT" -> "L"
    "R", "RIGHT" -> "R"
    else -> null
}

/** JS `String(숫자)`와 같은 글자: 50.0 → "50", 12.3 → "12.3"(지수 표기 없이). */
internal fun jsNumber(x: Double): String = when {
    x == floor(x) && abs(x) < 1e21 -> BigDecimal(x).toPlainString()
    x.isFinite() -> BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()
    else -> x.toString()
}

/** core.js `round1` — JS `Math.round`처럼 .5는 위로. */
private fun jsRound1(x: Double): Double = floor(x * 10 + 0.5) / 10
