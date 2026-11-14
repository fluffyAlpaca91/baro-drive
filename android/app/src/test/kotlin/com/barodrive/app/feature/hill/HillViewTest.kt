package com.barodrive.app.feature.hill

import com.barodrive.app.R
import com.barodrive.app.feature.parking.XmlTextRes
import com.barodrive.app.session.api.ActivePhase
import com.barodrive.app.session.api.DrivingState
import com.barodrive.app.session.api.SessionPhase
import com.barodrive.app.ui.components.StepState
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillDownHint
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.HillParkHint
import com.barodrive.core.model.HillParkResult
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillWheelHint
import com.barodrive.core.model.Hints
import com.barodrive.core.model.Profile
import com.barodrive.core.model.StepOutput
import com.barodrive.core.model.TurnDir
import com.barodrive.core.model.Utterance
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HL-1·HL-2 화면 모델 (프로토타입 hill.js `BaroApp.hill.view`·`still`, 시험 t6_hill '화면 모델(BaroApp.hill)').
 * 프로토타입 시험은 엔진을 돌려 얻은 힌트를 넣는다 — 여기서는 그때의 힌트 값을 그대로 만들어 넣는다.
 */
class HillViewTest {
    private val res = XmlTextRes

    private fun view(hint: HillHint?, snap: VehicleSnapshot? = null, coach: Coach = Coach.DAD) = HillViews.view(hint, snap, coach, null)

    @Test
    fun withoutAHintItIsUphillStepOne() {
        val v = view(null)
        assertEquals(listOf<Any>(HillPractice.UP, 1, "1단계 · 지금 할 일", "오르막 중간에서 브레이크를 밟고 멈추세요.", false), listOf(v.practice, v.step, v.noText(res), v.text, v.tap))
        assertEquals("오르막에서 멈췄다가 출발하기", v.sub)
        assertEquals("차가 멈추면 넘어가요", v.sig.text(res))
        assertEquals(listOf(StepState.Now, StepState.Todo, StepState.Todo, StepState.Todo), v.strip.map { it.state })
        assertEquals(listOf("멈추기", "사이드브레이크", "발 옮기기", "천천히 출발"), v.strip.map { it.label })
        assertEquals("1 멈추기", res.text(R.string.hl_strip_step, 1, v.strip[0].label))
        assertEquals(HillVis.Icon("i-brake"), v.vis)
    }

    @Test
    fun uphillStepThreeShowsPedalsAndCheer() {
        val v = view(HillHint(practice = HillPractice.UP, step = 3, steps = 4, key = "hl1.up.3", cheer = true), VehicleSnapshot(t = 1.0, gear = Gear.D, speed = 0.0))
        assertEquals(HillVis.Pedals, v.vis)
        assertTrue(v.cheer)
        assertEquals("브레이크를 떼면 다음 단계로 넘어가요", v.sig.text(res))
        assertEquals(listOf(StepState.Done, StepState.Done, StepState.Now, StepState.Todo), v.strip.map { it.state })
        assertEquals("브레이크에서 엑셀로 발을 빠르게 옮기고, 엑셀을 살살 밟으세요.", v.text)
        assertEquals("3단계 · 지금 할 일", v.noText(res))
    }

    @Test
    fun downhillStepTwoCountsPressesAndWarnsOnHolding() {
        val hint = HillHint(practice = HillPractice.DOWN, step = 2, steps = 3, key = "hl1.down.2", down = HillDownHint(presses = 1, warn = true, bound = true))
        val v = view(hint, VehicleSnapshot(t = 1.0, gear = Gear.D, speed = 20.0))
        assertEquals(listOf<Any>(HillPractice.DOWN, 2, HillVis.Count(1), true, "계속 밟지 말고 나눠서 밟아요", false), listOf(v.practice, v.step, v.vis, v.warn, v.sig.text(res), v.tap))
        // 칩으로 시작해 내비 구간에 묶이지 않으면 [했어요] 안내(계속 밟음 줄보다 나중이라 그것을 덮는다 — 색은 주의 그대로)
        val chip = view(hint.copy(down = HillDownHint(presses = 2, warn = true, bound = false), tap = true))
        assertEquals("내리막이 끝나면 [했어요]를 눌러요", chip.sig.text(res))
        assertTrue(chip.warn)
        assertTrue(chip.tap)
    }

