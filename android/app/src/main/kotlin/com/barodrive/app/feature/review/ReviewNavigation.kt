package com.barodrive.app.feature.review

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.feature.feedback.MyFeedbackPresets
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.license.LicenseCheck
import com.barodrive.app.feature.parking.ParkingSelect
import com.barodrive.app.feature.share.ShareSheet
import com.barodrive.app.feature.stage.Graduation
import com.barodrive.app.feature.stage.StagePassed
import com.barodrive.app.ui.navigation.navigateToRoot
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.SAMPLE_RECORD
import com.barodrive.app.ui.navigation.ScreenId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 리뷰 경로 (프로토타입 js/app/screens/review.js, 설계 4.2 RV-1, 6.10). 다른 모드 리뷰(RV-4·5·6, PK-4)도 이 화면의 구역을 같이 쓴다.

/**
 * RV-1 오늘 연습 돌아보기. [recordId] = 저장된 회차 기록 id('sess-…'). 회차가 끝나면 BaroRoot가 연다(홈 위에 하나만 쌓임).
 * 기록(ST-2)·홈 알림 줄에서 다시 열면 [readOnly](하단 [닫기] 하나).
 */
@Serializable
@SerialName("RV-1")
data class Review(val recordId: String, val readOnly: Boolean = false, val focus: String? = null) {
    companion object {
        /** [focus]: 급제동 본인 확인 카드가 보이게(홈 알림 줄, review.js `focus: 'f2'`). */
        const val FOCUS_F2 = "f2"
    }
}

/**
 * 리뷰 그래프. 나감(설계 4.2 RV-1): [홈으로] · [N단계 통과 확인] → StagePassed(1~3단계)·Graduation(4단계) · [다시 연습하기] → 그 모드 시작 화면
 * (5 → FB-2 · 6 → PK-1 · 8 → LC-1 · 나머지 CR-1) · '가족에게 요약 보내기 ›' → ShareSheet(recordId) · 읽기 전용 [닫기] → 온 곳(없으면 홈).
 */
fun NavGraphBuilder.reviewGraph(navController: NavController) {
    composable<Review> { entry ->
        val recordId = entry.toRoute<Review>().recordId
        ReviewRoute(
            actions = ReviewActions(
                onHome = { navController.navigateToRoot(Home) },
                onClose = { if (!navController.popBackStack()) navController.navigateToRoot(Home) },
                onPass = { pass -> navController.navigate(if (pass.graduated) Graduation(recordId) else StagePassed(recordId)) },
                onAgain = { mode -> navController.navigate(againRouteFor(mode)) },
                onShare = { navController.navigate(ShareSheet(recordId)) },
            ),
        )
    }
}

/** [다시 연습하기]가 갈 곳(review.js `againTarget`): 5 → FB-2 · 6 → PK-1 · 8 → LC-1 · 나머지 → CR-1(그 모드). */
fun againRouteFor(mode: Int): Any = when (mode) {
    5 -> MyFeedbackPresets()
    6 -> ParkingSelect()
    8 -> LicenseCheck
    else -> CourseRecommend(mode)
}

/** 시연 패널 '화면 목록'에 보일 리뷰 화면. */
internal val reviewCatalog = listOf(CatalogEntry(ScreenId.RV_1, Review(SAMPLE_RECORD, readOnly = true)))
