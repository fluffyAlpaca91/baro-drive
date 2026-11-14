package com.barodrive.core.sim.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** 가상 주차 모델 (프로토타입 tests/unit/t5a_parking.test.js — 화면 등록 시험은 앱 몫이라 뺐다). */
class VirtualParkingTest {
    private val p = VirtualParking
    private val dt = 0.1
    private val models = listOf("avante", "grandeur", "tucson")
    private val kinds = listOf("parallel" to "L", "parallel" to "R", "back" to "L", "back" to "R", "front" to "L", "front" to "R")

    private fun near(a: Double, b: Double, tol: Double, msg: String = "") = assertTrue("$msg — $a ≈ $b ±$tol", abs(a - b) <= tol)
    private fun ticks(inst: ParkingPractice, n: Int, input: ParkInput): ParkState {
        repeat(n) { inst.step(dt, input) }
        return inst.state
    }

    private fun armed(type: String, side: String, model: String): ParkingPractice {
        val inst = p.create(type, side, model)
        ticks(inst, 1, ParkInput(0.0, true, "D"))
        return inst
    }

    @Test
    fun namesAndKeys() {
        assertEquals(listOf("parallel", "back", "front"), p.TYPES.map { it.id })
        assertEquals(listOf("L", "R"), p.SIDES.map { it.first })
        assertEquals("parallel-R", p.key("parallel", "R"))
        assertEquals("back-L", p.key("back", "left"))
        assertEquals("front" to "L", p.parse("front-L"))
        assertNull(p.parse("nope-R"))
        assertEquals("평행 · 오른쪽 칸", p.title("parallel", "R"))
        assertEquals("후방(후진) · 왼쪽", p.label("back", "L"))
        assertEquals("전방 · 왼쪽 칸", p.title("front", "L"))
    }

    @Test
    fun carSizesAndMinimumRadius() {
        val want = mapOf("avante" to listOf(4.71, 1.825, 2.72, 34.0), "grandeur" to listOf(5.035, 1.88, 2.895, 33.0), "tucson" to listOf(4.63, 1.865, 2.755, 34.0))
        for (m in models) {
            val c = p.car(m)
            assertEquals(m, want.getValue(m), listOf(c.length, c.width, c.wheelbase, c.maxSteer))
            near(c.front + c.rear + c.wheelbase, c.length, 1e-6, "$m 앞·뒤 내민 길이 + 축거 = 길이")
            near(p.minRadius(m), c.wheelbase / tan(c.maxSteer * PI / 180), 1e-9, m)
        }
        near(p.minRadius("avante"), 4.03, 0.01, "아반떼")
        near(p.minRadius("grandeur"), 4.46, 0.01, "그랜저")
        near(p.minRadius("tucson"), 4.08, 0.01, "투산")
        assertTrue("그랜저가 가장 크게 돈다", p.minRadius("avante") < p.minRadius("tucson") && p.minRadius("tucson") < p.minRadius("grandeur"))
    }

    @Test
    fun fullLockDrawsTheMinimumRadiusArc() {
        for (m in models) {
            for ((dir, sign) in listOf("D" to -1, "R" to 1)) {
                val inst = armed("parallel", "R", m)
                val max = inst.car.maxSteer
                val a = Triple(inst.state.x, inst.state.y, inst.state.h)
                val st = ticks(inst, 20, ParkInput(sign * max, false, dir))
                assertNotEquals("$m $dir 열린 쪽으로 돌았어요", "touched", st.status)
                val dh = abs(st.h - a.third)
                val chord = hypot(st.x - a.first, st.y - a.second)
                val r = chord / (2 * sin(dh / 2))
                near(r, p.minRadius(m), p.minRadius(m) * 0.005, "$m $dir 반지름")
            }
        }
    }

