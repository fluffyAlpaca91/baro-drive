package com.barodrive.core.engine.detect.hill

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.round1
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.InputEvent
import com.barodrive.core.model.SessionInput
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * 언덕 검출기 셋 (프로토타입 detectors/hill.js HL1·HL2·HL3, 설계 6.12.2·6.3.1·6.4·6.5.1·7.8, 결정-61·83). 모드 7에서만 돈다.
 * 등록 순서대로 돈다(HL1 → HL2 → HL3) — HL2의 밀림 표시는 다음 스냅숏 힌트에, HL3 판정은 HL1이 P를 본 같은 스냅숏에서.
 * PB2(주차 후 사이드브레이크)는 basic.PB2가 한다 — 모드 7에서는 'pb2.hill' 문장.
 */

private val MODES = listOf(7)

/** 표본 사이 시간을 셀 때 한 번에 더하는 최대(초). */
private const val STEP_MAX = 0.5

/** hl2 말 후보가 기다리는 최대 시간(그 순간 일이라 짧게). */
private const val ROLL_SAY_SEC = 6.0

/** 언덕 주차 P 때 hl3 말 우선순위 — 안내(2)와 같게 두어 먼저 생긴 HL3가 pb2.hill보다 먼저 나온다(결정사항 E T6-12). */
private const val HL3_PRIORITY = 2

/**
 * HL1 언덕 단계 기계 (hill.js 'hill.HL1'): 연습 셋 · 단계 넘김 · 단계 문장 말 · 내리막 나눠 밟기 세기 · 언덕 주차 P 순간과
 * 사이드브레이크 3초 · 화면 힌트 hints.hill · 회차 결과 rec.hill. 판정: HL1 ok|fail `{practice:'down', run, presses, holds}`(내리막 구간마다),
 * HL2 ok `{attempt}`(밀림 없이 끝난 시도 — 뒤로 가는 속도를 못 읽으면 내지 않음).
 * 화면 사건: hillPress · hillSituation · hillPractice(칩) · hillStep(단계 칸) — 칩·단계 칸은 멈춰 있을 때만 받는다.
 * 단계 문장은 차가 처음 움직일 때(3km/h) 말하기 시작한다 — 기어 D 순간에 말하면 20초 간격에 출발 전 안내가 막힌다(결정사항 E T6-3).
 */
internal val HL1 = DetectorDef(id = "hill.HL1", items = listOf("HL1"), modes = MODES) { ctx ->
    val st = ctx.hill()
    val m = HillMachine(ctx, st)
    ctx.on<InputEvent> { e ->
        when (val ev = e.input) {
            SessionInput.HillPress -> m.press()
            is SessionInput.HillSituation -> m.chooseSituation(ev.value)
            is SessionInput.PickHillPractice -> if (m.isStill()) m.choosePractice(ev.practice)
            is SessionInput.HillStep -> if (m.isStill()) m.chooseStep(ev.step)
            else -> return@on
        }
        if (st.snap != null) m.publish()
    }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val t = snap.t
            val dt = st.lastT?.let { max(0.0, min(STEP_MAX, t - it)) } ?: 0.0
            st.lastT = t
            st.snap = snap
            if (snap.skip) { // 건너뛰기: 재던 시간을 버림
                st.upCur?.fastSince = null
                st.downCur?.runSec = 0.0
            }
            val g = snap.gear
            val prev = st.gear
            if (g != null) st.gear = g
            if (g != null && g != prev) {
                if (g == Gear.P) {
                    if (st.practice == HillPractice.UP) {
                        m.closeUp(false)
                        m.setStep(HillPractice.UP, 1, quiet = true)
                    } else {
                        m.parkAtP(snap)
                    }
                } else if (prev == Gear.P && st.started) {
                    st.sayAgain = true // P에 들어갔다 나오면 다시 움직일 때 지금 단계 문장을 다시 말함
                }
            }
            val moving = abs(ctx.speedOf(snap) ?: 0.0) >= MOVE_V - EPS && st.gear != Gear.P
            if (moving && (!st.started || st.sayAgain)) {
                st.started = true
                st.sayAgain = false
                m.announce()
            }
            m.watchHandbrake(snap, dt)
            if (st.gear != Gear.P) {
                m.watchCautions(snap)
                if (!st.done) {
                    when (st.practice) {
                        HillPractice.UP -> m.stepUp(snap)
                        HillPractice.DOWN -> if (st.step == 2) m.countBrake(snap, dt)
                        HillPractice.PARK -> m.stepPark(snap)
                    }
                }
            }
            m.publish()
        }

        /** 끝나지 않은 것: 브레이크를 뗀 오르막 시도는 세고, 내리막 2단계는 지금까지로 판정, 사이드브레이크 창은 마지막 값으로 닫음. */
        override fun finish(rec: RecordDraft) {
            if (st.practice == HillPractice.UP) m.closeUp(false)
            if (st.practice == HillPractice.DOWN && !st.done && st.downCur?.step2 == true) m.judgeDown()
            val hb = st.snap?.handbrake
            for (p in st.parkList) {
                val w = p.hb
                if (w != null && w.open) w.close(stateOf(hb))
            }
            ctx.noteHill(st.result())
        }
    }
}

