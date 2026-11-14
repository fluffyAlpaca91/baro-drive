package com.barodrive.app.feature.predrive

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.feature.weather.SevereWeatherSheet
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 연습 시작 전 경로 (프로토타입 js/app/screens/predrive.js, 설계 4.2 PR-1, 3.2 세션 수명).

/**
 * PR-1 연습 시작 전. 어떤 연습인지(모드·코스·회차·날씨·피드백)는 경로가 아니라 세션 관리자의 '준비 세션'에 있다(설계 3.2):
 * 들어오는 화면(CR-1·FB-2·LC-1)이 준비 세션을 만든 뒤 여기로 온다. 변형(자율·코스 없이·언덕·면허·주차)은 [PredriveVariant]로 고른다.
 * 준비 세션이 없으면 CR-1(지금 단계)로 바꿔 간다(바로 앞이 CR-1이면 뒤로, PR-1이 맨 위일 때만). 기어가 D가 되면 주행 덮개가 덮는다. 뒤로 → CR-1.
 */
@Serializable
@SerialName("PR-1")
data object Predrive

/** 연습 시작 전 그래프. */
fun NavGraphBuilder.predriveGraph(navController: NavController) {
    composable<Predrive> {
        PredriveRoute(
            actions = PredriveActions(
                onBack = { navController.popBackStack() },
                onChangeCourse = { mode ->
                    // predrive.js `changeCourse`: 바로 앞이 CR-1이면 뒤로(고른 코스가 그대로), 아니면 그 모드 CR-1.
                    val prev = ScreenId.ofRoute(navController.previousBackStackEntry?.destination?.route)
                    if (prev == ScreenId.CR_1) navController.popBackStack() else navController.navigate(CourseRecommend(mode))
                },
                onSevere = { navController.navigate(SevereWeatherSheet) },
                onNoPrep = { stage ->
                    // Only the PR-1 on top may redirect: when a session finishes the stack is already [Home, review],
                    // and a PR-1 still recomposing with prep = null must not stack CR-1 on the review (QA 2026-11-14 BUG-1).
                    if (ScreenId.ofRoute(navController.currentDestination?.route) == ScreenId.PR_1) {
                        // Like changeCourse: back to the CR-1 below instead of stacking a second one (QA BUG-2).
                        val prev = ScreenId.ofRoute(navController.previousBackStackEntry?.destination?.route)
                        if (prev == ScreenId.CR_1) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(CourseRecommend(stage)) { popUpTo<Predrive> { inclusive = true } }
                        }
                    }
                },
                onRoute = { route -> navController.navigate(route) },
            ),
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 연습 시작 전 화면. */
internal val predriveCatalog = listOf(CatalogEntry(ScreenId.PR_1, Predrive))
