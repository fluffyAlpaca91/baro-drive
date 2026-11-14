package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 모드 전용 회차 결과 (설계 8.1 `hill`·`parking`·`license` 칸). 검출기가 회차 끝에 `ctx.note(키, 값)`으로 넘긴다.
 * 모양은 각 검출기 머리 주석·결정사항 E절 그대로다(hill.js noteOf · parking.js note · license.js finish · T5a-7).
 */

/**
 * 언덕 회차 결과 (`rec.hill`, hill.js `noteOf`, 결정사항 E T6-7). 리뷰는 [tasks]를 과제 결과로 그대로 쓴다.
 */
@Serializable
data class HillResult(
    /** 언덕 과제 셋: 'T-HILLUP'·'T-HILLDOWN'·'T-HILLPARK'. */
    val tasks: Map<String, HillTaskCount> = emptyMap(),
    val up: HillUpSummary = HillUpSummary(),
    val down: HillDownSummary = HillDownSummary(),
    /** 마지막 언덕 주차. 없으면 null. */
    val park: HillParkResult? = null,
    val parks: List<HillParkResult> = emptyList(),
    /** 회차 끝의 연습. */
    val practice: HillPractice? = null,
    /** 회차 끝의 단계. */
    val step: Int? = null,
    /** 마지막 연습을 끝까지 마침. */
    val done: Boolean = false,
)

/** 언덕 과제 하나의 셈. [badText]의 {n}은 리뷰가 실패 수로 바꾼다. 뒤로 가는 속도를 못 읽으면 T-HILLUP은 total 0, off 'reverseSpeed'. */
@Serializable
data class HillTaskCount(
    val success: Int = 0,
    val total: Int = 0,
    val off: String? = null,
    val badText: String? = null,
)

/** 오르막 출발 요약. */
@Serializable
data class HillUpSummary(
    val attempts: Int = 0,
    val rollbacks: Int = 0,
    /** 뒤로 가는 속도를 못 읽음(밀림을 세지 않음). */
    val rollOff: Boolean = false,
    val list: List<HillUpAttempt> = emptyList(),
)

/** 오르막 출발 시도 하나. */
@Serializable
data class HillUpAttempt(
    val n: Int = 0,
    val rolled: Boolean = false,
    val completed: Boolean = false,
)

/** 내리막 감속 요약. */
@Serializable
data class HillDownSummary(
    val runs: Int = 0,
    val presses: Int = 0,
    val holds: Int = 0,
    val list: List<HillDownRun> = emptyList(),
)

/** 내리막 구간 하나(2번 이상 나눠 밟고 8초 넘게 계속 밟지 않으면 ok). */
@Serializable
data class HillDownRun(
    val n: Int = 0,
    val presses: Int = 0,
    val holds: Int = 0,
    val ok: Boolean = false,
    /** 내비 내리막 주의 구간으로 시작함. */
    val viaNav: Boolean = false,
)

/** 언덕 주차 한 번 (hill.js `parkResult`). [ok]가 null이면 판정할 수 없음(핸들·사이드브레이크를 못 읽음). */
@Serializable
data class HillParkResult(
    val n: Int = 0,
    val situation: HillSlope? = null,
    /** 앞바퀴를 돌려야 할 쪽. */
    val need: TurnDir? = null,
    val wheelOk: Boolean? = null,
    val wheelSide: TurnDir? = null,
    val wheelDeg: Double? = null,
    val handbrake: HandbrakeState? = null,
    val ok: Boolean? = null,
)

/** 사이드브레이크 상태 (hill.js 'on'·'off'·'unknown'). */
@Serializable
enum class HandbrakeState {
    @SerialName("on")
    ON,

