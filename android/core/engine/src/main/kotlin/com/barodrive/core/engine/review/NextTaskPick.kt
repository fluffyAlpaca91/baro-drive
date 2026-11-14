package com.barodrive.core.engine.review

import com.barodrive.core.engine.api.BaroEngine
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.NextTaskReason
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TaskResult

/**
 * 다음 과제 하나 (프로토타입 js/engine/review.js `taskRef`·`nextTaskOf`·`wxNextTask`, 설계 6.10.3, 결정-39, 결정사항 E T2a-8·T11-6).
 */
internal object NextTaskPick {
    private const val EPS = 1e-6

    private fun taskRef(id: String, reason: NextTaskReason): NextTask {
        val t = ReviewTasks.def(id)
        return NextTask(
            id = id,
            label = t?.nextLabel?.takeIf { it.isNotEmpty() } ?: t?.label?.takeIf { it.isNotEmpty() } ?: id,
            short = t?.short.orEmpty(),
            reason = reason,
        )
    }

    /**
     * 다음 과제 하나 (review.js `nextTaskOf`): 어린이보호구역 > 30 구역 > 제한속도(안전 위반) >
     * 성공률이 가장 낮은 과제(같으면 모드 과제 순서) > 모두 100%면 판정이 가장 적었던 과제 > 판정이 없으면 첫 과제. 과제가 없으면 null.
     * [l] = 리뷰가 세는 판정([TaskCount.judged]).
     */
    fun nextTaskOf(l: List<JudgeEvent>, tasks: List<TaskResult>): NextTask? {
        val e2 = pickIdx(l, "E2", ::isFail)
        val e1 = pickIdx(l, "E1", ::isFail)
        if (e2.isNotEmpty() || e1.any { l[it].data.truthy("school") }) return taskRef("T-SCHOOL", NextTaskReason.SAFETY)
        if (e1.any { isZone30(l[it]) }) return taskRef("T-30", NextTaskReason.SAFETY)
        if (e1.isNotEmpty()) return taskRef("T-LIMIT", NextTaskReason.SAFETY)
        if (l.isEmpty()) { // 요약만 있는 회차(미리 만든 상태): 구역 과제의 실패를 안전 위반으로 본다
            if (tasks.any { it.id == "T-30" && it.total > it.success }) return taskRef("T-30", NextTaskReason.SAFETY)
            if (tasks.any { it.id == "T-LIMIT" && it.total > it.success }) return taskRef("T-LIMIT", NextTaskReason.SAFETY)
        }
        if (tasks.isEmpty()) return null
        val rated = tasks.withIndex().filter { it.value.total > 0 }
        if (rated.isEmpty()) return tasks[0].let { NextTask(it.id, it.label, it.short, NextTaskReason.FIRST) }
        val rate = { t: TaskResult -> t.success.toDouble() / t.total }
        val low = rated.sortedWith { a, b -> sign(rate(a.value) - rate(b.value)).takeIf { it != 0 } ?: (a.index - b.index) }[0].value
        if (rate(low) < 1 - EPS) return NextTask(low.id, low.label, low.short, NextTaskReason.LOWEST)
        val few = rated.sortedWith { a, b -> (a.value.total - b.value.total).takeIf { it != 0 } ?: (a.index - b.index) }[0].value
        return NextTask(few.id, few.label, few.short, NextTaskReason.LEAST)
    }

    /**
     * 비·눈·아주 나쁜 날 회차는 제한 기준이 낮아진다(설계 6.8) — 다음 과제 글의 '30'(처음 하나)을 그 회차의 실제 기준(24·15)으로
     * (review.js `wxNextTask`). 날씨가 없는 기록은 그대로.
     */
    fun wxNextTask(n: NextTask?, record: SessionRecord): NextTask? {
        val w = record.weather
        if (n == null || !n.label.contains("30") || w == null) return n
        val eff = BaroEngine.effLimit(30.0, w) ?: return n
        if (eff == 30) return n
        return n.copy(label = n.label.replaceFirst("30", eff.toString()))
    }
}
