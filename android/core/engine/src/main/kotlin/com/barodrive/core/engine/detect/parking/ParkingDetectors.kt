package com.barodrive.core.engine.detect.parking

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.content.ParkingSignal
import com.barodrive.core.engine.content.ParkingStep
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.round1
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.InputEvent
import com.barodrive.core.model.JudgeEvent
import com.barodrive.core.model.ParkingHandbrake
import com.barodrive.core.model.ParkingStepLog
import com.barodrive.core.model.ParkingWheel
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs
import kotlin.math.max

/*
 * 실제 주차 안내 검출기 (프로토타입 detectors/parking.js PK1·PK2·PK3, 설계 6.12.1·6.3.1·6.5.1·7.8, 결정-60·61).
 * 모드 6에서만 돈다. 사이드브레이크(PB2)는 basic.PB2가 판정하고, 회차 끝에 PK1이 그 결과를 rec.parking.handbrake로 옮겨 적는다.
 */

private val MODES = listOf(6)

/** 핸들 끝까지 = 최대각 × 0.9 (설계 6.12.1). */
private const val FULL_RATIO = 0.9

/** 스냅숏에 차종 최대각이 없을 때(아반떼, 결정-58). */
private const val DEFAULT_MAX_STEER = 34.0

/** 단계 문장 말 후보가 기다리는 최대 시간(초). */
private const val STEP_SAY_SEC = 60.0

/** PK2 '후진 중'으로 보는 뒤로 가는 속도(기어를 못 읽을 때, km/h). */
private const val BACK_V = -0.5

/** 건너뛰기 장면 이름의 'n단계' (parking.js `sync`의 정규식 — `\s`는 JS가 잡는 공백 그대로). */
private val SKIP_STEP = Regex("([0-9]+)[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]*단계")

/** 주차 힌트를 다시 낸다 (parking.js `publish`). 회차 결과 칸은 [ParkingState.result]가 상태에서 바로 만든다. */
private fun DetectorContext.publish(s: ParkingState) {
    hints.parking = s.view()
}

/**
 * PK1 주차 단계 기계 (parking.js 'parking.PK1'): 종류·방향 단계 표([ParkingStep]) → 신호로 넘김(한 스냅숏에 한 단계) ·
 * 단계 문장 말(안내, 그 단계가 지나면 만료) · 화면 힌트 hints.parking · 다 마치면 hints.arrived(결정사항 E T5b-Q1) · 회차 결과.
 * 판정: 단계를 마칠 때마다 PK1 ok `{step, signal, by, last}`, 건너뛴 단계는 excluded `{step, signal, excluded: 'skipped'}`.
 * [다음 단계](pkNext)는 어느 단계든 넘긴다 — step이 붙어 있으면 그 단계일 때만.
 */
