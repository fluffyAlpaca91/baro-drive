package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 모드 전용 화면 힌트 (엔진 출력 `hints.hill`·`hints.parking`·`hints.license`). 판정 규칙은 엔진에만 있고 화면은 이 값만 그린다
 * (설계 1.4 '판정은 엔진에서'). 모양은 hill.js `view` · parking.js `view` · license.js `hint` 그대로(결정사항 E T6-7·T5b-5·T8-10).
 */

/** 언덕 화면 값 (hill.js `view`). 바뀔 때만 새 값([seq]가 오른다). */
@Serializable
data class HillHint(
    val practice: HillPractice = HillPractice.UP,
    /** 지금 단계, 1부터. */
    val step: Int = 1,
    /** 이 연습의 단계 수(오르막 4 · 내리막 3 · 언덕 주차 3). */
    val steps: Int = 1,
    val done: Boolean = false,
    /** [했어요] 버튼을 보임. */
    val tap: Boolean = false,
    /** 단계 문장 대사 키('hl1.up.2' …). */
    val key: String? = null,
    /** 대사 변수(언덕 주차 2단계 `{side}`). */
    val vars: JsonObject? = null,
    /** 오르막 3단계 응원 말풍선(말하지 않음, 'hl1.cheer'). */
    val cheer: Boolean = false,
    val up: HillUpHint = HillUpHint(),
    val down: HillDownHint = HillDownHint(),
    val park: HillParkHint = HillParkHint(),
    /** 지금 기어가 P. */
    val gearP: Boolean = false,
    val seq: Int = 0,
)

/** 언덕 힌트 — 오르막 출발. */
@Serializable
data class HillUpHint(
    val attempts: Int = 0,
    val rollbacks: Int = 0,
    /** 지금 시도에서 밀림. */
    val rolled: Boolean = false,
    val rollOff: Boolean = false,
)

/** 언덕 힌트 — 내리막 감속. */
@Serializable
data class HillDownHint(
    val presses: Int = 0,
    val holds: Int = 0,
    /** '계속 밟지 말고 나눠서 밟아요' 줄. */
    val warn: Boolean = false,
    val braking: Boolean = false,
    /** 내비 구간에 묶임. */
    val bound: Boolean = false,
    val runs: Int = 0,
    val lastOk: Boolean? = null,
)

/** 언덕 힌트 — 언덕 주차. */
@Serializable
data class HillParkHint(
    val situation: HillSlope? = null,
    val need: TurnDir? = null,
    val wheel: HillWheelHint? = null,
    /** 핸들을 못 읽어 화면 누름으로 넘김. */
    val steerOff: Boolean = false,
    val result: HillParkResult? = null,
)

/** 지금 앞바퀴: 쪽(똑바로면 null)·각도·맞는지. */
@Serializable
data class HillWheelHint(
    val side: TurnDir? = null,
    val deg: Double = 0.0,
    val ok: Boolean? = null,
)

/** 실제 주차 안내 화면 값 (parking.js `view`). 단계·결과가 바뀔 때마다 새 값. */
@Serializable
data class ParkingHint(
    val type: ParkingType = ParkingType.PARALLEL,
    val side: TurnDir = TurnDir.RIGHT,
    /** 'parallel-R'. */
    val key: String = "",
    /** 단계 수. */
    val total: Int = 0,
    /** 지금 단계 번호, 1부터. */
    val step: Int = 1,
    /** 지금 단계 index, 0부터. */
    val index: Int = 0,
    val last: Boolean = false,
    val done: Boolean = false,
    /** 단계 문장 대사 키('pk1.parallel.4'). */
    val line: String? = null,
    /** 대사 변수(`{dir}`). */
    val vars: JsonObject? = null,
    /** 단계 문장(표 A-5). */
    val text: String? = null,
    /** 넘김 신호('press'·'stop'·'gearR'·'gearP'·'steerFull'·'steerStraight'). */
    val signal: String? = null,
    /** 핸들을 돌릴 쪽(steerFull만). */
    val dir: TurnDir? = null,
    /** 'signal' 신호로 넘김 · 'press' 화면 누름. */
    val via: String? = null,
    /** '다음 단계 신호' 줄(목업 s08b). */
    val signalText: String? = null,
    /** 단계 그림. */
    val visual: ParkingVisual? = null,
    /** 기준점 꿀팁 번호(0·1 = 표 A-6 첫째·둘째 문장, 2 = 바퀴 문장). */
    val tip: Int? = null,
    val pk2: Int = 0,
    val startedAt: Double? = null,
    val stepAt: Double? = null,
    val doneAt: Double? = null,
)

/** 주차 단계 그림 (parking 단계 표 `visual`). */
@Serializable
data class ParkingVisual(
    /** 'hazard' · 'wheel' · 'gear' · 'final'. */
    val kind: String = "",
    /** 그림 속 핸들 회전°(오른쪽 +). */
    val rot: Double? = null,
    val gear: Gear? = null,
    val label: String? = null,
)

/** 면허 모드 구간 진행 힌트 (license.js `hint`). */
@Serializable
data class LicenseHint(
    val courseId: String? = null,
    val letter: String? = null,
    val name: String? = null,
    /** 지금 구간, 1부터. */
    val segment: Int = 1,
    val count: Int = 1,
    /** 마친 구간 수. */
    val done: Int = 0,
    val complete: Boolean = false,
    /** '구간 2/3'. */
    val text: String = "",
    /** '구간 2/3 · 시험장 주변 C 코스'. */
    val line: String = "",
    /** '면허 · 구간 2/3'. */
    val head: String = "",
)

/** 단계 넘김 신호 이름 (parking 단계 표 `SIGNALS`, 설계 6.12.1). 힌트·기록에는 글자로 둔다 — 비교할 때 [code]. */
@Serializable
enum class ParkingSignal(val code: String) {
    @SerialName("press")
    PRESS("press"),

    @SerialName("stop")
    STOP("stop"),

    @SerialName("gearR")
    GEAR_R("gearR"),

    @SerialName("gearP")
    GEAR_P("gearP"),

    @SerialName("steerFull")
    STEER_FULL("steerFull"),

    @SerialName("steerStraight")
    STEER_STRAIGHT("steerStraight"),
}
