package com.barodrive.app.data.signal.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.util.Log
import com.barodrive.core.model.Blinker
import javax.inject.Provider

/**
 * Pleos Vehicle SDK로 브레이크·깜빡이·조향을 받는다 (API-레퍼런스 3.1·3.4.4·3.4.6·3.4.7·3.5).
 * `initialize()`에는 연결 콜백이 없다 — 연결 전 요청은 SDK가 쌓아 두었다가 보낸다. Vehicle Service가 없으면
 * 리스너의 `onFailed`가 오고, 그 값은 null로 둔다. 해제는 등록 때와 같은 리스너 객체로 한다.
 */
internal class PleosVehicleSignals(
    private val vehicles: Provider<Vehicle>,
    private val latest: LatestSignals,
) {
    private var vehicle: Vehicle? = null

    private val brake = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            latest.brake = status
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "브레이크를 못 읽어요", e)
            latest.brake = null
        }
    }

    private val turnSignal = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            latest.blinker = state?.let(::blinkerOf)
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "깜빡이를 못 읽어요", e)
            latest.blinker = null
        }
    }

    private val steering = object : SteeringWheelAngleListener {
        // 앞바퀴 각도(°), 왼쪽 음수 — 스냅숏 steer와 같은 뜻이라 그대로 쓴다.
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            latest.steer = angle?.toDouble()
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "조향각을 못 읽어요", e)
            latest.steer = null
        }
    }

    @Synchronized
    fun start() {
        if (vehicle != null) return
        try {
            val v = vehicles.get()
            vehicle = v
            v.initialize()
            v.getBrake().registerBrakePedalStatus(brake)
            v.getTurnSignal().registerTurnSignalState(turnSignal)
            v.getSteeringWheel().registerSteeringWheelAngle(steering)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pleos Vehicle에 붙지 못했어요 — 브레이크·깜빡이·조향은 null", e)
        }
    }

    @Synchronized
    fun stop() {
        val v = vehicle ?: return
        vehicle = null
        try {
            v.getBrake().unregisterBrakePedalStatus(brake)
            v.getTurnSignal().unregisterTurnSignalState(turnSignal)
            v.getSteeringWheel().unregisterSteeringWheelAngle(steering)
            v.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pleos Vehicle 해제 실패", e)
        }
    }

    private fun blinkerOf(value: TurnSignalValue): Blinker = when (value) {
        TurnSignalValue.LEFT -> Blinker.LEFT
        TurnSignalValue.RIGHT -> Blinker.RIGHT
        TurnSignalValue.OFF -> Blinker.OFF
    }

    private companion object {
        const val TAG = "PleosVehicle"
    }
}
