package com.barodrive.core.engine.detect.misc

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.detect.basic.DRIVE_MODES
import com.barodrive.core.engine.detect.basic.isIc
import com.barodrive.core.engine.round1
import com.barodrive.core.engine.review.round2
import com.barodrive.core.model.BrakeEndEvent
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.InputEvent
import com.barodrive.core.model.RerouteEvent
import com.barodrive.core.model.ReviewTip
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.ZoneEnter
import com.barodrive.core.model.ZoneExit
import kotlin.math.max

/** E3 리뷰 줄 글(설계 2.7 해요체, fallback.js `e3Text`). */
internal fun e3Text(n: Int): String = "내비가 과속을 알려 준 구간 ${n}곳 · 차속을 못 읽어 내비 경고로만 셌어요"

/** E3 구역 하나. */
private class E3Zone(val id: String, val limit: Double?, val school: Boolean) {
    var overSince: Double? = null
    var fired = false
    var peak = 0.0
}

/**
 * fallback.E3 — 차속을 못 읽을 때만 깨어나는 대안(프로토타입 detectors/fallback.js, 설계 7.8 '대신' 열, 결정-51, 결정사항 E T10-8).
 * E1이 꺼진 대신 내비 속도(추적기가 트립 거리로 어림한 v)가 제한속도(날씨 배율)를 3초 넘게 넘으면 그 구역 1번 fail
 * `{zoneId, limit, effLimit, school, peak, overSec, via: 'navi'}`. 말은 없고 회차 끝에 리뷰 줄 하나.
 * 24개 항목이 아니라 items 없음(늘 켬). needs nav: 내비를 못 읽으면 제한속도도 몰라 함께 꺼진다.
 */
internal val E3 = DetectorDef(id = "fallback.E3", needs = listOf(SignalKey.NAV), modes = DRIVE_MODES) { ctx ->
    if (ctx.cfg.caps.speed) return@DetectorDef DetectorInstance.NONE // 차속을 읽으면 E1이 본다
    var zone: E3Zone? = null
    var count = 0
    ctx.on<ZoneEnter> { z -> zone = E3Zone(z.id, z.limit, z.school) }
    ctx.on<ZoneExit> { z -> if (zone?.id == z.id) zone = null }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val z = zone ?: return
            val v = ctx.track.v ?: return // 내비 속도 흉내: 트립 거리 변화로 어림한 값
            if (z.fired || snap.gear == Gear.P) return
            val eff = ctx.limit(z.limit) ?: return
            if (v > eff + EPS) {
                val since = z.overSince ?: snap.t.also { z.overSince = it }
                z.peak = max(z.peak, v)
                if (snap.t - since > ctx.th("E1.sec") + EPS) {
                    z.fired = true
                    count++
                    ctx.emit(
                        "E3", EventResult.FAIL,
                        EventData(
                            zoneId = z.id, limit = z.limit, effLimit = eff.toDouble(), school = z.school, peak = round1(z.peak),
                            overSec = round1(snap.t - since), via = "navi",
                        ),
                    )
                }
            } else {
                z.overSince = null
            }
        }

        override fun finish(rec: RecordDraft) {
            if (count > 0) ctx.noteTip(ReviewTip(text = e3Text(count), icon = "i-slow", kind = "E3"))
        }
    }
}

/** K8 답을 고를 회전 (k8.js lastTurn). [okAt] = C2 성공 기준(40 · IC 110). */
internal class K8Turn(val t: Double, val id: String, val distance: Double?, val okAt: Double)

/** K8 답을 고를 정지 (k8.js lastStop). [fixAt] = F1 기준(7.5 · 날씨 6). */
internal class K8Stop(val t: Double, val drop: Double, val fixAt: Double)

/** K8 답 (k8.js `choose` 결과). */
internal data class K8Answer(val kind: String, val key: String, val vars: Map<String, Any?>, val turnId: String?, val distance: Double?, val maxDrop1s: Double?, val ago: Double?)

/** 직전 30 sim초(설계 6.5.5). */
private const val K8_WINDOW = 30.0

