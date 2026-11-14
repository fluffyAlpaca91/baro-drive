package com.barodrive.app.feature.feedback

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.predrive.Predrive
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 음성 피드백 설정 경로 (프로토타입 js/app/screens/feedback.js, 설계 4.2 FB-1·FB-2, 6.3.2).

/**
 * FB-1 음성 피드백 설정(모드별). [mode] = 처음 고른 모드 칩(들어올 때 넘긴 모드). 0(또는 1~8 밖)이면 지금 단계
 * (feedback.js `validMode(params.mode) || currentStage(profile)`). 모드 칩을 누르면 같은 화면 안에서 모드만 바뀐다.
 */
@Serializable
@SerialName("FB-1")
data class FeedbackSettings(val mode: Int = 0)

/**
 * FB-2 내 음성 피드백 설정(자율). [manage]면 설정(ST-1)에서 연 관리 화면이라 [이 설정으로 출발]이 없다.
 * 출발하면 준비 세션(모드 5)을 만들고 PR-1(자율 변형)로.
 */
@Serializable
@SerialName("FB-2")
data class MyFeedbackPresets(val manage: Boolean = false)

/** 음성 피드백 그래프. */
fun NavGraphBuilder.feedbackGraph(navController: NavController) {
    composable<FeedbackSettings> {
        FeedbackSettingsRoute(
            onBack = { navController.popBackStack() },
            onMyPresets = { navController.navigate(MyFeedbackPresets()) },
        )
    }
    composable<MyFeedbackPresets> {
        MyPresetsRoute(
            onBack = { navController.popBackStack() },
            onStart = { navController.navigate(Predrive) },
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 음성 피드백 화면. */
internal val feedbackCatalog = listOf(
    CatalogEntry(ScreenId.FB_1, FeedbackSettings(mode = 2)),
    CatalogEntry(ScreenId.FB_2, MyFeedbackPresets()),
)
