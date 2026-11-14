package com.barodrive.core.sim.scenario

import com.barodrive.core.model.Blinker
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SessionInput
import com.barodrive.core.sim.route.RouteDef

/**
 * 시나리오 정의 (설계 7.6 형식, 프로토타입 `Baro.scenario({ … })`). 시나리오는 '어떻게 운전하는지'를 적고 운전자 모델이 신호로 바꾼다.
 *
 * - 경로: [route](시나리오 안 다리 — 내비 경로 키 'scenario:<id>') → 없으면 [courseId] 코스 경로 · [placeId] 장소 경로 → 없으면 경로 없음
 *   (사건으로만 움직임, [drive] = EVENTS).
 * - [turns]: 회전 번호(경로의 모든 회전을 1부터 셈) → 그 회전을 어떻게 도는지. [events]: 시간(t)·거리(at, 원래 경로 m) 사건과 장면(mark).
 * - [variants]: 실력별로 덧붙일 것(events는 덧붙이고, turns는 회전마다 합치고, 그 밖 값은 바꿔 씀).
 */
data class ScenarioDef(
    val id: String,
    val title: String? = null,
    val mode: Int? = null,
    val courseId: String? = null,
    val placeId: String? = null,
    /** 그 코스·장소의 기본 시나리오. */
    val isDefault: Boolean = false,
    /** null이면 경로가 있으면 ROUTE, 없으면 EVENTS. */
    val drive: DriveKind? = null,
    val start: StartState? = null,
    /** 순항 km/h(다리 cruise가 먼저). null이면 40. */
    val cruise: Double? = null,
    /** 직진 흔들림 표준편차 °(null이면 실력 기본값). */
    val wobble: Double? = null,
    val turns: Map<Int, TurnPlan> = emptyMap(),
    val events: List<ScenarioEvent> = emptyList(),
    val variants: Map<DriverSkill, Variant> = emptyMap(),
    /** 판정이 나와야 할 것('C2.ok' · 'E1'). */
    val expect: List<String> = emptyList(),
    /** 시나리오 안 경로(다리·구간). */
    val route: RouteDef? = null,
    /** 내비 목적지 이름(시나리오 안 경로). */
    val destName: String? = null,
    /** 모드 6 주차 종류·방향. */
    val parking: ParkingChoice? = null,
    /** 일반 경로 생성기·틀이 만든 시나리오. */
    val generated: Boolean = false,
    /** 만든 틀 모드(4 등). */
    val template: Int? = null,
    /** 1단계 틀: 주차장 연습 정보. */
    val lot: LotInfo? = null,
)

/** 시나리오 운전 방식: ROUTE(경로를 따라 자동) · EVENTS(경로 없음, 사건으로만 — 실제 주차 안내 S6-P). */
enum class DriveKind { ROUTE, EVENTS }

/** 1단계 틀의 주차장 연습: [laps] 바퀴, 원래 경로 [startAt] m부터. */
data class LotInfo(val laps: Int, val startAt: Double)

/** 모드 6 주차 종류·방향(PK-1). [type] 'parallel'·'back'·'front', [side] 'L'·'R'. */
data class ParkingChoice(val type: String = "parallel", val side: String = "R") {
    companion object {
        val TYPES = listOf("parallel", "back", "front")

        /** 모르는 값이면 평행 · 오른쪽(data/lines/parking.js `norm`). */
        fun norm(type: String?, side: String?): ParkingChoice {
            val t = type?.takeIf { it in TYPES } ?: "parallel"
            val s = side?.uppercase()
            return ParkingChoice(t, if (s == "L" || s == "LEFT") "L" else "R")
        }
    }
}

/**
 * 불러올 때 차 상태(`start`). null인 칸은 지금 값 그대로. 기어·속도가 없으면 P·0.
 * (JS의 별칭 belt·lamp·temp는 [beltDriver]·[lowBeam]·[outsideTemp]로 적는다.)
 */
data class StartState(
    val gear: Gear? = null,
    val speed: Double? = null,
    val beltDriver: Boolean? = null,
    val passenger: Boolean? = null,
    val handbrake: Boolean? = null,
    val wiper: Boolean? = null,
    val lowBeam: Boolean? = null,
    val night: Boolean? = null,
    val outsideTemp: Double? = null,
    val blinker: Blinker? = null,
    val steer: Double? = null,
)

/**
 * 회전 하나를 어떻게 도는지(`turns[n]`, 운전자 모델 planFor). null인 칸은 정하지 않음(합칠 때 앞 값을 둔다).
 * [blinkAt] 깜빡이를 켤 남은 거리 m · [blink] false면 깜빡이 없이 · [steerAfter] 깜빡이 켜고 그 초에 먼저 꺾기 시작(C4)
 * · [wrong] 반대쪽 먼저 켜고 3초 뒤 바로잡음(C3) · [keepOn] 돈 뒤 15초 켜 둠(C6) · [miss] 그 회전을 지나침(재탐색)
 * · [missSkill] 'poor'면 능숙은 놓치지 않음 · [navVoiceAt] 내비 음성 거리들 · [skillApplies] false면 실력 보정을 하지 않음(주입).
 */
