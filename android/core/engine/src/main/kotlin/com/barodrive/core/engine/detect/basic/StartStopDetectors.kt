package com.barodrive.core.engine.detect.basic

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.review.jsRound
import com.barodrive.core.engine.num
import com.barodrive.core.engine.round1
import com.barodrive.core.engine.review.round2
import com.barodrive.core.model.BrakeEndEvent
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.StartEvent

/*
 * 출발·정지 검출기 (프로토타입 detectors/basic.js B1·F1·F2, 설계 6.3.1, 6.5.4 칭찬 규칙, 결정-29·69).
 */

/**
 * B1 부드러운 출발: 출발마다 1초 최대 증가량 > 11(날씨 8)이면 fail + 교정 카드, 아니면 ok(칭찬은 6.5.4).
 * 위험 회피 급제동으로 멈춘 바로 뒤 출발은 excluded `{reason: 'F2'}`.
 */
internal val B1 = DetectorDef(id = "basic.B1", items = listOf("B1"), needs = listOf(SignalKey.SPEED), modes = DRIVE_MODES) { ctx ->
    val pr = PraiseState()
    ctx.on<StartEvent> { s ->
        val sh = ctx.basic()
        val gain = round2(s.maxGain1s)
        val th = ctx.th("B1.gain")
        if (sh.afterF2) {
            sh.afterF2 = false
            ctx.emit("B1", EventResult.EXCLUDED, EventData(maxGain1s = gain, threshold = th, gear = s.gear, reason = "F2"))
            return@on
        }
        val fail = gain > th + EPS
        ctx.emit("B1", if (fail) EventResult.FAIL else EventResult.OK, EventData(maxGain1s = gain, threshold = th, gear = s.gear))
        if (fail) {
            pr.afterFail = true
            ctx.card(
                item = "B1", key = "b1.fix", icon = iconOf("B1"), category = CardCategory.FIX, title = "출발이 조금 셌어요",
                detail = "처음 1초에 ${num(gain)}km/h 빨라졌어요",
            )
        } else if (ctx.praiseOnSuccess(pr)) {
            ctx.say("b1.ok", item = "B1", category = Category.PRAISE)
        }
    }
    DetectorInstance.NONE
}

/**
 * F1 부드러운 정지(결정-69: 감속만 한 제동도 평가): 평가하는 제동 구간마다 1초 최대 감소 ≥ 7.5(날씨 6)면 fail + 교정 카드.
 * F2 급제동(11 이상)은 뺀다. 칭찬은 실제로 멈춘 제동에만(결정사항 E T2a-4). 회차 지표 stopTotal·stopFail·stopFailRate.
 */
internal val F1 = DetectorDef(id = "basic.F1", items = listOf("F1"), needs = listOf(SignalKey.SPEED), modes = DRIVE_MODES) { ctx ->
    val pr = PraiseState()
    var total = 0
    var fails = 0
    ctx.on<BrakeEndEvent> { b ->
        if (!b.eval) return@on
        val drop = round2(b.maxDrop1s)
        if (drop >= ctx.th("F2.drop") - EPS) return@on // 위험 회피 급제동(F2)은 F1에서 뺀다
        val th = ctx.th("F1.drop")
        val fail = drop >= th - EPS
        total++
        if (fail) fails++
        ctx.emit(
            "F1", if (fail) EventResult.FAIL else EventResult.OK,
            EventData(v0 = round1(b.v0), v1 = round1(b.v1), dur = round1(b.dur), maxDrop1s = drop, stopped = b.stopped, threshold = th),
        )
        if (fail) {
            pr.afterFail = true
            ctx.card(
                item = "F1", key = "f1.fix", icon = iconOf("F1"), category = CardCategory.FIX,
                title = if (b.stopped) "조금 급하게 섰어요" else "조금 급하게 줄였어요",
                detail = if (b.stopped) {
                    "${num(jsRound(b.v0))}km/h에서 ${num(b.dur)}초 만에 정지 · 가장 셀 때 1초에 ${num(drop)}km/h"
                } else {
                    "${num(jsRound(b.v0))}km/h에서 ${num(jsRound(b.v1))}km/h로 · 가장 셀 때 1초에 ${num(drop)}km/h"
                },
            )
        } else if (b.stopped && ctx.praiseOnSuccess(pr)) {
            ctx.say("f1.ok", item = "F1", category = Category.PRAISE)
        }
    }
    object : DetectorInstance {
        override fun finish(rec: RecordDraft) {
            rec.metrics.stopTotal = total
            rec.metrics.stopFail = fails
            rec.metrics.stopFailRate = if (total > 0) jsRound(fails.toDouble() / total * RATE_SCALE) / RATE_SCALE else null
        }
    }
}

/**
 * F2 위험 회피 급제동(1초 감소 11 이상): 말하지 않는다(침묵은 코어가 건다, T1c-6). info + 리뷰 본인 확인 줄(`rec.f2`).
 * 멈췄으면 다음 출발은 B1에서 뺀다.
 */
internal val F2 = DetectorDef(id = "basic.F2", items = listOf("F2"), needs = listOf(SignalKey.SPEED), modes = DRIVE_MODES) { ctx ->
    ctx.on<BrakeEndEvent> { b ->
        val drop = round2(b.maxDrop1s)
        if (drop < ctx.th("F2.drop") - EPS) return@on
        val t = round1(b.t0)
        ctx.emit(
            "F2", EventResult.INFO,
            EventData(t = t, v0 = round1(b.v0), v1 = round1(b.v1), dur = round1(b.dur), maxDrop1s = drop, stopped = b.stopped),
        )
        ctx.noteF2(F2Check(t = t, v0 = round1(b.v0), v1 = round1(b.v1), dur = round1(b.dur), maxDrop1s = drop, stopped = b.stopped))
        if (b.stopped) ctx.basic().afterF2 = true
    }
    DetectorInstance.NONE
}

private const val RATE_SCALE = 1000.0
