package com.barodrive.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 자율 모드 안전운전 점수 (설계 6.11, 6.1.1 `score.compute` → `{total, deductions[], excluded[]}`, 프로토타입 score.js 점수 `sc`).
 * 100점에서 빼고 0점 아래로 내려가지 않는다. X로 고른 항목도 점수에 들어가고, 못 읽는 신호의 줄은 [excluded]로 뺀다.
 */
@Serializable
data class ScoreResult(
    /** 점수 0~100. */
    val total: Int = 100,
    /** 깎인 점수 = 100 − total. */
    val lost: Int = 0,
    /** 감점 합(100을 넘을 수 있음). */
    val sum: Int = 0,
    /** 설계 6.11 표 순서 7줄(뺀 줄도 excluded = true로). */
    val rows: List<ScoreRow> = emptyList(),
    /** 깎인 줄(points > 0, 순서 같음). */
    val deductions: List<ScoreRow> = emptyList(),
    /** 못 읽는 신호로 뺀 줄. */
    val excluded: List<ScoreExcluded> = emptyList(),
    /** 뺀 항목. */
    val excludedItems: List<String> = emptyList(),
    /** 뺀 줄의 못 읽은 신호. */
    val excludedSignals: List<SignalKey> = emptyList(),
    /** 감점하지 않은 위험 회피 급제동. */
    val f2: ScoreF2 = ScoreF2(),
    /** G1 계산 값. G1 줄을 뺐거나 값이 없으면 null. */
    val g1: ScoreG1? = null,
    /** 가장 많이 깎인 곳(감점 문구 자리 'C2'·'C4'·'C5'·'BF'·'E1'·'G1'). 없으면 null. */
    val top: String? = null,
)

/**
 * 점수 줄 하나 (score.js `ROWS`: 'C2.late' 3점 · 'C2.almost' 1점 · 'C4' 2점 · 'C5' 5점 · 'BF' 3점 · 'E1' 5점(같은 구역 1번) ·
 * 'G1' 최대 10점).
 */
@Serializable
data class ScoreRow(
    val id: String,
    val label: String = "",
    val short: String = "",
    /** 감점 문구 자리('C2'·'C4'·'C5'·'BF'·'E1'·'G1'). */
    val fix: String = "",
    /** 1번에 깎는 점수(G1은 null). */
    val unit: Int? = null,
    /** 최대 감점(G1 10). */
    val max: Int? = null,
    /** 이 줄을 세는 항목. */
    val items: List<String> = emptyList(),
    /** 횟수(G1은 null). */
    val count: Int? = null,
    val points: Int = 0,
    /** 못 읽는 신호로 뺀 줄. */
    val excluded: Boolean = false,
    /** 뺀 줄의 못 읽은 신호. */
    val signals: List<SignalKey>? = null,
)

/** 못 읽는 신호로 뺀 점수 줄 (RV-4 '…을 못 읽어 … 점수에서 뺐어요'). */
@Serializable
data class ScoreExcluded(
    val id: String,
    val label: String = "",
    val short: String = "",
    val signals: List<SignalKey> = emptyList(),
)

/** 감점하지 않은 위험 회피 급제동 수. */
@Serializable
data class ScoreF2(val count: Int = 0)

/** G1 점수 계산 값: min(10, round((값/기준 − 1) × 20)), 값 > 기준일 때만. 기준 = 첫 회차 값(없으면 0.8°). */
@Serializable
data class ScoreG1(
    val value: Double? = null,
    val base: Double = 0.8,
    /** 기준이 첫 회차 값인가(아니면 0.8°). */
    val baseFirst: Boolean = false,
    /** 값 / 기준(소수 둘째 자리). */
    val ratio: Double? = null,
    /** 직진 창 3개 미만('측정 부족'). */
    val insufficient: Boolean = false,
    val points: Int = 0,
)

/** RV-4 감점 표 한 줄 (score.js `table`): 0번 줄은 묶어서 맨 끝 한 줄(id 'zero'). */
@Serializable
data class ScoreTableRow(
    val id: String,
    /** 묶은 줄 id들('zero'일 때). */
    val ids: List<String>? = null,
    val label: String = "",
    /** '1번' · '측정 부족' · '0번'. */
    val count: String = "",
    /** '−3' · '0'. */
    val points: String = "",
    val zero: Boolean = false,
)

/** 표 아래 발밑 줄 (score.js `footnotes`). */
@Serializable
data class ScoreFootnote(
    val icon: String? = null,
    val text: String = "",
    val muted: Boolean = false,
)

/**
 * 점수 대사 고르기 (score.js `line`, 설계 6.6.3): 90 이상 'rv.score.hi' · 70~89 'rv.score.mid' · 70 미만 'rv.score.lo'.
 * [vars] = `{S, dest, fixSib, fixMom, fixDad}`.
 */
@Serializable
data class ScoreLine(
    val key: String,
    /** 가장 많이 깎인 곳(감점 문구 자리). */
    val fix: String = "C2",
    val vars: JsonObject = JsonObject(emptyMap()),
)

/** 지난 자율 점수 (score.js `previous`: "지난번 82점 → 오늘 86점"). */
@Serializable
data class PreviousScore(
    val recordId: String? = null,
    val total: Int,
)
