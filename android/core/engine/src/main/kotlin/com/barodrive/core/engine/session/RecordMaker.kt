package com.barodrive.core.engine.session

import com.barodrive.core.engine.api.EngineReview
import com.barodrive.core.engine.api.EngineScore
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.review.jsRound
import com.barodrive.core.engine.round1
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.SessionMetrics
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.SessionWeather
import com.barodrive.core.model.ShareState
import kotlinx.serialization.json.JsonObject

/**
 * 회차 기록 만들기 (설계 6.9·8.1, 프로토타입 core.js `finalize`). 검출기 finish가 끝난 뒤 부른다.
 * - 판정: 출발 전 사건(`pre: true`)이 앞. F2는 빼서 `f2`로, 재탐색·건너뛰기로 놓친 회전의 판정은 `excluded`(+ `was`·`excluded`).
 * - K6: 주행 중(P 아님) 실제로 말한 것만 센다. 거리: 트립 거리 변화, 안 움직이면 속도를 쌓은 값.
 */
internal fun SessionCore.buildRecord(draft: RecordDraft, arrived: Boolean): SessionRecord {
    val start = t0
    val simSec = round1(if (start == null) 0.0 else now() - start)
    val drive = round1(driveSec)
    val a = trip0
    val b = tripLast
    val tripM = if (a != null && b != null) b - a else 0.0
    val w = notes.weather
    val pre = cfg.preEvents.map { it.copy(pre = true) }
    val main = ArrayList<JudgeEvent>()
    val f2Events = ArrayList<JudgeEvent>()
    for (e in events) {
        if (e.item == F2) {
            f2Events += e // F2 정지는 기록에서 빼고 본인 확인용으로 따로(6.9)
            continue
        }
        val why = droppedWhy(e)
        main += if (why != null && e.result != EventResult.EXCLUDED) {
            e.copy(result = EventResult.EXCLUDED, data = e.data + EventData(was = e.result, excluded = why))
        } else {
            e
        }
    }
    val f2Rows = notes.f2 ?: f2Events.map { e ->
        val d = e.data
        F2Check(t = d.t ?: e.t, v0 = d.v0, v1 = d.v1, dur = d.dur, maxDrop1s = d.maxDrop1s, stopped = d.stopped)
    }
    val counted = rules.spoken.filter { it.counted } // K6: 주행 중(P 아님) 실제로 말한 것(6.10.5)
    val m = draft.metrics
    return SessionRecord(
        mode = cfg.mode, stage = cfg.stage, noInMode = cfg.noInMode, courseId = cfg.courseId, placeId = cfg.placeId,
        presetId = cfg.presetId, startedAt = cfg.startedAt, durationSec = simSec, driveSec = drive,
        distanceM = jsRound(if (tripM > 0) tripM else distM), arrived = arrived,
        weather = SessionWeather(
            type = weather.type, severe = weather.severe, cold = weather.cold, by = w?.by, at = w?.at, tipCards = w?.tipCards,
        ),
        caps = cfg.caps, items = cfg.items, events = pre + main, utterances = utterances.toList(),
        metrics = SessionMetrics(
            c2Avg = m.c2Avg, c2N = m.c2N, stopTotal = m.stopTotal, stopFail = m.stopFail, stopFailRate = m.stopFailRate, g1 = m.g1,
            g1Windows = m.g1Windows, g1Better = m.g1Better,
            per10min = if (drive > 0) round1(counted.size / (drive / TEN_MIN)) else 0.0,
            praise = counted.count { it.category == Category.PRAISE }, correction = counted.count { it.category == Category.FIX },
            spoken = counted.size,
        ),
        f2 = f2Rows.mapIndexed { i, x -> x.copy(id = "f2-${i + 1}") },
        hill = notes.hill, parking = notes.parking, license = notes.license, tips = notes.tips?.toList().orEmpty(),
        notes = notes.rest.takeIf { it.isNotEmpty() }?.let { JsonObject(LinkedHashMap(it)) },
        share = ShareState(),
    )
}

/** 리뷰 요약(review.summarize)을 합치고, 자율 모드면 점수(score.compute)를 붙인다 (core.js `runHooks`). */
internal fun SessionCore.runHooks(rec: SessionRecord): SessionRecord {
    var r = rec
    try {
        r = r.withReview(EngineReview.summarize(r, cfg))
    } catch (e: Exception) {
        logError("리뷰 계산 오류", e)
    }
    if (cfg.mode == AUTONOMOUS) {
        try {
            r = r.copy(score = EngineScore.compute(r, cfg))
        } catch (e: Exception) {
            logError("점수 계산 오류", e)
        }
    }
    return r
}

/** 재탐색으로 놓친('reroute')·건너뛴('skipped') 회전의 판정인가 (core.js `droppedWhy`). */
private fun SessionCore.droppedWhy(e: JudgeEvent): String? {
    val key = e.data.turnId ?: return null
    return when {
        key in missed -> "reroute"
        key in skipped -> "skipped"
        else -> null
    }
}

private const val F2 = "F2"
private const val AUTONOMOUS = 5
private const val TEN_MIN = 600.0
