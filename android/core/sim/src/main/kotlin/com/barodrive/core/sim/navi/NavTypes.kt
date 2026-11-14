package com.barodrive.core.sim.navi

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.route.RouteTurn

/**
 * 내비가 가리키는 회전 하나 (navi.js `upcoming()`의 `tr`). 경로 회전이거나, 고리(안내 없음)에 붙인 가상 TBT([virtual])다.
 * 가상이면 [turn]이 차가 실제로 도는 고리 위 회전(JS `pathTurn`)이다.
 */
class TurnRef internal constructor(val turn: RouteTurn, val virtual: VirtualTurn? = null) {
    val id: String get() = virtual?.id ?: turn.id
    val type: String? get() = virtual?.type ?: turn.type
    val dir: TurnDir get() = virtual?.dir ?: turn.dir
    val blinker: Blinker get() = if (dir == TurnDir.LEFT) Blinker.LEFT else Blinker.RIGHT
    val n: Int? get() = turn.n
    val ic: Boolean get() = turn.ic
    val road: String get() = turn.road
    val injected: Boolean get() = turn.injected

    /** 가상 TBT면 고리 위 회전(JS `pathTurn`), 아니면 null. */
    val pathTurn: RouteTurn? get() = if (virtual != null) turn else null

    override fun toString(): String = "TurnRef($id)"
}

/** 다가오는 회전과 남은 거리(정확한 m). */
data class Upcoming(val ref: TurnRef, val remain: Double)

/** 고리(안내 없음)에 붙인 가상 TBT(navi.js `virtual`). */
class VirtualTurn internal constructor(val id: String, val turn: RouteTurn, val type: String, val dir: TurnDir) {
    internal var entered: Boolean = false
}

/** [Navi.injectTurn] 결과. [bound] = 이미 다가오던 회전에 묶음(새로 끼우지 않음). */
data class InjectedTurn(val turn: TurnRef, val bound: Boolean, val virtual: Boolean = false)

/** 내비 음성 하나(`sim:navVoice`). [t] = 시작한 sim초. */
data class NavVoice(val text: String, val dur: Double, val t: Double, val key: String?)

/** 경로 요약(`sim:route`). */
data class RouteSummary(
    val key: String?,
    val courseId: String?,
    val placeId: String?,
    val name: String?,
    val routeOption: String?,
    val segments: Int,
    val waypoints: Int,
    val lengthM: Long,
    val generated: Boolean,
)

/** 내비 손 바꾸기 값(제한속도·어린이보호구역·정체·주의 구간, 시연 패널 '내비'). */
data class NavOverrides(
    val limit: Int? = null,
    val limitUntilS: Double? = null,
    val limitUntilT: Double? = null,
    val school: OverrideSpan? = null,
    val jam: OverrideSpan? = null,
    val cautions: List<OverrideCaution> = emptyList(),
)

/** 손으로 넣은 구간(경로 거리). */
data class OverrideSpan(val id: String?, val s0: Double, val s1: Double)

/** 손으로 넣은 주의 구간(경로 거리 [s]에서 [len] m). */
data class OverrideCaution(val id: String, val s: Double, val type: CautionType, val len: Double)

/** 지금 안내 중인 경유지. */
data class NavWaypointState(val index: Int, val name: String, val remainM: Long, val passed: Boolean)

/**
 * 화면(DR-1·지도·시연 패널)이 보는 내비 상태 (navi.js `state`): 스냅숏 nav 값 + 남은 거리·시간·목적지·구간·경유지.
 * [searching] = 'reroute'(경로 다시 찾는 중) · 'segment'(다음 구간 찾는 중) · null.
 */
data class NavState(
    val nav: com.barodrive.core.model.NavSnapshot,
    val guiding: Boolean,
    val arrived: Boolean,
    val searching: String?,
    val remainM: Long,
    val remainSec: Long,
    val totalRemainM: Long,
    val destName: String?,
    val routeOption: String?,
    val courseId: String?,
    val placeId: String?,
    val key: String?,
    val waypoints: List<NavWaypointState>,
    val voiceText: String?,
    /** 제한속도 출처 'override' · 'route' · 'free' · 'school' · null. */
    val limitSource: String?,
    val loop: Boolean,
)
