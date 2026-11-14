package com.barodrive.core.engine.score

import com.barodrive.core.engine.content.Lines
import com.barodrive.core.engine.review.Josa
import com.barodrive.core.model.Coach
import com.barodrive.core.model.PreviousScore
import com.barodrive.core.model.RecordKind
import com.barodrive.core.model.ScoreFootnote
import com.barodrive.core.model.ScoreLine
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.ScoreRow
import com.barodrive.core.model.ScoreTableRow
import com.barodrive.core.model.SessionRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * RV-4가 그리는 점수 글 (프로토타입 js/engine/score.js `table`·`countText`·`lostText`·`excludedText`·`footnotes`·`line`·`previous`,
 * 설계 4.2 RV-4·6.6.3, 목업 s07).
 */
object ScoreText {
    /** 감점 자리의 빼기 글자(목업 '−3', U+2212). */
    private const val MINUS = "−"

    /** 0번 줄을 한 줄로 묶을 때 이름 순서(목업 s07 '제한속도 초과 · 깜빡이 없이 회전 0번 0'). */
    private val ZERO_ORDER = listOf("E1", "C5", "BF", "C4", "C2.late", "C2.almost")

    private const val HI = 90
    private const val MID = 70

    /** 줄의 둘째 칸 (score.js `countText`): '1번' · G1 '기준보다 큼'·'기준과 비슷'·'기준 이하'·'측정 부족' · 뺀 줄 '못 읽음'. */
    fun countText(row: ScoreRow, score: ScoreResult): String {
        if (row.excluded) return "못 읽음"
        if (row.id != "G1") return "${row.count ?: 0}번"
        val g = score.g1
        if (row.points > 0) return "기준보다 큼"
        if (g == null || g.insufficient) return "측정 부족"
        return if ((g.ratio ?: 0.0) > 1) "기준과 비슷" else "기준 이하"
    }

    /**
     * 감점 표 '어디서 깎였나요' (score.js `table`, 설계 4.2 RV-4): 6.11 표 순서, 0번인 줄은 묶어서 맨 끝 한 줄. 뺀 줄은 넣지 않는다
     * ([excludedText]). G1은 늘 제 줄(깎이지 않았으면 '0').
     */
    fun table(score: ScoreResult): List<ScoreTableRow> {
        val out = ArrayList<ScoreTableRow>()
        val zero = ArrayList<ScoreRow>()
        for (r in score.rows) {
            if (r.excluded) continue
            if (r.id == "G1") {
                out += ScoreTableRow(
                    id = r.id, label = r.label, count = countText(r, score),
                    points = if (r.points > 0) MINUS + r.points else "0", zero = r.points == 0,
                )
                continue
            }
            if ((r.count ?: 0) > 0) {
                out += ScoreTableRow(id = r.id, label = r.label, count = countText(r, score), points = MINUS + r.points, zero = false)
            } else {
                zero += r
            }
        }
        if (zero.isNotEmpty()) {
            val sorted = zero.sortedBy { ZERO_ORDER.indexOf(it.id) }
            val ids = sorted.map { it.id }
            var labels = sorted.map { it.label }
            if ("C2.late" in ids && "C2.almost" in ids) { // 둘 다 0번이면 "회전 전 깜빡이 '늦음'·'거의'"
                labels = sorted.filter { it.id != "C2.almost" }.map { if (it.id == "C2.late") "회전 전 깜빡이 '늦음'·'거의'" else it.label }
            }
            out += ScoreTableRow(id = "zero", ids = ids, label = labels.joinToString(" · "), count = "0번", points = "0", zero = true)
        }
        return out
    }

    /** '14점 깎였어요' · 100점이면 '한 점도 안 깎였어요' (score.js `lostText`). */
    fun lostText(score: ScoreResult): String = if (score.lost > 0) "${score.lost}점 깎였어요" else "한 점도 안 깎였어요"

