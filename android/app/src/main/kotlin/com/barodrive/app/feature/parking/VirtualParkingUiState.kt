package com.barodrive.app.feature.parking

import androidx.compose.runtime.Immutable
import com.barodrive.app.R
import com.barodrive.core.sim.parking.ParkCar
import com.barodrive.core.sim.parking.ParkOutcome
import com.barodrive.core.sim.parking.ParkPose
import com.barodrive.core.sim.parking.ParkScene
import com.barodrive.core.sim.parking.Tip
import com.barodrive.core.sim.parking.Touch
import kotlin.math.abs

/**
 * PK-2 세워 두고 연습 화면 값 (프로토타입 parking-virtual.js `paint`·`drawPose`, 설계 4.2 PK-2·7.9).
 * 장면·차는 시뮬레이터의 가상 주차 모델(`:core:sim` [com.barodrive.core.sim.parking.VirtualParking]) 그대로다.
 *
 * @property pose 그릴 차 자세(틱 사이를 이어 그린 값, 뒷바퀴 축 가운데). [steer] 화면 속 앞바퀴각°(차종 최대각까지).
 * @property status 'ready' · 'moving' · 'stopped' · 'touched'(닿음) · 'done'(주차 완료). [touch] 닿은 곳(붉은 표시).
 * @property armed 브레이크를 한 번 밟음(처음과 [처음부터] 뒤에는 밟아야 움직인다). [moved] 움직인 적 있음.
 * @property tip 기준점 꿀팁(0 첫 문장 · 1 둘째 문장, near = 지금 그 시점 — 카드 강조). [vgear] 가상 기어 'D'·'R'. [result] 성공 결과.
 */
@Immutable
data class VirtualParkingUiState(
    val type: String,
    val side: String,
    val scene: ParkScene,
    val car: ParkCar,
    val pose: ParkPose,
    val steer: Double = 0.0,
    val status: String = "ready",
    val touch: Touch? = null,
    val armed: Boolean = false,
    val moved: Boolean = false,
    val tip: Tip = Tip(0, false),
    val vgear: String = "D",
    val result: ParkOutcome? = null,
) {
    val touched: Boolean get() = status == STATUS_TOUCHED
    val done: Boolean get() = status == STATUS_DONE

    companion object {
        const val STATUS_TOUCHED = "touched"
        const val STATUS_DONE = "done"
    }
}

/** PK-2 글 (parking-virtual.js `wheelText`·`touchText`·힌트·꿀팁 라벨). */
internal object VirtualParkingText {
    /** 오른쪽 위 작은 정보: '앞바퀴 똑바로' · '앞바퀴 오른쪽 18°'. */
    fun wheel(steer: Double, res: TextRes): String {
        val a = jsRound(abs(steer))
        if (a == 0) return res.text(R.string.pk2_wheel_straight)
        return res.text(R.string.pk2_wheel_angle, res.text(if (steer > 0) R.string.pk_side_right else R.string.pk_side_left), a)
    }

    /** 왼쪽 위 시작 안내(결정사항 E T5a-3). 움직이기 시작하면 없음. */
    fun hint(s: VirtualParkingUiState, res: TextRes): String? = when {
        !s.armed -> res.text(R.string.pk2_hint_arm)
        !s.moved -> res.text(R.string.pk2_hint_go)
        else -> null
    }

    /** 닿음 토스트·카드 제목: 연석이면 '연석에 닿았어요', 아니면 '옆차에 닿았어요'. */
    fun touch(t: Touch?): Int = if (t?.kind == "curb") R.string.pk2_touch_curb else R.string.pk2_touch_car

    /** 꿀팁 카드 글(장면 표 A-6, 그 시점 문장). */
    fun tipText(s: VirtualParkingUiState): String = s.scene.tips.getOrNull(s.tip.index) ?: s.scene.tips.firstOrNull().orEmpty()
}
