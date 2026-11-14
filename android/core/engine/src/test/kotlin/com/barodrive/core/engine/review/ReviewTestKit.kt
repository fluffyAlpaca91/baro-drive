package com.barodrive.core.engine.review

import com.barodrive.core.engine.content.Items
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.Profile
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.StageProgress

/**
 * 회차 뒤 계산 시험 도우미 (프로토타입 tests/unit/t2a_review.test.js·t4_score.test.js의 `ev`·`many`·`rec`·`itemsOf`·`profile`).
 */
internal object ReviewTestKit {
    val OK = EventResult.OK
    val FAIL = EventResult.FAIL
    val LATE = EventResult.LATE
    val ALMOST = EventResult.ALMOST
    val INFO = EventResult.INFO
    val EXCLUDED = EventResult.EXCLUDED

    private var seq = 0

    /** 판정 하나(시각은 1씩 늘림): ev('B1', OK) · ev('E1', FAIL, EventData(limit = 30.0, zoneId = "z1")). */
    fun ev(item: String, result: EventResult, data: EventData = EventData.EMPTY, pre: Boolean = false, t: Double? = null): JudgeEvent =
        JudgeEvent(t = t ?: (++seq).toDouble(), item = item, result = result, data = data, pre = pre)

    fun many(n: Int, item: String, result: EventResult, data: EventData = EventData.EMPTY): List<JudgeEvent> =
        List(n) { ev(item, result, data) }

    /**
     * 그 모드 첫 회차의 전달 방식(프로토타입 `E.resolveItems(mode, null, { noInMode: 1 })` — 프로필이 없고 1회차면 기본값 그대로)
     * + [over].
     */
    fun itemsOf(mode: Int, over: Map<String, Delivery> = emptyMap()): Map<String, Delivery> =
        Items.ids().associateWith { Items.def(it)?.defaultFor(mode) ?: Delivery.OFF } + over

    /** 판정 기록으로 회차 하나(t2a `rec`): { mode, items: itemsOf(mode), events, metrics: {}, f2: [] }. */
    fun rec(
        mode: Int,
        events: List<JudgeEvent>,
        items: Map<String, Delivery> = itemsOf(mode),
        metrics: SessionMetrics = SessionMetrics(),
    ): SessionRecord = SessionRecord(mode = mode, items = items, events = events, metrics = metrics)

    /** 온보딩을 마친 기본 프로필, 지금 단계 [stage](t2a `profile`). */
    fun profile(stage: Int = 1): Profile =
        Profile().copy(progress = StageProgress(currentStage = stage, unlockedStage = stage))
}
