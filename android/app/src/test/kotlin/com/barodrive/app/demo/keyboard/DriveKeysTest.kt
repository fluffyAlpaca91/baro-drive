package com.barodrive.app.demo.keyboard

import android.view.KeyEvent
import com.barodrive.app.data.settings.DataSource
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 키 → 동작 표(drive-spec B + drive-spec-2)와 기어 한 칸·Q·E 깜빡이 규칙, 키를 받는 조건. */
class DriveKeysTest {
    @Test
    fun keyTableMatchesTheSpec() {
        val table = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to DriveAction.THROTTLE,
            KeyEvent.KEYCODE_DPAD_DOWN to DriveAction.BRAKE,
            KeyEvent.KEYCODE_DPAD_LEFT to DriveAction.STEER_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to DriveAction.STEER_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP to DriveAction.GEAR_UP,
            KeyEvent.KEYCODE_PAGE_DOWN to DriveAction.GEAR_DOWN,
            KeyEvent.KEYCODE_P to DriveAction.GEAR_P,
            KeyEvent.KEYCODE_R to DriveAction.GEAR_R,
            KeyEvent.KEYCODE_N to DriveAction.GEAR_N,
            KeyEvent.KEYCODE_D to DriveAction.GEAR_D,
            KeyEvent.KEYCODE_Q to DriveAction.BLINK_LEFT,
            KeyEvent.KEYCODE_E to DriveAction.BLINK_RIGHT,
            KeyEvent.KEYCODE_W to DriveAction.HAZARD,
            KeyEvent.KEYCODE_C to DriveAction.CENTER,
        )
        for ((code, action) in table) assertEquals("키 $code", action, DriveKeys.actionOf(code))
        assertEquals(listOf(Gear.P, Gear.R, Gear.N, Gear.D), listOf(DriveAction.GEAR_P, DriveAction.GEAR_R, DriveAction.GEAR_N, DriveAction.GEAR_D).map { it.gear })
        assertEquals(Blinker.LEFT, DriveAction.BLINK_LEFT.blinker)
        assertEquals(Blinker.RIGHT, DriveAction.BLINK_RIGHT.blinker)
        assertEquals("PgUp = 위(P 쪽)", -1, DriveAction.GEAR_UP.step)
        assertEquals("PgDn = 아래(D 쪽)", 1, DriveAction.GEAR_DOWN.step)
        // 동작마다 키가 하나씩(W·S 페달 별칭은 없어짐)
        assertEquals(DriveAction.entries.toSet(), table.values.toSet())
        assertEquals(DriveAction.entries.size, table.size)
    }

    @Test
    fun wIsTheHazardLightAndSIsNoLongerAPedal() {
        assertEquals("W = 비상등(가속 아님)", DriveAction.HAZARD, DriveKeys.actionOf(KeyEvent.KEYCODE_W))
        assertNull("S = 아무 일 없음(맵에 없는 키처럼 흘려보냄)", DriveKeys.actionOf(KeyEvent.KEYCODE_S))
    }

    @Test
    fun pageKeysWithoutAKeyCodeAreKnownByTheirLinuxScanCode() {
        // 에뮬레이터 키보드(qwerty.kl에 104·109 줄 없음): KEYCODE_UNKNOWN + 스캔 코드
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, DriveKeys.keyCodeOf(KeyEvent.KEYCODE_UNKNOWN, 104))
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, DriveKeys.keyCodeOf(KeyEvent.KEYCODE_UNKNOWN, 109))
        assertEquals(DriveAction.GEAR_UP, DriveKeys.actionOf(DriveKeys.keyCodeOf(KeyEvent.KEYCODE_UNKNOWN, DriveKeys.SCAN_PAGE_UP)))
        assertEquals(DriveAction.GEAR_DOWN, DriveKeys.actionOf(DriveKeys.keyCodeOf(KeyEvent.KEYCODE_UNKNOWN, DriveKeys.SCAN_PAGE_DOWN)))
        // USB 키보드(Generic.kl)는 키 그대로 · 다른 스캔 코드의 모르는 키는 그대로 모름(흘려보냄)
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, DriveKeys.keyCodeOf(KeyEvent.KEYCODE_PAGE_UP, 104))
        assertEquals(KeyEvent.KEYCODE_W, DriveKeys.keyCodeOf(KeyEvent.KEYCODE_W, 17))
        assertEquals("스캔 코드가 104여도 키가 있으면 키를 따른다", KeyEvent.KEYCODE_MOVE_HOME, DriveKeys.keyCodeOf(KeyEvent.KEYCODE_MOVE_HOME, 104))
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, DriveKeys.keyCodeOf(KeyEvent.KEYCODE_UNKNOWN, 0))
        assertNull(DriveKeys.actionOf(DriveKeys.keyCodeOf(KeyEvent.KEYCODE_UNKNOWN, 110)))
    }

    @Test
    fun backSystemAndOtherKeysAreNotTaken() {
        val others = listOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
        )
        for (code in others) assertNull("키 $code", DriveKeys.actionOf(code))
    }

    @Test
    fun gearStepFollowsTheLeverAndStopsAtBothEnds() {
        assertEquals("레버 차례 · P가 맨 위", listOf(Gear.P, Gear.R, Gear.N, Gear.D), DriveKeys.LEVER)
        val down = DriveAction.GEAR_DOWN.step
        val up = DriveAction.GEAR_UP.step
        // PgDn: P → R → N → D, D에서는 아무 일 없음
        assertEquals(Gear.R, DriveKeys.gearStep(Gear.P, down))
        assertEquals(Gear.N, DriveKeys.gearStep(Gear.R, down))
        assertEquals(Gear.D, DriveKeys.gearStep(Gear.N, down))
        assertNull("D 끝 — 돌아가지 않음", DriveKeys.gearStep(Gear.D, down))
        // PgUp: D → N → R → P, P에서는 아무 일 없음
        assertEquals(Gear.N, DriveKeys.gearStep(Gear.D, up))
        assertEquals(Gear.R, DriveKeys.gearStep(Gear.N, up))
        assertEquals(Gear.P, DriveKeys.gearStep(Gear.R, up))
        assertNull("P 끝 — 돌아가지 않음", DriveKeys.gearStep(Gear.P, up))
        // 방향은 부호만 본다(한 번에 한 칸) · 0이면 아무 일 없음
        assertEquals(Gear.R, DriveKeys.gearStep(Gear.P, 3))
        assertEquals(Gear.N, DriveKeys.gearStep(Gear.D, -2))
        for (g in Gear.entries) assertNull("$g · 방향 0", DriveKeys.gearStep(g, 0))
    }

    @Test
    fun turnSignalTogglesSwitchesSidesAndTurnsOff() {
        assertEquals("꺼져 있으면 켬", Blinker.LEFT, DriveKeys.blinkerAfter(Blinker.OFF, Blinker.LEFT))
        assertEquals("같은 키면 끔", Blinker.OFF, DriveKeys.blinkerAfter(Blinker.LEFT, Blinker.LEFT))
        assertEquals("다른 키면 그쪽으로", Blinker.RIGHT, DriveKeys.blinkerAfter(Blinker.LEFT, Blinker.RIGHT))
        assertEquals(Blinker.LEFT, DriveKeys.blinkerAfter(Blinker.RIGHT, Blinker.LEFT))
        assertEquals(Blinker.OFF, DriveKeys.blinkerAfter(Blinker.RIGHT, Blinker.RIGHT))
    }

    @Test
    fun onlyTheSimulatorSourceWithoutTypingOrSystemShortcuts() {
        assertTrue(DriveKeys.accepts(DataSource.SIM, typing = false, systemModifier = false))
        assertFalse("진짜 차는 차·Virtual Console 신호", DriveKeys.accepts(DataSource.CAR, typing = false, systemModifier = false))
        assertFalse("출처를 아직 모름", DriveKeys.accepts(null, typing = false, systemModifier = false))
        assertFalse("글자 입력 칸에 쓰는 중", DriveKeys.accepts(DataSource.SIM, typing = true, systemModifier = false))
        assertFalse("Ctrl·Alt·Meta 조합", DriveKeys.accepts(DataSource.SIM, typing = false, systemModifier = true))
    }
}
