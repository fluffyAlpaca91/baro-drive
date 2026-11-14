package com.barodrive.app.feature.parking

import androidx.annotation.StringRes
import com.barodrive.app.R
import com.barodrive.core.engine.content.ParkingGuide as ParkingSteps

// 주차 종류·방향 이름과 기준점 꿀팁 (프로토타입 js/sim/parking.js TYPES·SIDES·label·title·TIPS·WHEEL_TIP, 표 A-6).
// 종류 id 'parallel'·'back'·'front', 방향 'L'·'R'은 엔진 콘텐츠 ParkingGuide와 같은 글자다.

/** 6가지 카드 순서 (parking-select.js `ORDER`): 평행·후방·전방 × 왼쪽·오른쪽. */
internal val PARKING_ORDER: List<Pair<String, String>> = listOf(
    ParkingSpot.PARALLEL to ParkingSpot.LEFT,
    ParkingSpot.PARALLEL to ParkingSpot.RIGHT,
    ParkingSpot.BACK to ParkingSpot.LEFT,
    ParkingSpot.BACK to ParkingSpot.RIGHT,
    ParkingSpot.FRONT to ParkingSpot.LEFT,
    ParkingSpot.FRONT to ParkingSpot.RIGHT,
)

@StringRes
private fun typeRes(type: String): Int = when (type) {
    ParkingSpot.BACK -> R.string.pk_type_back
    ParkingSpot.FRONT -> R.string.pk_type_front
    else -> R.string.pk_type_parallel
}

@StringRes
private fun sideRes(side: String): Int = if (side == ParkingSpot.LEFT) R.string.pk_side_left else R.string.pk_side_right

/** '평행 · 오른쪽' (sim/parking.js `label`). 종류·방향은 [ParkingSteps.norm]으로 정리한다. */
internal fun parkingLabel(type: String?, side: String?, res: TextRes): String {
    val c = ParkingSteps.norm(type, side)
    return res.text(R.string.pk_label, res.text(typeRes(c.type)), res.text(sideRes(c.side)))
}

/** '평행 · 오른쪽 칸' (sim/parking.js `title`). */
internal fun parkingTitle(type: String?, side: String?, res: TextRes): String {
    val c = ParkingSteps.norm(type, side)
    return res.text(R.string.pk_title, res.text(typeRes(c.type)), res.text(sideRes(c.side)))
}

/**
 * 기준점 꿀팁 글 (parking-guide.js `tipText`): 0·1 = 표 A-6 그 종류의 첫째·둘째 문장, 2 = 바퀴 문장. 없으면 null(카드를 숨김).
 * 왼쪽 칸도 같은 문장이다(좌우 낱말이 없다).
 */
internal fun parkingTipText(type: String?, tip: Int?, res: TextRes): String? {
    if (tip == 2) return res.text(R.string.pk_tip_wheel)
    val t = ParkingSteps.norm(type, null).type
    val id = when (tip) {
        0 -> when (t) {
            ParkingSpot.BACK -> R.string.pk_tip_back_1
            ParkingSpot.FRONT -> R.string.pk_tip_front_1
            else -> R.string.pk_tip_parallel_1
        }
        1 -> when (t) {
            ParkingSpot.BACK -> R.string.pk_tip_back_2
            ParkingSpot.FRONT -> R.string.pk_tip_front_2
            else -> R.string.pk_tip_parallel_2
        }
        else -> return null
    }
    return res.text(id)
}

/** 차종 이름 (sim/vehicle.js MODELS — 모르면 아반떼, parking-select.js `modelName`). */
@StringRes
internal fun carNameRes(model: String?): Int = when (model) {
    "grandeur" -> R.string.pk_car_grandeur
    "tucson" -> R.string.pk_car_tucson
    else -> R.string.pk_car_avante
}
