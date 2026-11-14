package com.barodrive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.barodrive.app.BuildConfig
import com.barodrive.app.R
import com.barodrive.app.demo.DemoPanel
import com.barodrive.app.demo.DemoViewModel
import com.barodrive.app.feature.drive.DriveRoute
import com.barodrive.app.feature.drive.DriveStopHere
import com.barodrive.app.feature.hill.HillDrive
import com.barodrive.app.feature.home.Home
import com.barodrive.app.feature.parking.ParkingGuide
import com.barodrive.app.session.api.SessionEvent
import com.barodrive.app.ui.components.BaroToastHost
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.rememberBaroToastState
import com.barodrive.app.ui.navigation.BaroNavHost
import com.barodrive.app.ui.navigation.BaroSheetHost
import com.barodrive.app.ui.navigation.BaroSheetNavigator
import com.barodrive.app.ui.navigation.RootViewModel
import com.barodrive.app.ui.navigation.ScreenHost
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.coverWhenDriving
import com.barodrive.app.ui.navigation.isReview
import com.barodrive.app.ui.navigation.navigateToRoot
import com.barodrive.app.ui.navigation.openReviewAfterSession
import com.barodrive.app.ui.theme.BaroTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * 앱 화면의 뿌리 (프로토타입 shell/frame.js의 바로운전 창 + app/router.js + session.js 리졸버). 아래에서부터 한 창 안에 겹친다:
 * 정차 화면(NavHost) → 시트([BaroSheetHost]) → 주행 덮개 → 토스트 → 시연 손잡이(시연 빌드만).
 *
 * - 첫 화면: 온보딩 전 ON-1, 끝났으면 HM-1([RootViewModel.startRoute], router.start). 프로필을 읽기 전엔 빈 창.
 * - 주행 덮개: 기어가 P가 아니면([driving]) 또는 회차가 진행 중이고 중간 정차가 아니면(P 마무리 동안 — session.js) 정차 화면을 덮는다.
 *   맨 위 경로가 움직이는 중 화면(PK-3·HL-1·HL-2)이면 덮지 않는다([coverWhenDriving]). NavHost는 덮인 동안에도 그대로 둔다.
 * - 회차 끝([SessionEvent.Finished]) → [홈, 그 모드 리뷰](session.js `toReview`) · 기록이 없으면 홈.
 * - 도착 전 P(중간 정차) → DR-3 '여기서 마칠까요?'. 회차가 취소되면 DR-3에서 홈으로.
 * - PR-1이 아닌 정차 화면으로 가면 준비 세션을 버린다(session.js `onRouterChange` — 그 뒤 D는 DR-0).
 * - 세션 없이 달리다 P가 되면, 그 아래가 리뷰 화면이면 홈으로(결정-74 '리뷰를 닫고').
 * - 기어가 P를 벗어나는 순간 토스트를 거두고 입력 칸의 포커스를 푼다(설계 2.5, T11-1). 시트·대화상자는 [LocalDriving]을 보고 스스로 닫힌다.
 * - 진짜 차 자료 출처면 PR-1(정차)에서 속도 권한을 앱 실행마다 한 번 묻는다([SpeedPermissionRequest]).
 * - 시연 패널의 지금 화면은 보이는 화면이다: 덮개가 덮으면 그 안의 DR-0·DR-1·DR-2([RootViewModel.coverScreen]), 아니면 맨 위 경로.
 */
