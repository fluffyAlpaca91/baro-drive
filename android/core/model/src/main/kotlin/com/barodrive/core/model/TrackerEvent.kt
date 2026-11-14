package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 공용 추적기 사건 이름 (프로토타입 core.js `EVENTS`, 구현계획 1.8.2 `ctx.on(event, fn)`).
 * 같은 스냅숏 안 순서(core.js createTracker 머리 주석): gear → reroute·arrived·waypoint → waitEnd → brakeEnd → start →
 * turnApproach·turnPassed → steerTurn → straightWindow → zoneExit·zoneEnter → waitStart. input은 화면 사건.
 */
@Serializable
enum class TrackerEventType {
    @SerialName("gear")
    GEAR,

    @SerialName("start")
    START,

    @SerialName("brakeEnd")
    BRAKE_END,

    @SerialName("waitStart")
    WAIT_START,

    @SerialName("waitEnd")
    WAIT_END,

    @SerialName("turnApproach")
    TURN_APPROACH,

    @SerialName("turnPassed")
    TURN_PASSED,

    @SerialName("steerTurn")
    STEER_TURN,

    @SerialName("straightWindow")
    STRAIGHT_WINDOW,

    @SerialName("zoneEnter")
    ZONE_ENTER,

    @SerialName("zoneExit")
    ZONE_EXIT,

    @SerialName("reroute")
    REROUTE,

    @SerialName("arrived")
    ARRIVED,

    @SerialName("waypoint")
    WAYPOINT,

    @SerialName("input")
    INPUT,
}

/**
 * 공용 추적기가 검출기에 넘기는 사건 값 (프로토타입 core.js createTracker 머리 주석 '사건 값', 설계 6.2).
 * 엔진 안에서 추적기 → 검출기로만 오간다(화면은 보지 않는다). 칸 이름은 프로토타입과 같다.
 */
sealed interface TrackerEvent {
    /** 사건 이름. */
    val event: TrackerEventType
}

/** 기어가 바뀜. 세션은 P에서 시작한다고 본다(첫 스냅숏이 D면 P → D). */
@Serializable
data class GearEvent(val t: Double, val from: Gear? = null, val to: Gear? = null) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.GEAR
}

/** 출발: [t] = 5km/h를 넘은 때, [t0] = 움직이기 시작한 때, [maxGain1s] = 출발 뒤 5초 안 1초 최대 증가량(km/h). */
@Serializable
data class StartEvent(
    val t: Double,
    val t0: Double,
    val maxGain1s: Double,
    val gear: Gear? = null,
    val v: Double? = null,
) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.START
}

/**
 * 제동 구간 끝. [maxDrop1s] = 구간 안에서 끝나는 1초 창(시작 1초 전까지 거슬러 봄)의 최대 감소량.
 * [eval] = 평가하는 제동(v0 ≥ 10이고 (v1 < 0.5 또는 v0 − v1 ≥ 10)). [viaSpeed] = 브레이크 신호 없이 속도로 잡음.
 */
@Serializable
data class BrakeEndEvent(
    val t0: Double,
    val t1: Double,
    val v0: Double,
    val v1: Double,
    val maxDrop1s: Double,
    /** 브레이크 → 정지까지(서지 않았으면 끝까지) 초. */
    val dur: Double,
    val stopped: Boolean,
    val stopT: Double? = null,
    val eval: Boolean,
    val gear: Gear? = null,
    val viaSpeed: Boolean = false,
) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.BRAKE_END
}

/** 신호 대기 시작(기어 D·N — 못 읽으면 D로 봄 — 이고 정지 1초 이상). [since] = 선 때. */
@Serializable
data class WaitStartEvent(val t: Double, val since: Double) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.WAIT_START
}

/** 신호 대기 끝(움직이거나 기어가 P·R). */
@Serializable
data class WaitEndEvent(val t: Double, val since: Double, val dur: Double) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.WAIT_END
}

/**
 * 내비 회전 접근: 창(150m, IC 300m) 안에서 거리·깜빡이가 바뀔 때마다. 세션 출력 `turn`(주행 화면 깜빡이 히어로)도 이 모양이다.
 * [blinkOnDist] = 맞는 쪽 깜빡이가 처음 켜진 순간의 남은 거리(창 거리로 자름). 앞 회전 전에 켜 둔 깜빡이는 세지 않는다.
 * [blinkDir] = 지금 깜빡이. [first] = 창에 처음 들어옴.
 */
