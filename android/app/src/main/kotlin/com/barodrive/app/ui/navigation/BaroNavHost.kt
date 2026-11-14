package com.barodrive.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.barodrive.app.feature.autonomous.autonomousGraph
import com.barodrive.app.feature.coach.coachGraph
import com.barodrive.app.feature.course.courseGraph
import com.barodrive.app.feature.drive.driveGraph
import com.barodrive.app.feature.feedback.feedbackGraph
import com.barodrive.app.feature.hill.hillGraph
import com.barodrive.app.feature.history.historyGraph
import com.barodrive.app.feature.home.homeGraph
import com.barodrive.app.feature.license.licenseGraph
import com.barodrive.app.feature.mycourses.myCoursesGraph
import com.barodrive.app.feature.onboarding.onboardingGraph
import com.barodrive.app.feature.parking.parkingGraph
import com.barodrive.app.feature.predrive.predriveGraph
import com.barodrive.app.feature.review.reviewGraph
import com.barodrive.app.feature.settings.settingsGraph
import com.barodrive.app.feature.share.shareGraph
import com.barodrive.app.feature.stage.stageGraph
import com.barodrive.app.feature.weather.weatherGraph

/**
 * 정차 화면 이동 (프로토타입 app/router.js, 설계 3.3). 화면마다 경로는 기능의 `<기능>Navigation.kt`에 있고 여기서는 그래프 함수만
 * 부른다(목록은 [ScreenId] KDoc). 뒤로 가기 스택은 정차 화면에만 있다 — 주행 화면은 경로가 아니라 덮개다(BaroRoot).
 * 화면 사이 전환 효과는 없다(프로토타입처럼 바로 바뀐다).
 * [startDestination]은 앱 뿌리가 정한다(router.start: 온보딩 전 ON-1, 끝났으면 HM-1 — [RootViewModel.startRouteFor]).
 */
@Composable
fun BaroNavHost(navController: NavHostController, startDestination: Any, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        onboardingGraph(navController)
        homeGraph(navController)
        coachGraph(navController)
        feedbackGraph(navController)
        courseGraph(navController)
        myCoursesGraph(navController)
        predriveGraph(navController)
        weatherGraph(navController)
        licenseGraph(navController)
        driveGraph(navController)
        reviewGraph(navController)
        stageGraph(navController)
        autonomousGraph(navController)
        shareGraph(navController)
        parkingGraph(navController)
        hillGraph(navController)
        historyGraph(navController)
        settingsGraph(navController)
    }
}
