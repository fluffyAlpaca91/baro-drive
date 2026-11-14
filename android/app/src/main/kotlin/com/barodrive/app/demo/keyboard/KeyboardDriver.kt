package com.barodrive.app.demo.keyboard

import com.barodrive.app.demo.PEDAL_MIN_HOLD_MS
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear
import kotlin.math.abs
import kotlin.math.max

/**
 * 키보드가 모는 차 — 시연 패널 '차량'과 같은 시뮬레이터 호출이다([SimKeyboardCar]). 그래서 '재생 중 손 조작이면 재생을 멈춤' 같은
 * 규칙이 그대로 따라온다.
 */
interface KeyboardCar {
    /** 앞바퀴각 °(왼쪽 음수, ±35). */
    val steer: Double

    /** 속도 km/h(후진 음수). */
    val speed: Double
    val blinker: Blinker

    /** 차의 지금 기어(PgUp·PgDn 한 칸은 여기서 센다 — 미뤄 둔 기어가 아니라). */
    val gear: Gear

    /** 비상등(시연 전용 — 시뮬레이터 차 상태 밖). */
    val hazard: Boolean

    /** 시나리오 재생 중(그때는 시나리오가 핸들을 잡는다). */
    val playing: Boolean

    /** 가속('throttle')·브레이크('brake') 페달 누름·뗌(패널 [가속]·[브레이크]). */
    fun pedal(name: String, on: Boolean)

    /** 패널 기어 버튼(달리는 중 P·반대 방향이면 먼저 세움). */
    fun shift(gear: Gear)

    /** 패널 '깜빡이'와 같은 setter. */
    fun setTurnSignal(b: Blinker)

    /** 패널 '비상등' 토글과 같은 setter. */
    fun setHazardLights(on: Boolean)

    /** 패널 '핸들' 막대·[가운데로]와 같은 setter. */
    fun setWheel(deg: Double)
}

/**
 * 키보드 운전 (2026-11-11 drive-spec B, 2026-11-12 drive-spec-2): 키 누름·뗌을 패널 조작으로 바꾼다. 시각(ms)은 부르는 쪽이 넘긴다 —
 * 안드로이드도 벽시계도 없어서 단위 테스트로 그대로 돌린다. 한 스레드(메인)에서만 부른다.
 *
 * - 자동 반복은 스스로 거른다: 누르고 있는 키의 누름은 먹기만 한다. 첫 누름을 시스템이 삼켜도(터치 모드 벗어나기) 다음 누름이 첫 누름이 된다.
 * - 페달: ↑ 가속·↓ 브레이크를 누르는 동안 밟는다. 짧게 톡 눌러도 [minHoldMs]는 밟은 채로 둔다(패널 버튼과 같은 최소 시간) —
 *   떼는 것은 그 시각이 지난 [frame]에서.
 * - 핸들: [frame]마다 [KeySteering]으로. 뗀 뒤 스스로 풀림은 가운데에 닿거나, 재생이 시작되거나, 다른 데서(패널 막대·주입) 핸들을
 *   바꾸면 그친다. C는 바로 가운데.
 * - 기어(P R N D)·기어 한 칸(PgUp·PgDn)·깜빡이·비상등(W)·C는 누를 때 한 번. 기어 한 칸은 누른 그때 차의 기어에서 센다 — 기어를 미루거나
 *   (달리는 중 P·반대 방향) 안 바꾸면 다음 누름도 그 기어에서 센다(쌓아 두지 않음).
 */
class KeyboardDriver(private val car: KeyboardCar, private val minHoldMs: Long = PEDAL_MIN_HOLD_MS) {
    private enum class Pedal(val code: String, val action: DriveAction) {
        THROTTLE("throttle", DriveAction.THROTTLE),
        BRAKE("brake", DriveAction.BRAKE),
    }

    /** 누르고 있는 키 → 동작. */
    private val held = LinkedHashMap<Int, DriveAction>()
    private val pedalDownAt = HashMap<Pedal, Long>()

    /** 짧게 눌러 미뤄 둔 페달 떼기 시각. */
    private val releaseAt = HashMap<Pedal, Long>()

    /** 핸들을 마지막으로 움직인 시각(움직일 것이 없으면 null). */
    private var steerT: Long? = null

    /** 뗀 핸들이 가운데로 돌아오는 중(속도가 낮으면 그 각에서 기다림). */
    private var centering = false

    /** 키보드가 마지막으로 넣은 각 — 다른 데서 바꿨는지 본다. */
    private var lastSet: Double? = null

    /** 이 키를 누르고 있나(자동 반복 거르기). */
    fun isHeld(keyCode: Int): Boolean = keyCode in held

    /** 고리를 더 돌려야 하나: 미룬 페달 떼기 · 핸들 키 · 스스로 풀림. */
    val busy: Boolean get() = releaseAt.isNotEmpty() || steering() || centering

