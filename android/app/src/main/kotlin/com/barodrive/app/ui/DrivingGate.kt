package com.barodrive.app.ui

import com.barodrive.app.data.restriction.DrivingRestrictions
import com.barodrive.app.di.ApplicationScope
import com.barodrive.app.session.DrivingSessionManager
import com.barodrive.core.model.Gear
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * 주행 중 화면 문 (설계 2.5·3.1, Pleos UX 3.1). 기어가 P가 아니거나 주행 규제가 켜지면 주행 화면만 보인다.
 * 기어를 못 읽으면(null) 기어로는 열고 닫지 않고 규제만 본다(설계 7.8).
 */
@Singleton
class DrivingGate @Inject constructor(
    session: DrivingSessionManager,
    restrictions: DrivingRestrictions,
    @ApplicationScope scope: CoroutineScope,
) {
    /** true면 주행 화면만. */
    val driving: StateFlow<Boolean> = combine(session.state, restrictions.restricted) { state, restricted ->
        isDriving(state.gear, restricted)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    companion object {
        fun isDriving(gear: Gear?, restricted: Boolean): Boolean = restricted || (gear != null && gear != Gear.P)
    }
}
