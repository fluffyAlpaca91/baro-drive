package com.barodrive.app.data.signal.sim

import com.barodrive.app.session.NavVoice
import com.barodrive.app.session.RouteSummary
import com.barodrive.app.session.ScenarioState
import com.barodrive.app.session.SimLink
import com.barodrive.app.session.SimTick
import com.barodrive.core.model.Mode
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.VehicleSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 세션 관리자 ↔ 시연 시뮬레이터 (session.js가 `BaroSim.player`·`navi`·`clock`에 하는 일). [SimController]에 넘긴다.
 * - [send]: PR-1 [내비로 보내고 출발] → 내비 경로 + 그 코스·장소 시나리오를 불러 자동 재생. 모드 6 [주차 안내 시작]은 주차 종류·방향을
 *   고른 뒤 S6-P.
 * - [onDeparture]: 기어가 P를 벗어나는 틱에 회차가 시작되면 sim 시계 0(그 틱 스냅숏 t = 0).
 * - [hold]: P 마무리·기어 없이 정차 재기 동안 시계를 붙잡음.
 * - [takeTick]: 그 스냅숏 틱에 난 시나리오 상태·입력 — 세션이 엔진 걸음 바로 전에 꺼내 간다. [scenario]도 그 시점으로 맞춰진다.
 */
@Singleton
class DemoSimLink @Inject constructor(private val controller: SimController) : SimLink {
    override val scenario: StateFlow<ScenarioState> get() = controller.sessionScenario

    override val navVoice: Flow<NavVoice> get() = controller.navVoices

    override fun send(courseId: String?, placeId: String?): RouteSummary? = controller.send(courseId, placeId)

    /** 모드 6이면 주차 종류·방향을 고르고 S6-P(parking-guide.js [주차 안내 시작]), 아니면 코스·장소. */
    override fun send(prep: PrepSession): RouteSummary? =
        if (prep.mode == Mode.PARKING.no) controller.startParking(prep.parking?.type?.code, prep.parking?.side?.code)
        else super.send(prep)

    override fun pause() = controller.pauseScenario()

    override fun resume() = controller.resumeScenario()

    override fun onDeparture(gate: () -> Boolean) {
        controller.departureGate = gate
    }

    override fun hold(name: String, on: Boolean) = controller.hold(name, on)

    override fun takeTick(snapshot: VehicleSnapshot): SimTick = controller.takeTick(snapshot)
}