    /** 키 누름 → 먹었나(맵에 있는 키면 true). 누르고 있는 키면 먹기만 한다. */
    fun keyDown(keyCode: Int, nowMs: Long): Boolean {
        val action = DriveKeys.actionOf(keyCode) ?: return false
        if (keyCode in held) return true
        val already = action in held.values
        val wasSteering = steering()
        held[keyCode] = action
        when (action) {
            DriveAction.THROTTLE -> if (!already) pedalDown(Pedal.THROTTLE, nowMs)
            DriveAction.BRAKE -> if (!already) pedalDown(Pedal.BRAKE, nowMs)
            DriveAction.STEER_LEFT, DriveAction.STEER_RIGHT -> {
                centering = false
                if (!wasSteering) steerT = nowMs // 지난 풀림의 시각에서 잇지 않는다
            }
            DriveAction.CENTER -> {
                centering = false
                setSteer(0.0)
            }
            DriveAction.BLINK_LEFT, DriveAction.BLINK_RIGHT -> car.setTurnSignal(DriveKeys.blinkerAfter(car.blinker, requireNotNull(action.blinker)))
            DriveAction.HAZARD -> car.setHazardLights(!car.hazard)
            DriveAction.GEAR_P, DriveAction.GEAR_R, DriveAction.GEAR_N, DriveAction.GEAR_D -> car.shift(requireNotNull(action.gear))
            DriveAction.GEAR_UP, DriveAction.GEAR_DOWN -> DriveKeys.gearStep(car.gear, action.step)?.let { car.shift(it) }
        }
        return true
    }

    /** 키 뗌 → 먹었나(이 키의 누름을 받았으면 true). 누름을 받지 않은 키는 흘려보낸다. */
    fun keyUp(keyCode: Int, nowMs: Long): Boolean {
        val action = held[keyCode] ?: return false
        val steerKey = action == DriveAction.STEER_LEFT || action == DriveAction.STEER_RIGHT
        if (steerKey) steerTo(nowMs) // 뗀 때까지 돈 만큼
        held.remove(keyCode)
        when {
            action == DriveAction.THROTTLE && action !in held.values -> pedalUp(Pedal.THROTTLE, nowMs)
            action == DriveAction.BRAKE && action !in held.values -> pedalUp(Pedal.BRAKE, nowMs)
            steerKey && !steering() -> {
                centering = true
                lastSet = car.steer
            }
        }
        return true
    }

    /** 고리 한 번(약 50ms마다): 때가 된 페달 떼기, 핸들 돌리기·풀기. */
    fun frame(nowMs: Long) {
        if (releaseAt.isNotEmpty()) {
            val due = releaseAt.filterValues { it <= nowMs }.keys
            for (p in due) {
                releaseAt.remove(p)
                car.pedal(p.code, false)
            }
        }
        steerTo(nowMs)
    }

    /** 모두 뗀다(창이 포커스를 잃음 · 진짜 차로 바뀜 — 뗌 이벤트가 오지 않을 때): 페달은 바로 떼고 핸들은 그 각 그대로. */
    fun releaseAll() {
        val pedals = Pedal.entries.filter { it.action in held.values || it in releaseAt }
        held.clear()
        releaseAt.clear()
        pedalDownAt.clear()
        centering = false
        steerT = null
        for (p in pedals) car.pedal(p.code, false)
    }

    private fun steering(): Boolean = DriveAction.STEER_LEFT in held.values || DriveAction.STEER_RIGHT in held.values

    private fun pedalDown(p: Pedal, nowMs: Long) {
        pedalDownAt[p] = nowMs
        // 미뤄 둔 떼기가 있으면 아직 밟고 있는 것 — 다시 밟지 않고 떼기만 거둔다.
        if (releaseAt.remove(p) == null) car.pedal(p.code, true)
    }

    private fun pedalUp(p: Pedal, nowMs: Long) {
        val left = minHoldMs - (nowMs - (pedalDownAt[p] ?: nowMs))
        if (left > 0) releaseAt[p] = nowMs + left else car.pedal(p.code, false)
    }

    private fun steerTo(nowMs: Long) {
        val left = DriveAction.STEER_LEFT in held.values
        val right = DriveAction.STEER_RIGHT in held.values
        val keys = left || right
        if (!keys && !centering) {
            steerT = null
            return
        }
        // 시각은 뒤로 가지 않는다: 메인 스레드가 늦으면 뗌 이벤트 시각이 마지막 고리보다 앞일 수 있다(그 사이를 두 번 세지 않게).
        val from = steerT ?: nowMs
        val to = max(from, nowMs)
        steerT = to
        val angle = car.steer
        if (!keys) {
            val moved = lastSet?.let { abs(angle - it) > ANGLE_EPS } ?: false
            if (car.playing || moved) {
                centering = false
                steerT = null
                return
            }
        }
        val dt = ((to - from) / 1000.0).coerceAtMost(MAX_DT)
        val next = KeySteering.next(angle, left, right, car.speed, dt)
        if (next != angle) setSteer(next)
        if (!keys && next == 0.0) {
            centering = false
            steerT = null
        }
    }

    private fun setSteer(deg: Double) {
        car.setWheel(deg)
        lastSet = deg
    }

    private companion object {
        /** 한 번에 이만큼(초)까지만 돌린다 — 고리가 잠깐 멈췄다 돌아와도 핸들이 튀지 않게. */
        const val MAX_DT = 0.25
        const val ANGLE_EPS = 1e-6
    }
}
