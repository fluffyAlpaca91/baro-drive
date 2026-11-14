package com.barodrive.core.engine.api

import com.barodrive.core.engine.score.ScoreCalc
import com.barodrive.core.engine.score.ScoreText
import com.barodrive.core.model.PreviousScore
import com.barodrive.core.model.Profile
import com.barodrive.core.model.ScoreFootnote
import com.barodrive.core.model.ScoreLine
import com.barodrive.core.model.ScoreResult
import com.barodrive.core.model.ScoreRow
import com.barodrive.core.model.ScoreTableRow
import com.barodrive.core.model.SessionConfig
import com.barodrive.core.model.SessionRecord

/**
 * 자율 모드 안전운전 점수 (설계 6.11, 6.1.1 `score.compute`, 프로토타입 score.js 머리 주석). RV-4가 그리는 글도 여기서 만든다.
 * 계산은 `com.barodrive.core.engine.score` 패키지(ScoreCalc·ScoreText)에 있다.
 */
object EngineScore {
    /**
     * 점수 (설계 6.1.1 `score.compute(record, profile)`, score.js `compute(rec, cfg)`). 프로필에서는 G1 기준(`g1Baseline`)만 읽는다.
     * 감점: C2 늦음 3 · 거의 1 · C4 2 · C5 5(C5가 못 본 C2 '안 켬' 포함) · 급출발·급정지 3 · 제한속도 5(같은 구역 1번, E2 구역 포함) ·
     * G1 min(10, round((값/기준 − 1) × 20)). F2 감점 없음. excluded·출발 전 판정은 세지 않는다. 못 읽는 신호의 줄은 뺀다(7.8).
     */
    fun compute(record: SessionRecord, profile: Profile?): ScoreResult = ScoreCalc.compute(record, profile?.g1Baseline)

    /** 회차 끝 점수(score.js `compute(rec, cfg)`: G1 기준 = cfg.history.baselineG1). 엔진 세션이 모드 5 finish에서 부른다. */
    fun compute(record: SessionRecord, cfg: SessionConfig): ScoreResult = ScoreCalc.compute(record, cfg.history.baselineG1)

    /** 판정 없이 횟수로 점수(score.js `fromCounts`) — 데모 상태용. */
    fun fromCounts(counts: ScoreCounts): ScoreResult = ScoreCalc.fromCounts(counts)

    /** G1 감점(score.js `g1Points`): 값 > 기준일 때만 min(10, round((값/기준 − 1) × 20)). */
    fun g1Points(value: Double?, base: Double?): Int = ScoreCalc.g1Points(value, base)

    /** RV-4 감점 표(score.js `table`): 6.11 순서, 0번 줄은 한 줄로 묶어 맨 끝, 뺀 줄은 넣지 않음. */
    fun table(score: ScoreResult): List<ScoreTableRow> = ScoreText.table(score)

    /** 줄의 둘째 칸(score.js `countText`): '1번' · G1 '기준보다 큼'·'기준과 비슷'·'기준 이하'·'측정 부족' · 뺀 줄 '못 읽음'. */
    fun countText(row: ScoreRow, score: ScoreResult): String = ScoreText.countText(row, score)

    /** 깎인 점수 글(score.js `lostText`): '14점 깎였어요' · 100점이면 '한 점도 안 깎였어요'. */
    fun lostText(score: ScoreResult): String = ScoreText.lostText(score)

    /** 못 읽은 신호 줄(score.js `excludedText`): '차속을 못 읽어 급출발·급정지·제한속도는 점수에서 뺐어요'. 뺀 줄이 없으면 null. */
    fun excludedText(score: ScoreResult): String? = ScoreText.excludedText(score)

    /** 표 아래 발밑 줄(score.js `footnotes`): 위험 회피 급제동 n번 · 늦음·거의 기준. */
    fun footnotes(score: ScoreResult): List<ScoreFootnote> = ScoreText.footnotes(score)

    /**
     * 점수 대사 고르기(score.js `line`, 설계 6.6.3): 90 이상 'rv.score.hi' · 70~89 'rv.score.mid' · 70 미만 'rv.score.lo'.
     * 변수 { S, dest, fixSib, fixMom, fixDad } — 감점 문구는 대사 'rv.score.fix.<가장 많이 깎인 곳>'의 코치별 첫 글. dest가 없으면 '목적지'.
     */
    fun line(score: ScoreResult, dest: String? = null): ScoreLine = ScoreText.line(score, dest)

    /** 지난 자율 점수(score.js `previous`: 최신이 앞인 기록에서 [recordId]보다 오래된 첫 자율 점수). 없으면 null. */
    fun previous(records: List<SessionRecord>, recordId: String?): PreviousScore? = ScoreText.previous(records, recordId)
}
