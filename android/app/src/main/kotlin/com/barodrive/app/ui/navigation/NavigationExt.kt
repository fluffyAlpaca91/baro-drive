package com.barodrive.app.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.navigation.NavController
import com.barodrive.app.feature.autonomous.AutonomousResult
import com.barodrive.app.feature.hill.HillDrive
import com.barodrive.app.feature.hill.HillReview
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.license.LicenseReview
import com.barodrive.app.feature.parking.ParkingDone
import com.barodrive.app.feature.parking.ParkingPredrive
import com.barodrive.app.feature.review.Review
import com.barodrive.core.model.PrepSession

/**
 * 뒤로 스택을 모두 비우고 [route]로 간다. 프로토타입 router의 뿌리 화면(HM-1·ON-1)처럼 '가면 스택을 비우는' 이동에 쓴다
 * (온보딩 끝 → 홈, 동의 철회 → ON-1).
 */
fun NavController.navigateToRoot(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * 회차가 끝났을 때 열 리뷰 화면 (프로토타입 `Baro.reviewScreen(mode, id)` · session.js `reviewIdFor`): 5 자율 → RV-4,
 * 6 주차 → PK-4, 7 언덕 → RV-6, 8 면허 → RV-5, 나머지(1~4단계) → RV-1. 기록에서 다시 열 때는 [readOnly] = 참.
 * [focus] = 'f2'면 급제동 확인 카드가 보이게(홈 알림 줄, RV-1만 — 다른 리뷰 화면은 받지 않는다).
 */
fun reviewRouteFor(mode: Int, recordId: String, readOnly: Boolean = false, focus: String? = null): Any = when (mode) {
    5 -> AutonomousResult(recordId, readOnly)
    6 -> ParkingDone(recordId, readOnly)
    7 -> HillReview(recordId, readOnly)
    8 -> LicenseReview(recordId, readOnly)
    else -> Review(recordId, readOnly, focus)
}

/**
 * 그 모드 회차의 주행 화면이 따로 있으면 그 경로 (프로토타입 `Baro.driveScreen(mode, id)` · session.js `driveScreenId`):
 * 6 주차 → PK-3 실제 주차 안내(준비 세션의 종류·방향) · 7 언덕 → HL-1. 둘 다 '움직이는 중' 화면이라 주행 덮개가 덮지 않는다.
 * 나머지 모드는 null — 주행 덮개(DR-1·DR-2)가 그린다.
 */
fun driveRouteFor(mode: Int?, prep: PrepSession?): Any? = when (mode) {
    6 -> ParkingPredrive.startRoute(prep)
    7 -> HillDrive
    else -> null
}

/**
 * 회차가 끝나 리뷰로 간다 (session.js `toReview`): 뒤로 스택은 [홈, 리뷰] — 리뷰에서 뒤로 가도 주행·시작 전 화면으로 돌아가지 않게.
 * 저장하지 못했으면([recordId] = null) 홈.
 */
fun NavController.openReviewAfterSession(mode: Int, recordId: String?) {
    navigateToRoot(Home)
    if (recordId != null) navigate(reviewRouteFor(mode, recordId))
}

/**
 * 시연 패널 '화면 목록'에서 바로 여는 화면 하나: 화면 id와 열 때 쓸 경로(인자가 있으면 예시 값). 기능마다
 * `<기능>Navigation.kt`의 `<기능>Catalog`에 둔다 — 경로 모양을 바꾸면 그 목록도 같이 고친다.
 */
@Immutable
data class CatalogEntry(val screen: ScreenId, val route: Any)

/** 화면 목록 예시 값: 없는 기록 id. 리뷰 화면은 '이 회차 기록을 찾을 수 없어요' 상태로 열린다. */
const val SAMPLE_RECORD_ID = "sess-sample"

/** [SAMPLE_RECORD_ID]의 다른 이름. */
const val SAMPLE_RECORD = SAMPLE_RECORD_ID
