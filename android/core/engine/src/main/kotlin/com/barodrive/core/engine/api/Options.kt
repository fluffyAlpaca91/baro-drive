package com.barodrive.core.engine.api

import com.barodrive.core.model.Delivery
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.MyCourse
import com.barodrive.core.model.SignalCaps
import java.time.LocalDate

/*
 * 엔진 파사드 함수에 넘기는 선택 값들. 이름과 뜻은 프로토타입 함수 인자(opts·ctx) 그대로다.
 */

/** [BaroEngine.resolveItems]의 opts (core.js `resolveItems(mode, profile, { presetId, presetItems, noInMode })`). */
data class ResolveOptions(
    /** 자율: 고른 내 설정 id(프로필 `myPresets`에서 찾음). */
    val presetId: String? = null,
    /** 자율: 저장하지 않은 '새로 만들기' 값(있으면 presetId보다 앞섬). */
    val presetItems: Map<String, Delivery>? = null,
    /** 회차 번호. null이면 프로필 `sessionCount[mode]` + 1. */
    val noInMode: Int? = null,
)

/** [EngineFeedback.resolve]의 ctx (설계 6.1.1 `feedback.resolve(mode, item, { overrides, fading, sessionNoInMode, preset })`). */
data class FeedbackContext(
    /** 그 모드의 사용자 변경. */
    val overrides: Map<String, Delivery> = emptyMap(),
    /** 그 모드의 도움 줄여 가기 상태. */
    val fading: Map<String, FadeState> = emptyMap(),
    val sessionNoInMode: Int? = null,
    /** 자율: 고른 설정 값. */
    val preset: Map<String, Delivery>? = null,
)

/**
 * [EngineLines.render]의 opts (core.js `renderLine(..., { rotation, peek, index })`).
 * 같은 키의 문장이 2개면 번갈아 쓴다 — [rotation]은 키 → 순번(세션마다 따로, null이면 공용 순번).
 */
class RenderOptions(
    val rotation: MutableMap<String, Int>? = null,
    /** 순번을 올리지 않고 들여다보기. */
    val peek: Boolean = false,
    /** 이 번째 문장을 고름(순번을 올리지 않음). */
    val index: Int? = null,
)

/** [EngineCourses.rank]의 opts (core.js `rankCourses(mode, { town, congestionSeed, myCourses, tripOk, coursesDone })`). */
data class RankOptions(
    /** 동네 id(시연 패널 '동네'). */
    val town: String = "home",
    /** 혼잡 시드(1 이하·null = 코스 기본값 그대로 = 목업 s11). */
    val congestionSeed: Int? = null,
    /** 프로필 내 코스. */
    val myCourses: List<MyCourse> = emptyList(),
    /** 혼잡도(`requestTrip`)를 받았나. 못 받으면 가까운 순(결정-81). caps.trip이 false면 false로. */
    val tripOk: Boolean = true,
    /** 모드 8: 완주한 코스 글자(진행 표시 `ran`). */
    val coursesDone: List<String> = emptyList(),
)

/**
 * [EngineScore.fromCounts]의 횟수 묶음 (score.js `fromCounts({ late, almost, c4, c5, bf, e1, g1 | g1Points, f2, off | caps })`).
 * 판정 없이 횟수로 점수를 만든다 — 데모 상태가 실제 회차와 같은 모양을 만들 때.
 */
data class ScoreCounts(
    val late: Int = 0,
    val almost: Int = 0,
    val c4: Int = 0,
    val c5: Int = 0,
    /** 급출발·급정지 합. */
    val bf: Int = 0,
    /** 제한속도 초과 구역 수. */
    val e1: Int = 0,
    val g1: G1Input? = null,
    /** G1을 값 대신 깎일 점수로(값 = 0.8 × (1 + n/20)). */
    val g1Points: Int? = null,
    /** 감점하지 않은 위험 회피 급제동 수. */
    val f2: Int = 0,
    /** 꺼진 항목(또는 [caps]로 정함). */
    val off: List<String>? = null,
    val caps: SignalCaps? = null,
)

/** G1 값과 기준. */
data class G1Input(
    val value: Double? = null,
    val base: Double? = null,
    val baseFirst: Boolean = false,
)

/** [EngineLicense.startCheck] 입력 (license.js `startCheck({ passenger, checks, issueDate, today })`). */
data class LicenseStartInput(
    /** 조수석: true 탐 · false 빔 · null 못 읽음. */
    val passenger: Boolean?,
    val checks: LicenseChecks = LicenseChecks(),
    /** 발급일 'YYYY-MM-DD'. */
    val issueDate: String? = null,
    /** 오늘(앱이 넘김). */
    val today: LocalDate? = null,
)

/** LC-1 본인 체크 셋 (license.js 화면 `CHECKS`). */
data class LicenseChecks(
    /** 함께 탄 분은 면허를 받은 지 2년이 넘었고, 지금 면허정지가 아니에요. */
    val companion: Boolean = false,
    /** '주행연습' 표지를 붙였어요. */
    val sign: Boolean = false,
    /** 주행연습 목적으로만 운전해요. */
    val purpose: Boolean = false,
)
