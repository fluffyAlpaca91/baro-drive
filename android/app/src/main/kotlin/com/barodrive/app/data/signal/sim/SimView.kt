package com.barodrive.app.data.signal.sim

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.TbtItem
import com.barodrive.core.sim.MarkProgress
import com.barodrive.core.sim.PlayerState

/**
 * 시연 패널이 보는 시뮬레이터 모습 (프로토타입 shell/panel-sim.js `onRefresh`가 읽는 `BaroSim.player`·`vehicle.state`·`navi.state`·
 * `clock`). [SimController]가 실제 시간 고리 한 번(100ms)과 패널 조작마다 잠금 안에서 만든다 — 바꿀 수 없는 값만 담는다.
 */
data class SimView(
    /** 불러온 시나리오(없으면 손 조작). */
    val scenario: LoadedScenario? = null,
    val player: PlayerState = PlayerState.IDLE,
    /** sim 시계(세션 시작 때 0). */
    val simT: Double = 0.0,
    /** 경로 위 거리 m · 경로 길이 m(고리면 0) · 비율. */
    val progressS: Double = 0.0,
    val progressTotal: Double = 0.0,
    val progressRatio: Double = 0.0,
    val marks: List<MarkProgress> = emptyList(),
    val car: CarView = CarView(),
    /** 손 운전 목표 속도(km/h)와 브레이크 고정. */
    val manualTarget: Double = 0.0,
    val brakeLock: Boolean = false,
    /**
     * 비상등(시연 전용, [SimController.setHazard]) — 앱 층에만 있는 값이라 [car](시뮬레이터 차 상태)가 아니라 여기 둔다.
     * 스냅숏·엔진에는 없다.
     */
    val hazard: Boolean = false,
    val nav: NavView = NavView(),
    /** 배속 ×1·×2·×4 · 실제 속도 · 자동 재생(시뮬레이터에 들어간 값). 실제 속도면 배속은 1이다. */
    val speed: Int = 1,
    val realtime: Boolean = false,
    val autoplay: Boolean = true,
)

/** 불러온 시나리오 (`player.current`). [kind] 'scenario'·'template'·'generated'·'inline'. */
data class LoadedScenario(
    val id: String,
    val title: String,
    val mode: Int?,
    val skill: DriverSkill,
    val kind: String?,
    val generated: Boolean,
    val courseId: String?,
    val placeId: String?,
)

/** 차 상태 (`vehicle.state`) — 차는 자기 값을 늘 알므로 못 읽는 신호(caps)와 상관없다. */
data class CarView(
    val gear: Gear = Gear.P,
    val speed: Double = 0.0,
    val steer: Double = 0.0,
    val brake: Boolean = false,
    val blinker: Blinker = Blinker.OFF,
    val beltDriver: Boolean = true,
    val passenger: Boolean = false,
    val handbrake: Boolean = false,
    val wiper: Boolean = false,
    val lowBeam: Boolean = true,
    val night: Boolean = true,
    val outsideTemp: Double = 15.0,
    val tripDist: Double = 0.0,
    val model: String = "avante",
)

/** 내비 상태 (`navi.state` + 손 바꾸기 값). */
data class NavView(
    val guiding: Boolean = false,
    val arrived: Boolean = false,
    val tbt: List<TbtItem> = emptyList(),
    val limit: Int? = null,
    val inSchoolZone: Boolean = false,
    val congested: Boolean = false,
    /** 면허 모드 구간(1부터) · 구간 수. */
    val segmentIndex: Int? = null,
    val segmentCount: Int? = null,
    val destName: String? = null,
    val courseId: String? = null,
    val placeId: String? = null,
    /** 패널로 덮어쓴 값(어린이보호구역·정체·제한속도). */
    val overrideSchool: Boolean = false,
    val overrideJam: Boolean = false,
    val overrideLimit: Int? = null,
)
