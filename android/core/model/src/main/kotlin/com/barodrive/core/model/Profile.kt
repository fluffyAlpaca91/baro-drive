package com.barodrive.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 앱 상태 전체 (설계 8.1 `baro.app.v1`, 프로토타입 app/store.js `defaults`). 데모 프리셋(설계 8.3)은 이 모양을 만든다.
 * 앱은 저장소(Room·DataStore)에 나눠 둬도 되지만, 들고 나는 JSON은 이 모양이다.
 */
@Serializable
data class AppState(
    val version: Int = 1,
    val activeProfileId: String = "p1",
    val ui: UiMemory = UiMemory(),
    /** 프로필 id → 프로필. */
    val profiles: Map<String, Profile> = mapOf("p1" to Profile()),
) {
    /** 지금 프로필(없으면 그 id의 처음 상태). */
    val activeProfile: Profile get() = profiles[activeProfileId] ?: Profile.blank(activeProfileId)
}

/** 새로고침 복원용 마지막 정차 화면 (설계 3.3, 결정사항 E T1a-7). */
@Serializable
data class UiMemory(
    val lastScreen: String? = null,
    val lastParams: JsonObject = JsonObject(emptyMap()),
)

/**
 * 운전자 프로필 (설계 8.1, 프로토타입 store.js `defaultProfile`). 기본값은 온보딩 전 처음 상태다.
 * 모드별 칸(`sessionCount`·`feedback.overrides`·`fading`)의 키는 모드 번호(JSON에서는 "1"~"8").
 */
@Serializable
data class Profile(
    val id: String = "p1",
    val name: String = "운전자 1",
    /** 만든 시각(ISO 지역 시각). */
    val createdAt: String? = null,
    val onboarding: Onboarding = Onboarding(),
    /** 기록 저장·공유 동의(ST-4). */
    val consent: Consent = Consent(),
    val coach: Coach = Coach.DAD,
    val honorific: Honorific = Honorific(),
    /** 단계 진행(1~4단계 통과·졸업). */
    val progress: StageProgress = StageProgress(),
    /** 모드별 끝낸 회차 수(다음 회차 번호 = 이 값 + 1). 세워 두고 연습은 세지 않는다. */
    val sessionCount: Map<Int, Int> = Mode.numbers.associateWith { 0 },
    /** 음성 피드백 설정(FB-1 모드별 사용자 변경). */
    val feedback: FeedbackSettings = FeedbackSettings(),
    /** 도움 줄여 가기 상태: 모드 → 항목 → 상태(설계 6.7). */
    val fading: Map<Int, Map<String, FadeState>> = emptyMap(),
    /** 자율 모드 '내 음성 피드백 설정'(FB-2) — 예시 2개 + 내가 만든 것. */
    val myPresets: List<FeedbackPreset> = emptyList(),
    /** 마지막으로 고른 내 설정 id. */
    val lastPresetId: String? = null,
    /** 내 코스(ST-3, CR-1 별·CR-2 가져오기·리뷰 '내 코스로 저장'). */
    val myCourses: List<MyCourse> = emptyList(),
    /** 주차 연습 진행(세워 두고 연습 성공한 종류·방향). */
    val parking: ParkingProgress = ParkingProgress(),
    /** 연습면허 발급일과 완주한 시험장 코스. */
    val license: LicenseInfo = LicenseInfo(),
    /** G1 기준 = 처음 나온 G1 값(30회차를 넘어 기록이 지워져도 남는다, 결정-90). */
    val g1Baseline: Double? = null,
    /** K4 기준 = 지표마다 처음 나온 값. */
    val firstMetrics: FirstMetrics? = null,
    /** 회차 기록, 최신이 앞, 최대 30개. */
    val sessions: List<SessionRecord> = emptyList(),
    /** 네트워크가 끊겨 보내지 못한 요약(기록 id). */
    val shareQueue: List<String> = emptyList(),
) {
    /** [mode]의 다음 회차 번호(sessionCount + 1). */
    fun nextNoInMode(mode: Int): Int = (sessionCount[mode] ?: 0) + 1

    companion object {
        /** 기록 수 상한(설계 8.1). */
        const val MAX_RECORDS = 30

        /** 처음 상태 프로필(store.js `defaultProfile`) — 이름은 'p2' → '운전자 2'. */
        fun blank(id: String, name: String? = null, createdAt: String? = null): Profile =
            Profile(id = id, name = name ?: nameFor(id), createdAt = createdAt)

        /** 'p3' → '운전자 3'(store.js `nameFor`). */
        fun nameFor(id: String): String = Regex("^p(\\d+)$").find(id)?.groupValues?.get(1)?.let { "운전자 $it" } ?: "운전자"
    }
}

/** 온보딩 결과 (ON-1~ON-5, 설계 5.1). */
@Serializable
data class Onboarding(
    val done: Boolean = false,
    val answers: OnboardingAnswers = OnboardingAnswers(),
    /** 추천 시작 단계 1~3. */
    val recommendedStage: Int? = null,
)

/** ON-3 처음 질문 답. */
@Serializable
data class OnboardingAnswers(
    val licenseAge: LicenseAge? = null,
    val monthlyDrives: MonthlyDrives? = null,
)

