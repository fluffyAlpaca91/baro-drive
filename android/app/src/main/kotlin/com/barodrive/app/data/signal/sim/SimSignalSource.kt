package com.barodrive.app.data.signal.sim

import com.barodrive.app.data.signal.SignalSource
import com.barodrive.app.di.DefaultDispatcher
import com.barodrive.core.model.SignalCaps
import com.barodrive.core.model.VehicleSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 시뮬레이터 신호 (설계 7.1·7.2의 앱 쪽 실제 시간 고리, 프로토타입 `clock.run(requestAnimationFrame …)`). 실제 100ms쯤마다
 * 그동안 실제로 흐른 시간을 재어(프로토타입처럼 — 고리가 늦으면 따라잡는다) [SimController.advance]로 넘기고, 돈 틱의 스냅숏을 차례로
 * 낸다 — 재생 중이면 압축(3·6·10) × 배속(×1·×2·×4)만큼 많이(배속 '실제'면 실제 시간과 1:1),
 * 손 조작이면 ×1, 차가 서서 아무 일 없으면 하나도 없다(sim 시계가 선다). 엔진은 배속과 상관없이 늘 0.1초 간격 스냅숏을 받는다.
 * 이 출처를 모으는 동안만 시계가 돈다(시연 출처를 고른 동안).
 */
@Singleton
class SimSignalSource @Inject constructor(
    private val controller: SimController,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : SignalSource {
    override val snapshots: Flow<VehicleSnapshot> = flow {
        // 세워 둔 차는 시계가 서서 틱(스냅숏)이 없다. 모으기 시작할 때 지금 상태를 한 번 내야 화면이 '신호 못 읽음'으로 보이지 않는다.
        emit(controller.current())
        var resets = controller.resets.value
        var last = System.nanoTime()
        while (true) {
            // 실제로 흐른 시간(쉬는 시간 + 스냅숏을 넘긴 시간). 고정 0.1초를 넘기면 고리가 늦는 만큼 sim이 실제보다 느렸다(에뮬레이터 약 0.86배).
            // 적어도 쉰 만큼(0.1초)은 넘긴다 — 가상 시간 테스트에서도 고리 한 번 = 0.1초. 한 번에 0.25초까지는 시계가 자른다.
            val now = System.nanoTime()
            val real = max(FRAME_SEC, (now - last) / NANOS_PER_SEC)
            last = now
            val out = controller.advance(real)
            for (s in out) emit(s)
            // 차를 처음으로 돌렸는데 시계가 서 있으면(데모 상태 적용) 지금 상태를 한 번 — 지난 회차 끝 상태가 남지 않게.
            val r = controller.resets.value
            if (r != resets) {
                resets = r
                if (out.isEmpty()) emit(controller.current())
            }
            delay(FRAME_MS)
        }
    }.flowOn(dispatcher)

    override val caps: StateFlow<SignalCaps> = controller.caps

    private companion object {
        /** 실제 시간 한 번(초, 쉬는 시간) — 시뮬레이터는 한 번에 실제 0.25초까지만 따라간다. */
        const val FRAME_SEC = 0.1
        const val NANOS_PER_SEC = 1e9
        const val FRAME_MS = 100L
    }
}
