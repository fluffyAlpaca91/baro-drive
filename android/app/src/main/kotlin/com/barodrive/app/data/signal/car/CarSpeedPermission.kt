package com.barodrive.app.data.signal.car

import com.barodrive.app.data.signal.SpeedPermission
import com.barodrive.app.di.ApplicationScope
import com.barodrive.app.di.IoDispatcher
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 진짜 차 속도 권한 `CAR_SPEED` ([SpeedPermission]). 앱 실행마다 한 번만 묻는다 — 거절이 두 번이면 안드로이드가 더는 창을 띄우지 않는다.
 * 결과는 [CarSignalSource.refreshSpeedPermission]으로 넘겨 caps와 속도 구독을 맞춘다. 속도 구독은 차 서비스 바인더 호출
 * (`registerCallback` → `getPropertyConfigList`)이라 메인 스레드에서 하지 않는다 — 에뮬레이터에서 5초 넘게 걸려 ANR이 났다.
 */
@Singleton
class CarSpeedPermission @Inject constructor(
    private val car: CarSignalSource,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SpeedPermission {
    private val asked = AtomicBoolean(false)

    override val name: String = CarSignalSource.SPEED_PERMISSION

    override fun takeAsk(): Boolean = !car.speedGranted() && asked.compareAndSet(false, true)

    override fun refresh() {
        scope.launch(io) { car.refreshSpeedPermission() }
    }
}
