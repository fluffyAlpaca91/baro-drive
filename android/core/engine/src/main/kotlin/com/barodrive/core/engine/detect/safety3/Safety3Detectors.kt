package com.barodrive.core.engine.detect.safety3

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.basic.DRIVE_MODES
import com.barodrive.core.engine.detect.basic.isIc
import com.barodrive.core.engine.round1
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.Gear
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.SteerTurn
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * 안전3 검출기 (프로토타입 detectors/safety3.js C3·C6·J1, 설계 6.3.1, 결정-91). 말은 분류 '안전'(간격·상한에 막히지 않음),
 * 카드는 내지 않는다. 안전3 스위치가 꺼지면 세 항목이 cfg.items에서 빠져 세션이 검출기를 끈다(이유 'items').
 * 판정은 위반만 남긴다(결정사항 E T2a-2·T3-2).
 */

/** C6: 이만큼 안 꺼졌다 같은 쪽으로 다시 켜지면 이어서 본다(초). */
private const val DEBOUNCE = 1.0

/** 표본 사이 시간을 셀 때 한 번에 더하는 최대(초). */
private const val STEP_MAX = 0.5

/** 이보다 길게 비면 재던 시간을 버린다(core.js jump.sec와 같은 값). */
private const val JUMP = 1.5

/** 표본 사이 시간 (safety3.js `clockOf`): 건너뛰기나 1.5초 넘는 틈이면 jump. */
private class Clock {
    private var lastT: Double? = null

    fun tick(snap: VehicleSnapshot): Pair<Double, Boolean> {
        val gap = lastT?.let { snap.t - it } ?: 0.0
        lastT = snap.t
        val jump = snap.skip || gap > JUMP + EPS
        return (if (jump) 0.0 else max(0.0, min(STEP_MAX, gap))) to jump
    }
}

/** 지금 움직이는가: P가 아니고 속도(못 읽으면 추적기 어림) ≥ 0.5 (safety3.js `movingOf`). */
private fun DetectorContext.movingOf(snap: VehicleSnapshot): Boolean {
    if (snap.gear == Gear.P) return false
    val v = snap.speed ?: track.v ?: return false
    return abs(v) >= th("stop.v") - EPS
}

/**
 * C3 깜빡이 방향 틀림: 회전 접근 창 안에서 반대쪽 깜빡이를 새로 켬(추적기 blinkWrong) → 회전마다 1번 fail
 * `{turnId, dir, blink, distance, type, ic}` + 안전 말 'c3'(그 회전을 지나거나 깜빡이를 바로잡으면 버림).
 */
internal val C3 = DetectorDef(id = "safety3.C3", items = listOf("C3"), needs = listOf(SignalKey.BLINKER, SignalKey.NAV), modes = DRIVE_MODES) { ctx ->
    val done = HashSet<String>()
    ctx.on<TurnApproach> { a ->
        if (!a.blinkWrong) return@on
        val id = a.id
        val dir = a.dir ?: return@on
        if (id in done) return@on
        done += id
        val opp = dir.opposite
        ctx.emit(
            "C3", EventResult.FAIL,
            EventData(turnId = id, dir = dir, blink = opp, distance = a.wrongOnDist ?: a.distance, type = a.type, ic = a.ic),
        )
        ctx.say(
            "c3", item = "C3", vars = mapOf("dir" to dir.code), category = Category.SAFETY, pairWith = id,
            alive = { ctx.track.turn?.id == id && ctx.track.blinkDir == opp },
        )
    }
    DetectorInstance.NONE
}

/** C6 깜빡이 한 번 켠 동안 (safety3.js `fresh`). */
private class BlinkEpisode(val dir: TurnDir, val onT: Double) {
    var offT: Double? = null
    var used = false
    var since: Double? = null
    var acc = 0.0
    var prevOk = false
    var pre = false
    var preEndT: Double? = null
    var fired = false
    var afterTurnId: String? = null
}

/**
 * C6 깜빡이 끄기 잊음: 회전(조향 회전·내비 회전)을 마친 뒤 또는 곧게 달리는 동안 깜빡이를 10초 넘게 켜 두면 fail
 * `{dir, sec, afterTurn, afterTurnId}` + 안전 말 'c6'(끄면 버림). 깜빡이 한 번 켠 동안 1번. 회전 접근 창 안·기어 P에서는 세지 않는다.
 * 시간 재기 규칙은 safety3.js 머리 주석 'C6 시간 재기'(결정사항 E T3-3).
 */
