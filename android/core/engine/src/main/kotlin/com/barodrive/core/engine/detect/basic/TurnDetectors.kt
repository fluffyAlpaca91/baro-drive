package com.barodrive.core.engine.detect.basic

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.content.Words
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.num
import com.barodrive.core.engine.round1
import com.barodrive.core.engine.review.Josa
import com.barodrive.core.model.CardCategory
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.SteerTurn
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WaitStartEvent

/*
 * 깜빡이·회전 검출기 (프로토타입 detectors/basic.js C1·C2·C4·C5, 설계 6.3.1, 결정-37·43·61).
 */

/** 신호 대기 칭찬 카드를 내는 모드(6.5.4). */
private val PRAISE_CARD_MODES = listOf(1, 2, 8)

/**
 * C1 회전 70m 전 사전 안내: 70m 안에서 깜빡이가 꺼져 있으면 info `{turnId, distance, dir}` + 사전 안내 후보(같은 회전 C2와 짝).
 * 내비 음성이 끝난 뒤 1초 기다림·50m 안이면 버림(under50)은 전달 관리자가 한다. 그사이 깜빡이를 켜면 필요 없어진다.
 */
internal val C1 = DetectorDef(id = "basic.C1", items = listOf("C1"), needs = listOf(SignalKey.BLINKER, SignalKey.NAV), modes = DRIVE_MODES) { ctx ->
    val seen = HashSet<String>()
    ctx.on<TurnApproach> { a ->
        if (a.id in seen || !(a.distance <= ctx.th("C1.dist") + EPS)) return@on
        seen += a.id
        if (a.blinkDir != null) return@on // 깜빡이가 꺼져 있을 때만(반대쪽은 C3)
        ctx.emit("C1", EventResult.INFO, EventData(turnId = a.id, distance = a.distance, dir = a.dir))
        ctx.say(
            "c1", item = "C1", vars = mapOf("dir" to (a.dir ?: TurnDir.RIGHT).code), category = Category.PRE, pairWith = a.id,
            alive = { ctx.track.blinkDir == null },
        )
    }
    DetectorInstance.NONE
}

/** C2 회전 하나의 상태. */
private class C2Turn(val ic: Boolean) {
    var result: EventResult? = null
    var distance: Double? = null
    var excluded = false
}

/**
 * C2 회전 몇 m 전에 깜빡이(결정-37·43): 성공(40m · IC 110m 이상)은 깜빡이를 켜는 순간 칭찬(같은 회전 C1과 짝 — 간격 예외),
 * 거의(30m · IC 100m)·늦음은 회전이 끝난 뒤(turnPassed) 교정, 안 켬은 fail(말은 C5가 카드로). 재탐색·건너뛴 회전은 판정하지 않는다.
 * 신호 대기 칭찬 카드(모드 1·2·8): 직전 내비 회전이 C2 성공·C4 성공이면 1장. 회차 지표 c2Avg·c2N(IC·뺀 회전 제외).
 */
internal val C2 = DetectorDef(id = "basic.C2", items = listOf("C2"), needs = listOf(SignalKey.BLINKER, SignalKey.NAV), modes = DRIVE_MODES) { ctx ->
    val turns = LinkedHashMap<String, C2Turn>()
    fun mine(id: String, ic: Boolean) = turns.getOrPut(id) { C2Turn(ic) }
    fun judge(id: String, ic: Boolean, type: String, dir: TurnDir?, result: EventResult, dist: Double?) {
        val t = mine(id, ic)
        t.result = result
        t.distance = dist
        val rec = ctx.basic().turnRec(id)
        rec.c2 = result
        rec.distance = dist
        ctx.emit("C2", result, EventData(turnId = id, distance = dist, type = type, ic = ic, dir = dir))
    }
    ctx.on<TurnApproach> { a ->
        val t = mine(a.id, a.ic)
        val on = a.blinkOnDist
        if (t.result != null || on == null) return@on
        if (on >= ctx.th("C2.ok", isIc(a.ic, a.type)) - EPS) {
            judge(a.id, a.ic, a.type, a.dir, EventResult.OK, on)
            ctx.say("c2.ok", item = "C2", vars = mapOf("N" to on), category = Category.PRAISE, pairWith = a.id)
        }
    }
    ctx.on<TurnPassed> { p ->
        val t = mine(p.id, p.ic)
        if (p.missed || p.skipped) { // 재탐색으로 놓친·건너뛴 회전은 판정하지 않는다(6.9)
            t.excluded = true
            return@on
        }
        ctx.basic().lastNavTurn = p.id
        if (t.result != null) return@on
        val dist = p.blinkOnDist
        val ic = isIc(p.ic, p.type)
        val okT = ctx.th("C2.ok", ic)
        if (dist == null) { // 안 켬: 말은 C5가 카드로(6.3.1)
            judge(p.id, p.ic, p.type, p.dir, EventResult.FAIL, null)
            return@on
        }
        if (dist >= okT - EPS) { // 창 끝에서 켬(드묾)
            judge(p.id, p.ic, p.type, p.dir, EventResult.OK, dist)
            return@on
        }
        val almost = dist >= ctx.th("C2.almost", ic) - EPS
        judge(p.id, p.ic, p.type, p.dir, if (almost) EventResult.ALMOST else EventResult.LATE, dist)
        // 같은 회전의 C1 → C2 짝은 20초 간격 예외(6.5.2 규칙 7) — 거의·늦음도 같은 회전 짝이다
        ctx.say(
            if (almost) "c2.almost" else "c2.late", item = "C2", vars = mapOf("T" to okT, "N" to dist), category = Category.FIX,
            pairWith = p.id,
        )
    }
    ctx.on<WaitStartEvent> {
        if (ctx.mode !in PRAISE_CARD_MODES) return@on
        val sh = ctx.basic()
        val rec = sh.lastNavTurn?.let { sh.turns[it] } ?: return@on
        val dist = rec.distance
        if (rec.carded || rec.c2 != EventResult.OK || rec.c4 != EventResult.OK || dist == null) return@on
        rec.carded = true
        ctx.card(
            item = "C2", key = "c2.ok", vars = mapOf("N" to dist), icon = iconOf("C2"), category = CardCategory.PRAISE, turnId = rec.id,
            title = "깜빡이 타이밍 좋았어요", detail = "${num(dist)}m 전에 켜고 ${num(rec.interval ?: 0.0)}초 뒤에 꺾었어요",
        )
    }
    object : DetectorInstance {
        override fun finish(rec: RecordDraft) {
            val ds = turns.values.filter { !it.excluded && !it.ic && it.result != null }.mapNotNull { it.distance }
            rec.metrics.c2Avg = if (ds.isNotEmpty()) round1(ds.fold(0.0) { a, b -> a + b } / ds.size) else null
            rec.metrics.c2N = ds.size
        }
    }
}

