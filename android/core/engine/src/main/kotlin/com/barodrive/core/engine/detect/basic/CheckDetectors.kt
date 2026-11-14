package com.barodrive.core.engine.detect.basic

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.round1
import com.barodrive.core.model.ArrivedEvent
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.max
import kotlin.math.min

/*
 * 출발 전·주차 뒤 점검 검출기 (프로토타입 detectors/basic.js A1·PB1·PB2, 설계 6.3.1).
 */

/** A1 출발 전 운전석 벨트: P에서 D·R로 바꿀 때 벨트가 풀려 있으면(바꿀 때마다) fail `{gear}` + 말 'a1'. */
internal val A1 = DetectorDef(id = "basic.A1", items = listOf("A1"), needs = listOf(SignalKey.BELT_DRIVER), modes = DRIVE_MODES) { ctx ->
    var prev: Gear = ctx.cfg.startGear
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            ctx.basic().observe(snap)
            val g = snap.gear ?: return
            if (g != prev && prev == Gear.P && (g == Gear.D || g == Gear.R) && snap.beltDriver == false) {
                ctx.emit("A1", EventResult.FAIL, EventData(gear = g))
                ctx.say("a1", item = "A1", category = Category.INFO)
            }
            prev = g
        }
    }
}

/**
 * PB1 출발 전 사이드브레이크(주행 쪽: 읽힐 때): D·R이고 사이드브레이크가 2초 넘게 채워져 있으면 fail `{dur}`(채운 한 번에 1번) +
 * 말 'pb1.on'(풀면 버림). 못 읽을 때의 PR-1 질문은 시작 전 화면이 한다.
 */
internal val PB1 = DetectorDef(id = "basic.PB1", items = listOf("PB1"), modes = DRIVE_MODES) { ctx ->
    var since: Double? = null
    var fired = false
    var last: VehicleSnapshot? = null
    fun on(sn: VehicleSnapshot?) = sn != null && (sn.gear == Gear.D || sn.gear == Gear.R) && sn.handbrake == true
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            ctx.basic().observe(snap)
            last = snap
            if (!on(snap)) {
                since = null
                fired = false
                return
            }
            val s0 = since ?: snap.t.also { since = it }
            if (!fired && snap.t - s0 > ctx.th("PB1.sec") + EPS) {
                fired = true
                ctx.emit("PB1", EventResult.FAIL, EventData(dur = round1(snap.t - s0)))
                ctx.say("pb1.on", item = "PB1", category = Category.INFO, alive = { on(last) })
            }
        }
    }
}

/**
 * PB2 주차 후 사이드브레이크. 읽힘: P에 들어간 뒤 3초 동안 안 채우면 fail `{dur}` + 말, 채우면 ok `{dur}`.
 * 못 읽음: 도착 뒤 P에 들어갈 때 1번 info `{unreadable}` + 말(주차·언덕 모드는 주행이 시작된 뒤 처음 P — 결정사항 E T10-7).
 * 모드 7은 'pb2.hill' 문장이 있으면 그것.
 */
internal val PB2 = DetectorDef(id = "basic.PB2", items = listOf("PB2"), modes = null) { ctx ->
    var prev: Gear = ctx.cfg.startGear
    var epT0: Double? = null
    var epDone = false
    var lastT: Double? = null
    var askedBlind = false
    ctx.on<ArrivedEvent> { ctx.basic().arrived = true }
    fun key() = if (ctx.mode == 7 && ctx.line("pb2.hill").isNotEmpty()) "pb2.hill" else "pb2"
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            ctx.basic().observe(snap)
            val t = snap.t
            val dt = lastT?.let { max(0.0, min(DT_MAX, t - it)) } ?: 0.0
            lastT = t
            val g = snap.gear ?: return
            if (g == Gear.P && prev != Gear.P) {
                epT0 = t
                epDone = false
            }
            if (g != Gear.P) epT0 = null
            prev = g
            val t0 = epT0 ?: return
            if (epDone) return
            if (snap.handbrake == true) {
                epDone = true
                ctx.emit("PB2", EventResult.OK, EventData(dur = round1(t - t0)))
                return
            }
            if (snap.handbrake == null) { // 못 읽음
                epDone = true
                if (!askedBlind && (ctx.basic().arrived || ctx.mode == 6 || ctx.mode == 7)) {
                    askedBlind = true
                    ctx.emit("PB2", EventResult.INFO, EventData(unreadable = true))
                    ctx.say(key(), item = "PB2", category = Category.INFO)
                }
                return
            }
            if (t - t0 + dt >= ctx.th("PB2.sec") - EPS) {
                epDone = true
                ctx.emit("PB2", EventResult.FAIL, EventData(dur = round1(t - t0 + dt)))
                ctx.say(key(), item = "PB2", category = Category.INFO)
            }
        }
    }
}

/** PB2: 표본 사이 시간을 셀 때 한 번에 더하는 최대(초). */
private const val DT_MAX = 0.5