internal val C6 = DetectorDef(id = "safety3.C6", items = listOf("C6"), needs = listOf(SignalKey.BLINKER), modes = DRIVE_MODES) { ctx ->
    val clock = Clock()
    var ep: BlinkEpisode? = null
    /* 회전을 마쳤는데 깜빡이가 켜져 있다 → 그 회전을 마친 때부터 다시 잰다 */
    fun release(endT: Double?, turnId: String?) {
        val e = ep ?: return
        if (e.offT != null || e.fired) return
        val t = ctx.t
        e.since = if (endT != null) min(endT, t) else t
        e.used = true
        if (turnId != null) e.afterTurnId = turnId
    }
    ctx.on<SteerTurn> { st ->
        val e = ep ?: return@on
        if (e.onT > st.endT + EPS) return@on // 핸들을 푼 뒤에 켠 깜빡이는 이번 회전과 상관없음
        release(st.endT, st.navTurnId)
    }
    ctx.on<TurnPassed> { p ->
        val e = ep ?: return@on
        if (p.missed || p.skipped) return@on // 놓친·건너뛴 회전은 돌지 않은 것
        val reached = p.reachedT ?: ctx.t
        if (e.onT > reached + EPS) return@on // 회전 지점을 지난 뒤 켠 깜빡이
        // 조향이 없던 회전(IC 진입로 등)은 그 회전이 내비 안내(tbt)에서 사라진 때 = 회전 길을 다 지난 때부터
        val preEnd = e.preEndT
        var end = p.steerEndT ?: if (preEnd != null && preEnd >= reached - EPS) preEnd else reached
        val since = e.since
        if (e.used && since != null && since > end) end = since // 같은 회전의 조향 회전이 먼저 왔으면 그대로
        release(end, p.id)
    }
    /* 가장 가까운 내비 회전이 회전 접근 창(150m · IC 300m) 안에 있나 — 그 안의 깜빡이는 C2(미리 켬)·C3(반대쪽)의 몫 */
    fun turnNear(snap: VehicleSnapshot): Boolean {
        val x = snap.nav?.tbt?.firstOrNull() ?: return false
        val dir = x.dir ?: tbtDir(x.type) ?: return false
        if (!x.distance.isFinite()) return false
        return dir.let { x.distance <= ctx.th("turn.near", isIc(x.ic, x.type)) + EPS }
    }
    fun fire(over: Double) {
        val me = ep ?: return
        me.fired = true
        ctx.emit("C6", EventResult.FAIL, EventData(dir = me.dir, sec = round1(over), afterTurn = me.used, afterTurnId = me.afterTurnId))
        ctx.say("c6", item = "C6", category = Category.SAFETY, alive = { ep === me && me.offT == null })
    }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val t = snap.t
            val (dt, jump) = clock.tick(snap)
            val b = snap.blinker?.dir
            // 1. 깜빡이 한 번 켠 동안(1초 안 꺼짐은 이어짐)
            val cur = ep
            if (b != null) {
                val off = cur?.offT
                if (cur != null && cur.dir == b && (off == null || t - off < DEBOUNCE - EPS)) cur.offT = null else ep = BlinkEpisode(b, t)
            } else if (cur != null) {
                val off = cur.offT
                if (off == null) cur.offT = t else if (t - off >= DEBOUNCE - EPS) ep = null
            }
            val e = ep ?: return
            if (e.fired) return
            if (e.offT != null) {
                e.prevOk = false
                return
            }
            if (jump) {
                e.acc = 0.0
                e.prevOk = false
                if (e.used) e.since = t
                return
            }
            // 2. 회전 접근 창 안이면 세지 않는다. 창을 벗어난 때를 적어 둠(조향 없는 회전을 마친 때)
            if (turnNear(snap)) {
                e.pre = true
                e.used = false
                e.since = null
                e.acc = 0.0
                e.prevOk = false
                return
            }
            if (e.pre) {
                e.pre = false
                e.preEndT = t
            }
            // 3. 기어 P는 세지 않는다(서 있는 동안 켜 둔 것은 주행이 아님)
            if (snap.gear == Gear.P) {
                e.prevOk = false
                if (e.used) e.since = t
                return
            }
            val steer = snap.steer
            val straight = steer == null || abs(steer) < ctx.th("steer.end") - EPS
            val over: Double
            val since = e.since
            if (e.used && since != null) {
                over = t - since
            } else {
                // 핸들이 3° 넘게 돌면(차로를 바꾸거나 꺾는 중) 처음부터. 앞 표본과 이 표본이 모두 '곧게 달림'일 때 그 사이를 센다
                if (!straight) e.acc = 0.0
                val ok = straight && ctx.movingOf(snap)
                if (ok && e.prevOk) e.acc += dt
                e.prevOk = ok
                over = e.acc
            }
            if (over > ctx.th("C6.sec") + EPS && straight) fire(over)
        }
    }
}

/** TBT 종류 이름에서 방향 (safety3.js `tbtDir`). */
private fun tbtDir(type: String): TurnDir? {
    val u = type.uppercase()
    return when {
        Regex("UTURN|U_TURN").containsMatchIn(u) -> TurnDir.LEFT
        Regex("LEFT|_L$|_L_").containsMatchIn(u) -> TurnDir.LEFT
        Regex("RIGHT|_R$|_R_").containsMatchIn(u) -> TurnDir.RIGHT
        else -> null
    }
}

/** J1 꺼짐 구간 하나. */
private class DarkEpisode {
    var acc = 0.0
    var prevOk = false
    var fired = false
}

/**
 * J1 야간 전조등: 밤에 하향등이 꺼진 채 5초 넘게 달리면(P가 아니고 움직이는 시간만 셈) 꺼짐 구간마다 1번 fail `{sec, n}` +
 * 안전 말 'j1'(켜면 버림). 회차 최대 2번.
 */
internal val J1 = DetectorDef(id = "safety3.J1", items = listOf("J1"), modes = DRIVE_MODES) { ctx ->
    val clock = Clock()
    var ep: DarkEpisode? = null
    var count = 0
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            val (dt, jump) = clock.tick(snap)
            val dark = snap.night == true && snap.lowBeam == false
            if (!dark) {
                ep = null
                return
            }
            val e = ep ?: DarkEpisode().also { ep = it } // 꺼진 그 표본부터 잰다
            if (e.fired || count >= ctx.th("J1.max")) return
            if (jump) {
                e.acc = 0.0
                e.prevOk = false
                return
            }
            val ok = ctx.movingOf(snap) // 앞 표본과 이 표본이 모두 '달림'일 때 그 사이를 센다
            if (ok && e.prevOk) e.acc += dt
            e.prevOk = ok
            if (e.acc > ctx.th("J1.sec") + EPS) {
                e.fired = true
                count++
                ctx.emit("J1", EventResult.FAIL, EventData(sec = round1(e.acc), n = count))
                ctx.say("j1", item = "J1", category = Category.SAFETY, alive = { ep === e })
            }
        }
    }
}
