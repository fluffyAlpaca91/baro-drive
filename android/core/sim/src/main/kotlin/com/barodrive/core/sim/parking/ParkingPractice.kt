package com.barodrive.core.sim.parking

import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.util.JsMath.DEG
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 차종 크기 + 앞·뒤 내민 길이(m) — parking.js `car(model)`. */
data class ParkCar(
    val id: String,
    val name: String,
    val length: Double,
    val width: Double,
    val wheelbase: Double,
    val maxSteer: Double,
    val rear: Double,
    val front: Double,
)

/** 칸 경계(또는 변마다 봐주는 폭). */
data class Box(val x0: Double, val x1: Double, val y0: Double, val y1: Double)

/** 주차 칸: 경계 [box], 변마다 봐주는 폭 [tol], 바르게 선 방향 [heading](라디안). */
data class Slot(val box: Box, val tol: Box, val heading: Double)

/** 세워 둔 옆차. */
data class ParkedCar(val id: String, val x: Double, val y: Double, val h: Double, val length: Double, val width: Double, val poly: List<Pt>)

/** 연석(다각형 · 선 · 그림 쪽). */
data class Curb(val id: String, val poly: List<Pt>, val line: List<Pt>, val side: List<Pt>)

/** 멈춤턱([a]–[b] 선분, [n] = 벽 쪽 법선). */
data class StopBar(val id: String, val a: Pt, val b: Pt, val n: Pt)

/** 정해 둔 조작의 기준 값(멈출 곳 [brakeAt] — [axis] 'x'·'y' 방향 [sign]). */
data class Ideal(
    val axis: String,
    val sign: Int,
    val brakeAt: Double,
    val phi: Double? = null,
    val yTurn: Double? = null,
    val yF: Double? = null,
    val xF: Double? = null,
    val ys: Double? = null,
)

/** 장면(parking.js `scene`): 시작 자세·칸·옆차·연석·멈춤턱·주차선·차선·기준 값·팁. */
data class ParkScene(
    val type: String,
    val side: String,
    val w: Double,
    val h: Double,
    val start: ParkPose,
    val slot: Slot,
    val cars: List<ParkedCar>,
    val curbs: List<Curb>,
    val stops: List<StopBar>,
    val lines: List<List<Pt>>,
    val dashes: List<List<Pt>>,
    val ideal: Ideal,
    val xE: Double? = null,
    val xW: Double? = null,
    val model: String = "avante",
    val tips: List<String> = emptyList(),
)

/** 한 단계 입력: 앞바퀴각 [steer]°(오른쪽 +), [brake], 가상 기어 [dir] 'D'·'R'. */
data class ParkInput(val steer: Double = 0.0, val brake: Boolean = false, val dir: String = "D")

/** 닿은 곳: 'car'(옆차)·'curb'(연석), 그 id, 자리. */
data class Touch(val kind: String, val id: String, val x: Double, val y: Double)

/** 기준점 팁: [index] 0 = 첫 문장(멈출·돌릴 곳) · 1 = 둘째(핸들 풀 때), [near] = 지금 그 시점 근처(카드 강조). */
data class Tip(val index: Int, val near: Boolean)

/** 성공 결과(걸린 초 · 앞뒤로 바꾼 횟수 · 끝 바퀴각 · 바퀴 똑바로(5° 이하)). */
data class ParkOutcome(val sec: Double, val switches: Int, val wheelAngle: Double, val wheelStraight: Boolean)

/**
 * 연습 상태(parking.js `state`). [status] 'ready' · 'moving' · 'stopped' · 'touched' · 'done', [blocked] null · 'stop'(멈춤턱) · 'edge'(그림 끝).
 * [v] m/s(뒤로 −), [steer] 적용한 앞바퀴각°, [sec] 처음 움직인 뒤 시간, [still] 멈춘 지 초.
 */
class ParkState internal constructor(
    var x: Double,
    var y: Double,
    var h: Double,
) {
    var v = 0.0
    var speedKmh = 0.0
    var steer = 0.0
    var dir = "D"
    var brake = false
    var armed = false
    var moved = false
    var status = "ready"
    var touch: Touch? = null
    var blocked: String? = null
    var t = 0.0
    var sec = 0.0
    var switches = 0
    var still = 0.0
    internal var lastDir: String? = null
    var inside = false
    var aligned = false
    var turned = false
    var tip = Tip(0, false)
    var result: ParkOutcome? = null
}

