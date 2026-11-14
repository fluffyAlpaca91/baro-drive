package com.barodrive.core.engine.detect.hill

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.model.CautionType
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillDownRun
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillUpAttempt
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs

/** '움직였다'·'움직이는 중'으로 보는 속도(km/h) — 오르막 1단계, 내리막 '계속 밟음' 시간 (hill.js `MOVE_V`). */
internal const val MOVE_V = 3.0

/** '앞으로 나감'(km/h) — 오르막 4단계 문장, 누르지 않고 출발 (hill.js `FWD_V`). */
private const val FWD_V = 1.0

/**
 * 언덕 단계 기계 (프로토타입 detectors/hill.js의 단계 함수들, 설계 6.12.2, 표 A-8, 결정사항 E T6-2~T6-7).
 * 검출기마다 하나씩 만든다(말·판정이 그 검출기 ctx로 나간다) — 상태 [st]는 셋이 나눠 쓴다.
 *
 * 오르막 up: 1 멈추기(움직였다가 D로 1초 멈춤) → 2 [했어요](누르지 않고 출발하면 4) → 3 브레이크 뗌 → 4 앞으로 3km/h 넘게 3초 → 다시 1.
 * 내리막 down: 1 [했어요] 또는 묶인 내리막 주의 구간에 들어섬 → 2 나눠 밟기 세기(구간이 끝나거나 [했어요]) → 3 [했어요] → 끝.
 * 언덕 주차 park: 1 상황 고름 → 2 맞는 쪽 8° 이상(핸들을 못 읽으면 [했어요]) → 3 기어 P → 판정·사이드브레이크 3초 → 끝.
 */
internal class HillMachine(val ctx: DetectorContext, val st: HillState) {
    /* ---------------------------------------------------------------- 말(단계 문장, HL1) */
    fun sayStep(p: HillPractice, n: Int) {
        val key = "hl1.${p.id}.$n"
        // 언덕 주차 2단계 문장은 고른 상황의 방향 글을 넣는다 — 상황을 고르기 전이면 말하지 않음
        val sit: HillSlope? = if (p == HillPractice.PARK && n == 2) st.parkCur?.situation ?: return else null
        val vars: Map<String, Any?> = if (sit != null) mapOf("side" to sideTextOf(sit)) else emptyMap()
        ctx.say(
            key, item = "HL1", vars = vars, category = Category.INFO, guide = true,
            alive = {
                st.practice == p && st.step == n && !st.done && st.gear != Gear.P && (sit == null || st.parkCur?.situation == sit)
            },
        )
    }

    fun announce() {
        if (st.done || !st.started || st.gear == Gear.P) return
        if (st.practice == HillPractice.UP && st.step == 4) return // 4단계 문장은 차가 앞으로 나갈 때(stepUp)
        sayStep(st.practice, st.step)
    }

    fun setStep(p: HillPractice, n: Int, moved: Boolean = false, quiet: Boolean = false) {
        st.practice = p
        st.step = n
        st.done = false
        if (p == HillPractice.UP && n == 1) st.moved = moved
        if (!quiet) announce()
    }

    /* ---------------------------------------------------------------- 오르막 출발 */
    fun newAttempt(t: Double): UpTry = UpTry(++st.upN, t).also { st.upCur = it }

    /** 시도를 닫는다. 브레이크를 뗀(4단계에 간) 시도만 센다 — 그 전에 그만두면 시도가 아님. */
    fun closeUp(completed: Boolean) {
        val a = st.upCur
        st.upCur = null
        if (a == null || !a.released) return
        st.upList += HillUpAttempt(n = a.n, rolled = a.rolled, completed = completed)
        if (!a.rolled && !st.rollOff) ctx.emit("HL2", EventResult.OK, EventData(attempt = a.n))
    }

    fun release() {
        val a = st.upCur ?: newAttempt(ctx.t)
        a.released = true
        setStep(HillPractice.UP, 4)
    }

