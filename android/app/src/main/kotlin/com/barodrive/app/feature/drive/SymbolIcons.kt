package com.barodrive.app.feature.drive

import androidx.annotation.DrawableRes
import com.barodrive.app.R

/**
 * 프로토타입 심볼 id('i-brake' · 'sys-user') → 앱 아이콘. 엔진이 카드·항목·팁에 심볼 id를 실어 보낸다(Card.icon · ItemDef.icon ·
 * ReviewTip.icon). 모르는 id면 [fallback].
 */
object SymbolIcons {
    private val MAP: Map<String, Int> = mapOf(
        "i-back" to R.drawable.ic_back, "i-close" to R.drawable.ic_close, "i-chev" to R.drawable.ic_chev,
        "i-voice" to R.drawable.ic_voice, "i-speaker" to R.drawable.ic_speaker, "i-mute" to R.drawable.ic_mute,
        "i-check" to R.drawable.ic_check, "i-lock" to R.drawable.ic_lock, "i-star" to R.drawable.ic_star,
        "i-star-f" to R.drawable.ic_star_f, "i-send" to R.drawable.ic_send, "i-share" to R.drawable.ic_share,
        "i-settings" to R.drawable.ic_settings, "i-history" to R.drawable.ic_history, "i-info" to R.drawable.ic_info,
        "i-plus" to R.drawable.ic_plus, "i-pencil" to R.drawable.ic_pencil, "i-refresh" to R.drawable.ic_refresh,
        "i-up" to R.drawable.ic_up, "i-calendar" to R.drawable.ic_calendar, "sys-user" to R.drawable.ic_user,
        "i-user" to R.drawable.ic_user, "i-flag" to R.drawable.ic_flag, "i-flag-check" to R.drawable.ic_flag_check,
        "i-medal" to R.drawable.ic_medal, "i-route" to R.drawable.ic_route, "i-belt" to R.drawable.ic_belt,
        "i-pbrake" to R.drawable.ic_pbrake, "i-seat" to R.drawable.ic_seat, "i-brake" to R.drawable.ic_brake,
        "i-pedals" to R.drawable.ic_pedals, "i-wheel" to R.drawable.ic_wheel, "i-slow" to R.drawable.ic_slow,
        "i-pulse" to R.drawable.ic_pulse, "i-hazard" to R.drawable.ic_hazard, "i-shield" to R.drawable.ic_shield,
        "i-bulb" to R.drawable.ic_bulb, "i-lamp" to R.drawable.ic_lamp, "i-turn-r" to R.drawable.ic_turn_r,
        "i-blink-l" to R.drawable.ic_blink_l, "i-blink-r" to R.drawable.ic_blink_r, "i-blink-both" to R.drawable.ic_blink_both,
        "i-car-top" to R.drawable.ic_car_top, "i-sun" to R.drawable.ic_sun, "i-rain" to R.drawable.ic_rain,
        "i-snow" to R.drawable.ic_snow, "i-wiper" to R.drawable.ic_wiper, "i-temp" to R.drawable.ic_temp,
    )

    @DrawableRes
    fun of(id: String?, @DrawableRes fallback: Int = R.drawable.ic_info): Int = id?.let { MAP[it] } ?: fallback
}