data class TurnPlan(
    val blinkAt: Double? = null,
    val blink: Boolean? = null,
    val steerAfter: Double? = null,
    val wrong: Boolean? = null,
    val keepOn: Boolean? = null,
    val miss: Boolean? = null,
    val missSkill: DriverSkill? = null,
    val navVoiceAt: List<Double>? = null,
    val noBlink: Boolean? = null,
    val skillApplies: Boolean? = null,
) {
    /** JS `Object.assign({}, this, other)`: [other]에 정한 칸이 이긴다. */
    fun mergedWith(other: TurnPlan?): TurnPlan = if (other == null) this else TurnPlan(
        blinkAt = other.blinkAt ?: blinkAt,
        blink = other.blink ?: blink,
        steerAfter = other.steerAfter ?: steerAfter,
        wrong = other.wrong ?: wrong,
        keepOn = other.keepOn ?: keepOn,
        miss = other.miss ?: miss,
        missSkill = other.missSkill ?: missSkill,
        navVoiceAt = other.navVoiceAt ?: navVoiceAt,
        noBlink = other.noBlink ?: noBlink,
        skillApplies = other.skillApplies ?: skillApplies,
    )
}

/** 실력별로 덧붙일 것(`variants.poor` …): events 덧붙임 · turns 회전마다 합침 · [cruise]·[wobble]은 바꿔 씀. */
data class Variant(
    val turns: Map<Int, TurnPlan> = emptyMap(),
    val events: List<ScenarioEvent> = emptyList(),
    val cruise: Double? = null,
    val wobble: Double? = null,
)

/**
 * 사건 하나(설계 7.6 `events[]`). 시각 [t](시나리오 sim초) 또는 거리 [at](원래 경로 m)에 [act]를 한다.
 * [mark]만 있으면 장면(재생 위치 표시 · '다음 장면' 건너뛰기), [end]는 끝([act] PARK면 세우고 P).
 * [v]는 사건마다 뜻이 다르다(기어 'D' · 벨트 false · 온도 −2 · 핸들 각 · 입력 사건 …).
 */
data class ScenarioEvent(
    val t: Double? = null,
    val at: Double? = null,
    val act: Act? = null,
    val v: EventValue? = null,
    val mark: String? = null,
    val end: Boolean = false,
    val drop: Double? = null,
    val hold: Double? = null,
    val gain: Double? = null,
    val speed: Double? = null,
    val dur: Double? = null,
    val sec: Double? = null,
    val n: Int? = null,
    val std: Double? = null,
    val text: String? = null,
    val type: String? = null,
    val turn: Int? = null,
    val meters: Double? = null,
    val startIn: Double? = null,
    val ahead: Double? = null,
    val len: Double? = null,
    val handbrake: Boolean? = null,
    /** 조건(`if`). */
    val cond: Cond? = null,
    /** 이 실력일 때만. */
    val skill: DriverSkill? = null,
    /** 이 실력들일 때만. */
    val skills: List<DriverSkill>? = null,
    /** 능숙이어도 안전 위반 사건을 끄지 않음. */
    val always: Boolean = false,
    val keepOn: Boolean? = null,
)

/** 사건 값 `v`. */
sealed interface EventValue {
    data class Bool(val value: Boolean) : EventValue
    data class Num(val value: Double) : EventValue
    data class Str(val value: String) : EventValue
    data class Input(val event: SessionInput) : EventValue

    companion object {
        /** Kotlin 값 → 사건 값(Boolean · 수 · 글자 · [SessionInput] · [Gear] · [Blinker]). */
        fun of(v: Any?): EventValue? = when (v) {
            null -> null
            is EventValue -> v
            is Boolean -> Bool(v)
            is Number -> Num(v.toDouble())
            is String -> Str(v)
            is Gear -> Str(v.name)
            is Blinker -> Str(v.name)
            is SessionInput -> Input(v)
            else -> throw IllegalArgumentException("사건 값으로 쓸 수 없어요: $v")
        }
    }
}

/** 사건 종류(`do`). [code] = 프로토타입 글자. */
enum class Act(val code: String) {
    GEAR("gear"), BELT("belt"), PASSENGER("passenger"), SEAT_EMPTY("seatEmpty"), HANDBRAKE("handbrake"), WIPER("wiper"),
    LAMP("lamp"), HIGH_BEAM("highBeam"), NIGHT("night"), TEMP("temp"), BLINKER("blinker"), STEER("steer"), BRAKE("brake"),
    STOP("stop"), LAUNCH("launch"), OVER("over"), SPEED("speed"), PARK("park"), WAIT("wait"), ASK("ask"), ROLLBACK("rollback"),
    BRAKE_PULSE("brakePulse"), HOLD("hold"), HILL_SITUATION("hillSituation"), PRESS("press"), INPUT("input"),
    NAV_VOICE("navVoice"), REROUTE("reroute"), ARRIVE("arrive"), LIMIT("limit"), SCHOOL("school"), JAM("jam"),
    CAUTION("caution"), WOBBLE("wobble"),
}

/** 사건 조건(`if`). */
enum class Cond(val code: String) {
    BELT_WAS_OFF("beltWasOff"), BELT_WAS_ON("beltWasOn"), BELT_OFF("beltOff"),
    HANDBRAKE_WAS_ON("handbrakeWasOn"), HANDBRAKE_WAS_OFF("handbrakeWasOff"), HANDBRAKE_ON("handbrakeOn"),
    PASSENGER_WAS_ON("passengerWasOn"), PASSENGER_WAS_OFF("passengerWasOff"), PASSENGER_ON("passengerOn"),
    NIGHT("night"), DAY("day"),
}
