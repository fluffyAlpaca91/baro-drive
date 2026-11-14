package com.barodrive.core.sim.parking

import com.barodrive.core.sim.util.JsMath
import com.barodrive.core.sim.util.JsMath.DEG
import com.barodrive.core.sim.vehicle.CarModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 가상 주차 모델 (프로토타입 sim/parking.js `BaroSim.parking`, 설계 7.9·결정-58·59) — PK-2 세워 두고 연습의 화면 속 차.
 * 시계·난수를 쓰지 않는다(넘겨받은 dt로만 나아간다).
 *
 * 좌표: 위에서 본 장면, m, x 오른쪽 · y 아래. 그림은 1m = 60px([PX]), 장면 840×640px = 14 × 10.67m([VIEW_W]·[VIEW_H]).
 * 차 자세 = 뒷바퀴 축 가운데, h = 차 앞 방향(라디안, 0 = 오른쪽). 자전거 모델을 0.1초 = 4단계 원호로 정확히 적분한다.
 * 속도: 브레이크를 떼면 가상 기어 방향으로 0.8m/s(약 3km/h)까지 0.5초, 밟으면 0.3초에 멈춘다. 기어를 바꾸면 먼저 멈춘 뒤 반대로.
 * 종류 'parallel' 평행 · 'back' 후방(후진) · 'front' 전방, 방향 'L'·'R'. 오른쪽 칸으로 만들고 왼쪽 칸은 좌우를 뒤집는다.
 */
object VirtualParking {
    const val PX = 60.0
    const val VIEW_W = 14.0
    const val VIEW_H = 640.0 / 60
    const val DT = 0.1
    const val V_MAX = 0.8
    const val ACCEL_SEC = 0.5
    const val STOP_SEC = 0.3
    internal const val ACC = V_MAX / 0.5
    internal const val DEC = V_MAX / 0.3
    internal const val SUB = 4
    const val DONE_HOLD = 1.0
    const val HEAD_TOL = 5.0
    const val WHEEL_OK = 5.0
    internal const val LINE_TOL = 0.15
    internal const val ENTRY_TOL = 0.4
    internal const val EDGE_PAD = 0.05
    private const val PARKED_LENGTH = 4.6
    private const val PARKED_WIDTH = 1.85
    const val SLOT_W = 2.5
    const val SLOT_L = 5.0
    const val PAR_EXTRA = 2.2
    private const val PAR_GAP = 0.8
    private const val PAR_PARK_OFF = 0.15
    private const val PAR_CURB_GAP = 0.5
    private const val PAR_STRAIGHT = 0.4
    private const val STOP_LINE = 0.95
    internal const val WHEEL_R = 0.3
    internal const val TIP_TURN = 10.0
    internal const val NEAR1 = 0.5
    internal const val NEAR2 = 12.0
    private const val PERP_XE = 8.8
    private const val FRONT_EARLY = 0.2
    private val STOP_DIST = V_MAX * V_MAX / (2 * DEC)

    /** 종류(id · 이름 · 전체 이름). */
    data class Kind(val id: String, val name: String, val full: String)

    val TYPES: List<Kind> = listOf(Kind("parallel", "평행", "평행 주차"), Kind("back", "후방(후진)", "후방(후진) 주차"), Kind("front", "전방", "전방 주차"))
    val SIDES: List<Pair<String, String>> = listOf("L" to "왼쪽", "R" to "오른쪽")

