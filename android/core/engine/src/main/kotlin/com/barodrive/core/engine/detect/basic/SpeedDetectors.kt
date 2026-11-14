package com.barodrive.core.engine.detect.basic

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.detect.DetectorDef
import com.barodrive.core.engine.detect.DetectorInstance
import com.barodrive.core.engine.detect.RecordDraft
import com.barodrive.core.engine.round1
import com.barodrive.core.engine.review.round2
import com.barodrive.core.model.Category
import com.barodrive.core.model.EventData
import com.barodrive.core.model.EventResult
import com.barodrive.core.model.SignalKey
import com.barodrive.core.model.StraightWindow
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.model.WeatherType
import com.barodrive.core.model.ZoneEnter
import com.barodrive.core.model.ZoneExit
import kotlin.math.max

/*
 * 속도·핸들 검출기 (프로토타입 detectors/basic.js E1·E2·G1, 설계 6.3.1·6.8, 결정-70).
 */

/** E1 구역 하나의 상태. */
private class E1Zone(val id: String, val limit: Double?, val school: Boolean) {
    var overSince: Double? = null
    var fired = false
    var peak = 0.0
}

/**
 * E1 제한속도 초과(구역마다 1번): 제한속도(날씨 배율)를 3초 넘게 넘으면 fail + 안전 말('e1'·'e1.wx', 속도가 내려가면 버림),
 * 안 넘고 나가면 ok. 어린이보호구역에서 E2를 말했으면 E1은 말하지 않는다(결정-70).
 */
internal val E1 = DetectorDef(id = "basic.E1", items = listOf("E1"), needs = listOf(SignalKey.SPEED, SignalKey.NAV), modes = DRIVE_MODES) { ctx ->
    var zone: E1Zone? = null
    var lastV: Double? = null
    ctx.on<ZoneEnter> { z -> zone = E1Zone(z.id, z.limit, z.school) }
    ctx.on<ZoneExit> { z ->
        val zz = zone
        if (zz != null && zz.id == z.id && !zz.fired) {
            ctx.emit(
                "E1", EventResult.OK,
                EventData(zoneId = zz.id, limit = zz.limit, effLimit = ctx.limit(zz.limit)?.toDouble(), school = zz.school),
            )
        }
        if (zz != null && zz.id == z.id) zone = null
    }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            ctx.basic().observe(snap)
            lastV = snap.speed
            val z = zone ?: return
            val v = snap.speed ?: return
            if (z.fired) return
            val eff = ctx.limit(z.limit) ?: return
            if (v > eff + EPS) {
                val since = z.overSince ?: snap.t.also { z.overSince = it }
                z.peak = max(z.peak, v)
                if (snap.t - since > ctx.th("E1.sec") + EPS) {
                    z.fired = true
                    ctx.emit(
                        "E1", EventResult.FAIL,
                        EventData(
                            zoneId = z.id, limit = z.limit, effLimit = eff.toDouble(), school = z.school, peak = round1(z.peak),
                            overSec = round1(snap.t - since),
                        ),
                    )
                    if (z.id in ctx.basic().e2Zones) return // E2를 말한 구역은 E1을 말하지 않는다(결정-70)
                    val w = ctx.weather()
                    val wx = when {
                        w.severe != null -> "severe"
                        w.type == WeatherType.RAIN -> "rain"
                        w.type == WeatherType.SNOW -> "snow"
                        else -> null
                    }
                    ctx.say(
                        if (wx != null) "e1.wx" else "e1", item = "E1",
                        vars = if (wx != null) mapOf("L" to eff, "wx" to wx) else mapOf("L" to eff), category = Category.SAFETY,
                        alive = { lastV.let { lv -> zone === z && lv != null && lv > eff + EPS } },
                    )
                }
            } else {
                z.overSince = null
            }
        }
    }
}

/** E2 구역 하나의 상태. */
private class E2Zone(val id: String, val t0: Double) {
    var fired = false
}

/**
 * E2 어린이보호구역: 들어선 뒤 3초 안에 30(날씨 배율)을 넘으면 fail `{zoneId, limit: 30, effLimit, speed}` + 안전 말 'e2'.
 * 그 뒤는 E1이 본다.
 */
internal val E2 = DetectorDef(id = "basic.E2", items = listOf("E2"), needs = listOf(SignalKey.SPEED, SignalKey.NAV), modes = DRIVE_MODES) { ctx ->
    var zone: E2Zone? = null
    var lastV: Double? = null
    ctx.on<ZoneEnter> { z -> zone = if (z.school) E2Zone(z.id, z.t) else null }
    ctx.on<ZoneExit> { z -> if (zone?.id == z.id) zone = null }
    object : DetectorInstance {
        override fun step(snap: VehicleSnapshot) {
            ctx.basic().observe(snap)
            lastV = snap.speed
            val z = zone ?: return
            val v = snap.speed ?: return
            if (z.fired) return
            if (snap.t - z.t0 > ctx.th("E2.sec") + EPS) return // 들어선 뒤 3초 안만(그 뒤는 E1이 본다)
            val eff = ctx.th("E2.limit")
            if (v > eff + EPS) {
                z.fired = true
                ctx.basic().e2Zones += z.id
                ctx.emit("E2", EventResult.FAIL, EventData(zoneId = z.id, limit = SCHOOL_LIMIT, effLimit = eff, speed = round1(v)))
                ctx.say(
                    "e2", item = "E2", vars = mapOf("L" to eff), category = Category.SAFETY,
                    alive = { lastV.let { lv -> zone === z && lv != null && lv > eff + EPS } },
                )
            }
        }
    }
}

/**
 * G1 직진할 때 핸들 흔들림(리포트): 회차 끝에 직진 창 표준편차 평균(창 3개 미만이면 null '측정 부족') info
 * `{value, windows, base, baseFirst, better, insufficient}`. 기준 = 프로필 첫 G1(없으면 0.8°). 회차 지표 g1·g1Windows·g1Better.
 */
internal val G1 = DetectorDef(id = "basic.G1", items = listOf("G1"), needs = listOf(SignalKey.STEER), modes = DRIVE_MODES) { ctx ->
    val stds = ArrayList<Double>()
    ctx.on<StraightWindow> { w -> if (w.std.isFinite()) stds += w.std }
    object : DetectorInstance {
        override fun finish(rec: RecordDraft) {
            val n = stds.size
            val value = if (n >= ctx.th("G1.minWindows")) round2(stds.fold(0.0) { a, b -> a + b } / n) else null
            val hb = ctx.cfg.history.baselineG1
            val baseFirst = hb != null && hb.isFinite() && hb > 0
            val base = if (baseFirst && hb != null) hb else ctx.th("G1.base")
            val better = value?.let { round2((base - it) / base) }
            ctx.emit(
                "G1", EventResult.INFO,
                EventData(value = value, windows = n, base = base, baseFirst = baseFirst, better = better, insufficient = value == null),
            )
            rec.metrics.g1 = value
            rec.metrics.g1Windows = n
            rec.metrics.g1Better = better
        }
    }
}

private const val SCHOOL_LIMIT = 30.0
