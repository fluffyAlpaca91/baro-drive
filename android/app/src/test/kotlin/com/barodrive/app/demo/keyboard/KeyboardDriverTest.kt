package com.barodrive.app.demo.keyboard

import android.view.KeyEvent
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 키보드 운전 상태(drive-spec B + drive-spec-2): 자동 반복 거르기 · 페달 최소 시간 · 기어·기어 한 칸(PgUp·PgDn)·깜빡이·비상등(W)·C ·
 * 핸들 고리(돌리기·스스로 풀림·그침) · 모두 떼기. 차는 가짜([FakeCar]) — 시각은 손으로 넘긴다.
 */
class KeyboardDriverTest {
    private class FakeCar : KeyboardCar {
        override var steer = 0.0
        override var speed = 0.0
        override var blinker = Blinker.OFF
        override var gear = Gear.P
        override var hazard = false
        override var playing = false
        val calls = ArrayList<String>()
        var steerSets = 0

        /** false면 기어를 미룬 것처럼(차 기어가 그대로) — 달리는 중 P·반대 방향. */
        var shiftsNow = true

        override fun pedal(name: String, on: Boolean) {
            calls += "$name:" + if (on) "on" else "off"
        }

        override fun shift(gear: Gear) {
            calls += "shift:$gear"
            if (shiftsNow) this.gear = gear
        }

        override fun setTurnSignal(b: Blinker) {
            blinker = b
            calls += "blinker:$b"
        }

        override fun setHazardLights(on: Boolean) {
            hazard = on
            calls += "hazard:$on"
        }

        override fun setWheel(deg: Double) {
            steer = deg
            steerSets++
        }
    }

    private val car = FakeCar()
    private val keys = KeyboardDriver(car, minHoldMs = 300)

    /** [from]에서 [to]까지 50ms마다 고리. */
    private fun frames(from: Long, to: Long) {
        var t = from + 50
        while (t <= to) {
            keys.frame(t)
            t += 50
        }
    }

    /** 누르고(자동 반복 한 번 포함) 뗀다. */
    private fun press(code: Int, at: Long) {
        keys.keyDown(code, at)
        keys.keyDown(code, at + 30) // 자동 반복
        keys.keyUp(code, at + 60)
    }

