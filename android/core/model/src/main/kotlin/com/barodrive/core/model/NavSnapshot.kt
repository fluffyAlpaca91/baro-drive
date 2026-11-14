package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 스냅숏의 내비 값 (설계 6.1.2 `nav.*`, 프로토타입 `sim/navi.js navFields()`). JSON 칸 이름은 프로토타입과 같다.
 * 진짜 차는 NaviHelper 콜백으로 채운다. 각 필드 KDoc 끝이 그 콜백이다.
 */
@Serializable
data class NavSnapshot(
    /** 안내 상태. `onNavigationStatus`. */
    val status: NavStatus = NavStatus.INACTIVE,
    /** 다가오는 회전, 가까운 순 2개. `onTBTInfo`. */
    val tbt: List<TbtItem> = emptyList(),
    /** 지금 도로의 제한속도 km/h. 모르면 null. `onCurrentRoadSpeedLimit`(리스너 등록 뒤 매초). */
    val limit: Int? = null,
    /** 앞에 있는 주의 구간. `onCautionPointInfo`. */
    val cautions: List<CautionItem> = emptyList(),
    /** 어린이보호구역 안. 주의 구간 + 제한속도 30으로 정한다. */
    val inSchoolZone: Boolean = false,
    /** 경로가 막힘. `onRouteStateInfo`의 `trafficStatus`. */
    val congested: Boolean = false,
    /** 내비 음성이 나오는 중. AudioFocus로 안다. */
    val voice: Boolean = false,
    /** 진행 방향 °(북 0, 시계 방향). 모르면 null. `onCurrentLocationInfo`의 `heading`. */
    val heading: Double? = null,
    /** 이번 스냅숏에 한 번만 실리는 사건. `onReRouteEnded`·`onDestinationArrived`·`onWayPointArrived`. */
    val events: List<@Serializable(with = NavEventSerializer::class) NavEvent> = emptyList(),
    /** 이 구간 목적지까지 남은 거리 m(안내 중일 때만). `onRouteRemainInfo`. */
    val remain: Double? = null,
    /** 면허 모드 구간 진행 { index(1부터), count }. 경로가 없으면 null(설계 6.12.3, navi.js `segment`). */
    val segment: NavSegment? = null,
)

/** 내비 안내 상태. 프로토타입은 둘만 쓴다 — NaviHelper의 INITIALIZING·FREE_DRIVE는 INACTIVE로 본다. */
@Serializable
enum class NavStatus { INACTIVE, ROUTE_DRIVE }

/** TBT 회전 하나 (프로토타입 navi.js `turnItem` `{id, type, distance, dir, blinker, ic, road}`). */
@Serializable
data class TbtItem(
    /** 회전 id. 같은 회전을 스냅숏 사이에서 알아보는 데 쓴다. */
    val id: String,
    /** NaviHelper `TurnType` 이름(예: "TURN_LEFT", "TURN_ENTER_IC_R"). 종류가 46개라 프로토타입처럼 글자로 둔다. */
    val type: String,
    /** 회전까지 남은 거리 m. */
    val distance: Double,
    /** 회전 방향. 직진·톨게이트처럼 방향이 없으면 null. */
    val dir: TurnDir? = null,
    /** 이 회전에서 켜야 할 깜빡이(시뮬레이터가 알려 줌). 모르면 null. */
    val blinker: Blinker? = null,
    /** IC 진입·진출 회전. 엔진은 [type]으로도 다시 판단한다(core.js turnInfo). */
    val ic: Boolean = false,
    /** 회전 뒤 도로 이름. */
    val road: String? = null,
)

/** 왼쪽·오른쪽 (프로토타입 'L'·'R'). 회전 방향, 주차 칸 쪽, 핸들을 돌릴 쪽에 함께 쓴다. */
@Serializable
enum class TurnDir(val code: String, val label: String) {
    @SerialName("L")
    LEFT("L", "왼쪽"),

    @SerialName("R")
    RIGHT("R", "오른쪽"),
    ;

    /** 반대쪽. */
    val opposite: TurnDir get() = if (this == LEFT) RIGHT else LEFT

    companion object {
        /** 'L'·'LEFT'·'R'·'RIGHT'(대소문자 무시) → 값, 그 밖은 null(core.js `normDir`). */
        fun fromCode(code: String?): TurnDir? = when (code?.uppercase()) {
            "L", "LEFT" -> LEFT
            "R", "RIGHT" -> RIGHT
            else -> null
        }
    }
}

/** 주의 구간 하나 (프로토타입 `{id, type, remain}`). */
@Serializable
data class CautionItem(
    val id: String,
    val type: CautionType,
    /** 구간까지 남은 거리 m. */
    val remain: Double,
)

/** 프로토타입이 쓰는 주의 구간 종류(NaviHelper `CautionPointType` 23개 중 셋). */
@Serializable
enum class CautionType { SCHOOL_ZONE, SLIPPERY_ROAD_ALERT, DOWNHILL_CAUTION_AREA }

/** 면허 모드 구간 진행 (navi.js `segment: { index, count }`). */
@Serializable
data class NavSegment(
    /** 지금 구간, 1부터. */
    val index: Int = 1,
    /** 구간 수. */
    val count: Int = 1,
)

/**
 * 내비 사건. 일어난 다음 스냅숏 하나에만 실린다. JSON은 프로토타입처럼 `{ type: 'reroute', turnId }` 모양이고,
 * 글자 하나('reroute')로 와도 읽는다.
 */
@Serializable
sealed interface NavEvent {
    /** 재탐색 끝. [turnId] = 놓친 회전(알 때만). `onReRouteEnded`. */
    @Serializable
    @SerialName("reroute")
    data class Reroute(val turnId: String? = null) : NavEvent

    /** 목적지 도착. `onDestinationArrived`. */
    @Serializable
    @SerialName("arrived")
    data object Arrived : NavEvent

    /**
     * 경유지 도착. `onWayPointArrived`. 면허 모드에서는 구간 끝도 이것으로 온다([kind] = 'segment', [segment] = 끝난 구간 번호).
     */
    @Serializable
    @SerialName("waypointArrived")
    data class WaypointArrived(
        val index: Int? = null,
        val segment: Int? = null,
        /** 'segment'(면허 구간 끝) 또는 null(보통 경유지). */
        val kind: String? = null,
    ) : NavEvent
}
