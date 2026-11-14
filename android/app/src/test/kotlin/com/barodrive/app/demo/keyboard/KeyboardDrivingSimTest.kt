package com.barodrive.app.demo.keyboard

import android.view.KeyEvent
import com.barodrive.app.data.signal.sim.CarView
import com.barodrive.app.data.signal.sim.FakeDemoPanelRepository
import com.barodrive.app.data.signal.sim.SimController
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear
import com.barodrive.core.model.VehicleSnapshot
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.SimTarget
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 키보드로 진짜 시연 시뮬레이터를 몬다(drive-spec B·drive-spec-2 확인 목록): D → ↑를 누르면 빨라지고 떼면 그 속도로 굴러가며 ↓를 누르면
 * 선다 · S는 아무 일도 없다 · ← →는 달릴 때 놓으면 가운데로, 서 있으면 각을 지킨다 · Q E · C · PgUp PgDn 기어 한 칸(패널 기어 규칙) ·
 * W 비상등(시뮬레이터 차 밖) · 재생 중에 누르면 패널처럼 재생을 멈춘다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardDrivingSimTest {
    private class Rig(scope: TestScope) {
        val sim = SimController(FakeDemoPanelRepository(), scope.backgroundScope)
        val keys = KeyboardDriver(SimKeyboardCar(sim))
        val notices = ArrayList<String>()
        var now = 0L
        val car: CarView get() = sim.view.value.car

        init {
            scope.backgroundScope.launch { sim.notices.collect { notices += it } }
        }

        /** 실제 [ms]: 50ms마다 키보드 고리, 100ms마다 시뮬레이터 고리(앱처럼). */
        fun run(ms: Long) {
            repeat((ms / 50).toInt()) {
                now += 50
                keys.frame(now)
                if (now % 100 == 0L) sim.advance(0.1)
            }
        }

        fun tap(code: Int) {
            keys.keyDown(code, now)
            keys.keyUp(code, now + 20)
        }

        fun hold(code: Int, ms: Long) {
            keys.keyDown(code, now)
            run(ms)
            keys.keyUp(code, now)
        }
    }

    @Test
    fun driveHoldUpAcceleratesReleaseCoastsHoldDownStops() = runTest {
        val r = Rig(this)
        runCurrent()
        r.tap(KeyEvent.KEYCODE_D)
        assertEquals("D(서 있으면 브레이크 없이 바로)", Gear.D, r.car.gear)
        r.hold(KeyEvent.KEYCODE_DPAD_UP, 3000)
        val v = r.car.speed
        assertTrue("가속 3초 → ${v}km/h(1초에 4.7)", v in 12.0..16.0)
        r.run(2000)
        assertTrue("떼면 그 속도로 굴러감 ${r.car.speed}", abs(r.car.speed - v) < 1.0)
        // S는 이제 브레이크가 아니다(아무 일 없음) — 그 속도 그대로
        r.hold(KeyEvent.KEYCODE_S, 2000)
        assertFalse("S로는 브레이크를 밟지 않음", r.car.brake)
        assertTrue("S를 눌러도 굴러감 ${r.car.speed}", abs(r.car.speed - v) < 1.0)
        r.keys.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, r.now)
        r.run(500)
        assertTrue("브레이크 밟음", r.car.brake)
        r.run(3500)
        r.keys.keyUp(KeyEvent.KEYCODE_DPAD_DOWN, r.now)
        assertEquals("섰다", 0.0, r.car.speed, 0.0)
        r.run(1000)
        assertEquals(0.0, r.car.speed, 0.0)
    }

    @Test
    fun steeringSelfCentersWhileMovingAndKeepsItsAngleWhenStopped() = runTest {
        val r = Rig(this)
        runCurrent()
        r.tap(KeyEvent.KEYCODE_D)
        r.hold(KeyEvent.KEYCODE_DPAD_UP, 4000) // 약 19km/h
        r.hold(KeyEvent.KEYCODE_DPAD_LEFT, 750)
        assertEquals("← 0.75초 → 왼쪽(음수) 반쯤", -17.5, r.car.steer, 1.2)
        r.run(1000)
        assertEquals("달리는 중 놓으면 가운데로", 0.0, r.car.steer, 0.0)
        r.hold(KeyEvent.KEYCODE_DPAD_DOWN, 5000)
        assertEquals(0.0, r.car.speed, 0.0)
        r.hold(KeyEvent.KEYCODE_DPAD_RIGHT, 600)
        val parked = r.car.steer
        assertEquals("→ 0.6초 → 오른쪽 14°쯤", 14.0, parked, 1.2)
        r.run(2000)
        assertEquals("서 있으면 그 각 그대로", parked, r.car.steer, 0.0)
        r.tap(KeyEvent.KEYCODE_C)
        assertEquals("C는 바로 가운데", 0.0, r.car.steer, 0.0)
        r.tap(KeyEvent.KEYCODE_E)
        assertEquals(Blinker.RIGHT, r.car.blinker)
        r.tap(KeyEvent.KEYCODE_Q)
        assertEquals(Blinker.LEFT, r.car.blinker)
        r.tap(KeyEvent.KEYCODE_Q)
        assertEquals(Blinker.OFF, r.car.blinker)
        r.tap(KeyEvent.KEYCODE_P)
        assertEquals(Gear.P, r.car.gear)
    }

    @Test
    fun pageUpPageDownStepTheGearLikeALeverWithThePanelRules() = runTest {
        val r = Rig(this)
        runCurrent()
        assertEquals(Gear.P, r.car.gear)
        // 서 있으면 바로: PgDn P → R → N → D, D 끝은 그대로 / PgUp D → N → R → P, P 끝은 그대로
        val down = (1..4).map { r.tap(KeyEvent.KEYCODE_PAGE_DOWN); r.car.gear }
        assertEquals(listOf(Gear.R, Gear.N, Gear.D, Gear.D), down)
        val up = (1..4).map { r.tap(KeyEvent.KEYCODE_PAGE_UP); r.car.gear }
        assertEquals(listOf(Gear.N, Gear.R, Gear.P, Gear.P), up)
        // 달리는 중(패널 기어 버튼과 같은 규칙): D에서 PgUp → N은 바로, 한 번 더 → R은 반대 방향이라 먼저 세운다(안내 줄)
        r.tap(KeyEvent.KEYCODE_D)
        r.hold(KeyEvent.KEYCODE_DPAD_UP, 3000)
        assertTrue(r.car.speed > 10.0)
        r.tap(KeyEvent.KEYCODE_PAGE_DOWN)
        assertEquals("D 끝", Gear.D, r.car.gear)
        r.tap(KeyEvent.KEYCODE_PAGE_UP)
        assertEquals(Gear.N, r.car.gear)
        r.tap(KeyEvent.KEYCODE_PAGE_UP)
        runCurrent()
        assertEquals("아직 N(세우는 중)", Gear.N, r.car.gear)
        assertEquals("멈춘 뒤 R로 바꿔요(브레이크)", r.notices.lastOrNull())
        r.run(300)
        r.tap(KeyEvent.KEYCODE_PAGE_UP)
        runCurrent()
        assertEquals("쌓지 않음 — 지금 기어(N)에서 또 R", Gear.N, r.car.gear)
        assertEquals(listOf("멈춘 뒤 R로 바꿔요(브레이크)", "멈춘 뒤 R로 바꿔요(브레이크)"), r.notices.takeLast(2))
        r.run(8000)
        assertEquals(0.0, r.car.speed, 0.0)
        assertEquals("선 뒤 R", Gear.R, r.car.gear)
        r.tap(KeyEvent.KEYCODE_PAGE_UP)
        assertEquals(Gear.P, r.car.gear)
        // P R N D 키도 그대로
        r.tap(KeyEvent.KEYCODE_N)
        assertEquals(Gear.N, r.car.gear)
        r.tap(KeyEvent.KEYCODE_PAGE_UP)
        assertEquals(Gear.R, r.car.gear)
    }

    @Test
    fun wTogglesTheDemoHazardLightsOutsideTheSimulatorCar() = runTest {
        val r = Rig(this)
        runCurrent()
        val before = r.sim.current()
        r.tap(KeyEvent.KEYCODE_W)
        assertTrue("W → 비상등 켬(패널이 보는 값)", r.sim.view.value.hazard)
        val after = r.sim.current()
        assertEquals("시뮬레이터 차·스냅숏은 그대로(골든이 적는 차 상태 밖)", before, after)
        assertEquals("스냅숏 키도 그대로", keysOf(before), keysOf(after))
        assertFalse("hazard" in keysOf(after))
        assertEquals("깜빡이와 따로", Blinker.OFF, r.car.blinker)
        r.tap(KeyEvent.KEYCODE_E)
        assertEquals(Blinker.RIGHT, r.car.blinker)
        assertTrue("깜빡이를 켜도 비상등 그대로", r.sim.view.value.hazard)
        r.tap(KeyEvent.KEYCODE_W)
        assertFalse(r.sim.view.value.hazard)
        assertEquals("비상등을 꺼도 깜빡이 그대로", Blinker.RIGHT, r.car.blinker)
        // 재생 중에 눌러도 재생을 멈추지 않는다(깜빡이처럼 손 운전이 아님)
        r.sim.load(SimTarget.Scenario("S1-B"))
        r.sim.toggle(null)
        r.run(1000)
        assertEquals(PlayerState.PLAYING, r.sim.view.value.player)
        r.tap(KeyEvent.KEYCODE_W)
        r.run(500)
        assertTrue(r.sim.view.value.hazard)
        assertEquals(PlayerState.PLAYING, r.sim.view.value.player)
        // 차를 처음으로 돌리면(데모 상태 적용·처음 실행으로 되돌리기) 꺼진다
        r.sim.resetAll()
        assertFalse(r.sim.view.value.hazard)
    }

    @Test
    fun aKeyDuringPlaybackStopsItLikeThePanel() = runTest {
        val r = Rig(this)
        runCurrent()
        r.sim.load(SimTarget.Scenario("S1-B"))
        r.sim.toggle(null)
        r.run(1000)
        assertEquals(PlayerState.PLAYING, r.sim.view.value.player)
        r.tap(KeyEvent.KEYCODE_Q)
        assertEquals("깜빡이는 재생을 멈추지 않음(패널과 같음)", PlayerState.PLAYING, r.sim.view.value.player)
        r.hold(KeyEvent.KEYCODE_DPAD_UP, 300)
        assertEquals("가속 페달은 손 조작 → 재생 멈춤", PlayerState.PAUSED, r.sim.view.value.player)
    }

    private companion object {
        val json = Json { encodeDefaults = true }

        /** 스냅숏 JSON 키(필드 이름 = 프로토타입 스냅숏 키). */
        fun keysOf(s: VehicleSnapshot): Set<String> = json.encodeToJsonElement(VehicleSnapshot.serializer(), s).jsonObject.keys
    }
}
