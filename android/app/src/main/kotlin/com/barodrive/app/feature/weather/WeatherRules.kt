package com.barodrive.app.feature.weather

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.PrepWeather
import com.barodrive.core.model.RainBy
import com.barodrive.core.model.Severe
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WeatherBadge
import kotlin.math.abs
import kotlin.math.floor

/** PR-1 날씨 줄에 그릴 값 (weather.js `model`). */
data class WeatherCardModel(
    /** 맨 위로(와이퍼 켜짐 또는 바깥 0°C 이하). */
    val top: Boolean,
    /** '오늘 날씨' · '오늘 날씨 · 바깥 −2°C'. */
    val title: String,
    val status: String,
    /** 엔진이 시작할 날씨(아주 나쁜 날 > 눈 > 비). 맑으면 null. */
    val limitKey: WeatherBadge?,
    /** DR-1 배지와 같은 글 '눈길 · 제한속도 20% 낮춤'. 맑으면 ''. */
    val limit: String,
    /** 와이퍼를 못 읽을 때만 [오늘 비 와요]. */
    val rainChip: Boolean,
    val wiperOn: Boolean,
    val readable: Boolean,
)

/** 아주 나쁜 날 고르기 줄 하나(copy.js `weather.severeOptions`). [severe]가 null이면 '해당 없어요'. */
data class SevereOption(val severe: Severe?, val label: String)

/**
 * PR-1 날씨 줄과 아주 나쁜 날 시트의 화면 없는 계산 (프로토타입 screens/weather.js `BaroApp.weatherCard`, 설계 4.2 PR-1 날씨 줄·5.6·6.8·7.8,
 * 결정-17, 결정사항 E T7-8). 고른 값은 준비 세션 `weather`에 두고, 엔진은 시작할 때 snow·rain·severe만 읽는다.
 * - snow [오늘 눈 와요] · severe 시트 · rain = [오늘 비 와요](와이퍼를 못 읽을 때) 또는 시작 전에 와이퍼가 이어서 30 sim초 넘게 켜짐.
 */
object WeatherRules {
    /** 날씨 줄이 있는 모드: 모드 6(실제 주차 안내)은 J3가 '안 함'이고 PR-1 주차 변형은 점검·코스 카드만이라 없다. */
    val MODES: List<Int> = listOf(1, 2, 3, 4, 5, 7, 8)

    /** PR-1 카드 자리(40). 와이퍼 켜짐·0°C 이하면 [TOP](변형 맨 위 카드보다 앞). */
    const val ORDER = 40
    const val TOP = -1
    private const val EPS = 1e-6

    val SEVERE_OPTIONS: List<SevereOption> = listOf(
        SevereOption(Severe.ICE, "길이 얼었거나 눈이 많이 쌓였어요(2cm 이상)"),
        SevereOption(Severe.FOG, "앞이 100m도 안 보여요"),
        SevereOption(null, "해당 없어요"),
    )

    private fun wiperSecTh(): Double = BaroEngine.threshold("J3.wiperSec") ?: 30.0

    private fun coldTh(): Double = BaroEngine.threshold("J3.cold") ?: 0.0

    /** 바깥 온도 0°C 이하. */
    fun cold(s: VehicleSnapshot?): Boolean {
        val t = s?.outsideTemp ?: return false
        return t.isFinite() && t <= coldTh() + EPS
    }

    /** 맨 위로: 와이퍼 켜짐 또는 바깥 온도 0°C 이하. */
    fun urgent(s: VehicleSnapshot?): Boolean = s != null && (s.wiper == true || cold(s))

    /** PR-1 카드 자리(weather.js `orderFor`). */
    fun orderFor(s: VehicleSnapshot?): Int = if (urgent(s)) TOP else ORDER

    /** '−2°C'(음수는 긴 빼기표). */
    fun tempText(v: Double): String {
        val n = floor(v + 0.5).toInt()
        return (if (n < 0) "−" + abs(n) else n.toString()) + "°C"
    }

