package com.barodrive.app.feature.autonomous

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.barodrive.app.feature.feedback.MyFeedbackPresets
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.share.ShareSheet
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.SAMPLE_RECORD_ID
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.navigateToRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 자율 모드 경로 (프로토타입 js/app/screens/autonomous.js, 설계 4.2 RV-4, 5.5, 6.11). 출발 전은 FB-2 → PR-1(자율 변형).

/**
 * RV-4 자율 모드 도착·점수. [recordId] = 저장된 회차 기록 id('sess-20261111-0912'). 모드 5 회차가 끝나면 열고, 기록에서 열면 [readOnly].
 * 나감: [홈으로] · [내 음성 피드백 설정] → FB-2 · '가족에게 요약 보내기 ›' → RV-7. 읽기 전용은 [닫기] → 온 곳.
 */
@Serializable
@SerialName("RV-4")
data class AutonomousResult(val recordId: String, val readOnly: Boolean = false)

/** 홈으로(뒤로 스택에 홈이 있으면 거기까지, 없으면 홈을 뿌리로). 리뷰 화면들의 [홈으로]·닫기. */
internal fun NavController.backToHome() {
    if (!popBackStack<Home>(inclusive = false)) navigateToRoot(Home)
}

/** 리뷰 닫기(review.js `close`): 읽기 전용(기록·알림 줄에서 엶)이면 온 곳으로, 회차 끝 리뷰면 홈. */
internal fun NavController.closeReview(readOnly: Boolean) {
    if (readOnly && popBackStack()) return
    backToHome()
}

/** 자율 모드 그래프. */
fun NavGraphBuilder.autonomousGraph(navController: NavController) {
    composable<AutonomousResult> { entry ->
        val route = entry.toRoute<AutonomousResult>()
        AutonomousRoute(
            onClose = { navController.closeReview(route.readOnly) },
            onHome = { navController.backToHome() },
            onMyPresets = { navController.navigate(MyFeedbackPresets()) },
            onShare = { navController.navigate(ShareSheet(it)) },
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 자율 모드 화면(없는 기록 — '기록을 찾을 수 없어요' 상태). */
internal val autonomousCatalog = listOf(CatalogEntry(ScreenId.RV_4, AutonomousResult(SAMPLE_RECORD_ID, readOnly = true)))