/**
 * 가상 주차 연습 하나 (parking.js `create(type, side, model)`). 처음과 [reset] 뒤에는 브레이크를 한 번 밟아야(armed) 움직인다.
 * 닿음: 옆차·연석과 겹치면 그 자리에서 멈추고 'touched'(reset까지 그대로). 멈춤턱·그림 끝은 그쪽으로만 막힌다.
 * 성공: 네 모서리가 칸 안 + 칸 방향과 ±5° + 1초 멈춤 → 'done'.
 */
class ParkingPractice internal constructor(val car: ParkCar, val scene: ParkScene) {
    val type: String get() = scene.type
    val side: String get() = scene.side

    private val obstacles: List<Triple<String, String, List<Pt>>> =
        scene.cars.map { Triple("car", it.id, it.poly) } + scene.curbs.map { Triple("curb", it.id, it.poly) }
    private var maxTurn = 0.0

    var state: ParkState = ParkState(scene.start.x, scene.start.y, scene.start.h)
        private set

    init {
        reset()
    }

    /** 처음 자리로(다시 브레이크를 밟아야 움직임). */
    fun reset(): ParkState {
        maxTurn = 0.0
        state = ParkState(scene.start.x, scene.start.y, scene.start.h)
        evaluate()
        return state
    }

    private fun pose() = ParkPose(state.x, state.y, state.h)

    /** 지금(또는 [p]) 차 네 모서리. */
    fun corners(p: ParkPose? = null): List<Pt> = ParkingGeometry.corners(car, p ?: pose())

    /** 지금(또는 [p]) 바퀴 넷. */
    fun wheels(p: ParkPose? = null, steer: Double? = null): List<Wheel> = ParkingGeometry.wheels(car, p ?: pose(), steer ?: state.steer)

    private fun insideBox(poly: List<Pt>): Boolean {
        val b = scene.slot.box
        val t = scene.slot.tol
        return poly.all { q ->
            q.x >= b.x0 - t.x0 - 1e-9 && q.x <= b.x1 + t.x1 + 1e-9 && q.y >= b.y0 - t.y0 - 1e-9 && q.y <= b.y1 + t.y1 + 1e-9
        }
    }

    private fun edgeOut(q: Pt): Double = maxOf(0.0, -q.x, q.x - scene.w, -q.y, q.y - scene.h)

    /** 지금 자세로 칸 안·방향·팁을 다시 셈. */
    private fun evaluate() {
        val st = state
        val p = pose()
        st.inside = insideBox(ParkingGeometry.corners(car, p))
        st.aligned = abs(ParkingGeometry.angDiff(p.h, scene.slot.heading)) <= VirtualParking.HEAD_TOL * DEG
        val turn = abs(ParkingGeometry.angDiff(p.h, scene.start.h)) / DEG
        if (turn > VirtualParking.TIP_TURN) st.turned = true
        if (turn > maxTurn) maxTurn = turn
        val i = scene.ideal
        val straight = abs(st.steer) <= 3
        val near = if (!st.turned) {
            // 첫 문장: 멈출 곳(평행·후방) · 핸들을 끝까지 돌릴 곳(전방 — 이미 끝까지 돌렸으면 끔)
            val prog = (if (i.axis == "x") p.x else p.y) * i.sign
            abs(prog - i.brakeAt * i.sign) <= VirtualParking.NEAR1 && !(scene.type == "front" && abs(st.steer) >= car.maxSteer * 0.9)
        } else if (scene.type == "parallel") {
            // 둘째 문장: 오른쪽 끝으로 돌아 들어가 '비스듬해질' 때(다시 펴지는 마지막 원호에서는 켜지 않음), 핸들을 풀면 끔
            val phi = i.phi ?: 0.0
            turn >= phi - 10 && turn <= phi + 6 && turn >= maxTurn - 0.5 && !straight
        } else {
            abs(ParkingGeometry.angDiff(p.h, scene.slot.heading)) <= VirtualParking.NEAR2 * DEG && !straight
        }
        st.tip = Tip(if (st.turned) 1 else 0, near && st.status != "touched" && st.status != "done")
    }

