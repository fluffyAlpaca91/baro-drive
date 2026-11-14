package com.barodrive.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 판정 기록 한 줄 (설계 6.9 `{t, item, result, data}`, 프로토타입 검출기 `ctx.emit` → `rec.events[]`·세션 출력 `events[]`).
 * 항목별 `data` 모양은 [EventData] KDoc(basic.js·safety3.js·hill.js·parking.js·license.js·weather.js·k8.js·fallback.js 머리 주석).
 *
 * - 재탐색으로 놓친·건너뛴 회전의 판정은 회차 끝에 [EventResult.EXCLUDED]가 되고 `data.was`·`data.excluded`가 붙는다(core.js finalize).
 * - F2 정지는 `events`에서 빠지고 기록의 `f2`로 간다.
 */
@Serializable
data class JudgeEvent(
    /** 판정한 시각, 세션 초. */
    val t: Double = 0.0,
    /** 항목 id(A1, C2 … 24개, 그 밖에 K8·M2·E3·A5). 없으면 null. */
    val item: String? = null,
    val result: EventResult = EventResult.INFO,
    val data: EventData = EventData.EMPTY,
    /** 출발 전(PR-1)에 앱이 넣은 사건(준비 세션 `preEvents` → 기록 맨 앞). 리뷰·점수·도움 줄여 가기는 세지 않는다. */
    val pre: Boolean = false,
) {
    /** 판정(ok·almost·late·fail)이고 출발 전 사건이 아님 — 리뷰·도움 줄여 가기가 세는 것(review.js `judged`). */
    val isJudged: Boolean get() = result.isJudged && !pre
}

/** 판정 결과 (프로토타입 core.js `RESULTS`). */
@Serializable
enum class EventResult(val code: String) {
    /** 성공. */
    @SerialName("ok")
    OK("ok"),

    /** 거의(C2 30~39m). */
    @SerialName("almost")
    ALMOST("almost"),

    /** 늦음(C2 30m 안). */
    @SerialName("late")
    LATE("late"),

    /** 실패. */
    @SerialName("fail")
    FAIL("fail"),

    /** 기록만(판정이 아님 — C1 후보, G1 값, J3 안내, K8 답 …). */
    @SerialName("info")
    INFO("info"),

    /** 판정에서 뺌(재탐색·건너뛰기·F2 직후 출발). */
    @SerialName("excluded")
    EXCLUDED("excluded"),
    ;

    /** 리뷰·도움 줄여 가기가 세는 판정인가(ok·almost·late·fail, review.js `JUDGED`). */
    val isJudged: Boolean get() = this == OK || this == ALMOST || this == LATE || this == FAIL

    companion object {
        fun fromCode(code: String?): EventResult? = entries.firstOrNull { it.code == code }
    }
}