    /** 표 A-6 기준점 팁(왼쪽 칸도 같은 문장). */
    val TIPS: Map<String, List<String>> = mapOf(
        "parallel" to listOf("앞차 뒷범퍼 끝이 내 어깨와 나란히 오면 멈추세요.", "주차할 칸 반대쪽 사이드미러에 뒤차 앞부분이 다 보이면 핸들을 똑바로 푸세요."),
        "back" to listOf("주차할 칸 다음 칸의 주차선이 내 어깨와 나란히 오면 멈추세요.", "양쪽 사이드미러에 주차선이 같은 간격으로 보이면 핸들을 똑바로 푸세요."),
        "front" to listOf("주차할 쪽 사이드미러가 칸의 첫 번째 주차선을 지나면 핸들을 끝까지 돌리세요.", "앞 유리 창틀 너머로 옆차와 내 차가 나란해 보이면 핸들을 똑바로 푸세요."),
    )
    const val WHEEL_TIP = "바퀴를 똑바로 두면 다음 출발이 편해요."

    private fun typeOf(t: String?): String? = TYPES.firstOrNull { it.id == t }?.id
    private fun sideOf(s: String?): String? = when (s?.uppercase()) {
        "L", "LEFT" -> "L"
        "R", "RIGHT" -> "R"
        else -> null
    }

    /* ---------------- 이름 ---------------- */

    fun typeName(t: String?): String = TYPES.firstOrNull { it.id == t }?.name ?: ""
    fun sideName(s: String?): String = SIDES.firstOrNull { it.first == sideOf(s) }?.second ?: ""

    /** 프로필 parking.done 키 'parallel-R'(설계 8.1). */
    fun key(type: String?, side: String?): String = (typeOf(type) ?: "parallel") + "-" + (sideOf(side) ?: "R")

    /** 'front-L' → (front, L) | null. */
    fun parse(k: String?): Pair<String, String>? {
        val m = Regex("^([a-z]+)-([LR])$").find(k.orEmpty()) ?: return null
        val t = typeOf(m.groupValues[1]) ?: return null
        return Pair(t, m.groupValues[2])
    }

    /** '평행 · 오른쪽'. */
    fun label(type: String?, side: String?): String = typeName(type) + " · " + sideName(side)

    /** '평행 · 오른쪽 칸'. */
    fun title(type: String?, side: String?): String = label(type, side) + " 칸"

    /* ---------------- 차종 ---------------- */

    /** 차종 크기 + 앞·뒤 내민 길이(id 또는 모르면 아반떼). */
    fun car(model: String?): ParkCar = car(CarModel.of(model) ?: CarModel.AVANTE)

    fun car(d: CarModel): ParkCar {
        val over = max(0.8, d.length - d.wheelbase)
        val rear = JsMath.round3(over * 0.54)
        return ParkCar(d.id, d.name, d.length, d.width, d.wheelbase, d.maxSteer, rear, JsMath.round3(over - rear))
    }

    /** 최대로 꺾었을 때 뒷바퀴 축 가운데가 도는 반지름 = 축거 / tan(최대각)(m). */
    fun minRadius(model: String?): Double {
        val c = car(model)
        return c.wheelbase / JsMath.tan(c.maxSteer * DEG)
    }

    /* ---------------- 장면 ---------------- */