    @SerialName("off")
    OFF,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * 주차 결과 (`rec.parking`). 실제 주차 안내(모드 6, parking.js note, 결정사항 E T5b-5)와
 * 세워 두고 연습(PK-2, [virtual] = true, 결정사항 E T5a-7)이 같은 칸을 쓴다. 쓰지 않는 칸은 null.
 */
@Serializable
data class ParkingResult(
    val type: ParkingType = ParkingType.PARALLEL,
    val side: TurnDir = TurnDir.RIGHT,
    /** 'parallel-R'. */
    val key: String = "",
    /** 차종 id('avante' …). */
    val model: String? = null,
    /** 걸린 sim초. */
    val sec: Double? = null,

    // ---- 실제 주차 안내 ----
    /** 단계 수(평행 8 · 후방 7 · 전방 5). */
    val total: Int? = null,
    /** 마친 단계까지 닿은 단계 번호(다 마치면 total). */
    val reached: Int? = null,
    /** 마지막 단계까지 마침. */
    val done: Boolean = false,
    /** [다음 단계]를 누른 수. */
    val pressed: Int? = null,
    val steps: List<ParkingStepLog> = emptyList(),
    /** '후진 천천히'(PK2) 수. */
    val pk2: Int? = null,
    /** P에서 앞바퀴(PK3). */
    val wheel: ParkingWheel? = null,
    /** 마지막 P 뒤 사이드브레이크(basic.PB2 판정). */
    val handbrake: ParkingHandbrake? = null,

    // ---- 세워 두고 연습 ----
    val virtual: Boolean = false,
    /** 'success' · 'stopped'. */
    val result: String? = null,
    /** 기어 바꾼 수. */
    val switches: Int? = null,
    val wheelAngle: Double? = null,
    val wheelStraight: Boolean? = null,
    val touches: Int? = null,
    val restarts: Int? = null,
)

/** 주차 단계 하나를 마친 기록: 넘긴 신호·방법·시각(시작 뒤 sim초). */
@Serializable
data class ParkingStepLog(
    val n: Int = 0,
    /** 'press'·'stop'·'gearR'·'gearP'·'steerFull'·'steerStraight'. */
    val signal: String? = null,
    /** 'signal' 신호 · 'press' 화면 누름 · 'skip' 건너뛰기. */
    val by: String? = null,
    val t: Double? = null,
)

/** P에서 앞바퀴: 각도와 똑바른지. */
@Serializable
data class ParkingWheel(
    val steer: Double? = null,
    val straight: Boolean = false,
)

/** 주차 뒤 사이드브레이크: PB2 판정 결과와 채웠는지. */
@Serializable
data class ParkingHandbrake(
    val result: EventResult? = null,
    val on: Boolean? = null,
)

/**
 * 면허 모드 결과 (`rec.license`, license.js finish note, 결정사항 E T8-10·T8-14).
 */
@Serializable
data class LicenseResult(
    val courseId: String? = null,
    val placeId: String? = null,
    /** 코스 글자 A~D. */
    val letter: String? = null,
    val name: String? = null,
    /** 시험장 주변 코스인가(진행률에 셈). */
    val test: Boolean = false,
    /** 구간 수. */
    val segments: Int = 1,
    /** 닿은 구간. */
    val reached: Int = 1,
    val segmentsDone: Int = 0,
    /** 완주(마지막 구간까지 와서 내비 도착, 결정사항 E T8-5). */
    val complete: Boolean = false,
    /** 조수석이 빈 수(프로토타입은 false 또는 수 — 읽으면 수). */
    @Serializable(with = LenientIntSerializer::class)
    val alone: Int = 0,
    val aloneAt: List<LicenseAlone> = emptyList(),
    /** 장면 수(판정 기록이 없는 회차의 RV-5 장면). */
    val scenes: LicenseSceneCounts = LicenseSceneCounts(),
    /** 이 회차 뒤 완주한 코스 글자(기록에서 다시 열어도 그 회차 기준 진행, T8-14). */
    val coursesDoneAfter: List<String>? = null,
)

/** 조수석이 빈 순간. */
@Serializable
data class LicenseAlone(
    val t: Double? = null,
    val gear: Gear? = null,
    val speed: Double? = null,
    val segment: Int? = null,
)

/** 시험 기준 장면 수 (설계 6.12.3 매핑, license.js `SCENES`). */
@Serializable
data class LicenseSceneCounts(
    /** 방향지시등 없이 회전. */
    val noSignal: Int = 0,
    /** 회전 신호가 늦음. */
    val lateSignal: Int = 0,
    /** 급브레이크. */
    val hardBrake: Int = 0,
    /** 제한속도 초과. */
    val speed: Int = 0,
    /** 어린이보호구역 속도 초과. */
    val school: Int = 0,
    /** 급출발. */
    val hardStart: Int = 0,
)
