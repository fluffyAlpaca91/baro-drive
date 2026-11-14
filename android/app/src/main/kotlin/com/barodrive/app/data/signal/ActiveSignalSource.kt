package com.barodrive.app.data.signal

import com.barodrive.app.data.settings.DataSource
import com.barodrive.app.data.settings.SettingsRepository
import com.barodrive.app.di.ApplicationScope
import com.barodrive.app.di.CarSignal
import com.barodrive.app.di.SimSignal
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** 어느 출처에서 온 스냅숏인지 꼬리표를 붙인 것. 출처가 바뀐 것을 스냅숏과 같은 순서로 알 수 있다. */
data class SignalFrame(val source: DataSource, val snapshot: VehicleSnapshot)

/**
 * 설정에서 고른 출처(시뮬레이터/진짜 차)의 신호. 출처가 바뀌면 앞 출처 수집을 끊고 새 출처로 넘어간다 —
 * 두 출처를 함께 모으지 않는다. 여러 곳에서 모아도 출처 수집은 하나다(shareIn).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ActiveSignalSource @Inject constructor(
    settings: SettingsRepository,
    @CarSignal private val car: SignalSource,
    @SimSignal private val sim: SignalSource,
    @ApplicationScope scope: CoroutineScope,
) : SignalSource {
    /** 꼬리표가 붙은 스냅숏. 세션 관리자는 이것으로 출처가 바뀐 것을 안다. */
    val frames: Flow<SignalFrame> = settings.dataSource
        .flatMapLatest { source -> sourceOf(source).snapshots.map { SignalFrame(source, it) } }
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    override val snapshots: Flow<VehicleSnapshot> = frames.map { it.snapshot }

    override val caps: StateFlow<SignalCaps> = settings.dataSource
        .flatMapLatest { sourceOf(it).caps }
        .stateIn(scope, SharingStarted.Eagerly, SignalCaps())

    private fun sourceOf(source: DataSource): SignalSource = when (source) {
        DataSource.SIM -> sim
        DataSource.CAR -> car
    }
}
