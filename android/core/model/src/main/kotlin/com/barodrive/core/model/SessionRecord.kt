package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 회차 기록 (설계 8.1 `SessionRecord`, 프로토타입 core.js `finish`·`finalize` + review.js `summarize`·`applyProgress`가 더한 칸,
 * 결정사항 E T1c-9·T2a-6). 엔진 `finish`가 만들고 앱이 JSON으로 저장한다. 칸 이름은 프로토타입 JSON과 같다
 * (Kotlin 이름이 다른 곳은 [SerialName] — `simSec` → [durationSec]).
 *
 * 리뷰·통과·K4·도움 줄여 가기는 `events`가 아니라 회차 요약(`tasks`, `metrics`, `f2`, `itemStats`)으로 계산한다.
 * 그래서 미리 만든 상태(데모 프리셋)는 요약만 넣어도 된다(설계 8.1 아래 줄).
 */
@Serializable
data class SessionRecord(
    /** 'sess-20261110-0912'. 저장소가 붙인다(같으면 '-2' …). */
    val id: String? = null,
    /** 기록 종류. 보통 회차는 null, 세워 두고 연습(PK-2)은 [RecordKind.VIRTUAL_PARKING](결정사항 E T5a-7). */
    val kind: RecordKind? = null,
    val mode: Int,
    val stage: Int? = null,
    /** 이 모드의 몇 번째 회차. 세워 두고 연습은 null. */
    val noInMode: Int? = null,
    val courseId: String? = null,
    val placeId: String? = null,
    val presetId: String? = null,
    /** 시작한 벽시계 시각(ISO 지역 시각, 예: '2026-11-10T09:12:00+09:00'). */
    val startedAt: String? = null,
    /** 세션 처음부터 끝까지 sim초(8.1 `simSec`). */
    @SerialName("simSec")
    val durationSec: Double = 0.0,
    /** 기어가 P가 아닌 sim초(K6 분모, 결정사항 E T1c-9). */
    val driveSec: Double = 0.0,
    /** 달린 거리 m(트립 거리 변화, 안 움직이면 속도를 쌓은 값). */
    val distanceM: Double = 0.0,
    /** 내비 도착 표시가 있은 뒤 끝났는지. */
    val arrived: Boolean = false,
    /** 회차 날씨. 세워 두고 연습은 null. */
    val weather: SessionWeather? = null,
    val caps: SignalCaps = SignalCaps(),
    /** 이 회차에 실제로 쓴 전달 방식. */
    val items: Map<String, Delivery> = emptyMap(),
    /** 판정 기록(설계 6.9). 출발 전 사건이 맨 앞(`pre: true`). 미리 만든 상태는 비어 있을 수 있다. */
    val events: List<JudgeEvent> = emptyList(),
    /** 말 기록 — 말한 것과 막힌 것을 시간 순으로(설계 6.9). [spoken]·[blocked]로 나눠 볼 수 있다. */
    val utterances: List<UtteranceLog> = emptyList(),
    /** 그 모드 과제별 성공·판정(설계 6.4·6.10.1). */
    val tasks: List<TaskResult> = emptyList(),
    /** 회차 달성률 = 성공 합 ÷ 판정 합(판정 0인 과제는 뺌), 소수 셋째 자리. 판정이 없으면 null. */
    val achievement: Double? = null,
    /** 통과 회차로 셈(달성 ≥ 0.8이고 기록이 적지 않음). */
    val qualified: Boolean = false,
    /** 판정 합 < 4 → '기록이 적은 회차'(통과 회차로 안 셈, 결정-38). */
    val fewEvents: Boolean = true,
    /** 다음 과제 하나(설계 6.10.3). 과제 없는 모드는 null. */
    val nextTask: NextTask? = null,
    val metrics: SessionMetrics = SessionMetrics(),
    /** 위험 회피 급제동(F2) — 본인 확인용(설계 6.3.1·결정-54). */
    val f2: List<F2Check> = emptyList(),
    /** 자율 모드 점수(설계 6.11). 그 밖은 null. */
    val score: ScoreResult? = null,
    /** 언덕 모드 결과(rec.hill). */
    val hill: HillResult? = null,
    /** 주차 결과(rec.parking — 실제 주차 안내 또는 세워 두고 연습). */
    val parking: ParkingResult? = null,
    /** 면허 모드 결과(rec.license). */
    val license: LicenseResult? = null,
    /** 리뷰 팁 줄(날씨 회차 '빗길 회차예요. …', E3 내비 과속 줄). */
    val tips: List<ReviewTip> = emptyList(),
    /** 그 밖 검출기 note(`ctx.note`)를 키 그대로(결정사항 E T1c-9 `rec.notes`). 없으면 null. */
    val notes: JsonObject? = null,
    /** 잘한 것(최대 3, 설계 6.10.2). */
    val good: List<ReviewLine> = emptyList(),
    /** 아쉬운 것(최대 3). X 항목은 넣지 않는다. */
    val bad: List<ReviewLine> = emptyList(),
    /**
     * 항목별 성공·판정 수(도움 줄여 가기용, 결정사항 E T2a-6). 없으면 null — 그때는 `events`에서 센다
     * (빈 맵과 null은 뜻이 다르다: fading.js `stats`).
     */
    val itemStats: Map<String, ItemStat>? = null,
    /** 이번 회차로 도움 줄여 가기가 내려간 항목. */
    val fadedNow: List<String> = emptyList(),
    /** RV-1 알림 줄(예: '출발·정지는 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.'). 없으면 null. */
    val fadedNote: String? = null,
    /** 진행 반영 결과(applyProgress — 같은 기록을 두 번 반영하지 않게). 반영 전이면 null. */
    val progress: ProgressResult? = null,
    /** 가족에게 요약 보내기 상태. */
    val share: ShareState = ShareState(),
) {
    /** 실제로 말한 것만, 시간 순(말 기록 중 `spoken = true`). */
    val spoken: List<Utterance> get() = utterances.filter { it.spoken }.map { it.toUtterance() }

    /** 막힌 말과 이유, 시간 순(말 기록 중 `spoken = false`). */
    val blocked: List<BlockedUtterance>
        get() = utterances.filter { !it.spoken }.map { BlockedUtterance(it.toUtterance(), it.reason ?: BlockReason.EXPIRED) }

    /** 세워 두고 연습(PK-2) 기록인가 — 회차 수·진행에 넣지 않는다. */
    val isVirtualParking: Boolean get() = kind == RecordKind.VIRTUAL_PARKING || parking?.virtual == true

    /** 확인 안 한 위험 회피 급제동 수(홈 알림 줄). */
    val uncheckedF2: Int get() = f2.count { it.choice == null }

    /**
     * 리뷰 요약을 합친 사본(core.js finish의 `runHooks`: metrics는 칸끼리 합치고 나머지는 바꾼다).
     * 리뷰 요약에 없는 칸(점수·모드 결과 …)은 그대로 둔다.
     */
    fun withReview(s: ReviewSummary): SessionRecord = copy(
        tasks = s.tasks, achievement = s.achievement, fewEvents = s.fewEvents, qualified = s.qualified, nextTask = s.nextTask,
        metrics = metrics.copy(k4 = s.metrics.k4 ?: metrics.k4), good = s.good, bad = s.bad, itemStats = s.itemStats,
        fadedNow = s.fadedNow, fadedNote = s.fadedNote,
    )
}

