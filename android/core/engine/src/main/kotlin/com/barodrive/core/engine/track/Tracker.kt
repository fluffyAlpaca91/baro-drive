package com.barodrive.core.engine.track

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.engine.review.round2
import com.barodrive.core.model.ArrivedEvent
import com.barodrive.core.model.BrakeEndEvent
import com.barodrive.core.model.Gear
import com.barodrive.core.model.GearEvent
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.StartEvent
import com.barodrive.core.model.TrackerEvent
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WaitEndEvent
import com.barodrive.core.model.WaitStartEvent
import com.barodrive.core.model.WaypointEvent
import kotlin.math.abs
import kotlin.math.max

/** 추적기 부품들이 같이 보는 값: 표본·깜빡이·세션 (core.js createTracker의 `samples`·`tr.blink`·`tr.blinkHist`·`S`). */
internal class TrackShared(val host: TrackerHost) {
    val samples = SampleBuffer()
    var blinkDir: TurnDir? = null
    var blinkOnT = Double.NEGATIVE_INFINITY
    var blinkOffT = Double.NEGATIVE_INFINITY
    val blinkHist = ArrayList<BlinkMark>()
}

/** 출발 창 (core.js `tr.launch`). */
private class Launch(val t0: Double, val gear: Gear?) {
    var maxGain = 0.0
    var t5: Double? = null
}

/** 제동 구간 (core.js `tr.seg`). */
private class BrakeSeg(val t0: Double, val v0: Double, var maxDrop: Double, val viaSpeed: Boolean) {
    var stopT: Double? = null
}

/**
 * 공용 추적기 (설계 6.2, 프로토타입 core.js `createTracker`). 스냅숏마다 표본을 쌓고 사건을 낸다. 같은 스냅숏 안 순서:
 * gear → reroute·arrived·waypoint → waitEnd → brakeEnd → start → turnApproach·turnPassed → steerTurn → straightWindow
 * → zoneExit·zoneEnter → waitStart (제동이 끝난 정지의 카드가 그 신호 대기에 바로 보이도록 brakeEnd가 waitStart보다 앞).
 * 사건 값은 모델 [TrackerEvent] 타입이다(칸 이름은 core.js 머리 주석 그대로).
 *
 * 스냅숏에 skip(건너뛰기)이 있거나 스냅숏 사이가 1.5초 넘게 비면 재던 창을 버린다(건너뛰기면 지나친 회전을 skipped로 끝냄).
 */
internal class Tracker(host: TrackerHost, startGear: Gear) {
    private val sh = TrackShared(host)
    private val turns = NavTurns(sh)
    private val steer = SteerTurns(sh, turns)
    private val straight = StraightWatch(sh)
    private val zones = ZoneWatch()

    private var gear: Gear? = startGear
    private var prevGear: Gear? = null
    private var stopped: Boolean? = null
    private var stopSince = 0.0
    private var waiting = false
    private var waitSince = 0.0
    private var seg: BrakeSeg? = null
    private var launch: Launch? = null

    /** 급제동 중(1초 감소 11 이상) — 말하지 않는다(규칙 5). */
    var hardBrake = false
        private set

    /** 검출기 `ctx.track`. */
    val view = TrackerView()

    /** id로 추적 중인 내비 회전(전달 관리자가 C1 50m 확인에 씀). */
    fun turn(id: String): NavTurn? = turns.turn(id)

