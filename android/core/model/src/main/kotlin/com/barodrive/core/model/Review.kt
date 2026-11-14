package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 리뷰 요약 (설계 6.10, 6.1.1 `review.compute(record, profile)`, 프로토타입 review.js `summarize`가 돌려주는 값).
 * 엔진이 회차 끝에 기록에 합친다([SessionRecord.withReview]). 리뷰 화면(RV-1·RV-2)은 기록에 합쳐진 값을 그린다.
 * K6(10분당 말 수·칭찬:교정)는 기록 `metrics`에 이미 있고, 끝 대사(rv.end.*)는 [achievement]로 고른다(결정사항 E T2d-3).
 */
@Serializable
data class ReviewSummary(
    /** 그 모드 과제(모드 문구 반영)별 성공·판정. 판정 = ok·almost·late·fail(excluded 뺌). */
    val tasks: List<TaskResult> = emptyList(),
    /** 성공 합 ÷ 판정 합(판정 0인 과제는 뺌, 결정-36), 소수 셋째 자리. 판정이 없으면 null. */
    val achievement: Double? = null,
    /** 판정 합 < 4. */
    val fewEvents: Boolean = true,
    /** 달성 ≥ 0.8이고 기록이 적지 않음. */
    val qualified: Boolean = false,
    /** 다음 과제 하나(6.10.3). 과제 없는 모드는 null. */
    val nextTask: NextTask? = null,
    /** 기록 `metrics`에 합칠 값 — 리뷰는 `k4`만 채운다. */
    val metrics: SessionMetrics = SessionMetrics(),
    /** 잘한 것(최대 3). */
    val good: List<ReviewLine> = emptyList(),
    /** 아쉬운 것(최대 3). */
    val bad: List<ReviewLine> = emptyList(),
    /** 항목별 성공·판정 수. */
    val itemStats: Map<String, ItemStat> = emptyMap(),
    /** 이번 회차로 내려갈 항목(cfg.fading·overrides로 미리 계산 — applyProgress가 확정). */
    val fadedNow: List<String> = emptyList(),
    /** RV-1 알림 줄 글. */
    val fadedNote: String? = null,
)

/** 과제 하나의 이번 회차 결과 (설계 6.4·6.10.1, 기록 `tasks[]` `{id, label, short, success, total}`). */
@Serializable
data class TaskResult(
    /** 과제 id('T-START' …). */
    val id: String,
    /** 과제 문구(모드 문구 반영). 미리 만든 상태의 요약 전 기록은 ''. */
    val label: String = "",
    /** 짧은 말(대사 `{task}` 자리). */
    val short: String = "",
    val success: Int = 0,
    val total: Int = 0,
) {
    /** 성공률. 판정이 없으면 null. */
    val rate: Double? get() = if (total > 0) success.toDouble() / total else null
}

/** 다음 과제 하나 (설계 6.10.3, 기록 `nextTask`). */
@Serializable
data class NextTask(
    val id: String,
    val label: String = "",
    val short: String = "",
    /** 고른 까닭. 미리 만든 상태처럼 없으면 null. */
    val reason: NextTaskReason? = null,
)

/** 다음 과제를 고른 까닭 (review.js `nextTaskOf`, 결정-39). */
@Serializable
enum class NextTaskReason {
    /** 안전 위반(E2 > E1). */
    @SerialName("safety")
    SAFETY,

    /** 성공률이 가장 낮은 과제. */
    @SerialName("lowest")
    LOWEST,

    /** 모두 100% — 판정이 가장 적었던 과제. */
    @SerialName("least")
    LEAST,

    /** 판정이 하나도 없음 — 모드 첫 과제. */
    @SerialName("first")
    FIRST,
}

/** 잘한 것·아쉬운 것 한 줄 (review.js `good`·`bad` `{id, text}`, 목업 s05 말투: '엑셀 살살 밟아서 출발하기 5/6번'). */
@Serializable
data class ReviewLine(
    /** 과제 id·묶음 id('T-START', 'G1', 'zones', 'school', 'E1-30', 'C2none' …). */
    val id: String,
    val text: String,
)

/** 항목 하나의 성공·판정 수 (도움 줄여 가기, 설계 6.7). */
@Serializable
data class ItemStat(
    val ok: Int = 0,
    val total: Int = 0,
)

/** K4 첫 회차와 비교 (설계 6.10.5, review.js `k4`). [first] = 아직 기준이 없음(첫 기록). */
@Serializable
data class K4Compare(
    val first: Boolean = true,
    val rows: List<K4Row> = emptyList(),
)

/** K4 줄 하나: 깜빡이 켠 거리 평균 · 급정지 비율 · 직진 핸들 흔들림. */
@Serializable
data class K4Row(
    /** 'c2Avg' · 'stop' · 'g1'. */
    val id: String,
    val label: String = "",
    /** '38m → 52m' · '3/8 → 1/8' · '첫날보다 30% 안정'. */
    val text: String = "",
    val from: Double? = null,
    val to: Double? = null,
    /** 좋아짐 true · 나빠짐 false · 비슷 null. */
    val better: Boolean? = null,
)

/**
 * 진행 반영 결과 (설계 6.10.4·5.2, review.js `applyProgress`가 돌려주고 기록 `progress`에 두는 값).
 * 같은 기록을 두 번 반영하지 않는다([applied]).
 */
@Serializable
data class ProgressResult(
    val applied: Boolean = true,
    val mode: Int = 1,
    /** 단계 모드면 그 단계, 아니면 null. */
    val stage: Int? = null,
    val qualified: Boolean = false,
    val qualifiedBefore: Int? = null,
    val qualifiedAfter: Int? = null,
    /** 통과에 필요한 통과 회차(2). */
    val need: Int = 2,
    val alreadyPassed: Boolean = false,
    /** 지난 단계를 다시 연습한 회차. */
    val pastStage: Boolean = false,
    /** 이번 회차로 지금 단계를 통과함. */
    val passed: Boolean = false,
    /** 이번 회차로 4단계를 통과함(졸업). */
    val graduated: Boolean = false,
    val nextStage: Int? = null,
    val passedAt: String? = null,
    val fadedNow: List<String> = emptyList(),
    val restoredNow: List<String> = emptyList(),
)

/** 진행 반영 뒤 값 (엔진 파사드 `review.applyProgress`): 바뀐 프로필, 진행 칸을 채운 기록, 결과. */
data class ProgressApplied(
    val profile: Profile,
    val record: SessionRecord,
    val result: ProgressResult,
)

/** PR-1 오늘 과제 한 줄 (결정-76, review.js `todayTasks`). 최대 3개. */
@Serializable
data class TodayTask(
    val id: String,
    val label: String = "",
    val short: String = "",
    /** 지난 회차 다음 과제에서 왔나, 모드 과제에서 왔나. */
    val from: TodayTaskFrom = TodayTaskFrom.MODE,
    /** 지난 회차 성공률(모드 과제만). */
    val rate: Double? = null,
)

/** 오늘 과제 출처. */
@Serializable
enum class TodayTaskFrom {
    @SerialName("next")
    NEXT,

    @SerialName("mode")
    MODE,
}
