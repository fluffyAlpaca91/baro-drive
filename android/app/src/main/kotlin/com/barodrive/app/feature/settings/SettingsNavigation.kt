package com.barodrive.app.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.coach.CoachPicker
import com.barodrive.app.feature.coach.HonorificSheet
import com.barodrive.app.feature.feedback.FeedbackSettings
import com.barodrive.app.feature.feedback.MyFeedbackPresets
import com.barodrive.app.feature.history.History
import com.barodrive.app.feature.mycourses.MyCourses
import com.barodrive.app.feature.onboarding.OnboardingStart
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.navigateToRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 설정 경로 (프로토타입 js/app/screens/settings.js, 설계 4.2 ST-1·ST-4·ST-5, 5.10). ST-2는 history, ST-3은 mycourses 묶음.

/** ST-1 설정. 줄 높이 88 목록(코치 · 부르는 말 · 음성 피드백 설정 · 내 음성 피드백 설정 · 내 코스 · 연습 기록 · 공유와 개인정보 …). */
@Serializable
@SerialName("ST-1")
data object Settings

/** ST-4 공유와 개인정보. '연습 기록 저장'을 끄면(확인 대화상자) 기록을 지우고 ON-1로(스택을 비움). */
@Serializable
@SerialName("ST-4")
data object Privacy

/** ST-5 앱 안내(정적 글). */
@Serializable
@SerialName("ST-5")
data object About

/** 설정 그래프. */
fun NavGraphBuilder.settingsGraph(navController: NavController) {
    composable<Settings> {
        SettingsRoute(
            onBack = { navController.popBackStack() },
            onOpen = { target ->
                navController.navigate(
                    when (target) {
                        SettingsTarget.CoachPicker -> CoachPicker
                        SettingsTarget.Honorific -> HonorificSheet
                        is SettingsTarget.Feedback -> FeedbackSettings(target.mode)
                        SettingsTarget.Presets -> MyFeedbackPresets(manage = true)
                        SettingsTarget.MyCourses -> MyCourses
                        SettingsTarget.History -> History()
                        SettingsTarget.Privacy -> Privacy
                        SettingsTarget.About -> About
                    },
                )
            },
        )
    }
    composable<Privacy> {
        PrivacyRoute(
            onBack = { navController.popBackStack() },
            onWithdrawn = { navController.navigateToRoot(OnboardingStart) },
        )
    }
    composable<About> {
        AboutScreen(onBack = { navController.popBackStack() })
    }
}

/** 시연 패널 '화면 목록'에 보일 설정 화면. */
internal val settingsCatalog = listOf(
    CatalogEntry(ScreenId.ST_1, Settings),
    CatalogEntry(ScreenId.ST_4, Privacy),
    CatalogEntry(ScreenId.ST_5, About),
)