    fun step(snap: VehicleSnapshot): List<TrackerEvent> {
        val t = snap.t
        val ev = ArrayList<TrackerEvent>()
        turns.skipNow = false
        val before = sh.samples.last()
        if (before != null && (snap.skip || t - before.t > Thresholds["jump.sec"] + EPS)) jump(t, snap.skip, ev)
        val prev = sh.samples.last()
        val v = speedOf(snap, t)
        val s = Sample(t, v, snap.steer, snap.blinker?.dir, snap.brake, snap.gear, snap.nav?.heading, snap.tripDist)
        sh.samples.add(s)
        trim(t)
        // 1. 기어
        val g = s.gear
        if (g != null && g != gear) {
            ev += GearEvent(t = t, from = gear, to = g)
            prevGear = gear
            gear = g
        }
        // 2. 깜빡이가 바뀐 순간
        if (s.bdir != sh.blinkDir) {
            if (s.bdir != null) sh.blinkOnT = t else sh.blinkOffT = t
            sh.blinkDir = s.bdir
            sh.blinkHist += BlinkMark(t, s.bdir)
        }
        // 3. 내비 사건(재탐색은 회전보다 먼저)
        navEvents(snap, t, ev)
        // 4. 정지·출발·제동·신호 대기 끝
        if (v != null) {
            val st = abs(v) < Thresholds["stop.v"]
            if (st != stopped) {
                val wasStopped = stopped == true
                stopped = st
                if (st) {
                    stopSince = t
                } else if (wasStopped) {
                    launch = Launch(t, s.gear)
                }
            }
        }
        val stopDur = if (stopped == true) t - stopSince else 0.0
        val waitGear = s.gear == null || s.gear == Gear.D || s.gear == Gear.N
        val waitingNow = stopped == true && waitGear && stopDur >= Thresholds["wait.sec"] - EPS
        if (waiting && !waitingNow) {
            ev += WaitEndEvent(t = t, since = waitSince, dur = round2(t - waitSince))
            waiting = false
        }
        brakeStep(t, s, prev, stopDur, ev)
        launchStep(t, v, ev)
        // 5. 회전
        turns.step(snap, t, ev)
        // 6~8. 조향 회전 · 직진 창 · 구역
        steer.step(t, ev)
        straight.step(t, ev)
        zones.step(snap, t, ev)
        // 9. 신호 대기 시작(맨 끝)
        if (!waiting && waitingNow) {
            waiting = true
            waitSince = t
            ev += WaitStartEvent(t = t, since = stopSince)
        }
        updateView(t, s)
        return ev
    }

    /** 세션 끝: 열린 것을 닫는다(아직 회전 지점에 닿지 않은 회전은 내지 않는다, core.js `flush`). */
    fun flush(t: Double): List<TrackerEvent> {
        val ev = ArrayList<TrackerEvent>()
        val s = sh.samples.last()
        val l = launch
        val t5 = l?.t5
        if (l != null && t5 != null) {
            ev += StartEvent(t = t5, t0 = l.t0, maxGain1s = round2(max(0.0, l.maxGain)), gear = l.gear, v = s?.v?.let(::round2))
        }
        launch = null
        if (seg != null) closeSeg(t, s?.v ?: 0.0, ev)
        turns.flush(t, ev)
        steer.flush(t, ev)
        straight.reset()
        zones.flush(t, ev)
        if (waiting) {
            ev += WaitEndEvent(t = t, since = waitSince, dur = round2(t - waitSince))
            waiting = false
        }
        view.waiting = false
        view.turn = null
        return ev
    }

    /** 차속을 못 읽으면 트립 거리 변화로 어림한다(설계 7.8, core.js `speedOf`). 이번 표본을 넣기 전에 부른다. */
    private fun speedOf(snap: VehicleSnapshot, t: Double): Double? {
        snap.speed?.let { return it }
        val trip = snap.tripDist ?: return null
        val first = sh.samples.first() ?: return null
        val t0 = max(first.t, t - 1)
        if (t - t0 < SPEED_MIN_SPAN) return sh.samples.last()?.v
        val d0 = sh.samples.at(t0) { it.trip } ?: return null
        return max(0.0, (trip - d0) / (t - t0) * MS_TO_KMH)
    }

    private fun trim(t: Double) {
        sh.samples.trim(t)
        if (sh.blinkHist.size > BLINK_HIST_MAX) sh.blinkHist.subList(0, sh.blinkHist.size - BLINK_HIST_MAX).clear()
    }

    private fun navEvents(snap: VehicleSnapshot, t: Double, ev: MutableList<TrackerEvent>) {
        for (e in snap.nav?.events.orEmpty()) {
            when (e) {
                is NavEvent.Reroute -> turns.onReroute(e.turnId, t, ev)
                NavEvent.Arrived -> {
                    sh.host.navArrived()
                    ev += ArrivedEvent(t = t)
                }
                is NavEvent.WaypointArrived -> ev += WaypointEvent(t = t, index = e.index, segment = e.segment, kind = e.kind)
            }
        }
    }

    /* ---------- 출발 ---------- */
    private fun launchStep(t: Double, v: Double?, ev: MutableList<TrackerEvent>) {
        val l = launch ?: return
        if (v != null && t <= l.t0 + Thresholds["start.window"] + EPS) {
            val vb = sh.samples.at(t - 1) { it.v }
            if (vb != null) l.maxGain = max(l.maxGain, v - vb)
        }
        if (l.t5 == null && v != null && v >= Thresholds["start.v"] - EPS) l.t5 = t
        val windowDone = t >= l.t0 + Thresholds["start.window"] - EPS
        val t5 = l.t5
        if (t5 != null && (windowDone || stopped == true)) {
            ev += StartEvent(t = t5, t0 = l.t0, maxGain1s = round2(max(0.0, l.maxGain)), gear = l.gear, v = v?.let(::round2))
            launch = null
        } else if (stopped == true || t > l.t0 + LAUNCH_GIVE_UP) {
            launch = null // 5km/h를 못 넘고 다시 섬(조금 굴러감)
        }
    }

