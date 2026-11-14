package com.barodrive.core.engine.detect.basic

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.content.Items
import com.barodrive.core.engine.detect.DetectorContext
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.SteerTurn
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.max

/** 주행 검출기가 도는 모드: 모드 6(실제 주차 안내)에는 주행이 없다(표 A-4, 결정사항 E T2a-1). */
internal val DRIVE_MODES = listOf(1, 2, 3, 4, 5, 7, 8)

/** 깜빡이가 바뀐 순간 (basic.js `blinkHist[]`). */
internal class BlinkChange(val t: Double, val dir: TurnDir?)

/** 내비 회전 하나에 모은 판정 (basic.js `turnRec`): C2·C4 결과와 칭찬 카드를 냈는지. */
internal class TurnRec(val id: String) {
    var c2: EventResult? = null
    var distance: Double? = null
    var c4: EventResult? = null
    var interval: Double? = null
    var carded = false
}

/** 조향 회전에 맞는 쪽 깜빡이 (basic.js `steerInfo`): 켠 순간 [act], 조향 시작 때 켜져 있었음 [onAtStart], 시작 뒤 1초 안에 켬 [late]. */
internal class SteerInfo(val act: BlinkChange?, val onAtStart: Boolean, val late: Boolean)

/**
 * basic.* 검출기끼리 나누는 세션 값 (basic.js `shared(ctx)`). 깜빡이 기록은 스냅숏마다 한 번([observe]) — 검출기 step에서 부르므로
 * 같은 스냅숏의 추적기 사건(steerTurn 등)을 처리할 때는 아직 앞 스냅숏까지의 기록이다(프로토타입과 같음).
 */
internal class BasicShared {
    private var seenT: Double? = null
    private var blink: TurnDir? = null
    val blinkHist = ArrayList<BlinkChange>()
    var lastSteerEndT = Double.NEGATIVE_INFINITY
    var steerCache: Pair<Pair<Double, TurnDir>, SteerInfo>? = null

    /** 내비 회전 id → 판정. */
    val turns = LinkedHashMap<String, TurnRec>()

    /** 마지막으로 지난 내비 회전 id(칭찬 카드). */
    var lastNavTurn: String? = null

    /** 위험 회피 급제동으로 멈춘 뒤 첫 출발은 B1에서 뺀다. */
    var afterF2 = false

    /** E2가 난 구역 — 그 구역에서는 E1을 말하지 않는다(결정-70). */
    val e2Zones = HashSet<String>()
    var arrived = false

    /** 매 스냅숏 한 번: 깜빡이 기록(어느 검출기의 step이 먼저 불려도 한 번만, basic.js `observe`). */
    fun observe(snap: VehicleSnapshot) {
        if (seenT == snap.t) return
        seenT = snap.t
        val b = snap.blinker?.dir
        if (b != blink) {
            blinkHist += BlinkChange(snap.t, b)
            blink = b
            if (blinkHist.size > HIST_MAX) blinkHist.subList(0, HIST_DROP).clear()
        }
    }

    fun turnRec(id: String): TurnRec = turns.getOrPut(id) { TurnRec(id) }

    private companion object {
        const val HIST_MAX = 240
        const val HIST_DROP = 120
    }
}

internal fun DetectorContext.basic(): BasicShared = shared(BasicShared::class) { BasicShared() }

/**
 * 조향 회전(steerTurn)에 맞는 쪽 깜빡이 (basic.js `steerInfo`): 조향 시작 + 1초 안에 켠 마지막 것(앞 조향 회전이 끝난 뒤에 켠 것만 —
 * 앞 회전 때 켜 둔 깜빡이로 이번 회전을 '켰다'고 보지 않는다). C4·C5가 같은 조향 회전을 같이 본다(한 번 계산해 둠).
 */
internal fun DetectorContext.steerInfo(st: SteerTurn): SteerInfo {
    val s = basic()
    val key = st.startT to st.dir
    s.steerCache?.let { (k, v) -> if (k == key) return v }
    val after = th("C5.after")
    val h = s.blinkHist
    var act: BlinkChange? = null
    var onAtStart = false
    var late = false
    for (i in h.indices.reversed()) {
        val c = h[i]
        if (c.t > st.startT + after + EPS) continue
        if (c.t < st.startT - LOOKBACK - EPS || c.t <= s.lastSteerEndT + EPS) break
        if (c.dir != st.dir) continue
        val next = h.getOrNull(i + 1)
        if (c.t > st.startT + EPS) {
            act = c
            late = true
        } else if (next == null || next.t > st.startT + EPS) {
            act = c
            onAtStart = true
        }
        break
    }
    val v = SteerInfo(act, onAtStart, late)
    s.steerCache = key to v
    s.lastSteerEndT = max(s.lastSteerEndT, st.endT)
    return v
}

/** B1·F1 칭찬 상태 (basic.js `praiseState`). */
internal class PraiseState {
    var afterFail = false
    var okCount = 0
    var lastPraiseT = Double.NEGATIVE_INFINITY
}

/** 성공일 때 칭찬할지(6.5.4, 결정-30): 실패 다음 첫 성공 또는 회차 첫 성공(모드 1만), 같은 항목 5분에 1번 (basic.js `praiseOnSuccess`). */
internal fun DetectorContext.praiseOnSuccess(p: PraiseState): Boolean {
    val want = (p.afterFail || (mode == 1 && p.okCount == 0)) && t - p.lastPraiseT >= th("praise.repeat") - EPS
    p.okCount++
    p.afterFail = false
    if (want) p.lastPraiseT = t
    return want
}

/** 항목 심볼 id (basic.js `icon`). */
internal fun iconOf(item: String): String? = Items.def(item)?.icon?.takeIf { it.isNotEmpty() }

/** 'C2.ok' 등 IC 기준을 쓸 회전인가 (core.js `isIc`: ic 표시 또는 종류 이름에 'IC'). */
internal fun isIc(ic: Boolean, type: String?): Boolean = ic || type.orEmpty().contains("IC")

/** 짧게 쓰는 시간 상수. */
private const val LOOKBACK = 30.0
