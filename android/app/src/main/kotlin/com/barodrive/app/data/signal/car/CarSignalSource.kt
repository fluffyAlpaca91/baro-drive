package com.barodrive.app.data.signal.car

import ai.pleos.playground.vehicle.Vehicle
import android.car.Car
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.barodrive.app.data.navi.NaviController
import com.barodrive.app.data.signal.SignalSource
import com.barodrive.core.model.Blinker
import com.barodrive.core.model.Gear
import com.barodrive.core.model.NavEvent
import com.barodrive.core.model.NavSnapshot
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 진짜 차 신호 (설계 6.1.2 '출처 흉내' 칸의 실제 API). 모으는 동안만 차에 붙는다:
 * 속도·기어는 AAOS CarPropertyManager([CarPropertySignals]), 브레이크·깜빡이·조향은 Pleos Vehicle([PleosVehicleSignals]),
 * 내비 값은 [NaviController]. 콜백이 오는 대로 최신 값을 들고 있다가 100ms마다 스냅숏 하나를 낸다. t = elapsedRealtime(초).
 *
 * 차 서비스·Pleos 서비스·권한이 없어도 죽지 않는다 — 로그만 남기고 그 값은 null로 낸다.
 * 속도 권한(CAR_SPEED)이 없으면 [caps]의 속도도 꺼서, 속도가 필요한 항목은 신호 지원 규칙대로 꺼진다(설계 7.8). 권한은 PR-1에서 묻고
 * ([CarSpeedPermission] · ui/SpeedPermissionRequest), 허용되면 [refreshSpeedPermission]이 모으는 중에도 속도를 받기 시작한다.
 */
@Singleton
class CarSignalSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vehicles: Provider<Vehicle>,
    private val navi: NaviController,
) : SignalSource {
    // TODO(port): check*Capabilities·getCarPropertyConfig로 이 차가 실제로 주는 신호를 확인해 채운다(설계 7.8).
    private val _caps = MutableStateFlow(capsFor(speedGranted()))
    override val caps: StateFlow<SignalCaps> = _caps.asStateFlow()

    /** 모으는 동안의 속도·기어 구독. 속도 권한이 나중에 생기면 여기에 속도를 더한다. */
    @Volatile
    private var properties: CarPropertySignals? = null

    override val snapshots: Flow<VehicleSnapshot> = callbackFlow {
        _caps.value = capsFor(speedGranted()) // 시연 패널이 진짜 차로 바꾸기 전에 권한을 받았을 수 있다
        val latest = LatestSignals()
        val properties = CarPropertySignals(context, latest).also { it.connect() }
        this@CarSignalSource.properties = properties
        val pleos = PleosVehicleSignals(vehicles, latest).also { it.start() }
        navi.start()
        val navEvents = ConcurrentLinkedQueue<NavEvent>()
        launch { navi.events.collect { navEvents.add(it) } }
        launch {
            while (isActive) {
                val nav = navi.navState.value?.copy(events = navEvents.drain())
                send(latest.toSnapshot(t = SystemClock.elapsedRealtime() / 1000.0, nav = nav))
                delay(TICK_MS)
            }
        }
        awaitClose {
            if (this@CarSignalSource.properties === properties) this@CarSignalSource.properties = null
            pleos.stop()
            properties.disconnect()
            navi.stop()
        }
    }

    /** 속도 권한(CAR_SPEED)을 지금 가졌나. */
    fun speedGranted(): Boolean = context.checkSelfPermission(SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 속도 권한이 바뀌었을 수 있을 때(권한 창 결과 · 앱으로 돌아옴): [caps]를 다시 정하고, 모으는 중이고 허용이면 속도를 받기 시작한다.
     * 거절이면 속도는 계속 null이다. 회차 설정은 회차를 시작할 때의 caps로 정하므로 달리는 회차는 바뀌지 않는다.
     */
    fun refreshSpeedPermission() {
        val granted = speedGranted()
        _caps.value = capsFor(granted)
        if (granted) properties?.subscribeSpeed()
    }

    companion object {
        /** 진짜 차에서 실행 중에 받아야 하는 권한. CAR_SPEED는 AAOS 보호 수준 dangerous(위치 묶음)다. */
        const val SPEED_PERMISSION: String = Car.PERMISSION_SPEED

        private const val TICK_MS = 100L

        /**
         * 지금 차에서 읽는 신호(나머지는 null로 낸다). 속도 권한이 없으면 속도와 뒤로 가는 속도(같은 PERF_VEHICLE_SPEED)도 못 읽는다 —
         * 그러면 엔진이 속도가 필요한 항목(B1·F1·F2·E1·E2·PK2·HL2)을 끈다.
         */
        internal fun capsFor(speedGranted: Boolean) = SignalCaps(
            speed = speedGranted,
            reverseSpeed = speedGranted,
            passenger = false,
            beltDriver = false,
            handbrake = false,
            wiper = false,
            outsideTemp = false,
            trip = false,
        )

        private fun ConcurrentLinkedQueue<NavEvent>.drain(): List<NavEvent> = generateSequence { poll() }.toList()
    }
}

/** 콜백 스레드가 쓰고 100ms 틱이 읽는 최신 값. 못 읽으면 null. */
internal class LatestSignals {
    @Volatile var gear: Gear? = null

    @Volatile var speed: Double? = null

    @Volatile var brake: Boolean? = null

    // TODO(진짜 차 확인): 에뮬레이터 Virtual Console의 조향값이 반대 부호로 온다(15 → -15). 실차에서도 그런지 보고, 그렇다면
    //  여기(또는 PleosVehicleSignals의 조향 리스너)에서 부호를 뒤집어 스냅숏 steer(왼쪽 음수)에 맞춘다. 지금은 그대로 둔다.
    @Volatile var steer: Double? = null

    @Volatile var blinker: Blinker? = null

    fun toSnapshot(t: Double, nav: NavSnapshot?) = VehicleSnapshot(
        t = t,
        gear = gear,
        speed = speed,
        brake = brake,
        steer = steer,
        blinker = blinker,
        // TODO(port) 아래는 아직 null:
        //   beltDriver  — Pleos Seat.registerSeatBeltBuckledState
        //   passenger   — Pleos Seat.getCurrentSeatOccupancy(registerSeatOccupancy)
        //   handbrake   — AAOS PARKING_BRAKE_ON (CAR_POWERTRAIN)
        //   wiper       — Pleos Wiper.registerWiperState(앞)
        //   lowBeam     — Pleos Light.registerLowBeamLightState
        //   night       — 기기 시각
        //   outsideTemp — AAOS ENV_OUTSIDE_TEMPERATURE
        //   tripDist    — Pleos Odometer.getTripCurrentDrivingDistance(registerTripCurrentDrivingDistance)
        nav = nav,
    )
}
