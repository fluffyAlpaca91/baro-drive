package com.barodrive.core.engine.review

import com.barodrive.core.engine.Thresholds
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.ReviewLine
import com.barodrive.core.model.SessionHistory
import com.barodrive.core.model.SessionRecord

/**
 * 잘한 것·아쉬운 것 (프로토타입 js/engine/review.js `goodLines`·`badLines`·`groupOf`·`taskHidden`, 설계 6.10.2, 목업 s05 말투).
 * 각 3줄까지. X로 둔 항목(과제와 이어진 항목이 모두 X인 과제 포함)은 넣지 않는다(설계 6.3.2 아래 줄).
 */
internal object ReviewLines {
    private const val EPS = 1e-6
    private val ZONE_TASKS = listOf("T-30", "T-LIMIT")
    private val ZONE_TEXT = mapOf("T-30" to "30 구역 다 지켰어요", "T-LIMIT" to "제한속도 다 지켰어요")

    /** 과제와 이어진 항목이 모두 X면 리뷰 줄에 넣지 않는다 (review.js `taskHidden`). */
    private fun taskHidden(id: String, items: Map<String, Delivery>?): Boolean {
        val t = ReviewTasks.def(id) ?: return false
        if (items == null) return false
        val its = t.items.filter { it in items }
        return its.isNotEmpty() && its.all { items[it] == Delivery.OFF }
    }

    /** 줄 후보: 글 + 정렬 값(성공률 [r]·판정 수 또는 횟수 [n]·같을 때 순서 [i]). */
    private class Line(val id: String, val text: String, val r: Double = 0.0, val n: Int = 0, val i: Int = 0)

    /** 잘한 것 3줄까지 (review.js `goodLines`): 80% 이상 과제 2줄 → G1(10% 넘게 좋아짐)·구역(다 지킴) → 남은 과제. */
    fun good(
        record: SessionRecord,
        l: List<JudgeEvent>,
        tasks: List<TaskCount.CountedTask>,
        history: SessionHistory,
        items: Map<String, Delivery>?,
    ): List<ReviewLine> {
        val anyE2 = pickIdx(l, "E2", ::isFail).isNotEmpty()
        val order = { id: String -> tasks.indexOfFirst { it.id == id } }
        val taskLines = ArrayList<Line>()
        for (t in tasks) {
            if (t.total <= 0 || taskHidden(t.id, items)) continue
            val r = t.success.toDouble() / t.total
            if (t.id in ZONE_TASKS) {
                if (t.success == t.total && !anyE2) taskLines += Line(t.id, ZONE_TEXT.getValue(t.id), 1.0, t.total)
            } else if (r >= Thresholds["pass.rate"] - EPS) {
                taskLines += Line(t.id, "${t.label} ${t.success}/${t.total}번", r, t.total)
            }
        }
        val sorted = taskLines.sortedWith { a, b ->
            sign(b.r - a.r).takeIf { it != 0 } ?: (b.n - a.n).takeIf { it != 0 } ?: (order(a.id) - order(b.id))
        }
        val extra = ArrayList<Line>()
        // G1: 첫 회차보다 10% 넘게 좋아짐(첫 기준이 있을 때만, G1이 X가 아닐 때)
        val base = K4Calc.g1Base(history)
        val g1 = record.metrics.g1
        if (base != null && g1 != null && items?.get("G1") != Delivery.OFF) {
            val b = (base - g1) / base
            if (b > Thresholds["G1.better"] + EPS) extra += Line("G1", "직진할 때 핸들 첫날보다 ${K4Calc.pct(b)}% 안정")
        }
        // 구역 과제가 없는 모드: 제한속도 구역을 모두 지킴
        if (tasks.none { it.id in ZONE_TASKS }) {
            val zones = pickIdx(l, "E1")
            if (zones.isNotEmpty() && zones.none { isFail(l[it]) } && !anyE2) {
                extra += Line("zones", if (zones.all { isZone30(l[it]) }) ZONE_TEXT.getValue("T-30") else ZONE_TEXT.getValue("T-LIMIT"))
            }
        }
        return (sorted.take(2) + extra + sorted.drop(2)).take(3).map { ReviewLine(it.id, it.text) }
    }

    /** 실패 묶음: 항목별 문장(명사형 + 횟수). 이 순서가 같은 횟수일 때의 순서(안전·벨트 먼저). */
    private class Bad(val id: String, val item: String, val text: String)

    private val BAD = listOf(
        Bad("school", "E2", "어린이보호구역 초과"),
        Bad("E1-30", "E1", "30 구역 초과"),
        Bad("E1", "E1", "제한속도 초과"),
        Bad("A1", "A1", "벨트 안 매고 출발"),
        Bad("B1", "B1", "출발할 때 엑셀을 조금 세게"),
        Bad("F1", "F1", "멈출 때 브레이크를 조금 세게"),
        Bad("C5", "C5", "깜빡이 없이 회전"),
        Bad("C4", "C4", "깜빡이 켜고 바로 꺾음"),
        Bad("C2", "C2", "회전 전 깜빡이 조금 늦게"),
        Bad("C2none", "C2", "회전 전 깜빡이 안 켬"),
        Bad("PB1", "PB1", "사이드브레이크 채운 채 출발"),
        Bad("PB2", "PB2", "주차 후 사이드브레이크 안 채움"),
    )

