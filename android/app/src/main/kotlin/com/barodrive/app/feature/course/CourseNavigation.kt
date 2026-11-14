package com.barodrive.app.feature.course

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.feedback.MyFeedbackPresets
import com.barodrive.app.feature.license.LicenseCheck
import com.barodrive.app.feature.mycourses.NaviImport
import com.barodrive.app.feature.parking.ParkingSelect
import com.barodrive.app.feature.predrive.Predrive
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 연습 코스 추천 경로 (프로토타입 js/app/screens/course.js, 설계 4.2 CR-1, 6.10.6).

/**
 * CR-1 연습 코스 추천. [mode] = 고른 모드(1~8, 잠긴 단계·범위 밖이면 지금 단계). 들어옴: 홈 [이어서 연습하기]·단계 카드·7번 카드,
 * RV-2 [다음 단계 코스 보기], LC-1 통과(모드 8), PK-1 [실제 주차 안내](모드 6), PR-1 [코스 바꾸기].
 * 나감: [X 코스로 연습하기] → 준비 세션을 만들고 PR-1.
 * 들어온 화면이 넘기는 준비 세션 값(프로토타입 `params.prep`): 모드 6 주차 종류·방향 [parkingType]('parallel'·'back'·'front') ·
 * [parkingSide]('L'·'R') — 그 모드로 연습할 때만 준비 세션에 넣는다.
 */
@Serializable
@SerialName("CR-1")
data class CourseRecommend(val mode: Int, val parkingType: String? = null, val parkingSide: String? = null)

/** 코스 추천 그래프. */
fun NavGraphBuilder.courseGraph(navController: NavController) {
    composable<CourseRecommend> {
        CourseRoute(
            actions = CourseActions(
                onBack = { navController.popBackStack() },
                onPredrive = { navController.navigate(Predrive) },
                onEntry = { m -> if (m == 6) navController.navigate(ParkingSelect()) else navController.navigate(LicenseCheck) },
                onAutonomous = { navController.navigate(MyFeedbackPresets()) },
                onImport = { m -> navController.navigate(NaviImport(mode = m)) },
            ),
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 코스 추천 화면. */
internal val courseCatalog = listOf(CatalogEntry(ScreenId.CR_1, CourseRecommend(mode = 2)))
