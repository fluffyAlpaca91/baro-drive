package com.barodrive.app.feature.hill

import androidx.compose.runtime.Immutable
import com.barodrive.app.feature.parking.ModeLines
import com.barodrive.app.session.api.DrivingState
import com.barodrive.core.engine.content.HillGuide
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.Profile

/**
 * HL-1 언덕 주행 화면 값 (프로토타입 hill.js HL-1 `update`, 설계 4.2 HL-1, 목업 s13a).
 * @property canTap 연습 칩·단계 칸을 받을 수 있나 — 회차 중이고 멈췄을 때만(움직이면 흐리게).
 * @property bubble 코치 말풍선: 오르막 3단계 응원(hl1.cheer, 말하지 않음) 또는 최근 8 sim초 안의 코치 말. 없으면 null(자리는 남김).
 * @property toPark 연습이 언덕 주차가 됨 → HL-2로 바꿔 끼운다.
 */
@Immutable
data class HillDriveUiState(
    val view: HillView = HillView(),
    val canTap: Boolean = false,
    val gear: Gear? = null,
    val moving: Boolean = false,
    val muted: Boolean = false,
    val coach: Coach = Coach.DAD,
    val bubble: String? = null,
    val toPark: Boolean = false,
)

/**
 * HL-2 언덕 주차 화면 값 (프로토타입 hill.js HL-2 `update`, 설계 4.2 HL-2, 목업 s13b).
 * @property canTap 연습 칩·상황 칩을 받을 수 있나 — 회차 중이고 멈췄고 P가 아닐 때만.
 * @property sub 앱 바 부제(언덕 주차 연습의 부제 '언덕에 세울 때 앞바퀴 방향').
 * @property toDrive 연습이 언덕 주차가 아님 → HL-1로 바꿔 끼운다.
 */
@Immutable
data class HillParkUiState(
    val view: HillView = HillView(),
    val canTap: Boolean = false,
    val gear: Gear? = null,
    val moving: Boolean = false,
    val muted: Boolean = false,
    val sub: String = "",
    val toDrive: Boolean = false,
)

/** 세션 상태 + 프로필(코치·호칭) → HL-1·HL-2 화면 값. 판정은 엔진 몫이고 여기서는 옮기기만 한다(설계 1.4). */
internal object HillStates {
    fun drive(s: DrivingState, profile: Profile): HillDriveUiState {
        val out = s.output
        val snap = s.snapshot
        val v = HillViews.view(out?.hints?.hill, snap, profile.coach, profile.honorific)
        val cheer = if (v.cheer) ModeLines.render(CHEER, profile.coach, emptyMap(), profile.honorific).ifEmpty { null } else null
        return HillDriveUiState(
            view = v,
            canTap = canTapDrive(s),
            gear = snap?.gear,
            moving = HillViews.moving(snap),
            muted = s.muted,
            coach = profile.coach,
            bubble = cheer ?: HillViews.recentSay(s)?.text,
            toPark = v.practice == HillPractice.PARK && s.active,
        )
    }

    fun park(s: DrivingState, profile: Profile): HillParkUiState {
        val out = s.output
        val snap = s.snapshot
        val hint = out?.hints?.hill
        val v = HillViews.view(hint, snap, profile.coach, profile.honorific)
        return HillParkUiState(
            view = v,
            canTap = canTapPark(s),
            gear = snap?.gear,
            moving = HillViews.moving(snap),
            muted = s.muted,
            sub = HillGuide.practice(HillPractice.PARK.id)?.sub.orEmpty(),
            toDrive = v.practice != HillPractice.PARK && s.active && hint != null,
        )
    }

    /** HL-1 칩·칸: 회차 중이고 멈춤(hill.js `still(snap, out) && S.active`). */
    fun canTapDrive(s: DrivingState): Boolean = HillViews.still(s.snapshot, s.output) && s.active

    /** HL-2 칩: 거기에 P가 아님(힌트 gearP, 신호가 있으면 신호의 기어). */
    fun canTapPark(s: DrivingState): Boolean {
        val snap = s.snapshot
        val gearP = if (snap != null) snap.gear == Gear.P else s.output?.hints?.hill?.gearP == true
        return canTapDrive(s) && !gearP
    }

    /** 오르막 3단계 응원 말풍선 대사(말하지 않음). */
    private const val CHEER = "hl1.cheer"
}
