package com.barodrive.core.engine.review

import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.fading.FadingCalc
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.FadeState
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ReviewSummary
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord

/**
 * 회차 리뷰 요약 (프로토타입 js/engine/review.js `summarize`·`compute`·`cfgFor`, 설계 6.10, 결정-24·36·38·39·54).
 * 순수 함수다 — 기록·프로필을 고치지 않는다. 부분 계산은 [TaskCount]·[NextTaskPick]·[K4Calc]·[ReviewLines].
 */
internal object ReviewCalc {
    private const val EPS = 1e-6

    /** summarize의 cfg에서 읽는 값 (review.js: cfg.items·history·fading·overrides). */
    data class Context(
        val items: Map<String, Delivery>? = null,
        val history: SessionHistory = SessionHistory(),
        val fading: Map<String, FadeState> = emptyMap(),
        val overrides: Map<String, Delivery> = emptyMap(),
    ) {
        companion object {
            /** 세션 설정에서(엔진 세션이 회차 끝에 부를 때). */
            fun of(cfg: SessionConfig?): Context = if (cfg == null) {
                Context()
            } else {
                Context(items = cfg.items, history = cfg.history, fading = cfg.fading, overrides = cfg.overrides)
            }

            /** 프로필 값으로 (review.js `cfgFor(profile, mode)` — 설계 6.1.1 `review.compute`). */
            fun of(profile: Profile?, mode: Int): Context = Context(
                items = null,
                history = SessionHistory(baselineG1 = profile?.g1Baseline, firstSessionMetrics = profile?.firstMetrics),
                fading = profile?.fading?.get(mode).orEmpty(),
                overrides = profile?.feedback?.overrides?.get(mode).orEmpty(),
            )
        }
    }

    /**
     * 회차 요약 (review.js `summarize(rec, cfg)`). 판정이 있거나 tasks가 비었으면 판정으로 세고, 아니면(미리 만든 상태) 기록의 tasks로.
     * 달성률 = 성공 합 ÷ 판정 합(판정 0인 과제는 뺌), 판정 합 4 미만이면 기록이 적은 회차, 80% 이상이고 기록이 적지 않으면 통과 회차.
     * 전달 방식(X 항목 빼기)은 기록의 `items`, 비었으면 cfg의 items.
     */
    fun summarize(record: SessionRecord, ctx: Context): ReviewSummary {
        val mode = record.mode
        val l = TaskCount.judged(record)
        val useEvents = l.isNotEmpty() || record.tasks.isEmpty()
        val tasks = if (useEvents) TaskCount.countTasks(record, mode, l) else TaskCount.normTasks(record.tasks, mode)
        var success = 0
        var total = 0
        for (t in tasks) {
            success += t.success
            total += t.total
        }
        val achievement = if (total > 0) round3(success.toDouble() / total) else null
        val fewEvents = total < Thresholds["fewEvents"]
        val qualified = !fewEvents && total > 0 && success.toDouble() / total >= Thresholds["pass.rate"] - EPS
        val items = record.items.ifEmpty { ctx.items }
        val good = ReviewLines.good(record, l, tasks, ctx.history, items)
        val bad = ReviewLines.bad(l, tasks, good.map { it.id }, items)
        val stats = if (useEvents || record.itemStats == null) TaskCount.itemStats(l) else record.itemStats.orEmpty()
        val fadedNow = if (mode in 1..8) {
            runCatching { FadingCalc.next(ctx.fading, stats, mode, ctx.overrides).fadedNow }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val results = tasks.map { it.toResult() }
        return ReviewSummary(
            tasks = results,
            achievement = achievement,
            fewEvents = fewEvents,
            qualified = qualified,
            nextTask = NextTaskPick.wxNextTask(NextTaskPick.nextTaskOf(l, results), record),
            metrics = SessionMetrics(k4 = K4Calc.k4(record.metrics, ctx.history)),
            good = good,
            bad = bad,
            itemStats = stats,
            fadedNow = fadedNow,
            fadedNote = FadingCalc.note(fadedNow),
        )
    }
}