    @Test
    fun doneShowsTheFinishedTextAndFlag() {
        val v = view(HillHint(practice = HillPractice.UP, step = 4, steps = 4, done = true, tap = true, key = "hl1.up.4"))
        assertEquals("오르막 출발을 한 번 마쳤어요", v.text)
        assertEquals("오르막 출발 · 끝", v.noText(res))
        assertEquals("다른 연습은 멈췄을 때 위 칩으로 골라요", v.sig.text(res))
        assertEquals(HillVis.Icon("i-flag-check"), v.vis)
        assertFalse("다 마치면 [했어요] 없음", v.tap)
        assertTrue(v.strip.all { it.state == StepState.Done })
    }

    @Test
    fun hillParkResultLineMatchesThePrototype() {
        // t6: 오르막·연석에서 앞바퀴 오른쪽 20°로 P → '앞바퀴 오른쪽 · 왼쪽이 맞아요'
        val hint = HillHint(
            practice = HillPractice.PARK,
            step = 3,
            steps = 3,
            done = true,
            key = "hl1.park.3",
            park = HillParkHint(
                situation = HillSlope.UP,
                need = TurnDir.LEFT,
                wheel = HillWheelHint(side = TurnDir.RIGHT, deg = 20.0, ok = false),
                result = HillParkResult(n = 1, situation = HillSlope.UP, need = TurnDir.LEFT, wheelOk = false, wheelSide = TurnDir.RIGHT, wheelDeg = 20.0, handbrake = HandbrakeState.OFF, ok = false),
            ),
        )
        val v = view(hint, VehicleSnapshot(t = 1.0, gear = Gear.P, speed = 0.0, steer = 20.0))
        assertEquals(listOf<Any?>(HillPractice.PARK, true, "오르막·연석 있음", false, "앞바퀴 오른쪽 · 왼쪽이 맞아요"), listOf(v.practice, v.done, v.park.label, v.park.ok, v.park.line.text(res)))
        assertTrue(v.gearP)
        assertEquals("L", v.park.side)
        assertEquals("차가 뒤로 밀려도 앞바퀴 뒤쪽이 연석에 걸려요.", v.park.why)
        assertFalse(v.park.nextDone)
        assertEquals("언덕 주차를 마쳤어요", v.text)
    }

    @Test
    fun hillParkLiveLinesFollowTheWheel() {
        fun park(p: HillParkHint, step: Int = 2) = view(
            HillHint(practice = HillPractice.PARK, step = step, steps = 3, key = "hl1.park.$step", vars = JsonObject(mapOf("side" to JsonPrimitive("연석 쪽(오른쪽)"))), park = p),
        )
        val straight = park(HillParkHint(situation = HillSlope.DOWN, need = TurnDir.RIGHT, wheel = HillWheelHint(side = null, deg = 0.0)))
        assertEquals("지금 앞바퀴 똑바로 · 오른쪽으로 돌려요", straight.park.line.text(res))
        assertEquals(false, straight.park.ok)
        assertEquals("브레이크를 밟은 채 앞바퀴를 연석 쪽(오른쪽)으로 돌리세요.", straight.text)
        val wrong = park(HillParkHint(situation = HillSlope.DOWN, need = TurnDir.RIGHT, wheel = HillWheelHint(side = TurnDir.LEFT, deg = -12.0, ok = false)))
        assertEquals("지금 앞바퀴 왼쪽 · 반대예요", wrong.park.line.text(res))
        assertEquals(false, wrong.park.ok)
        val good = park(HillParkHint(situation = HillSlope.DOWN, need = TurnDir.RIGHT, wheel = HillWheelHint(side = TurnDir.RIGHT, deg = 20.0, ok = true)))
        assertEquals("지금 앞바퀴 오른쪽 · 방향이 맞아요", good.park.line.text(res))
        assertEquals(true, good.park.ok)
        val off = park(HillParkHint(situation = HillSlope.NONE, need = TurnDir.RIGHT, steerOff = true))
        assertEquals("핸들 각도를 못 읽어요", off.park.line.text(res))
        assertEquals("핸들 각도를 못 읽어요 · 돌렸으면 [했어요]를 눌러요", off.sig.text(res))
        assertNull(off.park.ok)
        assertFalse("연석 없음", off.park.curb)
        assertEquals("오르막·내리막 같음", off.park.front)
        // 상황을 안 골랐으면 줄 없음, 까닭은 화면 기본 글, 그림은 연석 있음·똑바로
        val none = park(HillParkHint(), step = 1)
        assertEquals("", none.park.line.text(res))
        assertNull(none.park.why)
        assertNull(none.park.side)
        assertTrue(none.park.curb)
        assertEquals("상황을 고르면 앞바퀴 방향을 알려 드려요", res.text(R.string.hl_hp_why_none))
        // P에서 판정할 때 앞바퀴를 못 읽음 · 사이드브레이크를 채움
        val done = view(
            HillHint(
                practice = HillPractice.PARK,
                step = 3,
                steps = 3,
                done = true,
                park = HillParkHint(situation = HillSlope.NONE, need = TurnDir.RIGHT, result = HillParkResult(situation = HillSlope.NONE, wheelOk = null, handbrake = HandbrakeState.ON)),
            ),
        )
        assertEquals("앞바퀴 방향을 확인하지 못했어요", done.park.line.text(res))
        assertTrue(done.park.nextDone)
        assertEquals("사이드브레이크를 채웠어요", res.text(R.string.hl_hp_next_done))
        assertEquals("다음 · 기어를 P로 바꾸고\n사이드브레이크를 꼭 채우세요.", res.text(R.string.hl_hp_next))
    }

