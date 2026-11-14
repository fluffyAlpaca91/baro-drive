package com.barodrive.core.engine.review

import com.barodrive.core.model.EventResult
import com.barodrive.core.model.ItemStat
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TaskResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 판정 목록에서 [item] 판정(조건 [pred])의 자리들 (review.js `pick`). */
internal fun pickIdx(l: List<JudgeEvent>, item: String, pred: (JudgeEvent) -> Boolean = { true }): List<Int> =
    l.indices.filter { l[it].item == item && pred(l[it]) }

internal fun isFail(e: JudgeEvent): Boolean = e.result == EventResult.FAIL

/** 판정 data의 제한속도 (review.js `limitOf`). */
internal fun limitOf(e: JudgeEvent): Double? = e.data.limit

/** 30 이하 구역의 판정인가(T-30·'30 구역 초과'). */
internal fun isZone30(e: JudgeEvent): Boolean = limitOf(e)?.let { it <= 30 } == true

/**
 * 과제 세기 (프로토타입 js/engine/review.js `COUNTERS`·`noted`·`countTasks`·`normTasks`·`itemStats`, 설계 6.4 '세는 단위 · 성공').
 * 판정은 ok·almost·late·fail이고 출발 전 사건이 아닌 것만 센다. 언덕 과제(T-HILL*)는 언덕 검출기가 넘긴
 * `rec.hill.tasks`(또는 `rec.notes.tasks`) 값을 그대로 쓴다.
 */
internal object TaskCount {
    /** 리뷰가 세는 판정(review.js `evs`): ok·almost·late·fail이고 출발 전 사건이 아님. */
    fun judged(record: SessionRecord): List<JudgeEvent> = record.events.filter { it.isJudged }

    /** 셈 하나: 성공·판정 수와 센 판정의 자리(판정 목록 [judged]의 번호 — 같은 판정을 두 번 세지 않게 자리로 본다). */
    class Count(val success: Int, val total: Int, val events: List<Int> = emptyList(), val badText: String? = null)

    /** 과제 한 줄 + 센 판정 자리·언덕 아쉬운 것 글(review.js `_events`·`_badText`). */
    class CountedTask(
        val id: String,
        val label: String,
        val short: String,
        val success: Int,
        val total: Int,
        val events: List<Int>,
        val badText: String?,
    ) {
        fun toResult(): TaskResult = TaskResult(id = id, label = label, short = short, success = success, total = total)
    }

    private fun okCount(l: List<JudgeEvent>, idx: List<Int>): Count = Count(idx.count { l[it].result == EventResult.OK }, idx.size, idx)

    private val COUNTERS: Map<String, (List<JudgeEvent>) -> Count> = mapOf(
        "T-START" to { l -> okCount(l, pickIdx(l, "B1")) },
        "T-STOP" to { l -> okCount(l, pickIdx(l, "F1")) },
        "T-STOPSIG" to { l -> okCount(l, pickIdx(l, "F1")) },
        "T-NOBRAKE" to { l -> okCount(l, pickIdx(l, "F1")) },
        "T-SIGNAL1" to { l ->
            val idx = pickIdx(l, "C5")
            Count(idx.count { l[it].result == EventResult.OK && l[it].data.before != false }, idx.size, idx)
        },
        "T-30" to { l -> okCount(l, pickIdx(l, "E1", ::isZone30)) },
        "T-LIMIT" to { l -> okCount(l, pickIdx(l, "E1")) },
        "T-SIGNAL30" to { l -> okCount(l, pickIdx(l, "C2")) },
        "T-SIGNALPRE" to { l -> okCount(l, pickIdx(l, "C2")) },
        "T-IC" to { l -> okCount(l, pickIdx(l, "C2") { it.data.truthy("ic") }) },
        "T-WAIT3" to { l -> okCount(l, pickIdx(l, "C4")) },
    )

    /** 검출기가 넘긴 과제 셈(review.js `noted`): `rec.hill.tasks[id]`, 없으면 `rec.notes.tasks[id]`. total이 수가 아니면 null. */
    private fun noted(record: SessionRecord, id: String): Count? {
        record.hill?.tasks?.get(id)?.let { h ->
            val total = maxOf(0, h.total)
            return Count(maxOf(0, minOf(total, h.success)), total, badText = h.badText?.takeIf { it.isNotEmpty() })
        }
        val n = ((record.notes?.get("tasks") as? JsonObject)?.get(id) as? JsonObject) ?: return null
        val rawTotal = jsNumber(n["total"]) ?: return null
        val total = maxOf(0.0, jsRound(rawTotal)).toInt()
        val success = maxOf(0.0, minOf(total.toDouble(), jsRound(jsNumber(n["success"]) ?: 0.0))).toInt()
        val badText = n["badText"]?.takeIf { jsTruthy(it) }?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
        return Count(success, total, badText = badText)
    }

    private fun countOne(record: SessionRecord, id: String, l: List<JudgeEvent>): Count =
        noted(record, id) ?: COUNTERS[id]?.invoke(l) ?: Count(0, 0)

    /** 그 모드 과제를 판정으로 센다 (review.js `countTasks(rec, mode, true)`). */
    fun countTasks(record: SessionRecord, mode: Int, l: List<JudgeEvent> = judged(record)): List<CountedTask> =
        ReviewTasks.modeTasks(mode).map { t ->
            val c = countOne(record, t.id, l)
            CountedTask(t.id, t.label, t.short, c.success, c.total, c.events, c.badText)
        }

    /** 미리 만든 회차의 tasks를 모드 과제 모양으로 (review.js `normTasks`). */
    fun normTasks(list: List<TaskResult>, mode: Int): List<CountedTask> = ReviewTasks.modeTasks(mode).map { t ->
        val x = list.firstOrNull { it.id == t.id }
        val total = maxOf(0, x?.total ?: 0)
        CountedTask(t.id, t.label, t.short, maxOf(0, minOf(total, x?.success ?: 0)), total, emptyList(), null)
    }

    /** 항목별 성공·판정 수 (review.js `itemStats`, 도움 줄여 가기 · 설계 6.7). 판정 목록 순서대로 항목이 들어간다. */
    fun itemStats(l: List<JudgeEvent>): Map<String, ItemStat> {
        val out = LinkedHashMap<String, ItemStat>()
        for (e in l) {
            val item = e.item
            if (item.isNullOrEmpty()) continue
            val s = out[item] ?: ItemStat()
            out[item] = ItemStat(ok = s.ok + if (e.result == EventResult.OK) 1 else 0, total = s.total + 1)
        }
        return out
    }
}