    /** 요약만 있는 회차: 과제 실패를 묶음으로. */
    private val TASK_GROUP = mapOf(
        "T-START" to "B1", "T-STOP" to "F1", "T-STOPSIG" to "F1", "T-NOBRAKE" to "F1", "T-SIGNAL1" to "C5", "T-30" to "E1-30",
        "T-LIMIT" to "E1", "T-SIGNAL30" to "C2", "T-SIGNALPRE" to "C2", "T-IC" to "C2", "T-WAIT3" to "C4",
    )

    private val HILL_BAD = mapOf(
        "T-HILLUP" to "오르막에서 뒤로 밀림",
        "T-HILLDOWN" to "내리막에서 브레이크 나눠 밟기 놓침",
        "T-HILLPARK" to "언덕 주차 바퀴·사이드브레이크 놓침",
    )

    /** 판정 하나가 들어갈 실패 묶음 (review.js `groupOf`). 없으면 null. */
    private fun groupOf(e: JudgeEvent, l: List<JudgeEvent>): String? {
        val d = e.data
        return when (e.item) {
            "E2" -> if (isFail(e)) "school" else null
            "E1" -> when {
                !isFail(e) -> null
                d.truthy("school") -> if (l.any { x -> x.item == "E2" && isFail(x) && x.data["zoneId"] == d["zoneId"] }) null else "school"
                else -> if (isZone30(e)) "E1-30" else "E1"
            }
            "C2" -> when (e.result) {
                EventResult.ALMOST, EventResult.LATE -> "C2"
                EventResult.FAIL -> { // 안 켬 — 같은 회전을 C5(깜빡이 없이 회전)가 잡았으면 그 줄로
                    val tid = if (d["turnId"] != null) d.jsString("turnId") else null
                    if (tid != null && l.any { x -> x.item == "C5" && isFail(x) && x.data.jsString("turnId") == tid }) null else "C2none"
                }
                else -> null
            }
            "B1", "F1", "C4", "C5", "A1", "PB1", "PB2" -> if (isFail(e)) e.item else null
            else -> null
        }
    }

    /** 아쉬운 것 3줄까지 (review.js `badLines`): 잘한 것에 든 과제의 판정은 다시 적지 않고, X 항목은 넣지 않는다. 많은 순. */
    fun bad(
        l: List<JudgeEvent>,
        tasks: List<TaskCount.CountedTask>,
        goodIds: List<String>,
        items: Map<String, Delivery>?,
    ): List<ReviewLine> {
        val covered = HashSet<Int>()
        for (t in tasks) if (t.id in goodIds) covered += t.events
        val counts = LinkedHashMap<String, Int>()
        for ((i, e) in l.withIndex()) {
            if (i in covered) continue
            val g = groupOf(e, l) ?: continue
            counts[g] = (counts[g] ?: 0) + 1
        }
        if (l.isEmpty()) { // 요약만 있는 회차: 과제 실패로
            for (t in tasks) {
                val g = TASK_GROUP[t.id] ?: continue
                val n = t.total - t.success
                if (n > 0 && t.id !in goodIds) counts[g] = (counts[g] ?: 0) + n
            }
        }
        val out = ArrayList<Line>()
        BAD.forEachIndexed { i, b ->
            val n = counts[b.id] ?: 0
            if (n == 0) return@forEachIndexed
            if (items != null && items[b.item] == Delivery.OFF) return@forEachIndexed // X 항목은 넣지 않는다
            out += Line(b.id, "${b.text} ${n}번", n = n, i = i)
        }
        // 언덕 과제(언덕 검출기가 넘긴 값)
        tasks.forEachIndexed { j, t ->
            val head = HILL_BAD[t.id] ?: return@forEachIndexed
            if (t.id in goodIds || taskHidden(t.id, items)) return@forEachIndexed
            val n = t.total - t.success
            if (n <= 0) return@forEachIndexed
            val text = t.badText?.replace("{n}", n.toString()) ?: "$head ${n}번"
            out += Line(t.id, text, n = n, i = BAD.size + j)
        }
        // 언덕 주차 과제가 사이드브레이크까지 함께 본다 — 그 과제 줄이 있으면 PB2 줄은 빼서 같은 내용이 두 번 나오지 않게(T11-7)
        if (out.any { it.id == "T-HILLPARK" }) out.indexOfFirst { it.id == "PB2" }.takeIf { it >= 0 }?.let { out.removeAt(it) }
        return out.sortedWith { a, b -> (b.n - a.n).takeIf { it != 0 } ?: (a.i - b.i) }.take(3).map { ReviewLine(it.id, it.text) }
    }
}
