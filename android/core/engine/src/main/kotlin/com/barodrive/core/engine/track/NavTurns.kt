package com.barodrive.core.engine.track

import com.barodrive.core.engine.EPS
import com.barodrive.core.engine.Thresholds
import com.barodrive.core.model.RerouteEvent
import com.barodrive.core.model.TbtItem
import com.barodrive.core.model.TrackerEvent
import com.barodrive.core.model.TurnApproach
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.TurnPassed
import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.max
import kotlin.math.min

/** TBT 한 줄을 회전으로 읽은 값 (core.js `turnInfo`). */
internal class TurnInfo(val id: String, val type: String, val dir: TurnDir?, val ic: Boolean, val enter: Boolean, val distance: Double)

private val NOT_TURN = Regex("STRAIGHT|DESTINATION|WAYPOINT|TOLL|TUNNEL|SERVICE|REST")
private val TURN = Regex("LEFT|RIGHT|SHARP|UTURN|U_TURN|KEEP|(^|_)IC(_|$)|DIVIDE|DEVIDE|BRANCH|MERGE")
private val IC = Regex("(^|_)IC(_|$)")
private val UTURN = Regex("UTURN|U_TURN")
private val LEFT = Regex("LEFT|_L$|_L_")
private val RIGHT = Regex("RIGHT|_R$|_R_")
private val ENTER = Regex("ENTER|ENTRY")

/** 회전으로 보는 TBT인가 — 직진·목적지·톨게이트 …는 아님. 방향이 없으면 종류 이름에서(유턴은 왼쪽) (core.js `turnInfo`). */
internal fun turnInfo(x: TbtItem): TurnInfo? {
    val type = x.type.uppercase()
    if (NOT_TURN.containsMatchIn(type) || !TURN.containsMatchIn(type)) return null
    val ic = IC.containsMatchIn(type)
    val dir = x.dir ?: when {
        UTURN.containsMatchIn(type) -> TurnDir.LEFT
        LEFT.containsMatchIn(type) -> TurnDir.LEFT
        RIGHT.containsMatchIn(type) -> TurnDir.RIGHT
        else -> null
    }
    return TurnInfo(x.id, type, dir, ic, ic && ENTER.containsMatchIn(type), x.distance)
}

/** 추적 중인 내비 회전 하나 (core.js `newTurn`의 turn 객체). */
internal class NavTurn(info: TurnInfo, val firstSeenT: Double, var eligibleSince: Double) {
    val id: String = info.id
    val type: String = info.type
    val dir: TurnDir? = info.dir
    val ic: Boolean = info.ic
    val enter: Boolean = info.enter
    val window: Double = Thresholds[if (info.ic) "turn.near.ic" else "turn.near"]
    var dist: Double = info.distance
    var inWindow = false
    var lastKey: ApproachKey? = null
    var blinkOnDist: Double? = null
    var blinkOnT: Double? = null
    var wrongOnDist: Double? = null
    var wrongOnT: Double? = null
    var steerStartT: Double? = null
    var steerEndT: Double? = null
    var belowSince: Double? = null
    var reachedT: Double? = null
    var missed = false
    var passed = false
    var displaced = false
    var skipped = false
}

/** turnApproach를 다시 낼지 보는 열쇠 (core.js `distance|blink|blinkOnDist|wrongOnDist` 글자). */
internal data class ApproachKey(val distance: Double, val blink: TurnDir?, val blinkOnDist: Double?, val wrongOnDist: Double?)

/**
 * 내비 회전 추적 (core.js 추적기 '내비 회전' 절: newTurn·watchSteer·approach·reach·completeStep·skipTurn·turnsStep·onReroute).
 * 회전 지점에 닿으면(TBT에서 사라지거나 거리 0) 조향이 끝날 때까지(없으면 8초, 길어도 30초) 기다렸다가 turnPassed를 낸다.
 */
internal class NavTurns(private val sh: TrackShared) {
    val turns = LinkedHashMap<String, NavTurn>()
    var cur: NavTurn? = null
        private set
    private val reached = HashSet<String>()
    private var lastReachedT = Double.NEGATIVE_INFINITY

    /** 회전 지점에 닿고 마무리를 기다리는 회전들. */
    val completing = ArrayList<NavTurn>()

    /** 이번 스냅숏이 건너뛰기 바로 뒤. */
    var skipNow = false

    private val steerPre = Thresholds["turn.steerPre"]

    fun turn(id: String): NavTurn? = turns[id]