/** 기록 종류 (프로토타입 `rec.kind`). */
@Serializable
enum class RecordKind {
    /** 세워 두고 연습(가상 주차, PK-2). */
    @SerialName("virtualParking")
    VIRTUAL_PARKING,
}

/**
 * 회차 지표 (설계 8.1 `metrics`). K4 재료는 검출기가 finish에서 채우고(결정사항 E T2a-6), K6는 코어가, K4 비교는 리뷰가 더한다.
 * 모르면 null(세워 두고 연습처럼 `{}`인 기록).
 */
@Serializable
data class SessionMetrics(
    /** C2 깜빡이 켠 거리 평균 m(IC·재탐색·건너뛴 회전 뺌). */
    val c2Avg: Double? = null,
    /** C2 평균에 넣은 회전 수. */
    val c2N: Int? = null,
    /** F1 평가 제동 수. */
    val stopTotal: Int? = null,
    /** F1 실패 수. */
    val stopFail: Int? = null,
    /** 급정지 비율 = stopFail / stopTotal(소수 셋째 자리). */
    val stopFailRate: Double? = null,
    /** G1 회차 값(직진 창 표준편차 평균 °). 창 3개 미만이면 null('측정 부족'). */
    val g1: Double? = null,
    val g1Windows: Int? = null,
    /** G1 좋아짐 = (기준 − 값) / 기준. */
    val g1Better: Double? = null,
    /** K6: 주행 중 실제로 말한 문장 수 ÷ (주행 sim분 / 10), 소수 한 자리. */
    val per10min: Double? = null,
    /** K6: 주행 중 말한 칭찬 수. */
    val praise: Int? = null,
    /** K6: 주행 중 말한 교정 수. */
    val correction: Int? = null,
    /** 주행 중 실제로 말한 문장 수(P에서 한 말 뺌). */
    val spoken: Int? = null,
    /** K4 첫 회차와 비교(리뷰가 더함). */
    val k4: K4Compare? = null,
)

/** 위험 회피 급제동 한 건 (설계 8.1 `f2[]`, basic.F2 `ctx.note('f2')`). */
@Serializable
data class F2Check(
    /** 'f2-1' …. */
    val id: String = "",
    val t: Double? = null,
    val v0: Double? = null,
    val v1: Double? = null,
    val dur: Double? = null,
    val maxDrop1s: Double? = null,
    val stopped: Boolean? = null,
    /** 본인 확인: null(아직) · 위험을 피함 · 내 실수(결정-54). 통과·점수 계산엔 넣지 않는다. */
    val choice: F2Choice? = null,
)

/** 급제동 본인 확인 답 (프로토타입 'avoid'·'mistake'). */
@Serializable
enum class F2Choice {
    @SerialName("avoid")
    AVOID,

    @SerialName("mistake")
    MISTAKE,
}

/** 리뷰 팁 줄 (`rec.tips[]` `{text, icon, kind?}` — 날씨 회차 줄(결정사항 E T7-4), E3 줄(T10-8)). */
@Serializable
data class ReviewTip(
    val text: String = "",
    /** 심볼 id(예: 'i-rain'). */
    val icon: String? = null,
    /** 'E3' 등. */
    val kind: String? = null,
)

/** 가족에게 요약 보내기 상태 (설계 8.1 `share`, 프로토타입 share.js). */
@Serializable
data class ShareState(
    val status: ShareStatus = ShareStatus.NONE,
    /** 받는 사람 이름. */
    val to: String? = null,
    /** 보낸 시각(ISO). */
    val at: String? = null,
    /** 줄 세운 시각(ISO, 네트워크가 끊겼을 때). */
    val queuedAt: String? = null,
)

/** 보내기 상태 (프로토타입 'none'·'queued'·'sent'). */
@Serializable
enum class ShareStatus {
    @SerialName("none")
    NONE,

    @SerialName("queued")
    QUEUED,

    @SerialName("sent")
    SENT,
}
