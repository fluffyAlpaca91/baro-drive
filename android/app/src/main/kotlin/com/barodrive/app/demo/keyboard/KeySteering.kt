package com.barodrive.app.demo.keyboard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 키보드 핸들 (2026-11-11 drive-spec B '핸들'). 시연 패널 '핸들' 막대와 같은 단위·범위·부호다: 앞바퀴각 °, ±[FULL_LOCK], 왼쪽 음수
 * (스냅숏 규칙). 같은 setter(`VehiclePatch(steer)`)로 넣는다.
 *
 * - ← 또는 →를 누르는 동안 끝(±35)까지 일정한 빠르기로 돈다 — 가운데에서 끝까지 [FULL_LOCK_SEC]초.
 * - 둘 다 누르면 아무 일도 없다(그 각 그대로).
 * - 떼면: 속도가 [SELF_CENTER_KMH] 넘으면 같은 빠르기로 가운데로 돌아오고(달리는 차의 복원), 그 이하(주차)면 그 각을 지킨다.
 */
object KeySteering {
    /** 끝(패널 막대 범위). */
    const val FULL_LOCK = 35.0

    /** 가운데 → 끝 시간(초). */
    const val FULL_LOCK_SEC = 1.5

    /** 도는 빠르기 °/초. */
    const val RATE = FULL_LOCK / FULL_LOCK_SEC

    /** 이 속도(km/h, 크기)를 넘으면 뗀 핸들이 가운데로 돌아온다. */
    const val SELF_CENTER_KMH = 5.0

    /**
     * [dt]초 뒤 각. [left]·[right] = 그 키를 누르고 있음. 둘 다 아니면 뗀 핸들(스스로 풀림은 [speedKmh]로 정한다).
     */
    fun next(angle: Double, left: Boolean, right: Boolean, speedKmh: Double, dt: Double): Double {
        val step = RATE * max(0.0, dt)
        return when {
            left && right -> angle
            left -> max(-FULL_LOCK, angle - step)
            right -> min(FULL_LOCK, angle + step)
            !selfCenters(speedKmh) -> angle
            angle > 0 -> max(0.0, angle - step)
            else -> min(0.0, angle + step)
        }
    }

    /** 뗀 핸들이 가운데로 돌아오는 속도인가(후진도 크기로). */
    fun selfCenters(speedKmh: Double): Boolean = abs(speedKmh) > SELF_CENTER_KMH
}
