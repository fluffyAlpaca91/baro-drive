package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 읽을 수 있는 신호 (설계 7.8 '신호 지원', 프로토타입 core.js `CAP_KEYS`). 키 12개, 이름은 프로토타입과 같다.
 * false인 신호는 스냅숏 값이 null이고, 그 신호에 기대는 코칭 항목이 꺼진다(항목 정의 [ItemDef.needs]).
 */
@Serializable
data class SignalCaps(
    val speed: Boolean = true,
    val gear: Boolean = true,
    val blinker: Boolean = true,
    val steer: Boolean = true,
    val nav: Boolean = true,
    val passenger: Boolean = true,
    val beltDriver: Boolean = true,
    val handbrake: Boolean = true,
    val wiper: Boolean = true,
    val outsideTemp: Boolean = true,
    /** 뒤로 가는 속도(음수)를 읽음. 못 읽으면 speed는 크기만 온다(HL2가 꺼진다). */
    val reverseSpeed: Boolean = true,
    /**
     * 코스 혼잡도(`requestTrip`). 트립 거리(tripDist)와는 상관없다 — 프로토타입 vehicle.js를 따랐다.
     * (core.js `CAP_FIELD`는 trip을 tripDist에 묶어 두어 둘이 어긋난다.)
     */
    val trip: Boolean = true,
) {
    /** [key] 신호를 읽을 수 있나. */
    operator fun get(key: SignalKey): Boolean = when (key) {
        SignalKey.SPEED -> speed
        SignalKey.GEAR -> gear
        SignalKey.BLINKER -> blinker
        SignalKey.STEER -> steer
        SignalKey.NAV -> nav
        SignalKey.PASSENGER -> passenger
        SignalKey.BELT_DRIVER -> beltDriver
        SignalKey.HANDBRAKE -> handbrake
        SignalKey.WIPER -> wiper
        SignalKey.OUTSIDE_TEMP -> outsideTemp
        SignalKey.REVERSE_SPEED -> reverseSpeed
        SignalKey.TRIP -> trip
    }

    /** [key]만 [readable]로 바꾼 사본. */
    fun with(key: SignalKey, readable: Boolean): SignalCaps = when (key) {
        SignalKey.SPEED -> copy(speed = readable)
        SignalKey.GEAR -> copy(gear = readable)
        SignalKey.BLINKER -> copy(blinker = readable)
        SignalKey.STEER -> copy(steer = readable)
        SignalKey.NAV -> copy(nav = readable)
        SignalKey.PASSENGER -> copy(passenger = readable)
        SignalKey.BELT_DRIVER -> copy(beltDriver = readable)
        SignalKey.HANDBRAKE -> copy(handbrake = readable)
        SignalKey.WIPER -> copy(wiper = readable)
        SignalKey.OUTSIDE_TEMP -> copy(outsideTemp = readable)
        SignalKey.REVERSE_SPEED -> copy(reverseSpeed = readable)
        SignalKey.TRIP -> copy(trip = readable)
    }

    /** 못 읽는 신호, [SignalKey] 순서. */
    val off: List<SignalKey> get() = SignalKey.entries.filterNot { this[it] }

    companion object {
        /** 모든 신호를 읽음(기본). */
        val ALL = SignalCaps()
    }
}

/**
 * 신호 이름 (프로토타입 caps 키 = 스냅숏 필드 이름, [title]은 copy.js `signals` — 설계 7.8 표·RV-4 '…을 못 읽어').
 * JSON에는 caps 키를 쓴다.
 */
@Serializable
enum class SignalKey(val key: String, val title: String) {
    @SerialName("speed")
    SPEED("speed", "차속"),

    @SerialName("gear")
    GEAR("gear", "기어"),

    @SerialName("blinker")
    BLINKER("blinker", "깜빡이"),

    @SerialName("steer")
    STEER("steer", "핸들"),

    @SerialName("nav")
    NAV("nav", "내비"),

    @SerialName("passenger")
    PASSENGER("passenger", "좌석"),

    @SerialName("beltDriver")
    BELT_DRIVER("beltDriver", "벨트"),

    @SerialName("handbrake")
    HANDBRAKE("handbrake", "사이드브레이크"),

    @SerialName("wiper")
    WIPER("wiper", "와이퍼"),

    @SerialName("outsideTemp")
    OUTSIDE_TEMP("outsideTemp", "바깥 온도"),

    @SerialName("reverseSpeed")
    REVERSE_SPEED("reverseSpeed", "뒤로 가는 속도"),

    @SerialName("trip")
    TRIP("trip", "코스 혼잡도"),
    ;

    companion object {
        /** caps 키 → 값. 모르는 키면 null. */
        fun fromKey(key: String?): SignalKey? = entries.firstOrNull { it.key == key }
    }
}