/** 면허 딴 지 얼마나 (onboarding.js). [label]은 ON-3 칩 글. */
@Serializable
enum class LicenseAge(val label: String) {
    @SerialName("learner")
    LEARNER("연습면허예요"),

    @SerialName("under1")
    UNDER_1("1년이 안 됐어요"),

    @SerialName("1-3")
    YEARS_1_3("1~3년 됐어요"),

    @SerialName("over3")
    OVER_3("3년 넘었어요"),
}

/** 지난달 운전 횟수 (onboarding.js). */
@Serializable
enum class MonthlyDrives(val label: String) {
    @SerialName("0")
    NONE("안 했어요"),

    @SerialName("1-3")
    FEW("1~3번"),

    @SerialName("4-8")
    SOME("4~8번"),

    @SerialName("9+")
    MANY("9번 넘게"),
}

/** 동의 (설계 8.1 `consent`, ST-4). [record]는 필수 — 끄면 기록을 지우고 처음으로(결정사항 E T9-4). */
@Serializable
data class Consent(
    val record: Boolean = false,
    val family: Boolean = false,
    val academy: Boolean = false,
    val insurer: Boolean = false,
    /** 받는 사람 이름(10자, 비우면 '가족'). */
    val familyName: String = "가족",
)

/** 단계 진행 (설계 8.1 `progress`, 6.10.4). */
@Serializable
data class StageProgress(
    /** 지금 단계 1~4. */
    val currentStage: Int = 1,
    /** 열린 단계까지(1~4). */
    val unlockedStage: Int = 1,
    /** 졸업한 때(4단계 통과). */
    val graduatedAt: String? = null,
    /** 단계 1~4 → 통과 회차 수·통과한 때. */
    val stages: Map<Int, StageState> = (1..4).associateWith { StageState() },
)

/** 단계 하나의 통과 상태. 통과 회차(누적)가 2가 되면 통과. */
@Serializable
data class StageState(
    val qualified: Int = 0,
    /** 통과한 때(ISO, 없으면 회차 id 등). 아직이면 null. */
    val passedAt: String? = null,
)

/** 음성 피드백 설정 (설계 8.1 `feedback`): 모드 → 항목 → 사용자가 고른 전달 방식(FB-1). 기본값과 같으면 지운다. */
@Serializable
data class FeedbackSettings(
    val overrides: Map<Int, Map<String, Delivery>> = emptyMap(),
)

/** 자율 '내 음성 피드백 설정' 하나 (FB-2, 설계 8.1 `myPresets[]`, 결정-47). 최대 개수·이름 규칙은 화면 몫. */
@Serializable
data class FeedbackPreset(
    /** 'ex-commute'·'ex-rain'(예시) · 'my-1' …. */
    val id: String,
    val name: String = "",
    /** 예시 설정(값·이름을 바꾸면 false). */
    val example: Boolean = false,
    /** 예시 설명 줄. */
    val desc: String? = null,
    /** FB-2 항목 → 전달 방식. */
    val items: Map<String, Delivery> = emptyMap(),
)

/**
 * 내 코스 하나 (설계 8.1 `myCourses[]`): 팀 코스를 별로 저장([courseRef]) 또는 내비 장소를 가져옴([placeId] + [town]).
 * 진짜 차에서는 차 내비의 장소를 좌표째 가져온다([navi] + [town]).
 */
@Serializable
data class MyCourse(
    val id: String,
    val name: String = "",
    val mode: Int = 1,
    val source: CourseSource = CourseSource.SAVED,
    /** 팀 코스 id. */
    val courseRef: String? = null,
    /** 내비 장소 id. */
    val placeId: String? = null,
    /** 장소 코스의 동네('home'·'moved'). 없으면 'home'. */
    val town: String? = null,
    /** 예시로 넣은 줄(데모). */
    val example: Boolean = false,
    /** 저장한 때(ISO). */
    val savedAt: String? = null,
    /** 진짜 차 내비에서 가져온 장소(좌표). 프로토타입에 없는 칸이라 비어 있으면 JSON에 쓰지 않는다. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val navi: NaviPlace? = null,
)

/** 주차 연습 진행 (설계 8.1 `parking`): 'parallel-R' → 세워 두고 연습 성공. */
@Serializable
data class ParkingProgress(
    val done: Map<String, Boolean> = emptyMap(),
)

/** 연습면허 정보 (설계 8.1 `license`, LC-2). */
@Serializable
data class LicenseInfo(
    /** 발급일 'YYYY-MM-DD'. 없으면 null. */
    val issueDate: String? = null,
    /** 완주한 시험장 코스 글자, A~D 순서. */
    val coursesDone: List<String> = emptyList(),
)

/** K4 기준 = 지표마다 처음 나온 값 (설계 8.1 `firstMetrics`, 결정사항 E T2a-14). */
@Serializable
data class FirstMetrics(
    val c2Avg: Double? = null,
    val stopFailRate: Double? = null,
    val g1: Double? = null,
    /** 급정지 횟수 기준(있으면 K4 줄을 '3/8 → 1/8'로). */
    val stopTotal: Int? = null,
    val stopFail: Int? = null,
)