    /**
     * 비 정하기 (weather.js `syncRain`): 와이퍼를 읽을 수 있으면 손으로 고른 비는 거두고, 와이퍼가 30초 넘게 켜졌으면 비, 꺼지면 거둔다.
     * 못 읽으면 와이퍼로 정한 비만 거둔다. 고친 값.
     */
    fun syncRain(w: PrepWeather, s: VehicleSnapshot?): PrepWeather {
        var by = w.rainBy
        val readable = s != null && s.wiper != null
        if (readable) {
            if (by == RainBy.CHIP) by = null
            val on = s.wiper == true && w.wiperSec >= wiperSecTh() - EPS
            if (on) by = RainBy.WIPER else if (by == RainBy.WIPER) by = null
        } else if (by == RainBy.WIPER) {
            by = null
        }
        return w.copy(rainBy = by, rain = by == RainBy.CHIP || by == RainBy.WIPER)
    }

    /** 엔진이 시작할 날씨의 배지(DR-1과 같음): 아주 나쁜 날 > 눈 > 비. */
    fun limitKey(w: PrepWeather): WeatherBadge? = when {
        w.severe != null -> WeatherBadge.SEVERE
        w.snow -> WeatherBadge.SNOW
        w.rain -> WeatherBadge.RAIN
        else -> null
    }

    /** 날씨 줄 값과 비를 맞춘 준비 세션 날씨 (weather.js `model` — 프로토타입은 prep.weather를 그 자리에서 고친다). */
    fun model(weather: PrepWeather, s: VehicleSnapshot?): Pair<PrepWeather, WeatherCardModel> {
        val w = syncRain(weather, s)
        val readable = s != null && s.wiper != null
        val wiperOn = readable && s.wiper == true
        val status = when {
            w.snow -> "눈 오는 날 안내로 시작해요"
            w.rain && w.rainBy == RainBy.WIPER -> "와이퍼가 켜져 있어요 · 비 오는 날 안내로 시작해요"
            w.rain -> "비 오는 날 안내로 시작해요"
            !readable -> "와이퍼를 못 읽어요 · 비가 오면 직접 골라 주세요"
            wiperOn -> "와이퍼가 켜져 있어요 · 30초가 지나면 비 오는 날 안내로 시작해요"
            else -> "와이퍼가 30초 넘게 켜지면 비 오는 날 안내로 바뀌어요"
        }
        val lk = limitKey(w)
        val temp = s?.outsideTemp
        return w to WeatherCardModel(
            top = urgent(s),
            title = "오늘 날씨" + if (cold(s) && temp != null) " · 바깥 " + tempText(temp) else "",
            status = status,
            limitKey = lk,
            limit = lk?.label.orEmpty(),
            rainChip = !readable,
            wiperOn = wiperOn,
            readable = readable,
        )
    }

    /** 와이퍼 시간 쌓기: 켜져 있으면 [dt]만큼(0.1초 단위로 반올림), 꺼지면 0 (weather.js `sim:tick`). */
    fun addWiper(w: PrepWeather, s: VehicleSnapshot?, dt: Double): PrepWeather =
        if (s?.wiper == true) w.copy(wiperSec = floor((w.wiperSec + maxOf(0.0, dt)) * 10 + 0.5) / 10) else w.copy(wiperSec = 0.0)

    // ---------------- 토스트 글

    fun snowToast(on: Boolean): String = if (on) "눈 오는 날 안내로 시작해요" else "눈 오는 날 안내를 껐어요"

    fun rainToast(on: Boolean): String = if (on) "비 오는 날 안내로 시작해요" else "비 오는 날 안내를 껐어요"

    fun severeToast(severe: Severe?): String = if (severe != null) "아주 나쁜 날로 기록해요 · 제한속도 기준 50% 낮춤" else "아주 나쁜 날을 껐어요"
}