    fun stepUp(snap: VehicleSnapshot) {
        val v = ctx.speedOf(snap)
        val tr = ctx.track
        val gearD = snap.gear == null || snap.gear == Gear.D
        val fwd = v != null && v >= FWD_V - EPS
        val back = v != null && v < ctx.th("HL2.v") - EPS
        when (st.step) {
            1 -> {
                if (v != null && abs(v) >= MOVE_V - EPS) st.moved = true
                if (st.moved && gearD && tr.stopped && tr.stopDur >= ctx.th("stop.hold") - EPS) {
                    newAttempt(snap.t)
                    setStep(HillPractice.UP, 2)
                }
            }
            2 -> if (fwd || back) release() // [했어요] 없이 출발함
            3 -> if (snap.brake == false || (snap.brake == null && (fwd || back))) release()
            4 -> {
                val a = st.upCur
                if (a == null) {
                    setStep(HillPractice.UP, 1, moved = true)
                    return
                }
                if (!a.line4 && fwd) {
                    a.line4 = true
                    sayStep(HillPractice.UP, 4)
                }
                if (v != null && v > ctx.th("hill.startV") + EPS) {
                    val since = a.fastSince ?: snap.t.also { a.fastSince = it }
                    if (snap.t - since >= ctx.th("hill.startSec") - EPS) {
                        closeUp(true)
                        setStep(HillPractice.UP, 1, moved = true) // 다시 시도(설계 5.8)
                    }
                } else {
                    a.fastSince = null
                }
            }
            else -> Unit
        }
    }

    /* ---------------------------------------------------------------- 내리막 감속 */
    fun newRun(zoneId: String?): DownTry = DownTry(++st.downN, ctx.t, zoneId).also { st.downCur = it }

    fun startDown(zoneId: String?) {
        leavePractice()
        newRun(zoneId)
        setStep(HillPractice.DOWN, 1)
    }

    fun enterDown2() {
        val d = st.downCur ?: return
        d.step2 = true
        if (st.snap?.brake == true && !d.brakeOn) { // 셈은 움직일 때(countBrake)
            d.brakeOn = true
            d.counted = false
            d.runSec = 0.0
            d.flagged = false
        }
        setStep(HillPractice.DOWN, 2)
    }

    /** 내리막 한 구간 판정(설계 6.4 T-HILLDOWN: 2번 이상 나눠 밟고 8초 넘게 계속 밟지 않음). */
    fun judgeDown() {
        val d = st.downCur ?: return
        if (d.judged != null) return
        val ok = d.presses >= 2 && d.holds == 0
        val row = HillDownRun(n = d.n, presses = d.presses, holds = d.holds, ok = ok, viaNav = d.viaNav)
        d.judged = row
        st.downList += row
        ctx.emit(
            "HL1", if (ok) EventResult.OK else EventResult.FAIL,
            EventData(practice = HillPractice.DOWN, run = d.n, presses = d.presses, holds = d.holds),
        )
    }

    fun endDown2() {
        if (st.downCur?.step2 == true) judgeDown() // 2단계(나눠 밟기)를 거친 구간만 판정
        st.downCur?.let {
            it.brakeOn = false
            it.warn = false
        }
        setStep(HillPractice.DOWN, 3)
    }

    /**
     * 밟음 한 번 = 브레이크가 켜진 동안. 움직이는 중(3km/h)에 보인 밟음만 '나눠 밟기'로 세고(서서 밟은 것은 안 셈),
     * '계속 밟음' 시간은 켜진 첫 표본부터 움직이는 동안만 쌓는다 → 한 번에 8초 넘으면 계속 밟음(결정-83).
     */
    fun countBrake(snap: VehicleSnapshot, dt: Double) {
        val d = st.downCur ?: return
        val b = snap.brake == true
        val wasOn = d.brakeOn
        if (b && !wasOn) {
            d.brakeOn = true
            d.counted = false
            d.runSec = 0.0
            d.flagged = false
        } else if (!b && wasOn) {
            d.brakeOn = false
        }
        val v = ctx.speedOf(snap)
        val moving = v != null && abs(v) >= MOVE_V - EPS
        if (d.brakeOn && moving && !d.counted) {
            d.counted = true
            d.presses++
            d.warn = false
        }
        if (d.brakeOn && wasOn && moving) d.runSec += dt
        if (d.brakeOn && !d.flagged && d.runSec > ctx.th("hill.holdSec") + EPS) {
            d.flagged = true
            d.holds++
            d.warn = true
        }
    }

