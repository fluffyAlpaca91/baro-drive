package com.barodrive.app.feature.history

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.barodrive.app.ui.navigation.reviewRouteFor

// 연습 기록 경로 (프로토타입 js/app/screens/history.js, 설계 4.2 ST-2, 5.10, 결정-90).

/**
 * ST-2 연습 기록. [filter] = 처음 고를 묶음 칩: "all" 전체 · "stage" 단계 연습 · "free" 자율 · "parking" 주차 · "hill" 언덕 ·
 * "license" 면허. 비우면(기본) 이 앱이 떠 있는 동안 마지막으로 고른 묶음(history.js `lastFilter`).
 * 줄을 누르면 그 회차 리뷰를 읽기 전용으로.
 */
@Serializable
@SerialName("ST-2")
data class History(val filter: String = "") {
    companion object {
        const val FILTER_ALL = "all"
    }
}

/** 기록 줄 → 그 회차 리뷰(읽기 전용): 그 모드 리뷰 화면(5 RV-4 · 6 PK-4 · 7 RV-6 · 8 RV-5 · 나머지 RV-1, [reviewRouteFor]). */
internal fun NavController.openRecordReview(t: RecordTarget) {
    navigate(reviewRouteFor(t.mode, t.recordId, readOnly = true))
}

/** 연습 기록 그래프. */
fun NavGraphBuilder.historyGraph(navController: NavController) {
    composable<History> {
        HistoryRoute(
            onBack = { navController.popBackStack() },
            onOpen = { navController.openRecordReview(it) },
            onPractice = { navController.navigate(CourseRecommend(mode = it)) },
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 연습 기록 화면. */
internal val historyCatalog = listOf(CatalogEntry(ScreenId.ST_2, History(History.FILTER_ALL)))
