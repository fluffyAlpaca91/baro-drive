package com.barodrive.core.engine.score

import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.api.G1Input
import com.barodrive.core.engine.api.ScoreCounts
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.review.jsNum
import com.barodrive.core.engine.review.jsRound
import com.barodrive.core.engine.review.round2
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.ScoreExcluded
import com.barodrive.core.model.ScoreF2
import com.barodrive.core.model.ScoreG1
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.ScoreRow
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.SignalKey

/**
 * 자율 모드 안전운전 점수 계산 (프로토타입 js/engine/score.js `compute`·`fromCounts`·`g1Points`, 설계 6.11·7.8, 결정-19·54·55).
 *
 * 회차 기록의 판정만 보고 계산한다 — '안 하기'(X)로 고른 항목도 판정은 남으므로 점수에 들어간다(결정-19).
 * 위험 회피 급제동(F2)은 감점하지 않는다. 100점에서 빼고 0점 아래로 내려가지 않는다. 결과가 'excluded'(재탐색·건너뛰기로 뺀 회전)인
 * 판정과 출발 전 판정은 세지 않는다. 못 읽는 신호로 줄의 항목이 모두 꺼지면 그 줄은 빼고 excluded에 적는다(설계 7.8).
 */
object ScoreCalc {
    private const val EPS = 1e-9
    private const val G1_MAX = 10
    private const val G1_MUL = 20

    /** 점수 줄 정의 (score.js `ROWS`, 설계 6.11 표 순서). [unit] = 1번에 깎는 점수(G1은 null, 최대 [max]). */
    class RowDef(
        val id: String,
        val label: String,
        val short: String,
        val fix: String,
        val unit: Int?,
        val items: List<String>,
        val max: Int? = null,
    )

    val ROWS: List<RowDef> = listOf(
        RowDef("C2.late", "회전 전 깜빡이 '늦음'", "깜빡이 타이밍", "C2", 3, listOf("C2")),
        RowDef("C2.almost", "회전 전 깜빡이 '거의'", "깜빡이 타이밍", "C2", 1, listOf("C2")),
        RowDef("C4", "깜빡이 켜자마자 꺾음", "켜자마자 꺾음", "C4", 2, listOf("C4")),
        RowDef("C5", "깜빡이 없이 회전", "깜빡이 없이 회전", "C5", 5, listOf("C5", "C2")),
        RowDef("BF", "급출발·급정지", "급출발·급정지", "BF", 3, listOf("B1", "F1")),
        RowDef("E1", "제한속도 초과", "제한속도", "E1", 5, listOf("E1", "E2")),
        RowDef("G1", "직진할 때 핸들 흔들림", "핸들 흔들림", "G1", null, listOf("G1"), max = G1_MAX),
    )

    /** 가장 많이 깎인 곳이 같으면 이 순서(안전에 가까운 것 먼저, score.js `FIX_ORDER`). */
    val FIX_ORDER: List<String> = listOf("E1", "C5", "C2", "C4", "BF", "G1")

    fun rowDef(id: String): RowDef? = ROWS.firstOrNull { it.id == id }

    /** G1 기준 기본값(첫 회차 값이 없을 때 0.8°, 결정-55). */
    private val g1BaseDefault: Double get() = Thresholds["G1.base"]

    /** 못 읽는 신호로 꺼진 항목과 항목마다 못 읽은 신호. */
    class OffInfo(val items: Set<String>, val byItem: Map<String, List<SignalKey>>)

    /** score.js `offInfo(caps)`: 항목 `needs` 중 하나라도 못 읽으면 꺼짐(core.js `itemsOffByCaps`). */
    fun offInfo(caps: SignalCaps): OffInfo {
        val ids = Items.itemsOffByCaps(caps)
        val byItem = ids.associateWith { id ->
            Items.def(id)?.needs.orEmpty().mapNotNull { SignalKey.fromKey(it) }.filter { !caps[it] }
        }
        return OffInfo(ids.toSet(), byItem)
    }

