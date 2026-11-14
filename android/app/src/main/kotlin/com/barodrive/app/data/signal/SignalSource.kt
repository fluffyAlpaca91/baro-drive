package com.barodrive.app.data.signal

import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 차 신호 출처 (설계 6.1.2·7.1). 진짜 차와 시뮬레이터가 같은 [VehicleSnapshot]을 낸다 —
 * 엔진과 화면은 지금 어느 쪽인지 몰라도 된다.
 */
interface SignalSource {
    /** 약 10Hz 스냅숏. 차가운 흐름: 모으는 동안만 차·시뮬레이터에 붙고, 그만 모으면 떨어진다. */
    val snapshots: Flow<VehicleSnapshot>

    /** 이 출처가 읽을 수 있는 신호(설계 7.8). 세션을 시작할 때 엔진 설정에 넣는다. */
    val caps: StateFlow<SignalCaps>
}
