package com.barodrive.app.session

import com.barodrive.core.model.VehicleSnapshot
import kotlin.math.abs

/**
 * 기어를 못 읽을 때 트립 거리로 출발·정차를 정한다 (session.js `GL`·`gearlessTick`, 설계 7.8).
 * 움직임 = 트립 거리가 0.005 넘게 늘었거나 속도 크기가 0.5 이상. 움직이면 출발, 출발한 뒤 10 sim초 안 움직이면 정차.
 * 기어를 다시 읽으면 [reset].
 */
internal class GearlessTracker {
    /** 한 번씩 알리는 변화. */
    enum class Change { DROVE_OFF, STOPPED }

    /** 기어 없이 판단하는 중(마지막 스냅숏의 기어가 null). */
    var on = false
        private set

    private var driving = false
    private var still = 0.0
    private var lastTrip: Double? = null

    fun reset() {
        on = false
        driving = false
        still = 0.0
        lastTrip = null
    }

    /** 스냅숏 하나([dt] = 앞 스냅숏부터 sim초). 출발·정차가 바뀌었으면 그 변화. */
    fun tick(snap: VehicleSnapshot, dt: Double): Change? {
        on = true
        val trip = snap.tripDist?.takeIf { it.isFinite() }
        val speed = snap.speed?.takeIf { it.isFinite() }
        val prev = lastTrip
        val moved = (prev != null && trip != null && trip - prev > MOVE_TRIP) || (speed != null && abs(speed) >= MOVE_SPEED)
        if (trip != null) lastTrip = trip
        if (moved) {
            still = 0.0
            if (driving) return null
            driving = true
            return Change.DROVE_OFF
        }
        if (!driving) return null
        still += dt
        if (still < STILL_SEC - EPS) return null
        driving = false
        still = 0.0
        return Change.STOPPED
    }

    private companion object {
        const val MOVE_TRIP = 0.005
        const val MOVE_SPEED = 0.5

        /** 기어를 못 읽을 때 정차로 보는 sim초(session.js `GEARLESS_STILL`). */
        const val STILL_SEC = 10.0
        const val EPS = 1e-6
    }
}
