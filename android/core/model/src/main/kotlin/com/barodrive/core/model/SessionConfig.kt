package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 엔진 세션 설정 (설계 6.1.1 `createSession(cfg)`, 프로토타입 core.js `buildConfig`가 만드는 cfg — 'cfg' 머리 주석).
 * 앱은 엔진 파사드 `buildConfig(mode, profile, prep, caps)`로 만든다. 칸 이름은 프로토타입 JSON과 같다
 * (프로토타입 cfg의 `noInMode`는 [sessionNoInMode]와 같은 값이라 따로 두지 않는다 — [noInMode]로 읽을 수 있다).
 */
@Serializable
data class SessionConfig(
    /** 모드 1~8 (1~4단계, 5 자율, 6 주차, 7 언덕, 8 운전면허 연습). [Mode] 참고. */
    val mode: Int,
    /** 단계 1~4. 단계 모드가 아니면 null. */
    val stage: Int? = if (mode <= 4) mode else null,
    /** 이 모드의 몇 번째 회차인지(1부터) = 프로필 `sessionCount[mode]` + 1. */
    val sessionNoInMode: Int = 1,
    val coach: Coach = Coach.DAD,
    val honorific: Honorific = Honorific(),
    /** 항목별 이번 회차 전달 방식(설계 6.3.2 + 사용자 변경 + 도움 줄여 가기 + C1 처음 2회차). 없는 항목은 X로 본다. */
    val items: Map<String, Delivery> = emptyMap(),
    /** 항목별로 그 전달 방식이 정해진 까닭. */
    val itemReasons: Map<String, DeliveryReason> = emptyMap(),
    val weather: Weather = Weather(),
    /** 읽을 수 있는 신호. 세션 관리자가 지금 출처의 값으로 채운다. */
    val caps: SignalCaps = SignalCaps(),
    /** 지난 기록에서 가져온 값(G1 기준, K4 첫 회차 값, 이 모드 최근 회차). */
    val history: SessionHistory = SessionHistory(),
    /** 이 모드의 도움 줄여 가기 상태(프로필 `fading[mode]`). */
    val fading: Map<String, FadeState> = emptyMap(),
    /** 이 모드의 사용자 변경(프로필 `feedback.overrides[mode]`). */
    val overrides: Map<String, Delivery> = emptyMap(),
    /** 시드 난수의 씨앗. 엔진은 이것으로 만든 난수만 쓴다. buildConfig는 'baro:{프로필}:m{모드}:n{회차}:{코스·장소}'. */
    val seed: String = "baro:m$mode:n$sessionNoInMode",
    val profileId: String? = null,
    /** 팀 코스 id(c2-B …). */
    val courseId: String? = null,
    /** 내비 장소 id(mart …). */
    val placeId: String? = null,
    /** 자율 모드에서 고른 내 음성 피드백 설정 id. */
    val presetId: String? = null,
    /** 목적지 이름(장소 이름, 없으면 코스 이름) — 점수 대사 `{dest}`. */
    val dest: String? = null,
    /** 출발 전(PR-1)에 생긴 사건(L1·PB1·A5·주차 설정). 회차 기록 `events` 맨 앞에 `pre: true`로 들어간다. */
    val preEvents: List<JudgeEvent> = emptyList(),
    /** 모드 6 실제 주차 종류·방향(PK-1 → CR-1 → PR-1, 결정사항 E T5b-Q3). */
    val parking: ParkingSetup? = null,
    /** 세션을 시작한 벽시계 시각(ISO 지역 시각, 예: '2026-11-10T09:12:00+09:00'). 세션 연결이 넣는다. */
    val startedAt: String? = null,
    /** 처음부터 소리 끔. */
    val muted: Boolean = false,
    /** 세션이 시작된다고 보는 기어(추적기 처음 값). */
    val startGear: Gear = Gear.P,
    val flags: EngineFlags = EngineFlags(),
) {
    init {
        require(mode in 1..8) { "모드는 1~8이에요: $mode" }
        require(sessionNoInMode >= 1) { "회차 번호는 1부터예요: $sessionNoInMode" }
    }

    /** 프로토타입 cfg `noInMode`(= [sessionNoInMode]). */
    val noInMode: Int get() = sessionNoInMode
}