    /* ---------- 제동 구간 ---------- */
    private fun brakeStep(t: Double, s: Sample, prev: Sample?, stopDur: Double, ev: MutableList<TrackerEvent>) {
        val bs = s.brake
        val v = s.v
        val pv = prev?.v
        if (seg == null && v != null) {
            if (bs == true && prev?.brake != true) {
                // 브레이크가 켜진 것은 앞 표본과 이 표본 사이. 시작 = 앞 표본(그때 속도가 v0)
                val pre = prev != null && prev.brake == false && pv != null
                seg = if (pre && prev != null && pv != null) BrakeSeg(prev.t, pv, 0.0, false) else BrakeSeg(t, v, 0.0, false)
            } else if (bs == null && pv != null && prev != null && v < pv - BRAKE_NOISE && abs(pv) >= Thresholds["stop.v"]) {
                seg = BrakeSeg(prev.t, pv, max(0.0, pv - v), true)
            }
        }
        val sg = seg ?: return
        if (v != null) {
            val vb = sh.samples.at(t - 1) { it.v }
            if (t >= sg.t0 - EPS && vb != null) sg.maxDrop = max(sg.maxDrop, vb - v)
            if (stopped == true && sg.stopT == null) sg.stopT = t
        }
        if (sg.maxDrop >= Thresholds["F2.drop"] - EPS) hardBrake = true // 급제동 중에도 말하지 않는다(규칙 5)
        val end = bs == false || (stopped == true && stopDur >= Thresholds["stop.hold"] - EPS) ||
            (bs == null && v != null && pv != null && v > pv + BRAKE_NOISE)
        if (end) closeSeg(t, v ?: 0.0, ev)
    }

    private fun closeSeg(t: Double, v1: Double, ev: MutableList<TrackerEvent>) {
        val sg = seg ?: return
        val maxDrop = round2(sg.maxDrop)
        val stopT = sg.stopT
        val evalOk = sg.v0 >= Thresholds["brake.v0"] - EPS &&
            (abs(v1) < Thresholds["stop.v"] || sg.v0 - v1 >= Thresholds["brake.dv"] - EPS)
        ev += BrakeEndEvent(
            t0 = sg.t0, t1 = t, v0 = round2(sg.v0), v1 = round2(v1), maxDrop1s = maxDrop, dur = round2((stopT ?: t) - sg.t0),
            stopped = stopT != null, stopT = stopT, eval = evalOk, gear = gear, viaSpeed = sg.viaSpeed,
        )
        if (maxDrop >= Thresholds["F2.drop"] - EPS) sh.host.addSilence(t + Thresholds["F2.silence"], "F2") // 급제동 직후 30초(규칙 5)
        seg = null
        hardBrake = false
    }

    /** 건너뛰기 또는 시간 틈: 출발·제동·직진·조향 창을 버리고 표본을 새로 쌓는다(core.js `jump`). */
    private fun jump(t: Double, skip: Boolean, ev: MutableList<TrackerEvent>) {
        launch = null
        seg = null
        hardBrake = false
        straight.reset()
        steer.reset()
        stopped = null
        sh.samples.clear()
        turns.skipNow = skip
        if (skip) turns.skipCompleting(t, ev)
    }

    private fun updateView(t: Double, s: Sample) {
        view.t = t
        view.v = s.v
        view.gear = s.gear
        view.prevGear = prevGear
        view.stopped = stopped == true
        view.stopDur = if (stopped == true) t - stopSince else 0.0
        view.waiting = waiting
        view.waitDur = if (waiting) t - waitSince else 0.0
        view.moving = stopped == false
        view.steer = s.steer
        view.brake = s.brake
        view.braking = seg != null
        view.blinkDir = sh.blinkDir
        view.turn = turns.viewTurn()
    }

    private companion object {
        const val SPEED_MIN_SPAN = 0.05
        const val MS_TO_KMH = 3.6
        const val BLINK_HIST_MAX = 50
        const val LAUNCH_GIVE_UP = 30.0
        /** 속도로 제동을 볼 때 무시하는 흔들림(km/h). */
        const val BRAKE_NOISE = 0.05
    }
}