    /** 바르게 섰을 때 뒤로 S자로 옆으로 [dy]만큼 옮기는 꺾는 각 φ (2R(1−cosφ) + s·sinφ = dy). */
    private fun sCurveAngle(r: Double, dy: Double, s: Double): Double {
        var lo = 1 * DEG
        var hi = 89 * DEG
        repeat(60) {
            val mid = (lo + hi) / 2
            if (2 * r * (1 - JsMath.cos(mid)) + s * JsMath.sin(mid) < dy) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private fun parkedCar(id: String, cx: Double, cy: Double, h: Double): ParkedCar {
        val l = PARKED_LENGTH
        val w = PARKED_WIDTH
        val p = ParkPose(cx - (l / 2) * JsMath.cos(h), cy - (l / 2) * JsMath.sin(h), h)
        val poly = listOf(
            ParkingGeometry.local(p, l, -w / 2), ParkingGeometry.local(p, l, w / 2),
            ParkingGeometry.local(p, 0.0, w / 2), ParkingGeometry.local(p, 0.0, -w / 2),
        )
        return ParkedCar(id, cx, cy, h, l, w, poly)
    }

    /** 평행 · 오른쪽: 가로 길, 아래가 연석. 차는 오른쪽을 보고 앞차 옆 차선에서 시작 → 앞으로 가서 멈춤 → 뒤로 S자. */
    private fun parallelRight(c: ParkCar): ParkScene {
        val w = VIEW_W
        val h = VIEW_H
        val curbY = 9.55
        val off = PAR_PARK_OFF
        val len = JsMath.round3(c.length + PAR_EXTRA)
        val r = c.wheelbase / JsMath.tan(c.maxSteer * DEG)
        val py1 = curbY - off
        val py0 = py1 - PARKED_WIDTH
        val yLane = py0 - PAR_GAP - c.width / 2
        val yF = curbY - PAR_CURB_GAP - c.width / 2 // 다 넣었을 때 차 가운데
        val straight = PAR_STRAIGHT
        val phi = sCurveAngle(r, yF - yLane, straight)
        val travel = 2 * r * JsMath.sin(phi) + straight * JsMath.cos(phi)
        val rOut = JsMath.hypot(c.wheelbase + c.front, r + c.width / 2)
        val q = py0 - (yF - r)
        val d2 = sqrt(max(0.0, rOut * rOut - q * q))
        val lo = c.rear + 0.15
        val hi = len - d2 - 0.1
        val xFrel = (lo + hi) / 2
        val xStop = w - 0.5 - (c.wheelbase + c.front)
        val xF = xStop - travel
        val x0 = xF - xFrel
        val x1 = x0 + len
        val cars = listOf(
            parkedCar("rear", x0 - PARKED_LENGTH / 2, (py0 + py1) / 2, 0.0),
            parkedCar("front", x1 + PARKED_LENGTH / 2, (py0 + py1) / 2, 0.0),
        )
        return ParkScene(
            type = "parallel", side = "R", w = w, h = h,
            start = ParkPose(xStop - STOP_DIST - 2.5, yLane, 0.0),
            slot = Slot(Box(x0, x1, curbY - SLOT_W, curbY), Box(LINE_TOL, LINE_TOL, LINE_TOL, 0.0), 0.0),
            cars = cars,
            curbs = listOf(
                Curb(
                    "curb", ParkingGeometry.rect(-5.0, curbY, w + 5, curbY + 5), listOf(Pt(-1.0, curbY), Pt(w + 1, curbY)),
                    listOf(Pt(-1.0, curbY), Pt(w + 1, curbY), Pt(w + 1, h + 1), Pt(-1.0, h + 1)),
                ),
            ),
            stops = emptyList(),
            lines = emptyList(),
            dashes = listOf(listOf(Pt(-1.0, curbY - SLOT_W - 3.3), Pt(w + 1, curbY - SLOT_W - 3.3))),
            ideal = Ideal("x", 1, xStop - STOP_DIST, phi = phi / DEG, yTurn = yF - r * (1 - JsMath.cos(phi)), yF = yF, xF = xF),
        )
    }

    private class Perp(val xE: Double, val xW: Double, val cars: List<ParkedCar>, val lines: List<List<Pt>>, val stops: List<StopBar>)

    /** 직각 칸 줄(후방·전방 · 오른쪽): 세로 통로, 오른쪽에 칸(입구 xE, 벽 xW). */
    private fun perpSlots(ys: Double, h: Double): Perp {
        val xE = PERP_XE
        val xW = xE + SLOT_L
        val cars = ArrayList<ParkedCar>()
        val lines = ArrayList<List<Pt>>()
        val stops = ArrayList<StopBar>()
        for (k in -4..4) {
            val yc = ys + k * SLOT_W
            val top = yc - SLOT_W / 2
            val bot = yc + SLOT_W / 2
            if (bot < -0.5 || top > h + 0.5) continue
            lines += listOf(Pt(xE, bot), Pt(xW, bot))
            if (k == -4 || lines.size == 1) lines += listOf(Pt(xE, top), Pt(xW, top))
            stops += StopBar("stop$k", Pt(xW - STOP_LINE, yc - 0.9), Pt(xW - STOP_LINE, yc + 0.9), Pt(1.0, 0.0))
            if (k != 0) {
                val cx = xW - 0.25 - PARKED_LENGTH / 2
                cars += parkedCar("car$k", cx, yc, if (k % 2 != 0) Math.PI else 0.0) // 번갈아 앞·뒤로 세운 차
            }
        }
        return Perp(xE, xW, cars, lines, stops)
    }

    private fun perpBase(ys: Double, type: String, start: ParkPose, ideal: Ideal): ParkScene {
        val w = VIEW_W
        val h = VIEW_H
        val p = perpSlots(ys, h)
        return ParkScene(
            type = type, side = "R", w = w, h = h, start = start,
            slot = Slot(Box(p.xE, p.xW, ys - SLOT_W / 2, ys + SLOT_W / 2), Box(ENTRY_TOL, 0.0, LINE_TOL, LINE_TOL), if (type == "back") Math.PI else 0.0),
            cars = p.cars,
            curbs = listOf(
                Curb(
                    "curb", ParkingGeometry.rect(p.xW, -5.0, p.xW + 5, h + 5), listOf(Pt(p.xW, -1.0), Pt(p.xW, h + 1)),
                    listOf(Pt(p.xW, -1.0), Pt(w + 1, -1.0), Pt(w + 1, h + 1), Pt(p.xW, h + 1)),
                ),
            ),
            stops = p.stops, lines = p.lines, dashes = emptyList(), ideal = ideal, xE = p.xE, xW = p.xW,
        )
    }

    /** 후방(후진) · 오른쪽: 칸을 조금 지나 멈춤 → 오른쪽 끝 후진 → 칸과 나란하면 똑바로 → 멈춤턱까지. */
    private fun backRight(c: ParkCar): ParkScene {
        val r = c.wheelbase / JsMath.tan(c.maxSteer * DEG)
        val ys = JsMath.round3(r + c.wheelbase + c.front + 0.5) // 멈출 곳에서 차 앞이 그림 위 끝에서 50cm
        val x = 3.6
        val yStop = ys - r // 오른쪽 끝으로 90° 돌면 칸 가운데에 맞는 뒷바퀴 축 자리
        return perpBase(ys, "back", ParkPose(x, yStop + STOP_DIST + 2.2, -Math.PI / 2), Ideal("y", -1, yStop + STOP_DIST, ys = ys))
    }

    /** 전방 · 오른쪽: 통로 반대쪽으로 붙어 올라가다 칸 입구에서 오른쪽 끝 → 칸과 나란하면 똑바로 → 멈춤턱까지. */
    private fun frontRight(c: ParkCar): ParkScene {
        val r = c.wheelbase / JsMath.tan(c.maxSteer * DEG)
        val ys = JsMath.round3(VIEW_H - 0.3 - c.rear - 2.0 - r) // 시작할 때 차 뒤가 그림 아래 끝에서 30cm
        val xW = PERP_XE + SLOT_L
        // 돌 때 차 앞 왼쪽 모서리가 위 칸 차(입구 쪽 아래 모서리)를 60cm 비켜 가는 데까지 통로 반대쪽으로 붙인다(그림 왼쪽 끝 15cm까지)
        val rOut = JsMath.hypot(c.wheelbase + c.front, r + c.width / 2)
        val qx = xW - 0.25 - PARKED_LENGTH
        val qdy = SLOT_W - PARKED_WIDTH / 2 + r
        val dx = sqrt(max(0.0, rOut * rOut - qdy * qdy))
        val x = max(c.width / 2 + 0.15, min(2.2, qx - dx - r - 0.6))
        val yTurn = ys + r + FRONT_EARLY // 여기서 오른쪽 끝으로 90° 돌면 칸 가운데보다 조금 아래
        return perpBase(ys, "front", ParkPose(x, yTurn + 2.0, -Math.PI / 2), Ideal("y", -1, yTurn, ys = ys))
    }

    private fun mirrorPt(w: Double, q: Pt) = Pt(w - q.x, q.y)
    private fun mirrorPoly(w: Double, poly: List<Pt>) = poly.map { mirrorPt(w, it) }.reversed()

    /** 왼쪽 칸: 좌우를 뒤집는다(x → w − x, h → π − h). */
    internal fun mirrorScene(sc: ParkScene): ParkScene {
        val w = sc.w
        val mh = { h: Double -> Math.PI - h }
        val b = sc.slot.box
        val t = sc.slot.tol
        val ideal = sc.ideal.let { i ->
            val x = if (i.axis == "x") i.copy(sign = -i.sign, brakeAt = w - i.brakeAt) else i
            if (i.xF != null) x.copy(xF = w - i.xF) else x
        }
        return sc.copy(
            start = ParkPose(w - sc.start.x, sc.start.y, mh(sc.start.h)),
            slot = Slot(Box(w - b.x1, w - b.x0, b.y0, b.y1), Box(t.x1, t.x0, t.y0, t.y1), mh(sc.slot.heading)),
            cars = sc.cars.map { it.copy(x = w - it.x, h = mh(it.h), poly = mirrorPoly(w, it.poly)) },
            curbs = sc.curbs.map { it.copy(poly = mirrorPoly(w, it.poly), line = it.line.map { q -> mirrorPt(w, q) }, side = mirrorPoly(w, it.side)) },
            stops = sc.stops.map { it.copy(a = mirrorPt(w, it.a), b = mirrorPt(w, it.b), n = Pt(-it.n.x, it.n.y)) },
            lines = sc.lines.map { l -> l.map { mirrorPt(w, it) } },
            dashes = sc.dashes.map { l -> l.map { mirrorPt(w, it) } },
            ideal = ideal,
            xE = sc.xE?.let { w - it },
            xW = sc.xW?.let { w - it },
        )
    }

    /** 장면(오른쪽 칸으로 만들고 왼쪽 칸은 뒤집음). 모르는 종류·방향이면 평행 · 오른쪽. */
    fun scene(type: String?, side: String?, model: String?): ParkScene {
        val t = typeOf(type) ?: "parallel"
        val sd = sideOf(side) ?: "R"
        val c = car(model)
        val base = when (t) {
            "parallel" -> parallelRight(c)
            "back" -> backRight(c)
            else -> frontRight(c)
        }
        val sc = if (sd == "L") mirrorScene(base) else base
        return sc.copy(type = t, side = sd, model = c.id, tips = TIPS.getValue(t))
    }

    /* ---------------- 연습 ---------------- */

    /** 연습 하나(처음 자리, 브레이크를 한 번 밟아야 움직임). */
    fun create(type: String?, side: String?, model: String?): ParkingPractice = ParkingPractice(car(model), scene(type, side, model))

    /* ---------------- 정해 둔 조작 순서 ---------------- */

    /** 조작 한 순서: [dir]·[steer]로 브레이크를 떼고 [ticks] 틱 → 브레이크 [brakeTicks] 틱. */
    data class PlanStep(val dir: String, val steer: Double, val ticks: Int, val brakeTicks: Int)

    private class Canon(val x: Double, val y: Double, val h: Double, val st: ParkState)

    private class Recipe(val dir: String, val steer: Double, val brake: Boolean, val until: (Canon) -> Boolean)

    private fun canon(inst: ParkingPractice): Canon {
        val st = inst.state
        val sc = inst.scene
        if (sc.side != "L") return Canon(st.x, st.y, st.h, st)
        return Canon(sc.w - st.x, st.y, Math.PI - st.h, st)
    }

    private fun recipe(inst: ParkingPractice): List<Recipe> {
        val sc = if (inst.scene.side == "L") mirrorScene(inst.scene) else inst.scene // 오른쪽 칸 장면(조건 값)
        val c = inst.car
        val mx = c.maxSteer
        val r = c.wheelbase / JsMath.tan(mx * DEG)
        val rot = STOP_DIST / r // 브레이크를 밟고 더 도는 각
        val i = sc.ideal
        val dh = { q: Canon, ref: Double -> ParkingGeometry.angDiff(q.h, ref) }
        if (sc.type == "parallel") {
            val phi = (i.phi ?: 0.0) * DEG
            return listOf(
                Recipe("D", 0.0, true) { q -> q.x >= i.brakeAt },
                Recipe("R", mx, true) { q -> -dh(q, 0.0) >= phi - rot },
                Recipe("R", 0.0, false) { q -> q.y >= (i.yTurn ?: 0.0) },
                Recipe("R", -mx, true) { q -> dh(q, 0.0) >= -rot },
            )
        }
        if (sc.type == "back") {
            return listOf(
                Recipe("D", 0.0, true) { q -> q.y <= i.brakeAt },
                Recipe("R", mx, true) { q -> dh(q, Math.PI) <= rot },
                Recipe("R", 0.0, true) { q -> q.st.blocked == "stop" },
            )
        }
        return listOf(
            Recipe("D", 0.0, false) { q -> q.y <= i.brakeAt },
            Recipe("D", mx, true) { q -> dh(q, 0.0) >= -rot },
            Recipe("D", 0.0, true) { q -> q.st.blocked == "stop" },
        )
    }

    /**
     * 정해 둔 조작 순서(0.1초 틱). 맨 앞은 브레이크로 시작(armed), 순서마다 그 기어·핸들로 브레이크를 떼고 ticks 틱 뒤 brakeTicks 틱 밟음,
     * 맨 끝은 핸들을 똑바로 풀고 성공할 때까지 기다림.
     */
    fun plan(type: String?, side: String?, model: String?): List<PlanStep> {
        val inst = create(type, side, model)
        val mirror = if (inst.scene.side == "L") -1 else 1
        val out = ArrayList<PlanStep>()
        out += PlanStep("D", 0.0, 0, 2)
        repeat(2) { inst.step(DT, ParkInput(0.0, true, "D")) }
        for (r in recipe(inst)) {
            val input = ParkInput(r.steer * mirror, false, r.dir)
            var ticks = 0
            while (ticks < 600) {
                if (r.until(canon(inst)) || inst.state.status == "touched") break
                inst.step(DT, input)
                ticks++
            }
            var brakeTicks = 0
            if (r.brake) {
                while (brakeTicks < 30 && (inst.state.v != 0.0 || brakeTicks < 2)) {
                    inst.step(DT, input.copy(brake = true))
                    brakeTicks++
                }
                brakeTicks++
                inst.step(DT, input.copy(brake = true))
            }
            out += PlanStep(r.dir, r.steer * mirror, ticks, brakeTicks)
            if (inst.state.status == "touched") break
        }
        // 멈춘 채 핸들을 똑바로 풀고(표 A-5 마지막 단계) 성공할 때까지 기다린다
        out += PlanStep(out.last().dir, 0.0, 0, JsMath.round(DONE_HOLD / DT).toInt() + 3)
        return out
    }

    /** 조작 순서대로 돌린다 → 마지막 상태(단위 테스트). */
    fun runPlan(inst: ParkingPractice, steps: List<PlanStep>): ParkState {
        for (s in steps) {
            repeat(s.ticks) { inst.step(DT, ParkInput(s.steer, false, s.dir)) }
            repeat(s.brakeTicks) { inst.step(DT, ParkInput(s.steer, true, s.dir)) }
        }
        return inst.state
    }
}
