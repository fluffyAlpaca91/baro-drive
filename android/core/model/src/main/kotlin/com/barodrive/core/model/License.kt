package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 운전면허 연습 모드 계산 결과 (설계 6.12.3, 4.2 LC-1·LC-2·RV-5, 프로토타입 detectors/license.js `BaroEngine.license`).
 * 엔진 파사드 `license.*`가 돌려준다. 오늘 날짜는 앱이 넘긴다(엔진은 시계를 쓰지 않음).
 */

/** 연습면허 유효기간 상태 (license.js `status`). 유효기간 마지막 날 = 발급일 + 1년 − 1일. */
@Serializable
data class LicenseStatus(
    val issueDate: String? = null,
    /** 마지막 날 'YYYY-MM-DD'. */
    val expiresOn: String? = null,
    /** 오늘부터 마지막 날까지 남은 날(마지막 날 당일 0). */
    val days: Int? = null,
    /** 'D-141' · 'D-day'. 지났으면 null. */
    val dday: String? = null,
    val expired: Boolean = false,
    /** 남은 날 30일 이하(주의 색). */
    val soon: Boolean = false,
    /** 발급일이 오늘보다 뒤. */
    val future: Boolean = false,
    val state: LicenseState = LicenseState.NONE,
    /** 화면용 발급일 글. */
    val issueText: String? = null,
    /** 화면용 마지막 날 글. */
    val expiryText: String? = null,
)

/** 유효기간 상태 이름. */
@Serializable
enum class LicenseState {
    /** 발급일 없음. */
    @SerialName("none")
    NONE,

    @SerialName("ok")
    OK,

    @SerialName("soon")
    SOON,

    @SerialName("expired")
    EXPIRED,
}

/** LC-1 시작 판단 (license.js `startCheck`). [blocker]가 null이면 시작할 수 있다. */
@Serializable
data class LicenseStartCheck(
    val ok: Boolean = false,
    val seat: SeatState = SeatState.UNKNOWN,
    /** 체크한 수 0~3(동승자 · 표지 · 목적). */
    val checked: Int = 0,
    val allChecked: Boolean = false,
    val expired: Boolean = false,
    val status: LicenseStatus = LicenseStatus(),
    val blocker: StartBlocker? = null,
    /** 하단 버튼 글. */
    val label: String = "",
    /** 비활성 버튼을 눌렀을 때 토스트. */
    val hint: String? = null,
)

/** 조수석 상태(좌석을 못 읽으면 unknown — 이 차에서는 시작 못 함, 설계 7.8). */
@Serializable
enum class SeatState {
    @SerialName("on")
    ON,

    @SerialName("off")
    OFF,

    @SerialName("unknown")
    UNKNOWN,
}

/** 시작을 막는 까닭(먼저인 것 하나). */
@Serializable
enum class StartBlocker {
    @SerialName("expired")
    EXPIRED,

    @SerialName("seat")
    SEAT,

    @SerialName("checks")
    CHECKS,

    @SerialName("passenger")
    PASSENGER,
}

/** RV-5 코스 진행 카드 첫 줄 (license.js `courseResult`). */
@Serializable
data class LicenseCourseResult(
    val courseId: String? = null,
    val letter: String? = null,
    val name: String? = null,
    val test: Boolean = false,
    /** 구간 수. */
    val count: Int = 1,
    val reached: Int = 1,
    val segmentsDone: Int = 0,
    val complete: Boolean = false,
    /** '시험장 주변 C 코스를 끝까지 달렸어요' · '2구간까지 달렸어요(완주 아님)'. */
    val text: String = "",
)

/** 시험장 코스 진행 (license.js `progress`: '4개 코스 중 2개'). */
@Serializable
data class LicenseProgress(
    val done: Int = 0,
    val total: Int = 4,
    val letters: List<LicenseLetter> = emptyList(),
    val text: String = "",
)

/** 코스 글자 하나의 완주 여부. */
@Serializable
data class LicenseLetter(
    val letter: String,
    val courseId: String? = null,
    val done: Boolean = false,
)

/** 완주 반영 결과 (license.js `applyCourseDone`). 프로필에 넣는 것은 부르는 쪽(엔진 파사드는 바뀐 프로필을 함께 준다). */
@Serializable
data class CourseDoneUpdate(
    val before: List<String> = emptyList(),
    val after: List<String> = emptyList(),
    /** 이번에 새로 완주한 글자. */
    val added: String? = null,
    val progress: LicenseProgress = LicenseProgress(),
)

/** 시험 기준으로 다시 볼 장면 한 줄 (license.js `scenes`: '급브레이크 1번' — 횟수만, 점수·감점 말 없음, 결정-56). */
@Serializable
data class LicenseScene(
    /** 'noSignal'·'lateSignal'·'hardBrake'·'speed'·'school'·'hardStart'. */
    val id: String,
    val label: String = "",
    val n: Int = 0,
    val text: String = "",
)

/** RV-5가 그릴 값 묶음 (license.js `review`). */
@Serializable
data class LicenseReview(
    val course: LicenseCourseResult = LicenseCourseResult(),
    val progress: LicenseProgress = LicenseProgress(),
    val coursesDone: List<String> = emptyList(),
    val scenes: List<LicenseScene> = emptyList(),
    val sceneTitle: String = "",
    val sceneSub: String = "",
    val sceneNone: String = "",
    /** 조수석이 빈 수. */
    val alone: Int = 0,
    val aloneText: String? = null,
)