    fun step(snap: VehicleSnapshot, t: Double, ev: MutableList<TrackerEvent>) {
        val nav = snap.nav
        if (nav != null) {
            val info = nav.tbt.firstOrNull()?.let(::turnInfo)
            val c = cur
            if (c != null && skipNow && (info == null || info.id != c.id)) {
                skip(c, t, ev)
            } else if (c != null && info != null && info.id != c.id && info.id !in reached && c.dist > steerPre * 2 + EPS &&
                info.distance < c.dist - EPS
            ) {
                // 지금 재던 회전보다 가까운 곳에 회전이 끼어들었다(시연 패널 주입). 재던 회전은 아직 멀리 있으니 잠시 내려놓는다(T11-2)
                c.displaced = true
                cur = null
            } else if (c != null && (info == null || info.id != c.id || !(info.distance > EPS))) {
                reach(c, t)
            }
            if (info != null && cur == null && info.id !in reached && info.distance > EPS) {
                val known = turns[info.id]
                if (known != null && known.displaced) {
                    known.displaced = false
                    known.eligibleSince = max(known.eligibleSince, lastReachedT)
                }
                cur = known ?: NavTurn(info, t, lastReachedT).also { turns[it.id] = it }
            }
            val now = cur
            if (now != null && info != null) approach(now, info, t, ev)
        }
        complete(t, ev, flush = false)
    }

    /** 세션 끝: 마무리를 기다리던 회전을 모두 낸다(아직 닿지 않은 회전은 내지 않음). */
    fun flush(t: Double, ev: MutableList<TrackerEvent>) = complete(t, ev, flush = true)

    /** 건너뛰기: 닿고 조향을 기다리던 회전을 skipped로 끝낸다(core.js `jump`). */
    fun skipCompleting(t: Double, ev: MutableList<TrackerEvent>) {
        for (turn in completing.toList()) skip(turn, t, ev)
    }

    /** 창 안의 다음 회전 모양(출력 `turn`), 없으면 null (core.js `updateView`). */
    fun viewTurn(): TurnApproach? {
        val c = cur ?: return null
        if (c.dist > c.window + EPS) return null
        val b = sh.blinkDir
        return approachView(c, b, b != null && sh.blinkOnT > c.eligibleSince + EPS, first = false)
    }

    /** 재탐색 사건 (core.js `onReroute`): 놓친 회전을 missed로. turnId가 없으면 30m 안의 지금 회전 또는 15초 안에 닿은 회전. */
    fun onReroute(turnId: String?, t: Double, ev: MutableList<TrackerEvent>) {
        var turn: NavTurn? = turnId?.let { turns[it] }
        if (turn == null) {
            val c = cur
            turn = if (c != null && c.dist <= steerPre + EPS) {
                c
            } else {
                var best: NavTurn? = null
                for (x in turns.values) {
                    val r = x.reachedT ?: continue
                    if (t - r <= REROUTE_LOOKBACK + EPS && (best == null || r > (best.reachedT ?: Double.NEGATIVE_INFINITY))) best = x
                }
                best
            }
        }
        ev += RerouteEvent(t = t, turnId = turn?.id ?: turnId)
        if (turn == null || turn.missed) return
        turn.missed = true
        if (turn.reachedT == null) reach(turn, t)
        sh.host.turnMissed(turn.id)
    }

    /** 조향 회전에 이어질 내비 회전 id (core.js `findNavTurn`). */
    fun findForSteer(dir: TurnDir): String? {
        val c = cur
        if (c != null && c.dist <= steerPre + EPS && (c.dir == null || c.dir == dir)) return c.id
        for (i in completing.indices.reversed()) {
            val x = completing[i]
            if (!x.missed && (x.dir == null || x.dir == dir)) return x.id
        }
        return null
    }

    private fun approach(turn: NavTurn, info: TurnInfo, t: Double, ev: MutableList<TrackerEvent>) {
        turn.dist = info.distance
        val b = sh.blinkDir
        val fresh = b != null && sh.blinkOnT > turn.eligibleSince + EPS
        val opp = turn.dir?.opposite
        val within = info.distance <= turn.window + EPS
        if (b != null && fresh && (turn.dir == null || b == turn.dir) && turn.blinkOnDist == null) {
            turn.blinkOnDist = min(info.distance, turn.window)
            turn.blinkOnT = sh.blinkOnT
        }
        if (within && b != null && fresh && opp != null && b == opp && turn.wrongOnDist == null) {
            turn.wrongOnDist = info.distance
            turn.wrongOnT = sh.blinkOnT
        }
        if (info.distance <= steerPre + EPS) watchSteer(turn, t)
        if (within) {
            val key = ApproachKey(info.distance, b, turn.blinkOnDist, turn.wrongOnDist)
            if (!turn.inWindow || key != turn.lastKey) {
                ev += approachView(turn, b, fresh, first = !turn.inWindow)
                turn.inWindow = true
                turn.lastKey = key
            }
        }
    }