    fun watchCautions(snap: VehicleSnapshot) {
        val nav = snap.nav ?: return
        val list = nav.cautions.filter { it.type == CautionType.DOWNHILL_CAUTION_AREA }
        // 1. 새 내리막 주의 구간이 300m 안: 자동으로 내리막 1단계(오르막·마친 연습에서만) · 칩으로 고른 내리막이면 그 구간에 묶음
        for (c in list) {
            val id = c.id
            if (id in st.seenZones || !(c.remain <= ctx.th("hill.caution") + EPS)) continue
            st.seenZones += id
            val d = st.downCur
            if (st.practice == HillPractice.DOWN && !st.done && d != null && d.zoneId == null && st.step == 1) {
                d.zoneId = id
                d.viaNav = true
                continue
            }
            if (!st.done && st.practice == HillPractice.PARK) continue // 언덕 주차 중엔 바꾸지 않음
            if (!st.done && st.practice == HillPractice.DOWN && st.step < 3) continue // 다른 내리막을 하는 중(3단계면 이미 판정 → 새 내리막)
            startDown(id)
        }
        // 2. 묶인 구간: 들어서면(남은 거리 0) 2단계, 끝나면(목록에서 사라짐) 3단계
        val d = st.downCur
        val zone = d?.zoneId
        if (st.practice != HillPractice.DOWN || st.done || d == null || zone == null) return
        val z = list.firstOrNull { it.id == zone }
        if (z != null && z.remain <= EPS) {
            d.inside = true
            if (st.step == 1) enterDown2()
        } else if (z == null) {
            if (st.step == 2) {
                endDown2()
            } else if (st.step == 1) { // 들어서기 전에 사라짐(재탐색 등) → 화면 누름으로
                d.zoneId = null
                d.viaNav = false
            }
        }
    }

    /* ---------------------------------------------------------------- 언덕 주차 */
    fun newPark(sit: HillSlope?): ParkTry {
        val s = sit?.let(::situationOf)
        return ParkTry(++st.parkN, ctx.t, s?.id, s?.side).also { st.parkCur = it }
    }

    fun stepPark(snap: VehicleSnapshot) {
        val p = st.parkCur ?: return
        if (st.step != 2 || p.need == null) return
        if (sideOk(snap.steer, p.need, ctx.th("HL3.steer"))) setStep(HillPractice.PARK, 3)
    }

    fun chooseSituation(value: HillSlope) {
        val s = situationOf(value)
        val okNow = sideOk(st.snap?.steer, s.side, ctx.th("HL3.steer"))
        val p = st.parkCur
        if (st.practice != HillPractice.PARK || st.done || p == null) {
            leavePractice()
            newPark(s.id)
            setStep(HillPractice.PARK, if (okNow) 3 else 2)
            return
        }
        val sameNeed = p.need == s.side
        p.situation = s.id
        p.need = s.side
        val target = if (okNow) 3 else 2
        if (!(sameNeed && st.step == target)) setStep(HillPractice.PARK, target)
    }

    /** 기어가 P가 될 때: 언덕 주차를 판정(HL3는 HL3 검출기가 같은 스냅숏에서), 사이드브레이크 3초 창을 연다. */
    fun parkAtP(snap: VehicleSnapshot) {
        val p = st.parkCur ?: return
        if (st.practice != HillPractice.PARK || st.done || p.need == null || st.step < 2) return
        p.judge = ParkJudge(snap.t, snap.steer)
        p.hb = HandbrakeWindow(snap.t)
        st.parkList += p
        st.step = 3
        st.done = true
        // 언덕 주차가 끝난 P = 회차 끝: 내비 도착 전이어도 DR-3이 아니라 리뷰로(결정 T5b-Q1, 결정사항 E T6-11)
        ctx.hints.arrived = true
    }

    /** PB2와 같은 창(결정사항 E T2a-7): P 표본이 3초 분량이 된 때까지 한 번이라도 채우면 '채움'. */
    fun watchHandbrake(snap: VehicleSnapshot, dt: Double) {
        val hb = st.parkCur?.hb ?: return
        if (!hb.open) return
        val on = snap.handbrake
        when {
            snap.gear != null && snap.gear != Gear.P -> hb.close(stateOf(on))
            on == true -> hb.close(HandbrakeState.ON)
            on == null -> hb.close(HandbrakeState.UNKNOWN)
            snap.t - hb.t0 + dt >= ctx.th("PB2.sec") - EPS -> hb.close(HandbrakeState.OFF)
        }
    }