    @Test
    fun straightDistanceAndSpeed() {
        val inst = armed("parallel", "R", "avante")
        val x0 = inst.state.x
        val y0 = inst.state.y
        val h0 = inst.state.h
        ticks(inst, 5, ParkInput(0.0, false, "D"))
        near(inst.state.v, 0.8, 1e-9, "0.5초 뒤 0.8m/s")
        near(inst.state.speedKmh, 2.9, 0.05, "약 3km/h")
        ticks(inst, 25, ParkInput(0.0, false, "D"))
        near(inst.state.x - x0, 0.21 + 0.8 * 2.5, 0.01, "3초에 2.21m")
        assertEquals("똑바로", y0, inst.state.y, 0.0)
        assertEquals("방향 그대로", h0, inst.state.h, 0.0)
        val x1 = inst.state.x
        ticks(inst, 3, ParkInput(0.0, true, "D"))
        assertEquals("0.3초에 멈춤", 0.0, inst.state.v, 0.0)
        near(inst.state.x - x1, 0.11, 0.01, "밟고 더 간 거리")
        assertEquals("stopped", inst.state.status)
        ticks(inst, 10, ParkInput(0.0, false, "R"))
        near(inst.state.v, -0.8, 1e-9, "후진 0.8m/s")
    }

    @Test
    fun brakeOnceToArmAfterCreateAndReset() {
        val inst = p.create("back", "R", "avante")
        val x0 = inst.state.x
        val y0 = inst.state.y
        ticks(inst, 10, ParkInput(0.0, false, "D"))
        assertFalse(inst.state.armed)
        assertEquals("ready", inst.state.status)
        assertEquals("밟기 전에는 그대로", listOf(x0, y0), listOf(inst.state.x, inst.state.y))
        ticks(inst, 1, ParkInput(0.0, true, "D"))
        assertTrue(inst.state.armed)
        ticks(inst, 5, ParkInput(0.0, false, "D"))
        assertEquals("moving", inst.state.status)
        assertTrue("앞(위)으로 감", inst.state.y < y0)
        inst.reset()
        assertFalse("처음부터면 다시 밟아야 함", inst.state.armed)
        assertEquals(listOf<Any>(x0, y0, "ready", 0.0, 0), listOf<Any>(inst.state.x, inst.state.y, inst.state.status, inst.state.sec, inst.state.switches))
    }

    @Test
    fun switchingVirtualGearStopsFirstAndCountsSwitches() {
        val inst = armed("parallel", "R", "avante")
        ticks(inst, 10, ParkInput(0.0, false, "D"))
        assertEquals(0, inst.state.switches)
        ticks(inst, 1, ParkInput(0.0, false, "R"))
        assertTrue("먼저 줄인다", inst.state.v > 0 && inst.state.v < 0.8)
        ticks(inst, 10, ParkInput(0.0, false, "R"))
        assertTrue("그다음 뒤로", inst.state.v < 0)
        assertEquals("D → R 한 번", 1, inst.state.switches)
        ticks(inst, 15, ParkInput(0.0, false, "D"))
        assertEquals("R → D 두 번째", 2, inst.state.switches)
    }

    @Test
    fun touchingAParkedCarStopsThereUntilReset() {
        val inst = armed("parallel", "L", "avante")
        val st = ticks(inst, 120, ParkInput(-34.0, false, "R"))
        assertEquals("touched", st.status)
        val touch = requireNotNull(st.touch)
        assertEquals("car", touch.kind)
        assertEquals("rear", touch.id)
        assertEquals(0.0, st.v, 0.0)
        assertTrue("닿은 곳", touch.x.isFinite() && touch.y.isFinite())
        val pose = listOf(st.x, st.y, st.h)
        ticks(inst, 20, ParkInput(34.0, false, "D"))
        assertEquals("닿은 뒤에는 움직이지 않음", pose, listOf(inst.state.x, inst.state.y, inst.state.h))
        assertEquals("touched", inst.state.status)
        val car = inst.scene.cars.first { it.id == "rear" }
        assertFalse(ParkingGeometry.overlaps(inst.corners(), car.poly))
        inst.reset()
        assertEquals("ready", inst.state.status)
        assertNull(inst.state.touch)
    }