internal val PK1 = DetectorDef(id = "parking.PK1", items = listOf("PK1"), modes = MODES) { ctx ->
    val s = ctx.parking()
    s.noted = true

    fun say(idx: Int) {
        val st = s.steps[idx]
        ctx.say(st.key, item = "PK1", vars = st.vars, category = Category.INFO, expiresIn = STEP_SAY_SEC, alive = { !s.done && s.cur == idx })
    }

    fun enter(t: Double, quiet: Boolean) {
        s.stepT = t
        s.moved = ctx.track.moving
        s.straightSince = null
        if (!quiet) say(s.cur)
    }

    fun begin(t: Double) {
        s.started = true
        s.startT = t
        enter(t, false)
        ctx.publish(s)
    }

    /** 지금 단계를 마친다. by: 'signal' · 'press' · 'skip'. */
    fun complete(by: String, t: Double, quiet: Boolean) {
        val st = s.steps[s.cur]
        val last = s.cur == s.steps.size - 1
        s.logs += ParkingStepLog(n = st.n, signal = st.signal.id, by = by, t = s.since(t))
        if (by == VIA_PRESS) s.pressed++
        if (by == SKIP) {
            ctx.emit("PK1", EventResult.EXCLUDED, EventData(step = st.n, signal = st.signal.id, excluded = "skipped"))
        } else {
            ctx.emit("PK1", EventResult.OK, EventData(step = st.n, signal = st.signal.id, by = by, last = last))
        }
        if (last) {
            s.done = true
            s.doneT = t
            s.sec = s.since(t)
            ctx.hints.arrived = true // 세션 연결: 다음 P(지금이 P면 바로)에서 마무리 → PK-4(결정사항 E T5b-Q1)
        } else {
            s.cur++
            enter(t, quiet)
        }
        ctx.publish(s)
    }

    fun met(st: ParkingStep, snap: VehicleSnapshot, t: Double): Boolean {
        val tr = ctx.track
        return when (st.signal) {
            ParkingSignal.STOP -> when {
                !s.moved -> false
                snap.gear == Gear.P -> true
                else -> tr.stopped && tr.stopDur >= ctx.th("stop.hold") - EPS && snap.brake == true
            }
            ParkingSignal.GEAR_R -> snap.gear == Gear.R
            ParkingSignal.GEAR_P -> snap.gear == Gear.P
            ParkingSignal.STEER_FULL -> {
                val steer = snap.steer ?: return false
                val max = snap.maxSteer?.takeIf { it > 0 } ?: DEFAULT_MAX_STEER
                val need = max * FULL_RATIO - EPS
                if (st.dir == "L") steer <= -need else steer >= need
            }
            ParkingSignal.STEER_STRAIGHT -> s.straightSince.let { it != null && t - it >= ctx.th("steer.endSec") - EPS }
            ParkingSignal.PRESS -> false
        }
    }

    /** 건너뛰기: 장면 이름 'n단계'까지 맞춘다. 지나친 단계는 말하지 않고 excluded로. */
    fun sync(snap: VehicleSnapshot, t: Double) {
        val want = SKIP_STEP.find(snap.skipInfo?.label.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()
        if (want != null) {
            while (!s.done && s.stepNo < want) complete(SKIP, t, s.stepNo + 1 < want)
        }
        s.moved = true // 건너뛴 사이에 움직였는지 알 수 없으니 움직인 것으로 본다
    }

    ctx.on<InputEvent> { e ->
        val ev = e.input as? SessionInput.PkNext ?: return@on
        if (!s.started || s.done) return@on
        val step = ev.step
        if (step != null && step != s.stepNo) return@on // 늦게 온 누름
        complete(VIA_PRESS, ctx.t, false)
    }

    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val t = snap.t
            s.snap = snap
            snap.model?.takeIf { it.isNotEmpty() }?.let { s.model = it }
            if (snap.gear == Gear.P && s.prevGear != Gear.P) s.lastPT = t
            snap.gear?.let { s.prevGear = it }
            if (!s.started) begin(t)
            if (snap.skip) sync(snap, t)
            if (s.done) return
            if (ctx.track.moving) s.moved = true
            val st = s.steps[s.cur]
            if (st.signal == ParkingSignal.STEER_STRAIGHT) {
                val steer = snap.steer
                if (steer != null && abs(steer) <= ctx.th("steer.end") + EPS) {
                    if (s.straightSince == null) s.straightSince = t
                } else {
                    s.straightSince = null
                }
            }
            if (s.via[s.cur] == VIA_SIGNAL && met(st, snap, t)) complete(VIA_SIGNAL, t, false)
        }

        override fun finish(rec: RecordDraft) {
            if (!s.done) s.sec = s.since(ctx.t)
            // 사이드브레이크: 마지막으로 P에 들어간 뒤의 PB2 판정(basic.PB2). 없으면 마지막 스냅숏 값만
            val lastP = s.lastPT
            var pb: JudgeEvent? = null
            for (e in rec.events) if (e.item == "PB2" && (lastP == null || e.t >= lastP - EPS)) pb = e
            val on = s.snap?.handbrake
            s.handbrake = when {
                pb != null -> ParkingHandbrake(result = pb.result, on = if (pb.result == EventResult.OK) true else on)
                on != null -> ParkingHandbrake(result = null, on = on)
                else -> null
            }
            s.note(ctx)
        }
    }
}