/** 지난 기록에서 가져온 값 (설계 6.1.1 cfg `history`). */
@Serializable
data class SessionHistory(
    /** G1 기준 = 프로필 첫 회차 G1 값(`g1Baseline`). 없으면 null(엔진은 0.8°로 본다). */
    val baselineG1: Double? = null,
    /** K4 기준 = 프로필 첫 회차 지표(`firstMetrics`). */
    val firstSessionMetrics: FirstMetrics? = null,
    /** 이 모드의 최근 회차 요약, 최신이 앞, 최대 5개. */
    val recentSessionsInMode: List<RecordSummary> = emptyList(),
)

/** 회차 요약 (프로토타입 core.js `summaryOf` — cfg.history.recentSessionsInMode의 한 줄). */
@Serializable
data class RecordSummary(
    val id: String? = null,
    val noInMode: Int? = null,
    val achievement: Double? = null,
    val qualified: Boolean = false,
    val fewEvents: Boolean = false,
    val tasks: List<TaskResult> = emptyList(),
    val metrics: SessionMetrics = SessionMetrics(),
    val items: Map<String, Delivery> = emptyMap(),
    val fadedNow: List<String> = emptyList(),
) {
    companion object {
        /** 회차 기록 → 요약(core.js `summaryOf`). */
        fun of(r: SessionRecord): RecordSummary = RecordSummary(
            id = r.id, noInMode = r.noInMode, achievement = r.achievement, qualified = r.qualified, fewEvents = r.fewEvents,
            tasks = r.tasks, metrics = r.metrics, items = r.items, fadedNow = r.fadedNow,
        )
    }
}

/** 엔진 스위치 (설계 12, cfg `flags`). */
@Serializable
data class EngineFlags(
    /** 안전3(C3·C6·J1)을 넣을지(결정-91, 프로토타입 `BaroData.flags.EXTRA_SAFETY_ITEMS`). 기본 켬. */
    val extraSafety: Boolean = true,
)

/** 코치 (프로토타입 copy.js `coaches`: 'sib'·'mom'·'dad', 설계 4.2 CO-1). [title]·[tone]은 화면 글. */
@Serializable
enum class Coach(val title: String, val tone: String) {
    /** 형제/자매(툴툴). 호칭을 붙이지 않는다(6.6.1). */
    @SerialName("sib")
    SIB("형제/자매", "툴툴"),

    /** 엄마(호들갑). */
    @SerialName("mom")
    MOM("엄마", "호들갑"),

    /** 아빠/배우자(다정). 프로토타입 기본 코치. */
    @SerialName("dad")
    DAD("아빠/배우자", "다정"),
}

/** 호칭 (프로토타입 `{type, text}`, 설계 6.6.1). */
@Serializable
data class Honorific(
    val type: HonorificType = HonorificType.NONE,
    /** type이 CUSTOM일 때 부르는 말(8자까지). */
    val text: String = "",
) {
    companion object {
        /** 직접 쓴 호칭 최대 글자 수(copy.js `honorifics.maxLength`). */
        const val MAX_LENGTH = 8
    }
}

/** 호칭 종류 (프로토타입 'daughter'·'son'·'custom'·'none'). [label]은 CO-2 칩 글자(copy.js `honorifics`). */
@Serializable
enum class HonorificType(val label: String) {
    @SerialName("daughter")
    DAUGHTER("우리 딸"),

    @SerialName("son")
    SON("우리 아들"),

    @SerialName("custom")
    CUSTOM("직접 쓰기"),

    @SerialName("none")
    NONE("부르지 않기"),
}
