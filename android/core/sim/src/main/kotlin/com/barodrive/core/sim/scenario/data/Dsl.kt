package com.barodrive.core.sim.scenario.data

import com.barodrive.core.model.CautionType
import com.barodrive.core.model.DriverSkill
import com.barodrive.core.model.TurnDir
import com.barodrive.core.sim.route.CautionSpec
import com.barodrive.core.sim.route.Leg
import com.barodrive.core.sim.route.Span
import com.barodrive.core.sim.scenario.Act
import com.barodrive.core.sim.scenario.Cond
import com.barodrive.core.sim.scenario.EventValue
import com.barodrive.core.sim.scenario.ScenarioEvent
import com.barodrive.core.sim.util.JsMath
import kotlin.math.abs
import kotlin.math.max

/*
 * 시나리오 데이터 파일(m1~m8 · weather · rules)을 프로토타입 JS 객체처럼 짧게 적는 도구.
 * go/turn/wp = 경로 다리(`{ s: 'go' … }`), ev/mark/endPark = 사건(`{ t, at, do, v … }`).
 */

internal fun go(
    len: Number,
    limit: Int? = null,
    road: String? = null,
    nav: Boolean? = null,
    cruise: Number? = null,
    grade: Number = 0,
    school: Span? = null,
    caution: CautionSpec? = null,
    jam: Span? = null,
    free: Boolean = false,
    key: String? = null,
) = Leg.Go(
    len = len.toDouble(), limit = limit, road = road, nav = nav, cruise = cruise?.toDouble(), grade = grade.toDouble(),
    school = listOfNotNull(school), cautions = listOfNotNull(caution), jams = listOfNotNull(jam), free = free, key = key,
)

internal fun turn(dir: String = "R", angle: Number = 90, r: Number? = null, tbt: String? = null, noTbt: Boolean = false, road: String? = null, nav: Boolean? = null) =
    Leg.Turn(dir = if (dir.uppercase().startsWith("L")) TurnDir.LEFT else TurnDir.RIGHT, angle = angle.toDouble(), r = r?.toDouble(), tbt = tbt, noTbt = noTbt, road = road, nav = nav)

internal fun wp(name: String? = null) = Leg.Waypoint(name)

internal fun span(a: Number, b: Number) = Span(a.toDouble(), b.toDouble())

internal fun caution(at: Number, type: CautionType, len: Number) = CautionSpec(at.toDouble(), type, len.toDouble())

internal fun ev(
    t: Number? = null,
    at: Number? = null,
    act: Act? = null,
    v: Any? = null,
    drop: Number? = null,
    hold: Number? = null,
    gain: Number? = null,
    speed: Number? = null,
    dur: Number? = null,
    sec: Number? = null,
    n: Int? = null,
    text: String? = null,
    type: String? = null,
    handbrake: Boolean? = null,
    cond: Cond? = null,
    skills: List<DriverSkill>? = null,
    meters: Number? = null,
    startIn: Number? = null,
    ahead: Number? = null,
    len: Number? = null,
    turn: Int? = null,
    keepOn: Boolean? = null,
) = ScenarioEvent(
    t = t?.toDouble(), at = at?.toDouble(), act = act, v = EventValue.of(v), drop = drop?.toDouble(), hold = hold?.toDouble(),
    gain = gain?.toDouble(), speed = speed?.toDouble(), dur = dur?.toDouble(), sec = sec?.toDouble(), n = n, text = text, type = type,
    handbrake = handbrake, cond = cond, skills = skills, meters = meters?.toDouble(), startIn = startIn?.toDouble(),
    ahead = ahead?.toDouble(), len = len?.toDouble(), turn = turn, keepOn = keepOn,
)

/** 장면(`{ mark, at }` · `{ mark, t }`). */
internal fun mark(name: String, at: Number? = null, t: Number? = null) = ScenarioEvent(mark = name, at = at?.toDouble(), t = t?.toDouble())

/** 끝(`{ end: true, do: 'park' }`). */
internal fun endPark(handbrake: Boolean? = null) = ScenarioEvent(end = true, act = Act.PARK, handbrake = handbrake)

/** JS `Math.round`. */
internal fun jr(x: Double): Double = JsMath.round(x)

/** 원호 길이 셈 — 데이터 파일마다 JS 식이 달라 마지막 자리가 다를 수 있어 그대로 고른다. */
internal enum class ArcFormula {
    /** `r * a * D2R`(D2R = π/180, route.js와 같음) — m2·m3·m4·weather. */
    D2R,

    /** `r * a * Math.PI / 180` — m1·m5·m7·m8. */
    PI_180,

    /** `Math.PI * r * a / 180` — rules. */
    PI_FIRST,
    ;

    fun len(r: Double, a: Double): Double = when (this) {
        D2R -> r * a * JsMath.DEG
        PI_180 -> r * a * Math.PI / 180
        PI_FIRST -> Math.PI * r * a / 180
    }
}

/** 회전 기본 반지름(각도별, route.js와 같음). */
internal fun defaultR(a: Double): Double = if (a >= 150) 6.0 else if (a >= 100) 8.0 else if (a >= 30) 10.0 else 60.0

/** 회전 하나의 원래 경로 자리. */
internal class TurnAt(val n: Int, val s0: Double, val s1: Double, val nav: Boolean, val r: Double, val angle: Double, val leg: Leg.Turn)

/** 직진 다리 하나의 원래 경로 자리(m7 `goes`). */
internal class GoAt(val i: Int, val s0: Double, val s1: Double, val len: Double)

/** 주의 구간 자리(m7 `cautions`). */
internal class CautionAt(val type: CautionType, val s0: Double, val s1: Double, val len: Double)

/**
 * 다리 목록을 따라가며 원래 경로 거리(m)를 잰다(데이터 파일의 `measure`·`layout`). key가 있는 다리의 시작 거리로 [at],
 * 다리 번호로 [start].
 */
internal class Layout(legs: List<Leg>, arc: ArcFormula) {
    var len = 0.0
        private set
    var navEnd = 0.0
        private set
    val turns = ArrayList<TurnAt>()
    val goes = ArrayList<GoAt>()
    val cautions = ArrayList<CautionAt>()
    private val starts = HashMap<String, Double>()
    private val legStarts = ArrayList<Double>()

    init {
        var s = 0.0
        var n = 0
        var nav = true
        legs.forEachIndexed { i, l ->
            legStarts += s
            l.nav?.let { nav = it }
            when (l) {
                is Leg.Go -> {
                    l.key?.let { starts[it] = s }
                    val ln = max(0.0, l.len)
                    goes += GoAt(i, s, s + ln, ln)
                    for (c in l.cautions) {
                        val a = s + c.at
                        cautions += CautionAt(c.type, a, a + c.len, c.len)
                    }
                    s += ln
                    if (nav) navEnd = s
                }
                is Leg.Turn -> {
                    n++
                    val a = abs(l.angle)
                    val r = if (l.r != null && l.r > 0) l.r else defaultR(a)
                    val ln = arc.len(r, a)
                    turns += TurnAt(n, s, s + ln, nav, r, a, l)
                    s += ln
                    if (nav) navEnd = s
                }
                is Leg.Waypoint -> Unit
            }
        }
        len = s
    }

    /** key 다리 시작 + [off] m, JS `Math.round`. */
    fun at(key: String, off: Number = 0): Double {
        val s = starts[key] ?: error("다리 없음 $key")
        return jr(s + off.toDouble())
    }

    /** [i]번째 다리 시작(0부터). */
    fun start(i: Int): Double = legStarts[i]
}