    /** G1 감점 (score.js `g1Points`): 값 > 기준일 때만 min(10, round((값/기준 − 1) × 20)). 0.5 경계의 부동소수 흔들림 없이 반올림. */
    fun g1Points(value: Double?, base: Double?): Int {
        if (value == null || base == null || !value.isFinite() || !base.isFinite() || base <= 0 || value <= base + EPS) return 0
        return maxOf(0.0, minOf(G1_MAX.toDouble(), roundHalf((value / base - 1) * G1_MUL))).toInt()
    }

    private fun roundHalf(x: Double): Double = jsRound(jsRound(x * 1e6) / 1e6)

    /** 횟수 묶음의 G1 값. */
    private class G1Tally(val value: Double?, val base: Double, val baseFirst: Boolean, val insufficient: Boolean)

    /** 횟수 묶음(score.js `tally`). */
    private class Tally(
        val counts: Map<String, Int>,
        val g1: G1Tally?,
        val f2: Int,
    )

    /** 횟수 묶음 → 점수 (score.js `build`). compute와 fromCounts가 함께 쓴다. */
    private fun build(tally: Tally, off: OffInfo): ScoreResult {
        val rows = ArrayList<ScoreRow>()
        val excluded = ArrayList<ScoreExcluded>()
        var sum = 0
        for (def in ROWS) {
            val offAll = def.items.all { it in off.items }
            var row = ScoreRow(
                id = def.id, label = def.label, short = def.short, fix = def.fix, unit = def.unit, max = def.max, items = def.items,
                count = if (def.id == "G1") null else tally.counts[def.id] ?: 0, points = 0, excluded = offAll,
            )
            if (offAll) {
                val sig = ArrayList<SignalKey>()
                for (id in def.items) for (k in off.byItem[id].orEmpty()) if (k !in sig) sig += k
                row = row.copy(signals = sig, count = if (def.id == "G1") null else 0)
                excluded += ScoreExcluded(id = def.id, label = def.label, short = def.short, signals = sig.toList())
            } else if (def.id == "G1") {
                val g = tally.g1
                row = row.copy(points = if (g != null && !g.insufficient) g1Points(g.value, g.base) else 0)
            } else {
                row = row.copy(points = (row.count ?: 0) * (def.unit ?: 0))
            }
            sum += row.points
            rows += row
        }
        val total = maxOf(0, minOf(100, 100 - sum))
        val g1Row = rows.first { it.id == "G1" }
        val g = tally.g1
        val g1 = if (g != null && !g1Row.excluded) {
            ScoreG1(
                value = g.value,
                base = g.base,
                baseFirst = g.baseFirst,
                ratio = if (g.value != null && g.base > 0) round2(g.value / g.base) else null,
                insufficient = g.insufficient || g.value == null,
                points = g1Row.points,
            )
        } else {
            null
        }
        // 가장 많이 깎인 곳(감점 문구 자리): C2 '늦음'·'거의'는 '깜빡이 타이밍' 하나로 더한다
        val byFix = LinkedHashMap<String, Int>()
        for (r in rows) if (r.points > 0) byFix[r.fix] = (byFix[r.fix] ?: 0) + r.points
        var top: String? = null
        for (f in FIX_ORDER) {
            val v = byFix[f] ?: 0
            if (v > 0 && (top == null || v > (byFix[top] ?: 0))) top = f
        }
        val exItems = ArrayList<String>()
        val exSignals = ArrayList<SignalKey>()
        for (x in excluded) {
            for (id in rowDef(x.id)?.items.orEmpty()) if (id in off.items && id !in exItems) exItems += id
            for (k in x.signals) if (k !in exSignals) exSignals += k
        }
        return ScoreResult(
            total = total,
            lost = 100 - total,
            sum = sum,
            rows = rows,
            deductions = rows.filter { !it.excluded && it.points > 0 },
            excluded = excluded,
            excludedItems = exItems,
            excludedSignals = exSignals,
            f2 = ScoreF2(count = maxOf(0, tally.f2)),
            g1 = g1,
            top = top,
        )
    }

