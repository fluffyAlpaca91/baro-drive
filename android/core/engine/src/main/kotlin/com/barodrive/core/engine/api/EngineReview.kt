package com.barodrive.core.engine.api

import com.barodrive.core.engine.review.K4Calc
import com.barodrive.core.engine.review.NextTaskPick
import com.barodrive.core.engine.review.ProgressCalc
import com.barodrive.core.engine.review.ReviewCalc
import com.barodrive.core.engine.review.TaskCount
import com.barodrive.core.model.ItemStat
import com.barodrive.core.model.K4Compare
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ProgressApplied
import com.barodrive.core.model.ReviewSummary
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.TodayTask

/**
 * 리뷰 계산·진행 반영 (설계 6.10, 6.1.1 `review.compute`, 프로토타입 review.js 머리 주석).
 * 회차 끝에 엔진 세션이 [summarize]를 불러 기록에 합치고, 세션 관리자가 저장 전에 [applyProgress]로 프로필을 고친다.
 * 계산은 `com.barodrive.core.engine.review` 패키지(ReviewCalc·TaskCount·NextTaskPick·K4Calc·ReviewLines·ProgressCalc)에 있다.
 * RV-1 화면 글은 `review.ReviewText`, 단계 통과·졸업 화면 값은 `stage.StagePass`.
 */
object EngineReview {
    /**
     * 리뷰 요약 (설계 6.1.1 `review.compute(record, profile)` = review.js `compute`: 프로필 값으로 [summarize] —
     * G1·K4 기준 = 프로필 `g1Baseline`·`firstMetrics`, 도움 줄여 가기 = 프로필 `fading[mode]`·`feedback.overrides[mode]`).
     * 기록은 고치지 않는다.
     */
    fun compute(record: SessionRecord, profile: Profile?): ReviewSummary =
        ReviewCalc.summarize(record, ReviewCalc.Context.of(profile, record.mode))

    /**
     * 리뷰 요약 (review.js `summarize(rec, cfg)`): 과제 세기(6.4) · 달성률(판정 0인 과제 뺌) · 기록이 적은 회차(판정 < 4) ·
     * 통과(≥ 0.8) · 다음 과제(6.10.3, 날씨 회차면 '30'을 그 회차 기준으로) · K4(cfg.history) · 잘한 것·아쉬운 것(6.10.2, X 항목 뺌) ·
     * itemStats · 이번 회차로 내려갈 항목(cfg.fading·overrides로 미리 계산). events가 없으면 기록의 tasks·itemStats로.
     * 전달 방식은 기록의 `items`(비었으면 cfg의 items)로 본다.
     */
    fun summarize(record: SessionRecord, cfg: SessionConfig?): ReviewSummary = ReviewCalc.summarize(record, ReviewCalc.Context.of(cfg))

    /**
     * 진행 반영 (review.js `applyProgress(profile, rec, { now })`, 설계 6.10.4·5.2). 프로필과 기록을 고친 새 값을 돌려준다:
     * sessionCount[mode] + 1 · 1~4단계 통과 회차(누적 2번이면 통과, 지금 단계면 다음 단계를 열고 4단계면 졸업, 지난 단계는 그 카운트만) ·
     * g1Baseline·firstMetrics(처음 나온 값) · 도움 줄여 가기([EngineFading.update]) · 기록 `fadedNow`·`fadedNote`·`progress`.
     * 같은 기록(`progress.applied`)은 두 번 반영하지 않는다(프로필·기록 그대로, 결과 = 기록의 progress). passedAt = [now] › 기록 startedAt › 기록 id.
     */
    fun applyProgress(profile: Profile, record: SessionRecord, now: String? = null): ProgressApplied =
        ProgressCalc.applyProgress(profile, record, now)

    /**
     * PR-1 오늘 과제 최대 3개 (review.js `todayTasks`, 결정-76): 1번 = 지난 회차 다음 과제, 나머지 = 그 모드 과제 중
     * 도움 줄여 가기로 내려가지 않은 것, 지난 회차 성공률 낮은 순.
     */
    fun todayTasks(profile: Profile?, mode: Int): List<TodayTask> = ProgressCalc.todayTasks(profile, mode)

    /** 그 모드 과제를 기록의 판정으로 센다(review.js `countTasks`, 설계 6.4 '세는 단위 · 성공'). 언덕 과제는 언덕 검출기가 넘긴 값. */
    fun countTasks(record: SessionRecord, mode: Int): List<TaskResult> = TaskCount.countTasks(record, mode).map { it.toResult() }

    /** 항목별 성공·판정 수(review.js `itemStats`: 판정 ok·almost·late·fail, 출발 전 사건 뺌). */
    fun itemStats(record: SessionRecord): Map<String, ItemStat> = TaskCount.itemStats(TaskCount.judged(record))

    /** K4 첫 회차와 비교(review.js `k4`, 설계 6.10.5). 기준이 없으면 first = true. */
    fun k4(metrics: SessionMetrics, history: SessionHistory): K4Compare = K4Calc.k4(metrics, history)

    /** 다음 과제 하나(review.js `nextTaskOf`, 설계 6.10.3: E2 > 30 구역 E1 > E1 > 성공률 낮은 과제 > 판정 적은 과제). */
    fun nextTask(record: SessionRecord, tasks: List<TaskResult>, mode: Int): NextTask? =
        NextTaskPick.nextTaskOf(TaskCount.judged(record), tasks)
}