    /**
     * 못 읽은 신호 줄 (score.js `excludedText`, 설계 4.2 RV-4): '차속을 못 읽어 급출발·급정지·제한속도는 점수에서 뺐어요'.
     * 신호 이름·줄 이름은 한 번씩. 뺀 줄이 없으면 null.
     */
    fun excludedText(score: ScoreResult): String? {
        if (score.excluded.isEmpty()) return null
        val sig = ArrayList<String>()
        val names = ArrayList<String>()
        for (x in score.excluded) {
            for (k in x.signals) if (k.title !in sig) sig += k.title
            if (x.short !in names) names += x.short
        }
        val head = if (sig.isNotEmpty()) Josa.josa(sig.joinToString("·"), "을/를") + " 못 읽어 " else "못 읽는 신호가 있어 "
        return head + Josa.josa(names.joinToString("·"), "은/는") + " 점수에서 뺐어요"
    }

    /** 표 아래 발밑 줄 (score.js `footnotes`, 목업 s07): 위험 회피 급제동(있을 때) · 늦음·거의 기준(깜빡이 타이밍 줄을 뺐으면 없음). */
    fun footnotes(score: ScoreResult): List<ScoreFootnote> {
        val out = ArrayList<ScoreFootnote>()
        val n = score.f2.count
        if (n > 0) out += ScoreFootnote(icon = "i-shield", text = "위험을 피한 급제동 ${n}번은 감점하지 않았어요.")
        val c2Off = score.rows.any { it.id == "C2.late" && it.excluded }
        if (!c2Off) out += ScoreFootnote(icon = "i-info", text = "늦음 = 회전 30m 안에서 켬 · 거의 = 30~39m", muted = true)
        return out
    }

    /**
     * 점수 대사 고르기 (score.js `line`, 설계 6.6.3): 90 이상 'rv.score.hi' · 70~89 'rv.score.mid' · 70 미만 'rv.score.lo'.
     * 변수 { S, dest, fixSib, fixMom, fixDad } — 감점 문구는 대사 'rv.score.fix.<가장 많이 깎인 곳>'의 코치별 첫 글(깎인 곳이 없으면 C2).
     */
    fun line(score: ScoreResult, dest: String?): ScoreLine {
        val total = score.total
        val key = when {
            total >= HI -> "rv.score.hi"
            total >= MID -> "rv.score.mid"
            else -> "rv.score.lo"
        }
        val fix = score.top?.takeIf { it.isNotEmpty() } ?: "C2"
        fun frag(coach: Coach): String = Lines.sentences("rv.score.fix.$fix", coach).firstOrNull().orEmpty()
        val vars = JsonObject(
            linkedMapOf(
                "S" to JsonPrimitive(total),
                "dest" to JsonPrimitive(dest?.takeIf { it.isNotEmpty() } ?: "목적지"),
                "fixSib" to JsonPrimitive(frag(Coach.SIB)),
                "fixMom" to JsonPrimitive(frag(Coach.MOM)),
                "fixDad" to JsonPrimitive(frag(Coach.DAD)),
            ),
        )
        return ScoreLine(key = key, fix = fix, vars = vars)
    }

    /** [ScoreLine.vars]를 대사 그리기 변수로([Lines.render]에 그대로): S는 수, 나머지는 글자. */
    fun vars(line: ScoreLine): Map<String, Any?> = line.vars.mapValues { (_, v) ->
        val p = v as? JsonPrimitive
        when {
            p == null -> v.toString()
            p.isString -> p.content
            else -> p.intOrNull ?: p.doubleOrNull ?: p.content
        }
    }

    /**
     * 지난 점수 (score.js `previous`, 설계 4.2 RV-4 "지난번 82점 → 오늘 86점"): [records] = 최신이 앞인 회차 목록.
     * [recordId] 회차보다 앞선(오래된) 자율 회차 중 점수가 있는 첫 회차. [recordId]가 없으면 목록 맨 앞 회차를 '오늘'로 본다.
     */
    fun previous(records: List<SessionRecord>, recordId: String?): PreviousScore? {
        val i = if (recordId == null) 0 else records.indexOfFirst { it.id == recordId }
        if (i < 0) return null
        for (k in i + 1 until records.size) {
            val x = records[k]
            val sc = x.score
            if (x.mode == AUTONOMOUS && x.kind != RecordKind.VIRTUAL_PARKING && sc != null) {
                return PreviousScore(recordId = x.id?.takeIf { it.isNotEmpty() }, total = sc.total)
            }
        }
        return null
    }

    private const val AUTONOMOUS = 5
}
