package com.barodrive.app.data.restriction.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.barodrive.app.data.restriction.DrivingRestrictions
import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * CarUxRestrictionsManager로 주행 규제를 따른다 (Pleos UX 3.1, API-레퍼런스 10.2).
 * 진짜 차 출처일 때만 본다. 시뮬레이터 출처는 시연 도구라 규제 없음 — DEMO_TOOLS 빌드에서만 고를 수 있다.
 * 차 서비스가 없으면 규제 없음으로 둔다(기어로 하는 화면 문은 그대로 돈다).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CarDrivingRestrictions @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : DrivingRestrictions {
    override val restricted: StateFlow<Boolean> = settings.dataSource
        .flatMapLatest { if (it == DataSource.CAR) uxRestrictions() else flowOf(false) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private fun uxRestrictions(): Flow<Boolean> = callbackFlow {
        val manager = AtomicReference<CarUxRestrictionsManager?>(null)
        val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
            trySend(it.isRequiresDistractionOptimization)
        }
        val car = try {
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (ready) {
                    val m = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                    manager.set(m)
                    m?.registerListener(listener)
                    m?.currentCarUxRestrictions?.let { trySend(it.isRequiresDistractionOptimization) }
                } else {
                    manager.set(null)
                    trySend(false)
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "차 서비스에 붙지 못했어요 — 규제 없음으로 둠", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "android.car 라이브러리가 없어요 — 규제 없음으로 둠", e)
            null
        }
        if (car == null) trySend(false)
        awaitClose {
            try {
                manager.getAndSet(null)?.unregisterListener()
                car?.disconnect()
            } catch (e: RuntimeException) {
                Log.w(TAG, "규제 구독 해제 실패", e)
            }
        }
    }

    private companion object {
        const val TAG = "CarUxRestrictions"
    }
}