    /**
     * 회차 기록의 판정으로 점수 (score.js `compute(rec, cfg)`). [baseline] = G1 기준(설정 `history.baselineG1` 또는 프로필 `g1Baseline`),
     * 0 이하·null이면 없음. 신호 지원은 기록의 `caps`.
     */
    fun compute(record: SessionRecord, baseline: Double?): ScoreResult {
        val off = offInfo(record.caps)
        val evs = record.events.filter { !it.pre && it.result != EventResult.EXCLUDED }
        val counts = HashMap<String, Int>()
        fun add(k: String) { counts[k] = (counts[k] ?: 0) + 1 }
        val c5Turns = HashSet<String>()
        for (e in evs) if (e.item == "C5") e.data.turnId?.let { c5Turns += it }
        val zones = LinkedHashSet<String>()
        var g1ev: JudgeEvent? = null
        for (e in evs) {
            val d = e.data
            when (e.item) {
                "C2" -> when (e.result) {
                    EventResult.LATE -> add("C2.late")
                    EventResult.ALMOST -> add("C2.almost")
                    EventResult.FAIL -> if (d.turnId?.let { it in c5Turns } != true) add("C5") // C5가 못 본 '안 켬'
                    else -> Unit
                }
                "C4" -> if (e.result == EventResult.FAIL) add("C4")
                "C5" -> if (e.result == EventResult.FAIL) add("C5")
                "B1", "F1" -> if (e.result == EventResult.FAIL) add("BF")
                "E1", "E2" -> if (e.result == EventResult.FAIL) zones += d.zoneKey(e.item, e.t)
                "G1" -> g1ev = e
                else -> Unit
            }
        }
        counts["E1"] = zones.size
        // G1: 회차 끝 판정 값(기준은 검출기가 적은 값), 없으면 회차 지표와 설정 기준
        val cfgBase = baseline?.takeIf { it.isFinite() && it > 0 }
        val ev = g1ev
        val g1 = when {
            ev != null -> {
                val d = ev.data
                val base = d.base?.takeIf { it > 0 } ?: cfgBase ?: g1BaseDefault
                G1Tally(d.value, base, d.baseFirst ?: (cfgBase != null), d.insufficient == true || d.value == null)
            }
            record.metrics.g1 != null -> G1Tally(record.metrics.g1, cfgBase ?: g1BaseDefault, cfgBase != null, false)
            "G1" !in off.items -> G1Tally(null, cfgBase ?: g1BaseDefault, cfgBase != null, true)
            else -> null
        }
        return build(Tally(counts, g1, record.f2.size), off)
    }

    /** 같은 구역은 한 번(E2 구역 포함): 구역 id, 없으면 항목·시각으로 한 번씩. */
    private fun EventData.zoneKey(item: String?, t: Double): String = zoneId?.let { "z:$it" } ?: "t:$item:${jsNum(t)}"

    /**
     * 판정 없이 횟수로 점수 (score.js `fromCounts`) — 데모 상태가 실제 회차와 같은 모양을 만들 때.
     * [ScoreCounts.g1Points]를 주면 그만큼 깎이는 G1 값을 기준 0.8로 만든다(값 = 0.8 × (1 + n/20)).
     */
    fun fromCounts(x: ScoreCounts): ScoreResult {
        val off = x.caps?.let(::offInfo) ?: OffInfo(x.off.orEmpty().toSet(), x.off.orEmpty().associateWith { emptyList() })
        val g1 = x.g1?.let(::g1Of) ?: x.g1Points?.let { pts ->
            val base = g1BaseDefault
            val n = maxOf(0, minOf(G1_MAX, pts))
            G1Tally(round2(base * (1 + n.toDouble() / G1_MUL)), base, false, false)
        }
        fun n(v: Int) = maxOf(0, v)
        val counts = mapOf(
            "C2.late" to n(x.late), "C2.almost" to n(x.almost), "C4" to n(x.c4), "C5" to n(x.c5), "BF" to n(x.bf), "E1" to n(x.e1),
        )
        return build(Tally(counts, g1, n(x.f2)), off)
    }

    private fun g1Of(g: G1Input): G1Tally {
        val base = g.base?.takeIf { it.isFinite() && it > 0 } ?: g1BaseDefault
        val value = g.value?.takeIf { it.isFinite() }
        return G1Tally(value, base, g.baseFirst, value == null)
    }
}