    /** 회전 지점 근처(30m 안·지난 뒤)의 조향: 시작 = 같은 쪽 5° 넘은 순간, 끝 = 3° 아래 1초 (core.js `watchSteer`). */
    private fun watchSteer(turn: NavTurn, t: Double) {
        val st = sh.samples.last()?.steer ?: return
        val a = kotlin.math.abs(st)
        val sg = if (st < 0) TurnDir.LEFT else TurnDir.RIGHT
        if (turn.steerStartT == null) {
            if (a > Thresholds["steer.start"] + EPS && (turn.dir == null || sg == turn.dir)) turn.steerStartT = t
            return
        }
        if (turn.steerEndT != null) return
        if (a < Thresholds["steer.end"] - EPS) {
            val below = turn.belowSince ?: t.also { turn.belowSince = it }
            if (t - below >= Thresholds["steer.endSec"] - EPS) turn.steerEndT = below
        } else {
            turn.belowSince = null
        }
    }

    private fun reach(turn: NavTurn, t: Double) {
        turn.reachedT = t
        reached += turn.id
        lastReachedT = t
        if (turn.enter && !turn.missed) sh.host.addSilence(t + Thresholds["merge.silence"], "merge") // 합류 중(규칙 5)
        completing += turn
        if (cur === turn) cur = null
    }

    private fun complete(t: Double, ev: MutableList<TrackerEvent>, flush: Boolean) {
        var i = 0
        while (i < completing.size) {
            val turn = completing[i]
            if (!turn.missed && !flush) watchSteer(turn, t)
            val since = t - (turn.reachedT ?: t)
            val done = turn.missed || flush || turn.steerEndT != null ||
                (turn.steerStartT == null && since >= Thresholds["turn.timeout"] - EPS) || since >= Thresholds["turn.maxSec"] - EPS
            if (done) {
                completing.removeAt(i)
                turn.passed = true
                ev += passedView(turn)
            } else {
                i++
            }
        }
    }

    /** 건너뛰기로 지나친 회전: 판정하지 않고 skipped로만 알린다(설계 6.9, core.js `skipTurn`). */
    private fun skip(turn: NavTurn, t: Double, ev: MutableList<TrackerEvent>) {
        turn.skipped = true
        turn.passed = true
        if (turn.reachedT == null) turn.reachedT = t
        reached += turn.id
        lastReachedT = t
        if (cur === turn) cur = null
        completing.remove(turn)
        sh.host.turnSkipped(turn.id)
        ev += passedView(turn)
    }

    private fun approachView(turn: NavTurn, b: TurnDir?, fresh: Boolean, first: Boolean): TurnApproach {
        val opp = turn.dir?.opposite
        return TurnApproach(
            id = turn.id, type = turn.type, dir = turn.dir, ic = turn.ic, enter = turn.enter, distance = turn.dist, window = turn.window,
            blinkOnDist = turn.blinkOnDist, blinkOnT = turn.blinkOnT, wrongOnDist = turn.wrongOnDist, wrongOnT = turn.wrongOnT,
            blinkDir = b, blinkOk = b != null && fresh && (turn.dir == null || b == turn.dir),
            blinkWrong = b != null && fresh && opp != null && b == opp, first = first,
        )
    }

    private fun passedView(turn: NavTurn) = TurnPassed(
        id = turn.id, type = turn.type, dir = turn.dir, ic = turn.ic, enter = turn.enter, blinkOnDist = turn.blinkOnDist,
        blinkOnT = turn.blinkOnT, wrongOnDist = turn.wrongOnDist, wrongOnT = turn.wrongOnT, steerStartT = turn.steerStartT,
        steerEndT = turn.steerEndT, reachedT = turn.reachedT, missed = turn.missed, skipped = turn.skipped, window = turn.window,
    )

    private companion object {
        /** 재탐색에 회전 id가 없을 때 거슬러 보는 시간(초, core.js 15). */
        const val REROUTE_LOOKBACK = 15.0
    }
}
