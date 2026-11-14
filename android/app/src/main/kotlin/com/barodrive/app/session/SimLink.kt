package com.barodrive.app.session

import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.VehicleSnapshot
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 세션 관리자가 시연 시뮬레이터에 바라는 것 (session.js가 `BaroSim.player`·`BaroSim.navi`에 하는 일). 진짜 차에는 시나리오가 없다 —
 * 그때는 [NoSimLink]다. 시연 쪽(`data/signal/sim/`)이 구현해 묶는다.
 */
interface SimLink {
    /** 지금 시나리오 재생 상태('sim:scenario'). */
    val scenario: StateFlow<ScenarioState>

    /** 내비 음성 한 마디('sim:navVoice') — 자막·기록만(소리는 내비가 낸다). */
    val navVoice: Flow<NavVoice>

    /**
     * PR-1 [내비로 보내고 출발](session.js `send`): 코스면 `navi.setRoute(courseId)`, 장소면 `navi.setDestination(placeId)` 뒤
     * 그 시나리오를 불러 자동 재생. 보냈으면 경로 요약, 못 보냈으면 null.
     */
    fun send(courseId: String?, placeId: String?): RouteSummary?

    /**
     * 준비 세션 통째로 보내기(세션 관리자가 부른다). 기본은 코스(없으면 장소)로 [send]. 시연 시뮬레이터는 모드 6(실제 주차 안내)이면
     * 준비 세션의 주차 종류·방향을 먼저 고르고 S6-P를 불러 자동 재생한다(parking-guide.js [주차 안내 시작]).
     */
    fun send(prep: PrepSession): RouteSummary? = send(prep.courseId, if (prep.courseId != null) null else prep.placeId)

    /** 재생 멈춤(cancel·DR-3 [여기서 마치기]). */
    fun pause()

    /** 멈춘 재생 이어서(DR-3 → D). */
    fun resume()

    // ---- 세션 연결 훅 (session.js `start()`의 `clock.reset(0)` · `clockHold` · 시나리오 ask·press → `session.input`).
    // 시뮬레이터가 없으면 아무 일도 하지 않는다(기본 구현).

    /**
     * 출발 문(설계 3.2 '시뮬레이션 시계를 0으로'): 기어가 P를 벗어나는 그 틱 안에서 시뮬레이터가 [gate]를 물어 참이면(이 틱에 회차가
     * 시작됨) sim 시계를 0으로 맞춘다 — 그 틱 스냅숏의 t가 0. [gate]는 틱 안(신호 출처 스레드)에서 불리므로 잠그거나 기다리면 안 된다.
     * 세션 관리자가 시작할 때 한 번 건다.
     */
    fun onDeparture(gate: () -> Boolean) = Unit

    /** 시계 붙잡기(session.js `clockHold`): 차가 서 있어도 sim 시계를 ×1로 돌린다(P 마무리 표본·기어 없이 10초 정차 재기). */
    fun hold(name: String, on: Boolean) = Unit

    /**
     * [snapshot]의 틱(과 그 앞, 틱 밖)에서 시뮬레이터에 난 일 — 시나리오 상태 바뀜과 시나리오가 낸 입력(ask·[다음 단계]·언덕 상황).
     * 세션은 그 스냅숏을 엔진에 넣기 전에 차례대로 처리한다(프로토타입은 틱 안에서 바로 불렀다). [scenario]도 이때 그 스냅숏 시점으로 맞춘다.
     * 붙은 일이 없으면 [SimTick.EMPTY].
     */
    fun takeTick(snapshot: VehicleSnapshot): SimTick = SimTick.EMPTY

    companion object {
        /** P 마무리 동안(session.js `HOLD_FINISH`). */
        const val HOLD_FINISH = "session-finish"

        /** 기어를 못 읽을 때 10초 정차를 재는 동안(session.js `HOLD_GEARLESS`). */
        const val HOLD_GEARLESS = "session-gearless"
    }
}

/** 한 틱에 시뮬레이터에 난 일([SimLink.takeTick]): 시나리오 상태 바뀜(차례대로)과 시나리오 입력. */
data class SimTick(val scenario: List<ScenarioState> = emptyList(), val inputs: List<SessionInput> = emptyList()) {
    companion object {
        val EMPTY = SimTick()
    }
}

/** 시나리오 재생 상태 (player.state: 'idle'·'loaded'·'playing'·'paused'·'ended'). */
enum class ScenarioState { IDLE, LOADED, PLAYING, PAUSED, ENDED }

/** 내비 음성 한 마디. [t]는 신호 출처의 sim초(세션 초로는 관리자가 바꾼다). */
data class NavVoice(val text: String, val t: Double? = null, val dur: Double? = null, val key: String? = null)

/** 내비에 넣은 경로 요약(navi.setRoute·setDestination). */
data class RouteSummary(val name: String? = null, val waypoints: Int = 0)

/** 시뮬레이터가 없을 때(진짜 차·시연 도구를 뺀 빌드). 보내기는 못 하고, 시나리오는 늘 IDLE. */
class NoSimLink @Inject constructor() : SimLink {
    override val scenario: StateFlow<ScenarioState> = MutableStateFlow(ScenarioState.IDLE)
    override val navVoice: Flow<NavVoice> = emptyFlow()

    override fun send(courseId: String?, placeId: String?): RouteSummary? = null

    override fun pause() = Unit

    override fun resume() = Unit
}
