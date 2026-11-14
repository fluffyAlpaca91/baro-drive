package com.barodrive.core.engine.session

import com.barodrive.core.model.F2Check
import com.barodrive.core.model.HillResult
import com.barodrive.core.model.LicenseResult
import com.barodrive.core.model.ParkingResult
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.Severe
import com.barodrive.core.model.WeatherSource
import com.barodrive.core.model.WeatherType
import kotlinx.serialization.json.JsonElement

/**
 * 검출기 메모 (core.js `S.notes`, `ctx.note(키, 값)` — 결정사항 E T1c-9). 회차 끝에 기록 칸이 된다:
 * f2 → `rec.f2` · tips → `rec.tips` · weather → `rec.weather`에 합침 · hill·parking·license → 같은 칸 · 그 밖 → `rec.notes`.
 * f2·tips는 목록 메모다: 하나를 넘기면 덧붙이고 목록을 넘기면 바꾼다.
 */
internal class SessionNotes {
    /** 위험 회피 급제동 줄(id 없이). null이면 메모가 없어 F2 판정에서 만든다. */
    var f2: MutableList<F2Check>? = null
    var tips: MutableList<ReviewTip>? = null
    var weather: WeatherNote? = null
    var hill: HillResult? = null
    var parking: ParkingResult? = null
    var license: LicenseResult? = null
    val rest = LinkedHashMap<String, JsonElement>()

    fun addF2(row: F2Check) {
        (f2 ?: ArrayList<F2Check>().also { f2 = it }) += row
    }

    fun addTip(tip: ReviewTip) {
        (tips ?: ArrayList<ReviewTip>().also { tips = it }) += tip
    }

    fun replaceTips(list: List<ReviewTip>) {
        tips = list.toMutableList()
    }
}

/** 날씨 검출기 메모 (weather.js finish `ctx.note('weather', { by, at, tipCards })`). */
internal class WeatherNote(val by: WeatherSource?, val at: Double?, val tipCards: Int)

/**
 * 회차 날씨 상태 (core.js `S.weather` = { type, severe, cold }). 한 번 비·눈이 되면 맑음으로 돌아가지 않는다(T1c-12).
 */
internal class WeatherState(var type: WeatherType, var severe: Severe?, var cold: Boolean) {
    fun snapshot(): SessionWeather = SessionWeather(type = type, severe = severe, cold = cold)

    /** core.js `setWeather(w)`: 합치기만 한다. */
    fun merge(type: WeatherType?, severe: Severe?, cold: Boolean) {
        if (type != null && !(type == WeatherType.CLEAR && this.type != WeatherType.CLEAR)) this.type = type
        if (severe != null) this.severe = severe
        if (cold) this.cold = true
    }
}