/**
 * PK2 후진 천천히 (parking.js 'parking.PK2'): 기어 R(기어를 못 읽으면 뒤로 가는 중)이고 |속도| > 7이 1초 → fail
 * `{speed(가장 빠른 |속도|), n, step}` + 교정 말 'pk2'(R인 동안만 살아 있음). 다시 하려면 15초(설계 6.3.1).
 */
internal val PK2 = DetectorDef(id = "parking.PK2", items = listOf("PK2"), needs = listOf(SignalKey.SPEED), modes = MODES) { ctx ->
    var overSince: Double? = null
    var peak = 0.0
    fun inR(snap: VehicleSnapshot): Boolean = snap.gear == Gear.R || (snap.gear == null && (snap.speed ?: 0.0) < BACK_V)
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val s = ctx.parking()
            val t = snap.t
            val v = snap.speed
            if (!(inR(snap) && v != null && abs(v) > ctx.th("PK2.v") + EPS)) {
                overSince = null
                peak = 0.0
                return
            }
            val since = overSince ?: t.also { overSince = it }
            peak = max(peak, abs(v))
            if (t - since < ctx.th("PK2.sec") - EPS) return
            val lastT = s.pk2LastT
            if (lastT != null && t - lastT < ctx.th("PK2.repeat") - EPS) return // 다시 말하려면 15초
            s.pk2LastT = t
            s.pk2++
            ctx.emit("PK2", EventResult.FAIL, EventData(speed = round1(peak), n = s.pk2, step = if (s.started) s.stepNo else null))
            ctx.say("pk2", item = "PK2", category = Category.FIX, alive = { !s.done && s.snap?.let(::inR) == true })
            ctx.publish(s)
        }
    }
}

/**
 * PK3 주차 후 바퀴 똑바로 (parking.js 'parking.PK3'): 단계를 다 마치고 기어 P(기어를 못 읽으면 그때)에서 |핸들| > 5° → fail + 안내 말
 * 'pk3', 아니면 ok `{steer, threshold}`. 다 마치지 못하고 끝난 회차(DR-3 '여기서 마치기')도 끝 P의 바퀴는 적는다(말은 없음).
 */
internal val PK3 = DetectorDef(id = "parking.PK3", items = listOf("PK3"), needs = listOf(SignalKey.STEER), modes = MODES) { ctx ->
    fun judge(steer: Double, speak: Boolean) {
        val s = ctx.parking()
        s.wheelJudged = true
        val lim = ctx.th("PK3.steer")
        val bad = abs(steer) > lim + EPS
        ctx.emit("PK3", if (bad) EventResult.FAIL else EventResult.OK, EventData(steer = round1(steer), threshold = lim))
        s.wheel = ParkingWheel(steer = round1(steer), straight = !bad)
        if (bad && speak) ctx.say("pk3", item = "PK3", category = Category.INFO)
    }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val s = ctx.parking()
            val steer = snap.steer
            if (s.wheelJudged || !s.done || steer == null) return
            if (snap.gear == Gear.P || snap.gear == null) judge(steer, true)
        }

        override fun finish(rec: RecordDraft) {
            val s = ctx.parking()
            val sn = s.snap
            val steer = sn?.steer
            if (!s.wheelJudged && sn != null && steer != null && (sn.gear == Gear.P || sn.gear == null)) judge(steer, false)
            s.note(ctx)
        }
    }
}

/** 건너뛰기로 지나친 단계(parking.js by 'skip'). */
private const val SKIP = "skip"