    /* ---------------------------------------------------------------- 연습 바꾸기·단계 칸·[했어요] */
    /** 하던 연습을 닫는다: 오르막은 브레이크를 뗀 시도만 세고, 내리막은 2단계였으면 판정, 언덕 주차는 판정 전이면 버림. */
    fun leavePractice() {
        when (st.practice) {
            HillPractice.UP -> closeUp(false)
            HillPractice.DOWN -> {
                val d = st.downCur
                if (d != null && !st.done && d.step2 && d.judged == null) judgeDown()
                st.downCur = null
            }
            HillPractice.PARK -> if (st.parkCur?.judge == null) st.parkCur = null
        }
    }

    /** 연습 칩·단계 칸은 멈춰 있을 때만 받는다(설계 4.2 HL-1). */
    fun isStill(): Boolean = st.gear == Gear.P || ctx.track.stopped

    fun choosePractice(p: HillPractice) {
        if (p == st.practice && !st.done) return
        leavePractice()
        when (p) {
            HillPractice.UP -> {
                // 오르막에 서서 고르면 지금 멈춘 것이 1단계(멈추기) — 바로 2단계
                if (st.gear == Gear.D && ctx.track.stopped) {
                    newAttempt(ctx.t)
                    setStep(HillPractice.UP, 2)
                } else {
                    setStep(HillPractice.UP, 1)
                }
            }
            HillPractice.DOWN -> startDown(null)
            HillPractice.PARK -> {
                newPark(null)
                setStep(HillPractice.PARK, 1)
            }
        }
    }

    fun chooseStep(n: Int) {
        val p = st.practice
        if (n < 1 || n > p.steps() || (n == st.step && !st.done)) return
        when (p) {
            HillPractice.UP -> {
                if (n == 1) {
                    closeUp(false)
                    setStep(HillPractice.UP, 1, moved = true)
                    return
                }
                val a = st.upCur ?: newAttempt(ctx.t)
                if (n == 4) {
                    release()
                    return
                }
                a.released = false
                setStep(HillPractice.UP, n)
            }
            HillPractice.DOWN -> {
                val d = st.downCur
                if (d == null || (d.judged != null && n <= 2)) newRun(null) // 끝난 내리막을 다시 하면 새 구간
                when (n) {
                    1 -> setStep(HillPractice.DOWN, 1)
                    2 -> enterDown2()
                    else -> endDown2()
                }
            }
            HillPractice.PARK -> {
                val pk = st.parkCur
                if (n > 1 && pk?.need == null) return // 상황을 고르기 전에는 2·3단계로 못 감
                if (pk == null) newPark(null)
                setStep(HillPractice.PARK, n)
            }
        }
    }

    fun press() {
        if (st.done) return
        val n = st.step
        when (st.practice) {
            HillPractice.UP -> if (n == 2) setStep(HillPractice.UP, 3)
            HillPractice.DOWN -> when {
                n == 1 -> enterDown2()
                n == 2 && st.downCur?.let { it.zoneId == null } == true -> endDown2()
                n == 3 -> st.done = true
            }
            HillPractice.PARK -> if (n == 2 && st.parkCur?.need != null) {
                if (st.steerOff || st.snap?.steer == null) setStep(HillPractice.PARK, 3)
            }
        }
    }

    /** [했어요]를 보이는 단계 (hill.js `isTap`). */
    fun isTap(): Boolean {
        if (st.done) return false
        val n = st.step
        return when (st.practice) {
            HillPractice.UP -> n == 2
            HillPractice.DOWN -> n == 1 || n == 3 || (n == 2 && st.downCur?.let { it.zoneId == null } == true)
            HillPractice.PARK -> n == 2 && st.steerOff
        }
    }
}

/** 사이드브레이크 값 → 창 상태: 채움 'on' · 못 읽음 'unknown' · 안 채움 'off'. */
internal fun stateOf(on: Boolean?): HandbrakeState = when (on) {
    true -> HandbrakeState.ON
    null -> HandbrakeState.UNKNOWN
    false -> HandbrakeState.OFF
}

internal fun HandbrakeWindow.close(s: HandbrakeState) {
    open = false
    state = s
}
