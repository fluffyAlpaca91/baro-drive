package com.barodrive.core.engine

import com.barodrive.core.engine.review.jsRound
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.WeatherType

/**
 * 판정·말하기 기준값 표 (프로토타입 core.js `TH`를 키·값 그대로 옮김, 설계 6.2·6.3.1·6.5·6.8).
 * 문서 수치가 있으면 그대로, 없으면 설계 6.2의 '설계 가정'이다. 단위는 키마다 다르다(초·km/h·m·°·개).
 * '.wx'·'.severe'로 끝나는 키는 비·눈·아주 나쁜 날 값이다.
 *
 * 모르는 키는 바로 예외를 던진다 — 오타가 조용히 0이 되는 일을 막는다.
 * 날씨에 따라 '.wx'·'.severe' 값을 고르는 조회는 [weathered](core.js `threshold(key, weather, arg)`, 설계 6.8).
 */
object Thresholds {
    private val table: Map<String, Double> = mapOf(
        // 정지 abs(speed) < 0.5 · 신호 대기 1초 · 제동 구간 끝 = 정지 1초
        "stop.v" to 0.5, "wait.sec" to 1.0, "stop.hold" to 1.0,
        // 출발 speed ≥ 5 · 출발 뒤 5초 창
        "start.v" to 5.0, "start.window" to 5.0,
        // 평가하는 제동: v0 ≥ 10이고 (v1 < 0.5 또는 v0 − v1 ≥ 10)
        "brake.v0" to 10.0, "brake.dv" to 10.0,
        // 내비 회전 접근 창(IC 300)
        "turn.near" to 150.0, "turn.near.ic" to 300.0,
        // 회전 조향을 볼 거리 · 조향이 없으면 지난 뒤 8초에 마무리 · 최대 30초
        "turn.steerPre" to 30.0, "turn.timeout" to 8.0, "turn.maxSec" to 30.0,
        // 조향 회전(결정-61)
        "steer.start" to 5.0, "steer.big" to 10.0, "steer.bigSec" to 2.0, "steer.end" to 3.0, "steer.endSec" to 1.0,
        // 직진 창
        "straight.sec" to 10.0, "straight.heading" to 15.0, "straight.v" to 15.0,
        "B1.gain" to 11.0, "B1.gain.wx" to 8.0, "F1.drop" to 7.5, "F1.drop.wx" to 6.0, "F2.drop" to 11.0,
        "E.factor" to 1.0, "E.factor.wx" to 0.8, "E.factor.severe" to 0.5,
        "E1.sec" to 3.0, "E2.sec" to 3.0, "E2.limit" to 30.0,
        "C1.dist" to 70.0, "C1.min" to 50.0,
        "C2.ok" to 40.0, "C2.almost" to 30.0, "C2.ok.ic" to 110.0, "C2.almost.ic" to 100.0,
        "C4.sec" to 3.0, "C5.before" to 5.0, "C5.after" to 1.0, "C6.sec" to 10.0,
        "G1.base" to 0.8, "G1.minWindows" to 3.0, "G1.better" to 0.1,
        "J1.sec" to 5.0, "J1.max" to 2.0, "J3.wiperSec" to 30.0, "J3.cold" to 0.0, "J3.slip" to 300.0,
        "PB1.sec" to 2.0, "PB2.sec" to 3.0,
        "PK2.v" to 7.0, "PK2.sec" to 1.0, "PK2.repeat" to 15.0, "PK3.steer" to 5.0,
        "HL2.v" to -0.5, "HL2.sec" to 0.5, "HL3.steer" to 8.0,
        "hill.startV" to 3.0, "hill.startSec" to 3.0, "hill.holdSec" to 8.0, "hill.caution" to 300.0,
        // 말하기 규칙 7(간격)·8(상한)·4(내비 음성 뒤)
        "gap" to 20.0, "cap.window" to 600.0, "cap.n" to 6.0, "navVoice.after" to 1.0,
        // 규칙 5(침묵)
        "F2.silence" to 30.0, "merge.silence" to 20.0,
        "praise.expire" to 8.0, "fix.expire" to 15.0, "info.expire" to 30.0,
        "safety.expire" to 10.0, "safety.maxWait" to 120.0,
        // jump.sec: 스냅숏 사이가 1.5초 넘게 비면 재던 창을 새로 시작한다
        "pre.maxWait" to 60.0, "praise.repeat" to 300.0, "card.max" to 2.0, "jump.sec" to 1.5,
        "fewEvents" to 4.0, "pass.rate" to 0.8, "pass.count" to 2.0,
        "fade.rate" to 0.8, "fade.min" to 2.0, "fade.streak" to 2.0,
    )

    /** 표에 있는 키 전부. */
    val keys: Set<String> get() = table.keys

    /** [key]의 기준값. 표에 없는 키면 [IllegalArgumentException]. */
    operator fun get(key: String): Double =
        requireNotNull(table[key]) { "기준 없음: $key" }

    /**
     * 날씨를 반영한 기준값 (core.js `threshold(key, weather, arg)` = 검출기 `ctx.th`, 설계 6.8).
     * 'B1.gain' 11/8 · 'F1.drop' 7.5/6(비·눈·아주 나쁜 날) · 'E.factor' 1/0.8/0.5 · 'E2.limit'(30에 배율) ·
     * 'C2.ok'·'C2.almost'·'turn.near'는 [ic]면 IC 값. 그 밖은 표 그대로. 표에 없는 키면 [IllegalArgumentException].
     */
    fun weathered(key: String, weather: SessionWeather, ic: Boolean = false): Double {
        val wet = weather.type == WeatherType.RAIN || weather.type == WeatherType.SNOW
        val bad = wet || weather.severe != null
        return when (key) {
            "B1.gain" -> this[if (bad) "B1.gain.wx" else "B1.gain"]
            "F1.drop" -> this[if (bad) "F1.drop.wx" else "F1.drop"]
            "E.factor" -> factor(weather)
            "E2.limit" -> jsRound(this["E2.limit"] * factor(weather))
            "C2.ok", "C2.almost", "turn.near" -> this[if (ic) "$key.ic" else key]
            else -> this[key]
        }
    }

    /** 날씨 배율을 적용해 반올림한 제한속도 (core.js `effLimit`): 50 → 40(비·눈) · 25(아주 나쁜 날). 없거나 수가 아니면 null. */
    fun effLimit(limit: Double?, weather: SessionWeather): Int? =
        if (limit == null || !limit.isFinite()) null else jsRound(limit * factor(weather)).toInt()

    private fun factor(weather: SessionWeather): Double = when {
        weather.severe != null -> this["E.factor.severe"]
        weather.type == WeatherType.RAIN || weather.type == WeatherType.SNOW -> this["E.factor.wx"]
        else -> this["E.factor"]
    }
}