/**
 * 운전자 질문 답 고르기 (k8.js `choose`): 직전 30초 안에 끝난 회전·정지 가운데 가장 최근 것 하나.
 * 회전: 켠 거리 ≥ C2 성공 기준이면 'k8.turn.ok' { N }, 아니면 'k8.turn.late' · 정지: 1초 최대 감소 ≥ F1 기준이면 'k8.stop.fix', 아니면
 * 'k8.stop.ok' · 없으면 'k8.none'.
 */
internal fun chooseK8Answer(now: Double, lastTurn: K8Turn?, lastStop: K8Stop?): K8Answer {
    fun fresh(t: Double) = now - t <= K8_WINDOW + EPS && now >= t - EPS
    val turn = lastTurn?.takeIf { fresh(it.t) }
    val stop = lastStop?.takeIf { fresh(it.t) }
    if (turn != null && (stop == null || turn.t >= stop.t)) {
        val dist = turn.distance
        val ok = dist != null && dist >= turn.okAt - EPS
        return K8Answer(
            "turn", if (ok) "k8.turn.ok" else "k8.turn.late", if (ok && dist != null) mapOf("N" to dist) else emptyMap(), turn.id,
            dist?.let(::round1), null, round1(now - turn.t),
        )
    }
    if (stop != null) {
        val fix = stop.drop >= stop.fixAt - EPS
        return K8Answer("stop", if (fix) "k8.stop.fix" else "k8.stop.ok", emptyMap(), null, null, stop.drop, round1(now - stop.t))
    }
    return K8Answer("none", "k8.none", emptyMap(), null, null, null, null)
}

/**
 * k8.answer — "방금 어땠어?" 운전자 질문 답(프로토타입 detectors/k8.js, 설계 6.5.5, 결정-50). 모든 모드, items 없음(늘 O).
 * 재탐색으로 놓친·건너뛴 회전과 위험 회피 급제동은 고르지 않는다. 깜빡이를 못 읽으면 회전, 차속을 못 읽으면 정지를 고르지 않는다.
 * 판정 K8 info `{kind, key, turnId?, distance?, maxDrop1s?, ago}` 하나와 말 후보 하나(분류 '안내' + answer — 간격 규칙만 예외).
 */
internal val K8 = DetectorDef(id = "k8.answer", modes = null) { ctx ->
    val blindTurn = !ctx.cfg.caps.blinker
    val blindStop = !ctx.cfg.caps.speed
    var lastTurn: K8Turn? = null
    var lastStop: K8Stop? = null
    ctx.on<TurnPassed> { tp ->
        if (tp.missed || tp.skipped) { // 놓친·건너뛴 회전은 답으로 고르지 않는다
            if (lastTurn?.id == tp.id) lastTurn = null
            return@on
        }
        if (blindTurn) return@on
        lastTurn = K8Turn(ctx.t, tp.id, tp.blinkOnDist, ctx.th("C2.ok", isIc(tp.ic, tp.type)))
    }
    ctx.on<RerouteEvent> { r -> if (lastTurn != null && r.turnId == lastTurn?.id) lastTurn = null }
    ctx.on<BrakeEndEvent> { b ->
        if (blindStop || !b.eval || !b.stopped) return@on
        val drop = b.maxDrop1s
        if (drop >= ctx.th("F2.drop") - EPS) return@on // 위험 회피 급제동은 답으로 고르지 않는다
        lastStop = K8Stop(ctx.t, round2(drop), ctx.th("F1.drop"))
    }
    ctx.on<InputEvent> { ev ->
        if (ev.input != SessionInput.DriverAsk) return@on
        val a = chooseK8Answer(ctx.t, lastTurn, lastStop)
        ctx.emit(
            "K8", EventResult.INFO,
            EventData(kind = a.kind, key = a.key, ago = a.ago, turnId = a.turnId, distance = a.distance, maxDrop1s = a.maxDrop1s),
        )
        ctx.say(a.key, item = "K8", vars = a.vars, category = Category.INFO, answer = true)
    }
    DetectorInstance.NONE
}
