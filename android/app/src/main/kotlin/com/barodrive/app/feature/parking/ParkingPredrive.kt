package com.barodrive.app.feature.parking

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.barodrive.app.R
import com.barodrive.core.model.ParkingSetup
import com.barodrive.core.model.ParkingType
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.TurnDir
import com.barodrive.core.engine.content.ParkingGuide as ParkingSteps

/**
 * PR-1 주차 변형 (프로토타입 parking-guide.js `Baro.predriveVariant(6)`, 설계 4.2 PR-1 '주차 실제 안내') — PR-1 화면(ui-loop)이
 * 모드 6 준비 세션일 때 쓴다: 오늘 과제·조수석 카드 없음, 점검·코스 카드만, 부제 [subtitle], 코스 아래 한 줄 [COURSE_NOTE],
 * 하단 [주차 안내 시작]([START], 아이콘 [START_ICON]) → 준비 세션 `parking`을 [setup]으로 정리해 두고(시연은 S6-P를 그 종류·방향으로
 * 불러 자동 재생) [startRoute]로 간다(기어 그대로).
 */
object ParkingPredrive {
    /** 코스 아래 한 줄 '주차장에 도착하면 주차 안내를 시작해요'. */
    @StringRes
    val COURSE_NOTE: Int = R.string.pk_pr_course_note

    /** 하단 버튼 '주차 안내 시작'. */
    @StringRes
    val START: Int = R.string.pk_pr_start

    /** 하단 버튼 아이콘(`i-car-top`). */
    @DrawableRes
    val START_ICON: Int = R.drawable.ic_car_top

    /** 준비 세션의 주차 종류·방향(없거나 모르는 값이면 평행 · 오른쪽, parking.js `norm`). */
    fun setup(prep: PrepSession?): ParkingSetup {
        val p = prep?.parking
        val c = ParkingSteps.norm(p?.type?.code, p?.side?.code)
        return ParkingSetup(ParkingType.entries.first { it.code == c.type }, TurnDir.fromCode(c.side) ?: TurnDir.RIGHT)
    }

    /** 부제 '주차 연습 · 평행 · 오른쪽 칸 · 1회차'. */
    fun subtitle(prep: PrepSession?, res: TextRes): String {
        val s = setup(prep)
        return res.text(R.string.pk_pr_sub, parkingTitle(s.type.code, s.side.code, res), prep?.noInMode?.takeIf { it > 0 } ?: 1)
    }

    /** [주차 안내 시작]이 갈 PK-3 경로(그 종류·방향). */
    fun startRoute(prep: PrepSession?): ParkingGuide = setup(prep).let { ParkingGuide(it.type.code, it.side.code) }
}