/**
 * HL2 오르막 출발 때 뒤로 밀림 (hill.js 'hill.HL2', 안전): 오르막 3·4단계에서 기어 D(못 읽으면 D로 봄)이고 speed < −0.5가 0.5초 →
 * 그 시도에 1번 fail `{attempt, speed(가장 느린 음수), dur}` + 안전 말 'hl2'. 차속·뒤로 가는 속도를 못 읽으면 꺼진다(설계 7.8).
 */
internal val HL2 = DetectorDef(
    id = "hill.HL2", items = listOf("HL2"), needs = listOf(SignalKey.SPEED, SignalKey.REVERSE_SPEED), modes = MODES,
) { ctx ->
    val st = ctx.hill()
    var since: Double? = null
    var minV = 0.0
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val a = st.upCur
            val v = snap.speed
            val on = st.practice == HillPractice.UP && !st.done && (st.step == 3 || st.step == 4) && a != null && !a.rolled &&
                (snap.gear == null || snap.gear == Gear.D) && v != null && v < ctx.th("HL2.v") - EPS
            if (!on) {
                since = null
                return
            }
            val s0 = since ?: snap.t.also {
                since = it
                minV = v
            }
            minV = min(minV, v)
            if (snap.t - s0 >= ctx.th("HL2.sec") - EPS) {
                a.rolled = true
                a.rollT = snap.t
                ctx.emit("HL2", EventResult.FAIL, EventData(attempt = a.n, speed = round1(minV), dur = round1(snap.t - s0)))
                ctx.say("hl2", item = "HL2", category = Category.SAFETY, expiresIn = ROLL_SAY_SEC)
                since = null
            }
        }
    }
}

/**
 * HL3 언덕 주차 바퀴 방향 (hill.js 'hill.HL3'): 언덕 주차에서 기어 P가 될 때 앞바퀴가 맞는 쪽으로 8° 이상 → ok + 칭찬 말 'hl3.ok',
 * 아니면 fail + 교정 말 'hl3.wrong'(`{dir}` = 맞는 쪽) — `{situation, need, steer, side}`. 핸들을 못 읽으면 꺼진다.
 */
internal val HL3 = DetectorDef(id = "hill.HL3", items = listOf("HL3"), needs = listOf(SignalKey.STEER), modes = MODES) { ctx ->
    val st = ctx.hill()
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val p = st.parkCur ?: return
            val j = p.judge ?: return
            if (j.done) return
            j.done = true
            val steer = j.steer ?: snap.steer ?: 0.0
            val ok = sideOk(steer, p.need, ctx.th("HL3.steer"))
            j.ok = ok
            j.deg = round1(abs(steer))
            j.side = sideOf(steer, ctx.th("steer.end"))
            ctx.emit(
                "HL3", if (ok) EventResult.OK else EventResult.FAIL,
                EventData(situation = p.situation, need = p.need, steer = round1(steer), side = j.side),
            )
            if (ok) {
                ctx.say("hl3.ok", item = "HL3", category = Category.PRAISE, priority = HL3_PRIORITY)
            } else {
                ctx.say("hl3.wrong", item = "HL3", vars = mapOf("dir" to p.need?.code), category = Category.FIX, priority = HL3_PRIORITY)
            }
        }
    }
}