/**
 * C4 깜빡이 켜자마자 꺾음: 맞는 쪽 깜빡이를 켠 조향 회전마다 켠 뒤 조향까지 < 3초면 fail + 교정 카드(꺾은 뒤 켬 포함), 아니면 ok.
 * 판정 `{turnId, dir, interval, blinkOnT, steerStartT}`.
 */
internal val C4 = DetectorDef(id = "basic.C4", items = listOf("C4"), needs = listOf(SignalKey.BLINKER, SignalKey.STEER), modes = DRIVE_MODES) { ctx ->
    ctx.on<SteerTurn> { st ->
        val info = ctx.steerInfo(st)
        val act = info.act ?: return@on
        if (!(info.onAtStart || info.late)) return@on // 맞는 쪽 깜빡이 없음 → C5(없음)·C3(반대)
        val raw = st.startT - act.t
        val fail = raw < ctx.th("C4.sec") - EPS
        val interval = round1(raw)
        val turnId = st.navTurnId
        ctx.emit(
            "C4", if (fail) EventResult.FAIL else EventResult.OK,
            EventData(turnId = turnId, dir = st.dir, interval = interval, blinkOnT = act.t, steerStartT = st.startT),
        )
        if (turnId != null) {
            val rec = ctx.basic().turnRec(turnId)
            rec.c4 = if (fail) EventResult.FAIL else EventResult.OK
            rec.interval = interval
        }
        if (fail) {
            ctx.card(
                item = "C4", key = "c4.fix", icon = iconOf("C4"), category = CardCategory.FIX, turnId = turnId, title = "깜빡이 켜고 바로 꺾었어요",
                detail = if (raw < 0) "핸들을 돌린 뒤에 깜빡이를 켰어요" else "깜빡이 켜고 ${num(interval)}초 만에 핸들을 돌렸어요",
            )
        }
    }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) = ctx.basic().observe(snap)
    }
}

/**
 * C5 깜빡이 없이 회전: 조향 회전마다 시작 5초 전~1초 뒤에 깜빡이가 한 번도 없으면 fail + 교정 카드, 있으면 ok
 * `{before}` = 조향 시작 때 맞는 쪽 깜빡이가 켜져 있었음(T-SIGNAL1 성공).
 */
internal val C5 = DetectorDef(id = "basic.C5", items = listOf("C5"), needs = listOf(SignalKey.BLINKER, SignalKey.STEER), modes = DRIVE_MODES) { ctx ->
    ctx.on<SteerTurn> { st ->
        val info = ctx.steerInfo(st)
        val turnId = st.navTurnId
        if (!st.blinkBefore) {
            ctx.emit("C5", EventResult.FAIL, EventData(turnId = turnId, dir = st.dir, before = false))
            ctx.card(
                item = "C5", key = "c5.fix", icon = iconOf("C5"), category = CardCategory.FIX, turnId = turnId, title = "깜빡이 없이 돌았어요",
                detail = Josa.josa(Words.dirName(st.dir.code), "으로/로") + " 꺾을 때 깜빡이가 꺼져 있었어요",
            )
        } else {
            ctx.emit("C5", EventResult.OK, EventData(turnId = turnId, dir = st.dir, before = info.onAtStart))
        }
    }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) = ctx.basic().observe(snap)
    }
}
