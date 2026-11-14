package com.barodrive.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 준비 세션 (프로토타입 구현계획 1.5 `prep`, app/session.js `prepare`). CR-1·PR-1이 만들고 고친다. 기어가 P를 벗어나면
 * 엔진 파사드 `buildConfig(mode, profile, prep, caps)`로 세션 설정이 된다. 아직 기록에는 남지 않는다.
 */
@Serializable
data class PrepSession(
    val mode: Int,
    /** 고른 팀 코스(또는 courseRef 내 코스). */
    val courseId: String? = null,
    /** 고른 내비 장소(장소 내 코스·자율 목적지). */
    val placeId: String? = null,
    /** 자율: 고른 내 음성 피드백 설정 id. */
    val presetId: String? = null,
    /** 자율: 저장하지 않은 '새로 만들기' 값. */
    val presetItems: Map<String, Delivery>? = null,
    /** 회차 번호. null이면 프로필 `sessionCount[mode]` + 1. */
    val noInMode: Int? = null,
    /** PR-1 날씨 줄에서 고른 값. */
    val weather: PrepWeather = PrepWeather(),
    val checks: PrepChecks = PrepChecks(),
    /** 출발 전 판정·말 기록(L1·PB1·A5·주차 설정). 엔진이 회차 기록 맨 앞에 `pre: true`로 넣는다. */
    val preEvents: List<JudgeEvent> = emptyList(),
    /** 이번 준비 세션에서 이미 한 정차 중 말. */
    val lines: PrepLines = PrepLines(),
    /** 모드 6 실제 주차 종류·방향. */
    val parking: ParkingSetup? = null,
    /** 시작한 벽시계 시각(ISO). */
    val startedAt: String? = null,
    /** 좌석을 못 읽을 때 PR-1 "조수석에 누가 탔나요?" 답(설계 7.8). */
    val passengerAnswer: PassengerAnswer? = null,
    /**
     * 고른 진짜 차 내비 장소(내비에서 가져온 내 코스). 있으면 진짜 차에서 [내비로 보내고 출발]이 이 좌표로 안내를 부탁한다.
     * 프로토타입에 없는 칸이라 비어 있으면 JSON에 쓰지 않는다.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val navi: NaviPlace? = null,
)

/**
 * PR-1 날씨 고름 (프로토타입 weather.js — 엔진은 시작할 때 [snow]·[rain]·[severe]만 읽는다).
 */
@Serializable
data class PrepWeather(
    /** [오늘 눈 와요]. */
    val snow: Boolean = false,
    /** 아주 나쁜 날(시트). */
    @Serializable(with = SevereSerializer::class)
    val severe: Severe? = null,
    /** 비로 시작([rainBy]가 있을 때 참). */
    val rain: Boolean = false,
    /** 비로 시작하는 까닭: [오늘 비 와요] 칩 또는 시작 전 와이퍼 30초. */
    val rainBy: RainBy? = null,
    /** PR-1에 있는 동안 와이퍼가 이어서 켜진 sim초(꺼지면 0). */
    val wiperSec: Double = 0.0,
)

/** 비로 시작하는 까닭 (weather.js `rainBy`). */
@Serializable
enum class RainBy {
    @SerialName("chip")
    CHIP,

    @SerialName("wiper")
    WIPER,
}

/** PR-1 점검 (준비 세션 `checks`). */
@Serializable
data class PrepChecks(
    /** 사이드브레이크를 못 읽을 때 [풀었어요]를 누름. */
    val pbConfirmed: Boolean = false,
    /** 거울 점검(A5)을 체크함. */
    val mirror: Boolean = false,
)

/** 준비 세션에서 이미 한 정차 중 말 (준비 세션 `lines`). */
@Serializable
data class PrepLines(
    /** 오늘 과제를 읽음(p.tasks). */
    val tasksRead: Boolean = false,
    /** 동승자 안내(l1)를 말함. */
    val l1Said: Boolean = false,
)

/** 조수석 답 (demo.js `passengerAnswer`). */
@Serializable
enum class PassengerAnswer {
    @SerialName("aboard")
    ABOARD,

    @SerialName("alone")
    ALONE,
}

/** 주차 종류·방향 (프로토타입 cfg/prep `parking: { type, side }`, 결정사항 E T5a-1). 키는 'parallel-R'처럼. */
@Serializable
data class ParkingSetup(
    val type: ParkingType = ParkingType.PARALLEL,
    /** 주차 칸 쪽. */
    val side: TurnDir = TurnDir.RIGHT,
) {
    /** 'parallel-R' — 프로필 `parking.done` 키. */
    val key: String get() = "${type.code}-${side.code}"
}

/** 주차 종류 (프로토타입 'parallel' 평행 · 'back' 후방(후진) · 'front' 전방). [label]은 화면 글. */
@Serializable
enum class ParkingType(val code: String, val label: String) {
    @SerialName("parallel")
    PARALLEL("parallel", "평행"),

    @SerialName("back")
    BACK("back", "후방"),

    @SerialName("front")
    FRONT("front", "전방"),
}
