package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 세션 설정의 날씨 (설계 6.8, 프로토타입 core.js `buildConfig` → `cfg.weather = { snow, rain, severe }`).
 * PR-1에서 고른 값이다. 엔진은 시작할 때만 읽는다(눈은 처음부터, 비는 와이퍼 30초로 바뀔 수 있음 — [SessionWeather]).
 */
@Serializable
data class Weather(
    /** [오늘 눈 와요]. */
    val snow: Boolean = false,
    /** 비로 시작(와이퍼를 못 읽어 [오늘 비 와요]를 골랐거나 시작 전에 와이퍼가 30초 넘게 켜짐). */
    val rain: Boolean = false,
    /** 아주 나쁜 날(시트에서 고름). 없으면 null. */
    @Serializable(with = SevereSerializer::class)
    val severe: Severe? = null,
) {
    /** 세션을 시작할 때의 날씨 상태(core.js createSession `S.weather`: 눈 > 비 > 맑음). */
    val initial: SessionWeather
        get() = SessionWeather(
            type = when {
                snow -> WeatherType.SNOW
                rain -> WeatherType.RAIN
                else -> WeatherType.CLEAR
            },
            severe = severe,
        )
}

/**
 * 회차 날씨 상태 (프로토타입 세션 `S.weather`·`ctx.weather()`, 기록 `rec.weather` — 설계 8.1).
 * 한 번 비·눈이 되면 회차 끝까지 그대로다. 기록에는 날씨 검출기가 [by]·[at]·[tipCards]를 더한다(결정사항 E T7-4).
 */
@Serializable
data class SessionWeather(
    val type: WeatherType = WeatherType.CLEAR,
    /** 아주 나쁜 날. 프로토타입 기록은 없을 때 false를 쓰는데, 읽으면 null이 된다. */
    @Serializable(with = SevereSerializer::class)
    val severe: Severe? = null,
    /** 출발 때 바깥 온도 0°C 이하(기록만, 기준은 그대로). */
    val cold: Boolean = false,
    /** 지금 날씨 안내가 시작된 까닭. 맑으면 null. */
    val by: WeatherSource? = null,
    /** 날씨 안내가 시작된 sim초(회차 처음부터, 시작 전에 골랐으면 0). */
    val at: Double? = null,
    /** 카드 줄에 넣은 날씨 요령 수. */
    val tipCards: Int? = null,
) {
    /** 비·눈·아주 나쁜 날 중 하나(기준을 낮추는 날, 설계 6.8). */
    val isBad: Boolean get() = type != WeatherType.CLEAR || severe != null
}

/** 날씨 종류 (프로토타입 'clear'·'rain'·'snow'). */
@Serializable
enum class WeatherType {
    @SerialName("clear")
    CLEAR,

    @SerialName("rain")
    RAIN,

    @SerialName("snow")
    SNOW,
}

/**
 * 아주 나쁜 날의 까닭 (프로토타입 copy.js `weather.severeOptions`, 설계 6.8 '× 0.5').
 * JSON은 'ice'·'fog'. 모델의 `severe` 칸은 [SevereSerializer]로 읽어서, 프로토타입이 쓰는 false는 null로,
 * true나 모르는 글자는 [OTHER]로 읽는다.
 */
@Serializable
enum class Severe(val code: String, val label: String) {
    /** 길이 얼었거나 눈이 많이 쌓였어요(2cm 이상). */
    @SerialName("ice")
    ICE("ice", "길이 얼었거나 눈이 많이 쌓였어요(2cm 이상)"),

    /** 앞이 100m도 안 보여요. */
    @SerialName("fog")
    FOG("fog", "앞이 100m도 안 보여요"),

    /** 까닭을 모르는 아주 나쁜 날. */
    @SerialName("other")
    OTHER("other", "아주 나쁜 날"),
    ;

    companion object {
        fun fromCode(code: String?): Severe? = entries.firstOrNull { it.code == code }
    }
}

/** 날씨 안내가 시작된 까닭 (결정사항 E T7-4 `by`): 시작 전에 고름(snow·rain·severe) 또는 달리는 중 와이퍼. */
@Serializable
enum class WeatherSource {
    @SerialName("snow")
    SNOW,

    @SerialName("rain")
    RAIN,

    @SerialName("severe")
    SEVERE,

    @SerialName("wiper")
    WIPER,
}

/**
 * DR-1 날씨 배지 (엔진 힌트 `badge`, 설계 6.8 배지 문구). 아주 나쁜 날이면 [SEVERE]가 앞선다(결정사항 E T7-4).
 * [label]은 copy.js `weather.badge`, [road]는 `weather.road`, [wx]는 대사 `{wx}` 자리(`weather.wx`).
 */
@Serializable
enum class WeatherBadge(val label: String, val road: String, val wx: String) {
    @SerialName("rain")
    RAIN("빗길 · 제한속도 20% 낮춤", "빗길", "비 오는 날은"),

    @SerialName("snow")
    SNOW("눈길 · 제한속도 20% 낮춤", "눈길", "눈 오는 날은"),

    @SerialName("severe")
    SEVERE("아주 나쁜 날 · 제한속도 50% 낮춤", "아주 나쁜 날", "오늘 같은 날은"),
}
