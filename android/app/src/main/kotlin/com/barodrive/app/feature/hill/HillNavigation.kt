package com.barodrive.app.feature.hill

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.parking.closeReview
import com.barodrive.app.feature.predrive.Predrive
import com.barodrive.app.feature.share.ShareSheet
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.SAMPLE_RECORD_ID
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.navigateToRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 언덕 주행 경로 (프로토타입 js/app/screens/hill.js, 설계 4.2 HL-1·HL-2·RV-6, 5.8, 6.12.2).
// HL-1·HL-2는 움직이는 중 화면이라 이 경로가 맨 위면 주행 중에도 덮개가 덮지 않는다. 모드 7 세션이 출발하면 PR-1에서
// HL-1로 옮겨야 한다(프로토타입 Baro.driveScreen(7, 'HL-1')) — 앱 뿌리(세션 시작 알림)가 넣는다.

/** HL-1 언덕 주행(오르막 출발·내리막 감속). 연습이 언덕 주차가 되면 → HL-2(바꿔 끼움). */
@Serializable
@SerialName("HL-1")
data object HillDrive

/** HL-2 언덕 주차. 기어 P가 되면 HL3·PB2를 판정하고 회차를 끝내 RV-6으로. 연습이 바뀌면 → HL-1(바꿔 끼움). */
@Serializable
@SerialName("HL-2")
data object HillPark

/**
 * RV-6 언덕 주행 돌아보기. 모드 7 회차가 끝나면 열고, 기록에서 열면 [readOnly]. 나감: [홈으로] · [다시 하기] → PR-1부터.
 * [recordId]는 회차 기록 id(글자). 옛 숫자 id 경로(뼈대)도 받는다.
 */
@Serializable
@SerialName("RV-6")
data class HillReview(val recordId: String, val readOnly: Boolean = false)

/** 언덕 그래프. */
fun NavGraphBuilder.hillGraph(navController: NavController) {
    composable<HillDrive> {
        HillDriveRoute(
            onToPark = { navController.navigate(HillPark) { popUpTo<HillDrive> { inclusive = true } } },
            onHome = { navController.navigateToRoot(Home) },
            onClose = { navController.popBackStack() },
        )
    }
    composable<HillPark> {
        HillParkRoute(
            onToDrive = { navController.navigate(HillDrive) { popUpTo<HillPark> { inclusive = true } } },
            onHome = { navController.navigateToRoot(Home) },
            onClose = { navController.popBackStack() },
        )
    }
    composable<HillReview> {
        HillReviewRoute(
            onHome = { navController.navigateToRoot(Home) },
            onPredrive = { navController.navigate(Predrive) },
            onCourses = { mode -> navController.navigate(CourseRecommend(mode)) },
            onClose = { readOnly -> navController.closeReview(readOnly) },
            onShare = { id -> navController.navigate(ShareSheet(id)) },
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 언덕 화면. */
internal val hillCatalog = listOf(
    CatalogEntry(ScreenId.HL_1, HillDrive),
    CatalogEntry(ScreenId.HL_2, HillPark),
    CatalogEntry(ScreenId.RV_6, HillReview(SAMPLE_RECORD_ID, readOnly = true)),
)
