package com.barodrive.core.sim

import com.barodrive.core.sim.navi.RouteSummary
import com.barodrive.core.sim.scenario.ScenarioDef

/**
 * 시뮬레이터가 알리는 사건 (프로토타입 `Baro.bus`의 'sim:*' 사건). [BaroSim.addListener]로 받는다 — 틱 안에서 차례로(동기로) 부른다.
 * 앱 세션 연결은 [Gear](P를 벗어나면 세션 시작 · sim 시계 0)를, 화면은 [Scenario]·[NavVoice]를 듣는다.
 */
sealed interface SimEvent {
    /** 틱 하나가 끝남(`sim:tick`). [ff] = 빨리 감기. 건너뛰기 중에는 오지 않는다. */
    data class Tick(val t: Double, val dt: Double, val ff: Boolean) : SimEvent

    /** 기어가 바뀜(`sim:gear`) — 차 상태를 다 고친 뒤. [source] 'panel'·'scenario'·'driver'·'inject'·'sim'·'reset'. */
    data class Gear(val from: com.barodrive.core.model.Gear, val to: com.barodrive.core.model.Gear, val source: String) : SimEvent

    /**
     * 재생기 상태·장면이 바뀜(`sim:scenario`). [manual] = 손 조작으로 멈춤, [reason] = 끝난 까닭('timeout'·'route' …),
     * [skipped] = 건너뛴 장면 이름, [unloaded] = 시나리오를 내림.
     */
    data class Scenario(
        val state: PlayerState,
        val id: String?,
        val mark: String?,
        val manual: Boolean = false,
        val reason: String? = null,
        val skipped: String? = null,
        val unloaded: Boolean = false,
    ) : SimEvent

    /** 내비 음성이 시작됨(`sim:navVoice`). */
    data class NavVoice(val voice: com.barodrive.core.sim.navi.NavVoice) : SimEvent

    /** 경로가 바뀜(`sim:route`, null = 안내 끝). */
    data class Route(val summary: RouteSummary?) : SimEvent

    /** 재탐색(`sim:reroute`). */
    data class Reroute(val turnId: String) : SimEvent

    /** 목적지 도착(`sim:arrived`). [forced] = 주입·시나리오 '도착'. */
    data class Arrived(val courseId: String?, val placeId: String?, val forced: Boolean) : SimEvent

    /** 경유지 도착(`sim:waypoint`). [kind] 'waypoint'(구간 안 경유지) · 'segment'(면허 구간 끝 → [next]/[count]). */
    data class Waypoint(val index: Int, val segment: Int, val kind: String, val name: String? = null, val next: Int? = null, val count: Int? = null) : SimEvent

    /** 재생 중 손 조작으로 재생을 멈춤(`sim:manual`). [key] = 바꾼 신호. */
    data class Manual(val key: String) : SimEvent
}

/** [SimEvent] 받기. */
fun interface SimListener {
    fun onEvent(event: SimEvent)
}

/** 재생기 상태(`player.state`). */
enum class PlayerState(val code: String) { IDLE("idle"), LOADED("loaded"), PLAYING("playing"), PAUSED("paused"), ENDED("ended") }

/**
 * 불러올 대상 (player.js `load(id | { courseId } | { placeId } | 정의)`).
 * [Scenario] = 시나리오 id(또는 코스·장소 id, 'GEN:<코스|장소>') · [Course] = 그 코스 기본 시나리오 → 모드 틀 → 기본 틀
 * · [Place] = 그 장소 시나리오 → 기본 틀 · [Inline] = 정의 그대로.
 */
sealed interface SimTarget {
    data class Scenario(val id: String) : SimTarget
    data class Course(val courseId: String) : SimTarget
    data class Place(val placeId: String) : SimTarget
    data class Inline(val def: ScenarioDef) : SimTarget
}

/** 장면 하나(`marks()`). [s] = 경로 거리 m(거리 장면) · [t] = 시나리오 시각(시간 장면). */
data class SceneMark(val name: String, val s: Double?, val t: Double?, val passed: Boolean)

/** 불러온 시나리오 정보(`player.info()`). [kind] 'scenario'·'template'·'generated'·'inline'. */
data class ScenarioInfo(
    val id: String?,
    val title: String?,
    val mode: Int?,
    val courseId: String?,
    val placeId: String?,
    val skill: com.barodrive.core.model.DriverSkill?,
    val state: PlayerState,
    val t: Double,
    val kind: String?,
    val generated: Boolean,
    val routeKey: String?,
    val marks: List<String>,
    val mark: String?,
    /** 경로 길이 m(고리면 null). */
    val lengthM: Long?,
)

/** 재생 진행(`player.progress()`): 경로 거리 [s]/[total], 장면마다 자리 비율. */
data class SimProgress(
    val s: Double,
    val total: Double,
    val ratio: Double,
    val t: Double,
    val state: PlayerState,
    val marks: List<MarkProgress>,
)

/** 진행 막대 위 장면. [ratio] = 경로 위 비율(시간 장면은 null). */
data class MarkProgress(val name: String, val ratio: Double?, val t: Double?, val passed: Boolean)

/** 등록된 시나리오 요약(`player.list()`). */
data class ScenarioSummary(val id: String, val title: String, val mode: Int?, val courseId: String?, val placeId: String?, val isDefault: Boolean)

/** [Player.runUntil] 결과. [reason] 'cond'(조건이 참) · 'end'(시나리오 끝) · 'max'(시간 다 씀). */
data class RunResult(val reason: String, val ticks: Int, val simT: Double, val t: Double, val state: PlayerState, val mark: String?)

/** 시연 패널 주입 버튼 하나(설계 7.10). [item] = 관련 코칭 항목. */
data class InjectionInfo(val id: String, val label: String, val group: String, val item: String?)
