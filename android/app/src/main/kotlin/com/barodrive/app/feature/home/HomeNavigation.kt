package com.barodrive.app.feature.home

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.coach.CoachPicker
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.feature.feedback.FeedbackSettings
import com.barodrive.app.feature.feedback.MyFeedbackPresets
import com.barodrive.app.feature.history.History
import com.barodrive.app.feature.license.LicenseCheck
import com.barodrive.app.feature.parking.ParkingSelect
import com.barodrive.app.feature.review.Review
import com.barodrive.app.feature.settings.Settings
import com.barodrive.app.feature.stage.Graduation
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.reviewRouteFor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 홈 경로 (프로토타입 js/app/screens/home.js, 설계 4.2 HM-1, 5.2).

/** HM-1 홈. 온보딩 뒤 NavHost 시작 화면이자 뿌리 화면이다(가면 뒤로 스택을 비움 — [com.barodrive.app.ui.navigation.navigateToRoot]). */
@Serializable
@SerialName("HM-1")
data object Home

/**
 * 홈 그래프. 나감(설계 4.2 HM-1): 코치 칩 → CoachPicker · i-voice → FeedbackSettings(지금 단계) · i-settings → Settings ·
 * [이어서 연습하기]·단계 카드 → CourseRecommend(mode) · 5 → MyFeedbackPresets · 6 → ParkingSelect · 7 → CourseRecommend(7) ·
 * 8 → LicenseCheck · 졸업 → Graduation · 급제동 알림 → reviewRouteFor(mode, id, readOnly = true, 'f2') · '연습 기록 보기' → History.
 */
fun NavGraphBuilder.homeGraph(navController: NavController) {
    composable<Home> {
        HomeRoute(
            actions = HomeActions(
                onCoach = { navController.navigate(CoachPicker) },
                onFeedback = { mode -> navController.navigate(FeedbackSettings(mode)) },
                onSettings = { navController.navigate(Settings) },
                onCourse = { mode -> navController.navigate(CourseRecommend(mode)) },
                onAutonomous = { navController.navigate(MyFeedbackPresets()) },
                onParking = { navController.navigate(ParkingSelect()) },
                onLicense = { navController.navigate(LicenseCheck) },
                onGraduation = { navController.navigate(Graduation()) },
                onF2 = { ref ->
                    navController.navigate(reviewRouteFor(ref.mode ?: 1, ref.recordId, readOnly = true, focus = Review.FOCUS_F2))
                },
                onHistory = { navController.navigate(History()) },
            ),
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 홈 화면. */
internal val homeCatalog = listOf(CatalogEntry(ScreenId.HM_1, Home))
