package com.barodrive.app.feature.parking

import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.share.ShareSheet
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.SAMPLE_RECORD_ID
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.navigateToRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 주차 연습 경로 (프로토타입 js/app/screens/parking-select.js · parking-virtual.js · parking-guide.js, 설계 4.2 PK-1~PK-4, 5.7).
// 주차 종류 [ParkingSpot]: type = "parallel"(평행)·"back"(후방, 후진)·"front"(전방), side = "L"·"R"(프로토타입 data/lines/parking.js).

/** 주차 종류 값(경로 인자 글자). */
object ParkingSpot {
    const val PARALLEL = "parallel"
    const val BACK = "back"
    const val FRONT = "front"
    const val LEFT = "L"
    const val RIGHT = "R"
}

/** PK-1 주차 연습 고르기. [type]·[side] = 처음 고른 카드(PK-2·PK-4에서 돌아올 때, 없으면 지난번 고른 것). */
@Serializable
@SerialName("PK-1")
data class ParkingSelect(val type: String? = null, val side: String? = null)

/** PK-2 세워 두고 연습(가상 주차). 기어 P일 때만 — P를 벗어나면 바로 멈추고 덮개(DR-0)가 덮는다. */
@Serializable
@SerialName("PK-2")
data class VirtualParking(val type: String = ParkingSpot.PARALLEL, val side: String = ParkingSpot.RIGHT)

/**
 * PK-3 실제 주차 안내(움직이는 중 화면). PR-1 주차 변형의 [주차 안내 시작]으로 들어온다(기어 그대로). 모드 6 회차의 주행 화면이라
 * 이 경로가 맨 위면 주행 중에도 덮개가 덮지 않는다. 마지막 단계에서 기어 P → PK-4. [그만하기](앱 바 닫기) → PK-1, 기록 안 함.
 * [type]·[side]는 회차도 준비 세션도 종류를 모를 때만 쓴다(회차 중에는 엔진 힌트, 그 전에는 준비 세션 `prep.parking`).
 */
@Serializable
@SerialName("PK-3")
data class ParkingGuide(val type: String = ParkingSpot.PARALLEL, val side: String = ParkingSpot.RIGHT)

/**
 * PK-4 주차 연습 마무리. 모드 6 회차가 끝나면 열고, 기록에서 열면 [readOnly]. 나감: [다른 주차] → PK-1 · [홈으로].
 * [recordId]는 회차 기록 id(글자, 'sess-20261110-0912'). 옛 숫자 id 경로(뼈대)도 받는다.
 */
@Serializable
@SerialName("PK-4")
data class ParkingDone(val recordId: String, val readOnly: Boolean = false)

/** 주차 그래프. */
fun NavGraphBuilder.parkingGraph(navController: NavController) {
    composable<ParkingSelect> {
        ParkingSelectRoute(
            onBack = { navController.popBackStack() },
            onVirtual = { type, side -> navController.navigate(VirtualParking(type, side)) },
            // 프로토타입 parking-select.js `ctx.go('CR-1', { mode: 6, prep: { parking: { type, side } } })` — CR-1이 준비 세션 prep.parking에 넣는다.
            onGuide = { type, side -> navController.navigate(CourseRecommend(mode = 6, parkingType = type, parkingSide = side)) },
        )
    }
    composable<VirtualParking> {
        VirtualParkingRoute(onSelect = { type, side -> navController.backToSelect(type, side) })
    }
    composable<ParkingGuide> {
        ParkingGuideRoute(
            onSelect = { type, side -> navController.toParkingSelect(type, side) },
            onClose = { navController.popBackStack() },
        )
    }
    composable<ParkingDone> {
        ParkingDoneRoute(
            onHome = { navController.navigateToRoot(Home) },
            onOther = { type, side -> navController.navigate(ParkingSelect(type, side)) },
            onClose = { readOnly -> navController.closeReview(readOnly) },
            onShare = { id -> navController.navigate(ShareSheet(id)) },
        )
    }
}

/** 리뷰 닫기 (review.js `close`): 읽기 전용(기록·알림 줄에서 엶)이면 온 곳으로(없으면 홈), 회차 끝 리뷰면 홈. */
internal fun NavController.closeReview(readOnly: Boolean) {
    if (!readOnly || !popBackStack()) navigateToRoot(Home)
}

/** PK-2 → PK-1 (parking-virtual.js `toPK1`): 뒤에 PK-1이 있으면 그리로 돌아가고, 아니면 PK-1로 바꿔 끼운다. */
internal fun NavController.backToSelect(type: String, side: String) {
    if (previousBackStackEntry?.destination?.hasRoute<ParkingSelect>() == true) {
        popBackStack()
        return
    }
    navigate(ParkingSelect(type, side)) { popUpTo<VirtualParking> { inclusive = true } }
}

/** PK-1로: 뒤로 스택 [홈, PK-1] (parking-guide.js `stop` — `R.go('HM-1')` 뒤 `R.go('PK-1', …)`). */
internal fun NavController.toParkingSelect(type: String, side: String) {
    navigate(ParkingSelect(type, side)) {
        popUpTo<Home>()
        launchSingleTop = true
    }
}

/** 시연 패널 '화면 목록'에 보일 주차 화면. */
internal val parkingCatalog = listOf(
    CatalogEntry(ScreenId.PK_1, ParkingSelect()),
    CatalogEntry(ScreenId.PK_2, VirtualParking()),
    CatalogEntry(ScreenId.PK_3, ParkingGuide()),
    CatalogEntry(ScreenId.PK_4, ParkingDone(SAMPLE_RECORD_ID, readOnly = true)),
)
