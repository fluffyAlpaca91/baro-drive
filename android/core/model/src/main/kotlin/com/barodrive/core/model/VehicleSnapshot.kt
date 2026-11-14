package com.barodrive.core.model

import kotlinx.serialization.Serializable

/**
 * 한 순간의 차 신호 (설계 6.1.2 스냅숏, 프로토타입 `BaroSim.snapshot()` — sim/vehicle.js).
 *
 * 진짜 차와 시뮬레이터가 같은 모양으로 내고, 엔진과 화면은 이것만 본다(어느 쪽인지 모른다).
 * `t`를 뺀 값은 모두 null일 수 있다. null = 그 신호를 못 읽음(설계 7.8 caps) — 0이나 false로 메우지 않는다.
 * 필드 이름은 프로토타입 JSON과 같다. 각 필드 KDoc 끝의 API가 진짜 차에서 읽는 곳이다.
 */
@Serializable
data class VehicleSnapshot(
    /** 시각(초). 엔진의 모든 기준(20초 간격, 10분 상한 …)은 이 값으로 잰다. 세션 관리자가 세션 시작을 0으로 맞춘다. */
    val t: Double,
    /** 기어. AAOS `GEAR_SELECTION`(CarPropertyManager). */
    val gear: Gear? = null,
    /** 속도 km/h, 후진·뒤로 밀림은 음수. AAOS `PERF_VEHICLE_SPEED`(m/s × 3.6). */
    val speed: Double? = null,
    /** 브레이크 페달을 밟음. Pleos `Brake.registerBrakePedalStatus`. */
    val brake: Boolean? = null,
    /** 앞바퀴 조향각 °, 왼쪽이 음수, ±35 안팎(핸들 회전각이 아님). Pleos `SteeringWheel.registerSteeringWheelAngle`. */
    val steer: Double? = null,
    /** 방향지시등. Pleos `TurnSignal.registerTurnSignalState`. */
    val blinker: Blinker? = null,
    /** 운전석 벨트를 맴. Pleos `Seat.registerSeatBeltBuckledState`. */
    val beltDriver: Boolean? = null,
    /** 조수석에 사람이 앉음. Pleos `Seat.getCurrentSeatOccupancy`. */
    val passenger: Boolean? = null,
    /** 주차 브레이크가 걸림. AAOS `PARKING_BRAKE_ON`. */
    val handbrake: Boolean? = null,
    /** 앞 와이퍼가 켜짐. Pleos `Wiper.registerWiperState`(앞). */
    val wiper: Boolean? = null,
    /** 하향등이 켜짐. Pleos `Light.registerLowBeamLightState`. */
    val lowBeam: Boolean? = null,
    /** 밤. 기기 시각으로 정한다. */
    val night: Boolean? = null,
    /** 바깥 온도 °C. AAOS `ENV_OUTSIDE_TEMPERATURE`. */
    val outsideTemp: Double? = null,
    /** 이번 주행 거리 m. Pleos `Odometer.getTripCurrentDrivingDistance`. */
    val tripDist: Double? = null,
    /** 내비 값. 내비를 못 읽으면 null. NaviHelper 콜백들([NavSnapshot] 참고). */
    val nav: NavSnapshot? = null,
    /** 차종 id(프로토타입 sim 'avante'·'grandeur'·'tucson'). 진짜 차는 차 정보에서. 모르면 null. */
    val model: String? = null,
    /** 차종 최대 앞바퀴각 °(주차·언덕 '끝까지 돌림' 판정, 설계 6.12.1). 모르면 null(검출기는 34로 본다). */
    val maxSteer: Double? = null,
    /** 시뮬레이터 '다음 장면'·'끝으로'로 건너뛴 바로 다음 스냅숏. 엔진은 재던 창을 버린다(core.js 추적기). 진짜 차는 늘 false. */
    val skip: Boolean = false,
    /** 건너뛴 정보(skip일 때만). */
    val skipInfo: SkipInfo? = null,
)

/** 시뮬레이터 건너뛰기 정보 (프로토타입 sim/player.js `markSkip({ from, to, label })`). */
@Serializable
data class SkipInfo(
    val from: SkipPoint? = null,
    val to: SkipPoint? = null,
    /** 건너뛴 장면 이름(예: '끝으로', 주차 시나리오 '3단계'). */
    val label: String? = null,
)

/** 건너뛰기 앞뒤 위치: 경로 위 거리 [s](m)와 시나리오 시각 [t](초). */
@Serializable
data class SkipPoint(
    val s: Double? = null,
    val t: Double? = null,
)
