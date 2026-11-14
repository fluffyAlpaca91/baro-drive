package com.barodrive.app.demo.keyboard

import android.view.KeyEvent
import com.barodrive.app.data.settings.DataSource
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear

/**
 * 키보드 운전 동작 하나 (2026-11-11 drive-spec B 표, 2026-11-12 drive-spec-2). 페달·핸들은 누르고 있는 동안, 기어·기어 한 칸·깜빡이·
 * 비상등·C는 누를 때 한 번. [step] = 기어 레버 한 칸의 방향(음수 = 위·P 쪽, 양수 = 아래·D 쪽).
 */
enum class DriveAction(val gear: Gear? = null, val blinker: Blinker? = null, val step: Int = 0) {
    THROTTLE,
    BRAKE,
    STEER_LEFT,
    STEER_RIGHT,
    GEAR_P(gear = Gear.P),
    GEAR_R(gear = Gear.R),
    GEAR_N(gear = Gear.N),
    GEAR_D(gear = Gear.D),

    /** PgUp: 레버 한 칸 위(P 쪽). */
    GEAR_UP(step = -1),

    /** PgDn: 레버 한 칸 아래(D 쪽). */
    GEAR_DOWN(step = 1),
    BLINK_LEFT(blinker = Blinker.LEFT),
    BLINK_RIGHT(blinker = Blinker.RIGHT),

    /** W: 비상등 켜기·끄기(시연 전용 — 시뮬레이터 차 상태 밖, [com.barodrive.app.data.signal.sim.SimController.setHazard]). */
    HAZARD,
    CENTER,
}

/**
 * 키 → 동작 (drive-spec B·drive-spec-2, 시연 빌드의 시뮬레이터 자료 출처에서만): ↑ 가속 · ↓ 브레이크 · ← → 핸들 · PgUp PgDn 기어 한 칸
 * (P R N D 키도) · Q E 깜빡이 · W 비상등 · C 핸들 가운데. 그 밖의 키(S, 뒤로·시스템 키 포함)는 null — 가로채지 않는다.
 */
object DriveKeys {
    /** 기어 레버 차례(위 → 아래). P가 맨 위. */
    val LEVER: List<Gear> = listOf(Gear.P, Gear.R, Gear.N, Gear.D)

    /** 리눅스 입력 키 번호(스캔 코드) `KEY_PAGEUP`·`KEY_PAGEDOWN`. */
    const val SCAN_PAGE_UP = 104
    const val SCAN_PAGE_DOWN = 109

    /**
     * 키 이벤트 → 키 표([actionOf])에서 볼 키. 보통은 [keyCode] 그대로다. Pleos 에뮬레이터가 PC 키를 넘기는 키보드 장치(`qwerty2`,
     * 키 배치 `qwerty.kl`)에는 PgUp·PgDn(리눅스 104·109) 줄이 없어 안드로이드 키가 `KEYCODE_UNKNOWN`으로 온다 — 그때만 [scanCode]로
     * PgUp·PgDn을 알아본다. USB 키보드(`Generic.kl`)는 처음부터 `KEYCODE_PAGE_UP`·`KEYCODE_PAGE_DOWN`이다.
     */
    fun keyCodeOf(keyCode: Int, scanCode: Int): Int = when {
        keyCode != KeyEvent.KEYCODE_UNKNOWN -> keyCode
        scanCode == SCAN_PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        scanCode == SCAN_PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        else -> keyCode
    }

    fun actionOf(keyCode: Int): DriveAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> DriveAction.THROTTLE
        KeyEvent.KEYCODE_DPAD_DOWN -> DriveAction.BRAKE
        KeyEvent.KEYCODE_DPAD_LEFT -> DriveAction.STEER_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DriveAction.STEER_RIGHT
        KeyEvent.KEYCODE_PAGE_UP -> DriveAction.GEAR_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> DriveAction.GEAR_DOWN
        KeyEvent.KEYCODE_P -> DriveAction.GEAR_P
        KeyEvent.KEYCODE_R -> DriveAction.GEAR_R
        KeyEvent.KEYCODE_N -> DriveAction.GEAR_N
        KeyEvent.KEYCODE_D -> DriveAction.GEAR_D
        KeyEvent.KEYCODE_Q -> DriveAction.BLINK_LEFT
        KeyEvent.KEYCODE_E -> DriveAction.BLINK_RIGHT
        KeyEvent.KEYCODE_W -> DriveAction.HAZARD
        KeyEvent.KEYCODE_C -> DriveAction.CENTER
        else -> null
    }

    /**
     * PgUp·PgDn 기어 한 칸(drive-spec-2): 차의 지금 기어 [now]에서 레버([LEVER])로 한 칸 — [dir]이 음수면 위(P 쪽), 양수면 아래(D 쪽).
     * 끝(P에서 위 · D에서 아래)이거나 [dir]이 0이면 null(아무 일 없음, 반대 끝으로 돌아가지 않음).
     */
    fun gearStep(now: Gear, dir: Int): Gear? {
        val i = LEVER.indexOf(now)
        if (i < 0 || dir == 0) return null
        return LEVER.getOrNull(if (dir < 0) i - 1 else i + 1)
    }

    /** Q·E 깜빡이: 꺼져 있거나 반대쪽이면 그쪽으로, 같은 쪽이 켜져 있으면 끔. */
    fun blinkerAfter(current: Blinker, pressed: Blinker): Blinker = if (current == pressed) Blinker.OFF else pressed

    /**
     * 새로 누른 키를 받아도 되나: 자료 출처가 시뮬레이터(진짜 차는 차·Virtual Console 신호라 키가 아무 일도 안 함)이고, 글자 입력 칸에
     * 쓰는 중이 아니고, Ctrl·Alt·Meta 조합(시스템 단축키)이 아닐 때. 이미 누르고 있는 키의 뗌은 이와 상관없이 받는다(페달이 붙지 않게).
     */
    fun accepts(source: DataSource?, typing: Boolean, systemModifier: Boolean): Boolean =
        source == DataSource.SIM && !typing && !systemModifier
}
