package com.barodrive.app.feature.onboarding

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.home.Home
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.navigateToRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 첫 실행·온보딩 경로 (프로토타입 js/app/screens/onboarding.js, 설계 4.2 ON-1~ON-5, 5.1).
// 첫 화면 고르기(router.start)는 앱 뿌리가 한다: 온보딩 전이면 ON-1, 끝났으면 HM-1(RootViewModel.startRouteFor).

/** ON-1 시작. 뿌리 화면이라 들어올 때 뒤로 스택을 비운다([navigateToRoot]). */
@Serializable
@SerialName("ON-1")
data object OnboardingStart

/** ON-2 기록 저장 동의. */
@Serializable
@SerialName("ON-2")
data object OnboardingConsent

/** ON-3 처음 질문(두 개). */
@Serializable
@SerialName("ON-3")
data object OnboardingQuestions

/** ON-4 시작 단계 추천. */
@Serializable
@SerialName("ON-4")
data object OnboardingRecommend

/** ON-5 코치·호칭 고르기(온보딩). 끝나면 온보딩 완료를 저장하고 HM-1로(스택을 비움). */
@Serializable
@SerialName("ON-5")
data object OnboardingCoach

/** 온보딩 그래프: ON-1 → ON-2 → ON-3 → ON-4 → ON-5 → HM-1. 고르는 값은 [OnboardingDraftStore]에 있어 뒤로 갔다 와도 남는다. */
fun NavGraphBuilder.onboardingGraph(navController: NavController) {
    composable<OnboardingStart> {
        OnboardingStartRoute(onStart = { navController.navigate(OnboardingConsent) })
    }
    composable<OnboardingConsent> {
        OnboardingConsentRoute(onBack = { navController.popBackStack() }, onNext = { navController.navigate(OnboardingQuestions) })
    }
    composable<OnboardingQuestions> {
        OnboardingQuestionsRoute(onBack = { navController.popBackStack() }, onNext = { navController.navigate(OnboardingRecommend) })
    }
    composable<OnboardingRecommend> {
        OnboardingRecommendRoute(onBack = { navController.popBackStack() }, onNext = { navController.navigate(OnboardingCoach) })
    }
    composable<OnboardingCoach> {
        OnboardingCoachRoute(onBack = { navController.popBackStack() }, onDone = { navController.navigateToRoot(Home) })
    }
}

/** 시연 패널 '화면 목록'에 보일 온보딩 화면. */
internal val onboardingCatalog = listOf(
    CatalogEntry(ScreenId.ON_1, OnboardingStart),
    CatalogEntry(ScreenId.ON_2, OnboardingConsent),
    CatalogEntry(ScreenId.ON_3, OnboardingQuestions),
    CatalogEntry(ScreenId.ON_4, OnboardingRecommend),
    CatalogEntry(ScreenId.ON_5, OnboardingCoach),
)