    /** 한 단계(dt초): 속도 → 움직임 → 닿음·막힘. */
    private fun sub(dt: Double, steer: Double, brake: Boolean, want: Int) {
        val st = state
        val go = st.armed && !brake
        var target = if (go) want * VirtualParking.V_MAX else 0.0
        if (st.v * want < -1e-9) target = 0.0 // 반대로 가는 중이면 먼저 멈춘다
        val wasStill = abs(st.v) < 1e-9
        if (st.v < target) {
            st.v = min(target, st.v + (if (abs(st.v) < abs(target) && st.v >= 0) VirtualParking.ACC else VirtualParking.DEC) * dt)
        } else if (st.v > target) {
            st.v = max(target, st.v - (if (abs(st.v) < abs(target) && st.v <= 0) VirtualParking.ACC else VirtualParking.DEC) * dt)
        }
        if (abs(st.v) < 1e-9) st.v = 0.0
        if (wasStill && st.v != 0.0) { // 움직이기 시작: 앞뒤로 바꾼 횟수
            val d = if (st.v > 0) "D" else "R"
            val last = st.lastDir
            if (last != null && last != d) st.switches++
            st.lastDir = d
            st.moved = true
        }
        if (st.v == 0.0) return
        val from = pose()
        val to = ParkingGeometry.advance(car, from, st.v * dt, steer)
        val polyTo = ParkingGeometry.corners(car, to)
        for ((kind, id, poly) in obstacles) {
            if (ParkingGeometry.overlaps(polyTo, poly)) {
                val at = ParkingGeometry.contact(polyTo, poly) ?: Pt(to.x, to.y)
                st.v = 0.0
                st.status = "touched"
                st.touch = Touch(kind, id, JsMath.round3(at.x), JsMath.round3(at.y))
                return
            }
        }
        // 멈춤턱: 바퀴가 턱에 닿으면 그쪽으로는 더 못 감
        val wFrom = ParkingGeometry.wheels(car, from, steer)
        val wTo = ParkingGeometry.wheels(car, to, steer)
        for (s in scene.stops) {
            val len = JsMath.hypot(s.b.x - s.a.x, s.b.y - s.a.y)
            val ux = (s.b.x - s.a.x) / len
            val uy = (s.b.y - s.a.y) / len
            for (i in 0 until 4) {
                val q = wTo[i]
                val along = (q.x - s.a.x) * ux + (q.y - s.a.y) * uy
                if (along < -0.1 || along > len + 0.1) continue
                val dTo = (q.x - s.a.x) * s.n.x + (q.y - s.a.y) * s.n.y
                val dFrom = (wFrom[i].x - s.a.x) * s.n.x + (wFrom[i].y - s.a.y) * s.n.y
                if (dTo > -VirtualParking.WHEEL_R && dTo > dFrom && dFrom < 0) {
                    st.v = 0.0
                    st.blocked = "stop"
                    return
                }
            }
        }
        // 그림 끝
        val cFrom = ParkingGeometry.corners(car, from)
        for (i in 0 until 4) {
            val o2 = edgeOut(polyTo[i])
            if (o2 > VirtualParking.EDGE_PAD && o2 > edgeOut(cFrom[i]) + 1e-9) {
                st.v = 0.0
                st.blocked = "edge"
                return
            }
        }
        st.blocked = null
        st.x = to.x
        st.y = to.y
        st.h = to.h
    }

    /** [dt]초(0.1초 = 4단계) 나아간다 → 상태. */
    fun step(dt: Double, input: ParkInput = ParkInput()): ParkState {
        val st = state
        val d = if (dt > 0) dt else VirtualParking.DT
        val steer = JsMath.clamp(JsMath.orZero(input.steer), -car.maxSteer, car.maxSteer)
        val brake = input.brake
        val dir = if (input.dir == "R") "R" else "D"
        st.steer = steer
        st.brake = brake
        st.dir = dir
        st.t = JsMath.round3(st.t + d)
        if (st.status == "touched" || st.status == "done") {
            st.v = 0.0
            st.speedKmh = 0.0
            return st
        }
        if (brake && !st.armed) st.armed = true
        val want = if (dir == "R") -1 else 1
        val n = VirtualParking.SUB
        val h = d / n
        var i = 0
        while (i < n && st.status != "touched") {
            sub(h, steer, brake, want)
            i++
        }
        st.speedKmh = JsMath.round1(st.v * 3.6)
        if (st.moved && st.status != "touched") st.sec = JsMath.round3(st.sec + d)
        if (st.status != "touched") {
            st.still = if (st.v == 0.0) JsMath.round3(st.still + d) else 0.0
            st.status = if (st.v != 0.0) "moving" else if (st.moved) "stopped" else "ready"
        }
        evaluate()
        if (st.status == "stopped" && st.inside && st.aligned && st.still >= VirtualParking.DONE_HOLD - 1e-9) {
            st.status = "done"
            st.tip = st.tip.copy(near = false)
            st.result = ParkOutcome(JsMath.round1(st.sec), st.switches, JsMath.round1(steer), abs(steer) <= VirtualParking.WHEEL_OK)
        }
        return st
    }
}