    @Test
    fun touchingTheCurbIsCurb() {
        val inst = armed("parallel", "R", "avante")
        val sc = inst.scene
        val c = inst.car
        inst.state.x = sc.slot.box.x0 + c.rear + 0.4
        inst.state.y = sc.slot.box.y1 - c.width / 2 - 0.05
        inst.state.h = 0.0
        val st = ticks(inst, 30, ParkInput(34.0, false, "D"))
        assertEquals("touched", st.status)
        assertEquals("curb", st.touch?.kind)
    }

    @Test
    fun wheelStopsAndPictureEdgeBlockOnlyThatWay() {
        val inst = armed("parallel", "R", "avante")
        ticks(inst, 200, ParkInput(0.0, false, "D"))
        assertEquals("edge", inst.state.blocked)
        assertEquals("stopped", inst.state.status)
        assertTrue("차가 그림 안에 남음", inst.corners().all { it.x <= inst.scene.w + 0.06 })
        val x = inst.state.x
        ticks(inst, 10, ParkInput(0.0, false, "R"))
        assertTrue("반대로는 움직임", inst.state.x < x)
        assertNull(inst.state.blocked)
        // 멈춤턱: 후방 주차 마지막에 뒷바퀴가 턱에 닿아 선다
        val b = p.create("back", "R", "avante")
        val steps = p.plan("back", "R", "avante")
        val last = steps[steps.size - 2]
        p.runPlan(b, steps.subList(0, steps.size - 2))
        repeat(last.ticks) { b.step(dt, ParkInput(last.steer, false, last.dir)) }
        assertEquals("멈춤턱", "stop", b.state.blocked)
        assertNotEquals("touched", b.state.status)
        val stopX = b.scene.stops[0].a.x
        val deepest = b.wheels().filter { !it.front }.maxOf { it.x }
        near(deepest, stopX - 0.3, 0.025, "턱에 먼저 닿은 뒷바퀴가 턱 30cm 앞")
        near(requireNotNull(b.scene.xW) - stopX, 0.95, 1e-9, "멈춤턱은 벽에서 95cm")
    }

    @Test
    fun allSixKindsTimesThreeModelsParkWithThePlannedSteps() {
        for (m in models) {
            for ((type, side) in kinds) {
                val steps = p.plan(type, side, m)
                assertEquals("브레이크로 시작", listOf(2, 0), listOf(steps[0].brakeTicks, steps[0].ticks))
                assertEquals("끝은 핸들을 똑바로 풀고 기다림", 0.0, steps.last().steer, 0.0)
                val inst = p.create(type, side, m)
                val st = p.runPlan(inst, steps)
                val tag = "$type-$side $m"
                assertEquals("$tag: ${st.status} ${st.touch?.id}", "done", st.status)
                assertTrue("$tag 칸 안 · 방향", st.inside && st.aligned)
                val res = requireNotNull(st.result)
                assertTrue(tag, res.wheelStraight)
                assertEquals("$tag 앞뒤로 바꾼 횟수", if (type == "front") 0 else 1, res.switches)
                assertTrue("$tag 걸린 시간 ${res.sec}", res.sec > 5 && res.sec < 60)
                assertTrue(tag, abs(ParkingGeometry.angDiff(st.h, inst.scene.slot.heading)) <= 5 * PI / 180)
            }
        }
    }

    @Test
    fun doneAfterOneSecondStillAndWheelAngleIsReported() {
        val steps = p.plan("parallel", "R", "avante")
        val inst = p.create("parallel", "R", "avante")
        p.runPlan(inst, steps.subList(0, steps.size - 1))
        val lock = steps[steps.size - 2].steer
        assertEquals("마지막은 왼쪽 끝으로 돌아 나란해짐", 34.0, abs(lock), 0.0)
        assertEquals("막 멈춘 때는 아직", "stopped", inst.state.status)
        assertTrue(inst.state.inside && inst.state.aligned)
        ticks(inst, 5, ParkInput(lock, true, "R"))
        assertEquals("0.5초 멈춤은 아직", "stopped", inst.state.status)
        ticks(inst, 6, ParkInput(lock, true, "R"))
        assertEquals("1초 멈추면 주차 완료", "done", inst.state.status)
        val res = requireNotNull(inst.state.result)
        assertFalse(res.wheelStraight)
        assertEquals(lock, res.wheelAngle, 0.0)
        assertEquals("바퀴를 똑바로 두면 다음 출발이 편해요.", p.WHEEL_TIP)
        ticks(inst, 10, ParkInput(0.0, false, "D"))
        assertEquals("완료 뒤에는 그대로", "done", inst.state.status)
    }

