package com.barodrive.app.feature.stage

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.feedback.MyFeedbackPresets
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.SAMPLE_RECORD
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.navigateToRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 단계 통과·졸업 경로 (프로토타입 js/app/screens/stage.js, 설계 4.2 RV-2·RV-3, 5.4).

/** RV-2 단계 통과. [recordId] = 통과한 회차. 4단계 통과면 RV-3(졸업)으로 바꿔 간다. 나감: [홈으로] · [N+1단계 코스 보기] → CR-1. */
@Serializable
@SerialName("RV-2")
data class StagePassed(val recordId: String)

/** RV-3 졸업 · 연습 기록 요약. [recordId] = 4단계를 통과한 회차(홈 [연습 기록 요약 보기]에서 열면 null — 말하지 않음). */
@Serializable
@SerialName("RV-3")
data class Graduation(val recordId: String? = null)

/** 단계 그래프. 닫기·[홈으로] → 홈(스택을 비움). RV-2가 4단계 기록이면 RV-3으로 바꿔 간다. */
fun NavGraphBuilder.stageGraph(navController: NavController) {
    composable<StagePassed> { entry ->
        val recordId = entry.toRoute<StagePassed>().recordId
        StagePassedRoute(
            actions = StageActions(
                onHome = { navController.navigateToRoot(Home) },
                onCourse = { mode -> navController.navigate(CourseRecommend(mode)) },
                onGraduation = { navController.navigate(Graduation(recordId)) { popUpTo<StagePassed> { inclusive = true } } },
            ),
        )
    }
    composable<Graduation> {
        GraduationRoute(
            actions = StageActions(
                onHome = { navController.navigateToRoot(Home) },
                onFree = { navController.navigate(MyFeedbackPresets()) },
            ),
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 단계 화면. */
internal val stageCatalog = listOf(
    CatalogEntry(ScreenId.RV_2, StagePassed(SAMPLE_RECORD)),
    CatalogEntry(ScreenId.RV_3, Graduation()),
)