@Composable
fun BaroRoot(
    driving: StateFlow<Boolean>,
    onMoveTaskToBack: () -> Unit,
    root: RootViewModel = hiltViewModel(),
) {
    // 시연 빌드: 앱 테마는 시연 패널 주간/야간을 따른다(프로토타입 frame.js `applyTheme` — 야간이 처음 값).
    val demo: DemoViewModel? = if (BuildConfig.DEMO_TOOLS) hiltViewModel() else null
    val night = demo?.night?.collectAsStateWithLifecycle()?.value
    BaroTheme(darkTheme = night ?: isSystemInDarkTheme()) {
        val sheetNavigator = remember { BaroSheetNavigator() }
        val navController = rememberNavController(sheetNavigator)
        val toast = rememberBaroToastState()
        val focusManager = LocalFocusManager.current
        val isDriving by driving.collectAsStateWithLifecycle()
        val sessionCover by root.sessionCover.collectAsStateWithLifecycle()
        val coverScreen by root.coverScreen.collectAsStateWithLifecycle()
        val start by root.startRoute.collectAsStateWithLifecycle()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentScreen = ScreenId.ofRoute(backStackEntry?.destination?.route)
        LaunchedEffect(isDriving) {
            if (isDriving) {
                toast.hide()
                focusManager.clearFocus(force = true)
            }
        }
        val covered = (isDriving || sessionCover) && coverWhenDriving(currentScreen)
        // 시트가 떠 있으면 아래 화면은 프로토타입의 inert처럼 접근성 트리에서 뺀다(눌림은 시트의 어두운 판이 막는다).
        val underSheet = currentScreen?.host == ScreenHost.SHEET
        CompositionLocalProvider(LocalDriving provides isDriving, LocalBaroToast provides toast) {
            Box(Modifier.fillMaxSize().background(BaroTheme.colors.bgWindow)) {
                val startRoute = start
                if (startRoute != null) {
                    BaroNavHost(
                        navController = navController,
                        startDestination = startRoute,
                        // 덮인 동안 정차 화면은 접근성 트리에서도 뺀다.
                        modifier = Modifier.fillMaxSize().then(if (covered || underSheet) Modifier.clearAndSetSemantics {} else Modifier),
                    )
                    SessionNavigation(navController, root, isDriving, currentScreen)
                    SpeedPermissionRequest(active = currentScreen == ScreenId.PR_1 && !isDriving)
                }
                BaroSheetHost(sheetNavigator)
                if (covered) DrivingCover(onBack = onMoveTaskToBack)
                BaroToastHost(toast)
                if (demo != null && startRoute != null) {
                    DemoPanel(
                        currentScreen = if (covered) coverScreen else currentScreen,
                        onOpenRoute = { route -> navController.navigate(route) { launchSingleTop = true } },
                        onOpenRoot = { route -> navController.navigateToRoot(route) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        viewModel = demo,
                    )
                }
            }
        }
    }
}

/**
 * 세션이 정하는 화면 이동(session.js 리졸버·`toReview`·`onRouterChange`). NavHost가 그래프를 정한 뒤에만 부른다.
 */
@Composable
private fun SessionNavigation(navController: NavHostController, root: RootViewModel, isDriving: Boolean, currentScreen: ScreenId?) {
    val toast = LocalBaroToast.current
    LaunchedEffect(root) { root.shareNotices.collect { toast.show(it, R.drawable.ic_send) } }
    LaunchedEffect(navController) {
        root.events.collect { event ->
            when (event) {
                is SessionEvent.Finished -> navController.openReviewAfterSession(event.mode, event.recordId)
                is SessionEvent.Cancelled ->
                    if (ScreenId.ofRoute(navController.currentDestination?.route) == ScreenId.DR_3) navController.navigateToRoot(Home)
                else -> Unit
            }
        }
    }
    val stopped by root.stopped.collectAsStateWithLifecycle()
    LaunchedEffect(stopped) {
        if (stopped && ScreenId.ofRoute(navController.currentDestination?.route) != ScreenId.DR_3) {
            navController.navigate(DriveStopHere) { launchSingleTop = true }
        }
    }
    // 모드 6·7 회차는 P가 아닌 동안 그 모드 주행 화면(PK-3·HL-1, 움직이는 중 — 덮개가 덮지 않음). DR-3에서 이어 달리면 DR-3을 바꿔 끼운다.
    val moving by root.movingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(moving) {
        val route = moving ?: return@LaunchedEffect
        val want = screenOf(route)
        val top = ScreenId.ofRoute(navController.currentDestination?.route)
        if (top != want) {
            navController.navigate(route) {
                if (top == ScreenId.DR_3) popUpTo<DriveStopHere> { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(currentScreen) {
        if (RootViewModel.dropsPrep(currentScreen)) root.dropPrep()
    }
    // 결정-74: 세션 없이 달리다(DR-0) P가 되면, 덮개 아래가 리뷰 화면이면 리뷰를 닫고 홈.
    var wasDriving by remember { mutableStateOf(isDriving) }
    LaunchedEffect(isDriving) {
        val top = ScreenId.ofRoute(navController.currentDestination?.route)
        if (wasDriving && !isDriving && !root.sessionActive && top?.isReview == true) navController.navigateToRoot(Home)
        wasDriving = isDriving
    }
}

/** 움직이는 중 경로의 화면 id(PK-3 · HL-1). */
private fun screenOf(route: Any): ScreenId? = when (route) {
    is ParkingGuide -> ScreenId.PK_3
    HillDrive -> ScreenId.HL_1
    else -> null
}

/**
 * 정차 화면을 완전히 덮는 주행 화면. 아래로 터치가 새지 않고, 뒤로(CCP)는 앱을 숨기기만 한다(Pleos UX 4.4).
 * 누름은 보기만 하고 소비하지 않는다: 덮개가 맨 위 형제라 아래 정차 화면에는 누름이 가지 않고, 덮개 안 버튼(DR-1 소리 끄기)의
 * 누름은 취소되지 않는다.
 */
@Composable
private fun DrivingCover(onBack: () -> Unit) {
    // 덮개가 나타날 때 등록되므로 NavHost의 뒤로 가기보다 먼저 불린다.
    BackHandler(onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(BaroTheme.colors.bgWindow)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
    ) {
        DriveRoute()
    }
}
