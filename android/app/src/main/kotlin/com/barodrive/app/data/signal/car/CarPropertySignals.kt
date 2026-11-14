package com.barodrive.app.data.signal.car

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.barodrive.core.model.Gear

/**
 * AAOS CarPropertyManager로 속도·기어를 받는다 (설계 6.1.2, API-레퍼런스 10.1).
 * `PERF_VEHICLE_SPEED`(m/s, 후진은 음수) → km/h, `GEAR_SELECTION` → [Gear].
 * 차 서비스가 없거나 CAR_SPEED 권한이 없으면 로그만 남기고 그 값은 null로 둔다. 권한을 나중에 받으면 [subscribeSpeed]로 속도를 더한다.
 *
 * 구독: API 35부터 `subscribePropertyEvents`, 그 전(Pleos 에뮬레이터 API 34)에는 `registerCallback`.
 */
internal class CarPropertySignals(
    private val context: Context,
    private val latest: LatestSignals,
) {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    /** 속도를 구독했나. */
    private var speedOn = false

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val ok = value.isAvailable()
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    latest.speed = if (ok) (value.value as? Float)?.let { it * MPS_TO_KMH } else null
                VehiclePropertyIds.GEAR_SELECTION ->
                    latest.gear = if (ok) (value.value as? Int)?.let(::gearOf) else null
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "속성 오류: ${VehiclePropertyIds.toString(propertyId)}")
            clear(propertyId)
        }
    }

    fun connect() {
        car = try {
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (ready) subscribe(connected) else onServiceLost()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "차 서비스에 붙지 못했어요 — 속도·기어는 null", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "android.car 라이브러리가 없어요(자동차가 아닌 기기?) — 속도·기어는 null", e)
            null
        }
    }

    @Synchronized
    fun disconnect() {
        unsubscribe()
        try {
            car?.disconnect()
        } catch (e: RuntimeException) {
            Log.w(TAG, "차 서비스 연결 끊기 실패", e)
        }
        car = null
    }

    @Synchronized
    private fun subscribe(car: Car) {
        val m = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (m == null) {
            Log.w(TAG, "CarPropertyManager가 없어요")
            return
        }
        manager = m
        speedOn = false
        subscribeSpeed()
        subscribe(m, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
    }

    /** 속도 구독(CAR_SPEED가 있을 때만, 한 번). 차 서비스에 붙은 뒤 권한을 받으면 다시 불러 그때부터 속도를 받는다. */
    @Synchronized
    fun subscribeSpeed() {
        val m = manager ?: return
        if (speedOn) return
        if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAR_SPEED 권한이 없어 속도는 null")
            return
        }
        speedOn = subscribe(m, VehiclePropertyIds.PERF_VEHICLE_SPEED, SPEED_RATE_HZ)
    }

    /** 구독했으면 참. */
    private fun subscribe(m: CarPropertyManager, propertyId: Int, rateHz: Float): Boolean {
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                m.subscribePropertyEvents(propertyId, rateHz, callback)
            } else {
                @Suppress("DEPRECATION") // API 34에는 subscribePropertyEvents가 없다
                val registered = m.registerCallback(callback, propertyId, rateHz)
                registered
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "권한 없음: ${VehiclePropertyIds.toString(propertyId)}", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "지원하지 않는 속성: ${VehiclePropertyIds.toString(propertyId)}", e)
            false
        }
        if (!ok) Log.w(TAG, "구독 실패: ${VehiclePropertyIds.toString(propertyId)}")
        return ok
    }

    @Synchronized
    private fun unsubscribe() {
        val m = manager ?: return
        manager = null
        speedOn = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                m.unsubscribePropertyEvents(callback)
            } else {
                @Suppress("DEPRECATION") // API 34
                m.unregisterCallback(callback)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "구독 해제 실패", e)
        }
    }

    /** 차 서비스가 죽음. 다시 붙으면 lifecycle 콜백이 subscribe를 다시 부른다. */
    @Synchronized
    private fun onServiceLost() {
        manager = null
        speedOn = false
        latest.speed = null
        latest.gear = null
    }

    private fun clear(propertyId: Int) {
        when (propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> latest.speed = null
            VehiclePropertyIds.GEAR_SELECTION -> latest.gear = null
        }
    }

    private fun CarPropertyValue<*>.isAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            propertyStatus == CarPropertyValue.STATUS_AVAILABLE
        } else {
            @Suppress("DEPRECATION") // API 34에는 getPropertyStatus가 없다
            val status = status
            status == CarPropertyValue.STATUS_AVAILABLE
        }

    private fun gearOf(value: Int): Gear? = when (value) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        VehicleGear.GEAR_UNKNOWN -> null
        else -> Gear.D // GEAR_DRIVE와 1~9단은 모두 앞으로 가는 기어
    }

    private companion object {
        const val TAG = "CarProperty"
        const val MPS_TO_KMH = 3.6
        const val SPEED_RATE_HZ = 10f
    }
}
