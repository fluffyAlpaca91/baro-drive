package com.barodrive.core.engine

import com.barodrive.core.engine.review.jsNum
import com.barodrive.core.engine.review.jsRound
import com.barodrive.core.engine.review.toFixed1
import kotlin.math.floor

/*
 * 엔진 코어의 작은 수 규칙. JS 반올림·글자 규칙은 review/JsCompat(jsRound·round2·round3·toFixed1·Josa)을 같이 쓴다.
 */

/** 시각·속도 비교의 허용 오차 (core.js `EPS`). 0.1초 걸음이 쌓인 부동소수 오차를 흡수한다. */
internal const val EPS = 1e-6

/** core.js `round1` = `Math.round(x × 10) / 10`. */
internal fun round1(x: Double): Double = jsRound(x * 10) / 10

/**
 * 보임 숫자 (basic.js `num`): 소수 한 자리로 반올림해 정수면 그대로('28'), 아니면 소수 한 자리('2.8').
 * 카드 보조 줄의 수치에 쓴다.
 */
internal fun num(x: Double): String {
    val v = round1(x)
    return if (v == floor(v)) jsNum(v) else toFixed1(v)
}