    @Test
    fun referencePointTipsHighlightAtTheRightMomentsAndLeftUsesSameSentences() {
        val a6 = mapOf(
            "parallel" to listOf("앞차 뒷범퍼 끝이 내 어깨와 나란히 오면 멈추세요.", "주차할 칸 반대쪽 사이드미러에 뒤차 앞부분이 다 보이면 핸들을 똑바로 푸세요."),
            "back" to listOf("주차할 칸 다음 칸의 주차선이 내 어깨와 나란히 오면 멈추세요.", "양쪽 사이드미러에 주차선이 같은 간격으로 보이면 핸들을 똑바로 푸세요."),
            "front" to listOf("주차할 쪽 사이드미러가 칸의 첫 번째 주차선을 지나면 핸들을 끝까지 돌리세요.", "앞 유리 창틀 너머로 옆차와 내 차가 나란해 보이면 핸들을 똑바로 푸세요."),
        )
        for ((type, side) in kinds) {
            val tag = "$type-$side"
            val inst = p.create(type, side, "avante")
            assertEquals(tag, a6.getValue(type), inst.scene.tips)
            assertEquals("$tag 처음엔 첫 문장, 아직 멀다", Tip(0, false), inst.state.tip)
            val steps = p.plan(type, side, "avante")
            val seen = ArrayList<Tip>()
            for (s in steps) {
                repeat(s.ticks) { inst.step(dt, ParkInput(s.steer, false, s.dir)) }
                seen += inst.state.tip
                repeat(s.brakeTicks) { inst.step(dt, ParkInput(s.steer, true, s.dir)) }
            }
            assertEquals("$tag 멈출 곳(돌릴 곳)에서 첫 문장 강조", Tip(0, true), seen[1])
            assertEquals("$tag 핸들 풀 때 둘째 문장 강조", Tip(1, true), seen[2])
            assertFalse("$tag 완료 뒤 강조 없음", inst.state.tip.near)
        }
    }

    @Test
    fun leftSlotIsTheMirroredRightSlot() {
        for (type in listOf("parallel", "back", "front")) {
            val r = p.scene(type, "R", "avante")
            val l = p.scene(type, "L", "avante")
            val w = r.w
            near(l.start.x, w - r.start.x, 1e-9, "$type 시작 x")
            near(l.start.y, r.start.y, 1e-9, "$type 시작 y")
            near(cos(l.start.h), -cos(r.start.h), 1e-9, "$type 시작 방향")
            near(l.slot.box.x0, w - r.slot.box.x1, 1e-9, "$type 칸")
            near(l.slot.box.x1, w - r.slot.box.x0, 1e-9, "$type 칸")
            assertEquals(r.cars.size, l.cars.size)
            near(l.cars[0].x, w - r.cars[0].x, 1e-9, "$type 옆차")
            assertEquals(840.0, r.w * p.PX, 0.0)
            near(r.h * p.PX, 640.0, 1e-9, "그림 840×640")
        }
        val par = p.scene("parallel", "R", "avante")
        near(par.slot.box.x1 - par.slot.box.x0, 4.71 + p.PAR_EXTRA, 1e-6, "평행 칸 길이 = 차 길이 + PAR_EXTRA")
        near(par.slot.box.y1 - par.slot.box.y0, 2.5, 1e-9, "평행 칸 폭 2.5m")
        val back = p.scene("back", "R", "grandeur")
        near(back.slot.box.x1 - back.slot.box.x0, 5.0, 1e-9, "직각 칸 길이 5.0m")
        near(back.slot.box.y1 - back.slot.box.y0, 2.5, 1e-9, "직각 칸 폭 2.5m")
    }
}