    @Test
    fun autoRepeatIsIgnoredAndOnlyTheArrowKeysArePedals() {
        assertTrue(keys.keyDown(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertTrue("자동 반복은 먹기만", keys.keyDown(KeyEvent.KEYCODE_DPAD_UP, 40))
        assertEquals(listOf("throttle:on"), car.calls)
        assertTrue(keys.keyUp(KeyEvent.KEYCODE_DPAD_UP, 1000))
        assertEquals(listOf("throttle:on", "throttle:off"), car.calls)
        assertFalse(keys.busy)
        // W는 이제 가속이 아니라 비상등, S는 아무 일 없음(흘려보냄)
        car.calls.clear()
        keys.keyDown(KeyEvent.KEYCODE_W, 2000)
        keys.keyUp(KeyEvent.KEYCODE_W, 2100)
        assertEquals(listOf("hazard:true"), car.calls)
        assertFalse("S 누름은 먹지 않음", keys.keyDown(KeyEvent.KEYCODE_S, 3000))
        assertFalse("S 뗌도", keys.keyUp(KeyEvent.KEYCODE_S, 3500))
        assertFalse(keys.isHeld(KeyEvent.KEYCODE_S))
        keys.frame(4000)
        assertEquals("S는 페달을 밟지도 떼지도 않음", listOf("hazard:true"), car.calls)
        assertFalse(keys.busy)
    }

    @Test
    fun aQuickTapStillHoldsThePedalForTheMinimumTime() {
        keys.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_DOWN, 40)
        assertEquals("아직 밟은 채", listOf("brake:on"), car.calls)
        assertTrue(keys.busy)
        keys.frame(250)
        assertEquals(listOf("brake:on"), car.calls)
        keys.frame(300)
        assertEquals(listOf("brake:on", "brake:off"), car.calls)
        assertFalse(keys.busy)
        // 미뤄 둔 떼기 사이에 다시 누르면 밟은 채 이어서(다시 밟지 않음), 오래 누른 뒤 떼면 바로
        car.calls.clear()
        keys.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, 1000)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_DOWN, 1050)
        keys.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, 1100)
        keys.frame(1350)
        assertEquals(listOf("brake:on"), car.calls)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_DOWN, 1500)
        assertEquals(listOf("brake:on", "brake:off"), car.calls)
    }

    @Test
    fun gearBlinkerAndCenterActOncePerPress() {
        keys.keyDown(KeyEvent.KEYCODE_D, 0)
        keys.keyDown(KeyEvent.KEYCODE_D, 30)
        assertTrue("누름을 받은 키는 뗌도 먹는다", keys.keyUp(KeyEvent.KEYCODE_D, 60))
        keys.keyDown(KeyEvent.KEYCODE_R, 100)
        keys.keyUp(KeyEvent.KEYCODE_R, 120)
        assertEquals(listOf("shift:D", "shift:R"), car.calls)
        // Q·E: 누르면 켬, 다른 키는 그쪽으로, 같은 키는 끔
        val seen = ArrayList<Blinker>()
        for ((i, code) in listOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_E).withIndex()) {
            press(code, 1000L + i * 100) // 자동 반복은 다시 바꾸지 않음
            seen += car.blinker
        }
        assertEquals(listOf(Blinker.LEFT, Blinker.OFF, Blinker.RIGHT, Blinker.LEFT, Blinker.RIGHT, Blinker.OFF), seen)
        car.steer = 22.0
        keys.keyDown(KeyEvent.KEYCODE_C, 2000)
        assertEquals("C는 바로 가운데", 0.0, car.steer, 0.0)
    }

    @Test
    fun pageUpAndPageDownStepTheLeverOneGearPerPressAndStopAtTheEnds() {
        // PgDn: P → R → N → D, D에서는 아무 일 없음(돌아가지 않음)
        for ((i, want) in listOf(Gear.R, Gear.N, Gear.D, Gear.D).withIndex()) {
            press(KeyEvent.KEYCODE_PAGE_DOWN, i * 100L)
            assertEquals("PgDn ${i + 1}번", want, car.gear)
        }
        assertEquals("자동 반복은 한 칸으로 · D 끝은 부르지 않음", listOf("shift:R", "shift:N", "shift:D"), car.calls)
        // PgUp: D → N → R → P, P에서는 아무 일 없음
        car.calls.clear()
        for ((i, want) in listOf(Gear.N, Gear.R, Gear.P, Gear.P).withIndex()) {
            press(KeyEvent.KEYCODE_PAGE_UP, 1000 + i * 100L)
            assertEquals("PgUp ${i + 1}번", want, car.gear)
        }
        assertEquals(listOf("shift:N", "shift:R", "shift:P"), car.calls)
        // 끝에서도 맵에 있는 키라 먹는다(아래로 흘려보내지 않음)
        assertTrue(keys.keyDown(KeyEvent.KEYCODE_PAGE_UP, 2000))
        assertTrue(keys.keyUp(KeyEvent.KEYCODE_PAGE_UP, 2050))
        // P R N D 키도 그대로
        car.calls.clear()
        press(KeyEvent.KEYCODE_N, 3000)
        press(KeyEvent.KEYCODE_PAGE_DOWN, 3100)
        press(KeyEvent.KEYCODE_P, 3200)
        assertEquals(listOf("shift:N", "shift:D", "shift:P"), car.calls)
        assertFalse(keys.busy)
    }

    @Test
    fun aGearStepCountsFromTheCarsGearAtThatMomentAndIsNotQueued() {
        car.gear = Gear.N
        car.shiftsNow = false // 달리는 중 R: 시뮬레이터가 먼저 세우고 나중에 바꿈
        press(KeyEvent.KEYCODE_PAGE_UP, 0)
        press(KeyEvent.KEYCODE_PAGE_UP, 100)
        assertEquals("미룬 동안의 누름도 지금 기어(N)에서 — P로 쌓지 않음", listOf("shift:R", "shift:R"), car.calls)
        car.gear = Gear.R // 서서 R로 바뀜
        car.shiftsNow = true
        press(KeyEvent.KEYCODE_PAGE_UP, 200)
        assertEquals(Gear.P, car.gear)
        // 다른 데서(패널 버튼) 바꾼 기어에서도 이어서
        car.gear = Gear.D
        car.calls.clear()
        press(KeyEvent.KEYCODE_PAGE_UP, 300)
        assertEquals(listOf("shift:N"), car.calls)
    }

    @Test
    fun wTogglesTheHazardLightsOncePerPressAndLeavesTheTurnSignalAlone() {
        press(KeyEvent.KEYCODE_E, 0)
        assertEquals(Blinker.RIGHT, car.blinker)
        car.calls.clear()
        press(KeyEvent.KEYCODE_W, 100)
        assertTrue("W → 비상등 켬(자동 반복은 다시 바꾸지 않음)", car.hazard)
        assertEquals("깜빡이는 그대로", Blinker.RIGHT, car.blinker)
        press(KeyEvent.KEYCODE_Q, 200)
        assertEquals(Blinker.LEFT, car.blinker)
        assertTrue("깜빡이를 바꿔도 비상등은 그대로", car.hazard)
        press(KeyEvent.KEYCODE_W, 300)
        assertFalse("W 다시 → 끔", car.hazard)
        assertEquals(Blinker.LEFT, car.blinker)
        assertEquals(listOf("hazard:true", "blinker:LEFT", "hazard:false"), car.calls)
        // 패널 토글로 켠 것을 W가 끈다(같은 값을 본다)
        car.hazard = true
        press(KeyEvent.KEYCODE_W, 400)
        assertFalse(car.hazard)
        assertFalse(keys.busy)
    }

    @Test
    fun steeringTurnsWhileHeldAndKeepsItsAngleWhenParked() {
        keys.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        frames(0, 750)
        assertEquals(-17.5, car.steer, 1e-6)
        frames(750, 2000)
        assertEquals("끝(-35)에서 멈춤", -35.0, car.steer, 1e-6)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_LEFT, 2000)
        frames(2000, 3000)
        assertEquals("서 있으면 그 각 그대로", -35.0, car.steer, 0.0)
        assertTrue("달리기 시작하면 풀리도록 기다림", keys.busy)
        // 달리기 시작(5km/h 넘음) → 같은 빠르기로 가운데, 닿으면 고리 끝
        car.speed = 12.0
        frames(3000, 3750)
        assertEquals(-17.5, car.steer, 1e-6)
        frames(3750, 4600)
        assertEquals(0.0, car.steer, 0.0)
        assertFalse(keys.busy)
    }

    @Test
    fun aShortTapCountsUntilTheReleaseAndBothKeysDoNothing() {
        keys.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT, 30) // 고리가 돌기 전에 뗌 — 뗀 때까지 돈 만큼
        assertEquals(KeySteering.RATE * 0.03, car.steer, 1e-9)
        car.steer = 10.0
        keys.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, 1000)
        keys.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, 1000)
        frames(1000, 1500)
        assertEquals("둘 다 누르면 그대로", 10.0, car.steer, 0.0)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT, 1500)
        frames(1500, 2000)
        assertEquals(10.0 - KeySteering.RATE * 0.5, car.steer, 1e-6)
    }

    @Test
    fun aLateFrameNeverCountsTheSameTimeTwice() {
        car.speed = 30.0
        keys.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        keys.frame(250) // 메인 스레드가 바빠 늦게 돈 고리
        val atFrame = car.steer
        assertEquals(-KeySteering.RATE * 0.25, atFrame, 1e-9)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_LEFT, 200) // 실제로는 200에 뗐다(고리 뒤에 처리됨)
        assertEquals("뗌 시각이 마지막 고리보다 앞이면 더 돌리지 않음", atFrame, car.steer, 0.0)
        keys.frame(300)
        assertEquals("스스로 풀림은 250 → 300의 0.05초만", atFrame + KeySteering.RATE * 0.05, car.steer, 1e-9)
    }

    @Test
    fun selfCenteringStopsWhenSomethingElseMovesTheWheelOrAScenarioPlays() {
        car.speed = 30.0
        keys.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        frames(0, 600)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT, 600)
        assertEquals(14.0, car.steer, 1e-6)
        frames(600, 650)
        assertTrue(car.steer < 14.0)
        car.steer = 5.0 // 패널 '핸들' 막대
        frames(650, 1000)
        assertEquals("다른 데서 바꾼 각은 그대로", 5.0, car.steer, 0.0)
        assertFalse(keys.busy)
        // 재생이 시작되면 시나리오가 핸들을 잡는다
        keys.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, 2000)
        frames(2000, 2300)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_LEFT, 2300)
        val at = car.steer
        car.playing = true
        val sets = car.steerSets
        frames(2300, 2600)
        assertEquals(at, car.steer, 0.0)
        assertEquals(sets, car.steerSets)
        assertFalse(keys.busy)
    }

    @Test
    fun releaseAllLetsGoOfPedalsAtOnceAndUnknownKeysPassThrough() {
        keys.keyDown(KeyEvent.KEYCODE_DPAD_UP, 0)
        keys.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, 0)
        keys.keyUp(KeyEvent.KEYCODE_DPAD_DOWN, 50) // 최소 시간으로 미뤄 둠
        keys.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        keys.releaseAll()
        assertEquals(listOf("throttle:on", "brake:on", "throttle:off", "brake:off"), car.calls)
        assertFalse(keys.busy)
        assertFalse(keys.isHeld(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse("뗌 이벤트가 나중에 와도 흘려보냄", keys.keyUp(KeyEvent.KEYCODE_DPAD_UP, 100))
        assertFalse(keys.keyDown(KeyEvent.KEYCODE_BACK, 200))
        assertFalse(keys.keyUp(KeyEvent.KEYCODE_BACK, 210))
        assertFalse(keys.keyDown(KeyEvent.KEYCODE_A, 300))
        assertFalse(keys.keyDown(KeyEvent.KEYCODE_S, 310))
        assertFalse("누름을 받지 않은 키의 뗌", keys.keyUp(KeyEvent.KEYCODE_DPAD_UP, 400))
        assertEquals("모르는 키는 차를 건드리지 않음", 4, car.calls.size)
    }
}