    @Test
    fun stillMeansStoppedOrParked() {
        assertTrue(HillViews.still(VehicleSnapshot(t = 0.0, gear = Gear.D, speed = 0.3), null))
        assertFalse(HillViews.still(VehicleSnapshot(t = 0.0, gear = Gear.D, speed = 4.0), null))
        assertTrue(HillViews.still(VehicleSnapshot(t = 0.0, gear = Gear.D, speed = null), StepOutput(waiting = true)))
        assertFalse(HillViews.still(VehicleSnapshot(t = 0.0, gear = Gear.D, speed = null), StepOutput(waiting = false)))
        assertTrue(HillViews.still(VehicleSnapshot(t = 0.0, gear = Gear.P, speed = 9.0), null))
        assertTrue(HillViews.still(null, null))
    }

    private fun session(hint: HillHint?, snap: VehicleSnapshot, say: Utterance? = null) = DrivingState(
        phase = SessionPhase.ACTIVE,
        activePhase = ActivePhase.DRIVING,
        mode = 7,
        snapshot = snap,
        gear = snap.gear,
        output = StepOutput(t = snap.t, hints = Hints(hill = hint)),
        lastSay = say,
    )

    @Test
    fun driveStateSwapsToParkAndPicksTheBubble() {
        val stopped = VehicleSnapshot(t = 20.0, gear = Gear.D, speed = 0.0)
        val cheer = HillStates.drive(session(HillHint(practice = HillPractice.UP, step = 3, steps = 4, cheer = true), stopped), Profile(coach = Coach.DAD))
        assertEquals("천천히 해도 괜찮아. 살살 밟아 보자.", cheer.bubble)
        assertTrue(cheer.canTap)
        assertFalse(cheer.toPark)
        // 최근 코치 말(8 sim초 안) · 단계 문장(HL1)은 말풍선에 다시 쓰지 않음
        val said = Utterance(key = "hl2", text = "뒤로 밀렸어. 브레이크 꾹 밟자.", item = "HL2", t = 13.0)
        val hint = HillHint(practice = HillPractice.UP, step = 4, steps = 4)
        assertEquals(said.text, HillStates.drive(session(hint, stopped, said), Profile()).bubble)
        assertNull(HillStates.drive(session(hint, stopped.copy(t = 21.1), said), Profile()).bubble)
        assertNull(HillStates.drive(session(hint, stopped, said.copy(item = "HL1")), Profile()).bubble)
        // 움직이면 칩·칸을 받지 않음, 언덕 주차가 되면 HL-2로
        val moving = HillStates.drive(session(hint, VehicleSnapshot(t = 20.0, gear = Gear.D, speed = 12.0)), Profile())
        assertFalse(moving.canTap)
        assertTrue(moving.moving)
        assertTrue(HillStates.drive(session(HillHint(practice = HillPractice.PARK), stopped), Profile()).toPark)
        assertFalse("회차가 없으면 바꾸지 않음", HillStates.drive(DrivingState(snapshot = stopped), Profile()).toPark)
    }

    @Test
    fun parkStateRefusesTapsInParkAndSwapsBackToDrive() {
        val stopped = VehicleSnapshot(t = 20.0, gear = Gear.D, speed = 0.0)
        val park = HillStates.park(session(HillHint(practice = HillPractice.PARK, step = 1, steps = 3), stopped), Profile())
        assertTrue(park.canTap)
        assertFalse(park.toDrive)
        assertEquals("언덕에 세울 때 앞바퀴 방향", park.sub)
        assertFalse("P면 받지 않음", HillStates.park(session(HillHint(practice = HillPractice.PARK), stopped.copy(gear = Gear.P)), Profile()).canTap)
        assertTrue(HillStates.park(session(HillHint(practice = HillPractice.DOWN), stopped), Profile()).toDrive)
        assertFalse("힌트가 없으면 바꾸지 않음", HillStates.park(session(null, stopped), Profile()).toDrive)
    }
}
