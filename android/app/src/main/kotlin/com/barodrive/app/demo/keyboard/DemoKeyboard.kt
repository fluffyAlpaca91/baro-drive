package com.barodrive.app.demo.keyboard

import android.os.SystemClock
import android.view.KeyEvent
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.data.signal.sim.SimController
import com.barodrive.app.di.ApplicationScope
import com.barodrive.app.ui.components.TextFieldFocus
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear
import com.barodrive.core.sim.PlayerState
import com.barodrive.core.sim.vehicle.VehiclePatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 시연 키보드 운전 (2026-11-11 drive-spec B · 2026-11-12 drive-spec-2, 시연 빌드만): 에뮬레이터가 넘겨 주는 PC 키보드로 시뮬레이터
 * 차를 몬다 — 패널이 열려 있든 닫혀 있든. [com.barodrive.app.MainActivity.dispatchKeyEvent]가 Compose 포커스 이동(화살표)보다 먼저 넘긴다.
 *
 * 자료 출처가 시뮬레이터이고 글자 입력 칸에 쓰는 중이 아닐 때만 새 누름을 받는다([DriveKeys.accepts]). 맵에 없는 키·뒤로·시스템 키와
 * Ctrl·Alt·Meta 조합은 그대로 흘려보낸다. 판단은 [KeyboardDriver]가 하고, 여기서는 키 이벤트를 거르고 핸들·페달 고리(50ms)를 돌린다.
 * 모두 메인 스레드에서 돈다.
 */
@Singleton
class DemoKeyboard @Inject constructor(
    sim: SimController,
    settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val driver = KeyboardDriver(SimKeyboardCar(sim))
    private val source: StateFlow<DataSource?> = settings.dataSource.stateIn(scope, SharingStarted.Eagerly, null)
    private var loop: Job? = null

    init {
        // 진짜 차로 바꾸면 누르던 페달을 뗀다(그 뒤 키는 아무 일도 안 함).
        scope.launch(Dispatchers.Main.immediate) {
            source.collect { if (it != DataSource.SIM) driver.releaseAll() }
        }
    }

    /** 키 하나 → 먹었으면 true(아래 Compose로 보내지 않음). 에뮬레이터 키보드의 PgUp·PgDn은 스캔 코드로([DriveKeys.keyCodeOf]). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val code = DriveKeys.keyCodeOf(event.keyCode, event.scanCode)
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> when {
                driver.isHeld(code) -> true // 누르고 있는 동안의 자동 반복
                DriveKeys.actionOf(code) == null -> false
                !DriveKeys.accepts(source.value, TextFieldFocus.active, event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) -> false
                else -> driver.keyDown(code, event.eventTime)
            }
            KeyEvent.ACTION_UP -> driver.keyUp(code, event.eventTime)
            else -> false
        }
        if (handled) kick()
        return handled
    }

    /** 창이 포커스를 잃음(뗌 이벤트가 오지 않음): 누르던 페달을 뗀다. */
    fun releaseAll() {
        driver.releaseAll()
    }

    /** 고리: 할 일(핸들 키·스스로 풀림·미룬 페달 떼기)이 있는 동안만 돈다. */
    private fun kick() {
        if (loop?.isActive == true || !driver.busy) return
        loop = scope.launch(Dispatchers.Main.immediate) {
            while (driver.busy) {
                delay(FRAME_MS)
                driver.frame(SystemClock.uptimeMillis())
            }
        }
    }

    private companion object {
        const val FRAME_MS = 50L
    }
}

/** [KeyboardCar] = 시연 패널 '차량'과 같은 [SimController] 호출(재생 중 손 조작이면 재생을 멈추는 것까지 같다). */
class SimKeyboardCar(private val sim: SimController) : KeyboardCar {
    override val steer: Double get() = sim.view.value.car.steer
    override val speed: Double get() = sim.view.value.car.speed
    override val blinker: Blinker get() = sim.view.value.car.blinker
    override val gear: Gear get() = sim.view.value.car.gear
    override val hazard: Boolean get() = sim.view.value.hazard
    override val playing: Boolean get() = sim.view.value.player == PlayerState.PLAYING

    override fun pedal(name: String, on: Boolean) {
        sim.pressPedal(name, on)
    }

    override fun shift(gear: Gear) {
        sim.shift(gear)
    }

    override fun setTurnSignal(b: Blinker) {
        sim.setBlinker(b)
    }

    override fun setHazardLights(on: Boolean) {
        sim.setHazard(on)
    }

    override fun setWheel(deg: Double) {
        sim.set(VehiclePatch(steer = deg))
    }
}