@Serializable
data class TurnApproach(
    val id: String,
    val type: String,
    val dir: TurnDir? = null,
    val ic: Boolean = false,
    /** IC 진입(합류 침묵 20초, 설계 6.5.2 규칙 5). */
    val enter: Boolean = false,
    val distance: Double,
    /** 접근 창 거리(150 · IC 300). */
    val window: Double,
    val blinkOnDist: Double? = null,
    val blinkOnT: Double? = null,
    val wrongOnDist: Double? = null,
    val wrongOnT: Double? = null,
    val blinkDir: TurnDir? = null,
    val blinkOk: Boolean = false,
    val blinkWrong: Boolean = false,
    val first: Boolean = false,
) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.TURN_APPROACH
}

/**
 * 내비 회전 지남: 회전 지점을 지나고 조향이 끝난 뒤(조향이 없으면 8초 뒤). [missed] = 재탐색, [skipped] = 건너뛰기 —
 * 둘 다 판정하지 않는다(그 회전의 판정은 회차 끝에 excluded로 바뀐다).
 */
@Serializable
data class TurnPassed(
    val id: String,
    val type: String,
    val dir: TurnDir? = null,
    val ic: Boolean = false,
    val enter: Boolean = false,
    val blinkOnDist: Double? = null,
    val blinkOnT: Double? = null,
    val wrongOnDist: Double? = null,
    val wrongOnT: Double? = null,
    val steerStartT: Double? = null,
    val steerEndT: Double? = null,
    val reachedT: Double? = null,
    val missed: Boolean = false,
    val skipped: Boolean = false,
    val window: Double,
) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.TURN_PASSED
}

/**
 * 조향 회전: abs(steer) ≥ 10°가 2초 넘게(결정-61). 시작 = 5° 넘은 순간, 끝 = 3° 아래 1초.
 * [blinkBefore] = 시작 5초 전~1초 뒤에 깜빡이가 켜짐, [blinkSameBefore] = 그게 같은 쪽, [blinkAtStart] = 시작 순간 같은 쪽이 켜짐,
 * [blinkOnT] = 시작 전 30초 안에 같은 쪽을 켠 마지막 시각, [navTurnId] = 이어진 내비 회전.
 */
@Serializable
data class SteerTurn(
    val dir: TurnDir,
    val startT: Double,
    val endT: Double,
    val blinkBefore: Boolean,
    val blinkSameBefore: Boolean,
    val blinkAtStart: Boolean,
    val blinkOnT: Double? = null,
    val navTurnId: String? = null,
) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.STEER_TURN
}

/** 직진 창: 10초, 속도 > 15, heading 변화 < 15°(내비를 못 읽으면 abs(steer) < 10°). [std] = steer 표준편차. */
@Serializable
data class StraightWindow(
    val t0: Double,
    val t1: Double,
    val n: Int,
    val std: Double,
    val mean: Double,
) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.STRAIGHT_WINDOW
}

/** 구역 들어감: 같은 제한속도(어린이보호구역 여부)가 이어지는 구간. [id] = 'z1' …. */
@Serializable
data class ZoneEnter(val id: String, val limit: Double? = null, val school: Boolean = false, val t: Double) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.ZONE_ENTER
}

/** 구역 나감. */
@Serializable
data class ZoneExit(val id: String, val limit: Double? = null, val school: Boolean = false, val t: Double, val dur: Double) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.ZONE_EXIT
}

/** 재탐색 끝. [turnId] = 놓친 회전. */
@Serializable
data class RerouteEvent(val t: Double, val turnId: String? = null) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.REROUTE
}

/** 목적지 도착. */
@Serializable
data class ArrivedEvent(val t: Double) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.ARRIVED
}

/** 경유지 도착(면허 모드 구간 끝이면 [kind] = 'segment'). */
@Serializable
data class WaypointEvent(val t: Double, val index: Int? = null, val segment: Int? = null, val kind: String? = null) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.WAYPOINT
}

/** 화면 사건(세션 input). mute·unmute도 검출기에 넘긴다. */
@Serializable
data class InputEvent(val input: SessionInput) : TrackerEvent {
    override val event: TrackerEventType get() = TrackerEventType.INPUT
}
