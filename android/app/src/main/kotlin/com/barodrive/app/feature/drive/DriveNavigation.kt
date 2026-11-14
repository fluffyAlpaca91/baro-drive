package com.barodrive.app.feature.drive

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.feature.home.Home
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.navigateToRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 주행 경로 (프로토타입 js/app/screens/drive.js, 설계 4.2 DR-0~DR-3, 3.1 상태 기계, 4.6.1 깜빡이 히어로).
//
// DR-0·DR-1·DR-2는 NavHost 경로가 아니라 주행 덮개 화면이다(아키텍처-세팅 1.2 ③): 기어가 P가 아니면 BaroRoot가 정차 화면 위를
// 덮고 [DriveRoute]를 그린다. 정차 화면의 뒤로 스택은 덮인 채 그대로 남는다. 어느 덮개 화면인지는 DriveRoute가 세션 상태로 고른다.
// 아래 [DriveCoverScreen] 객체는 그 세 화면의 이름표다(UiState에 '지금 덮개 화면'을 담을 때 쓴다).
// 모드 6·7 세션의 주행 화면은 PK-3·HL-1(움직이는 중 경로)이다 — 그 경로가 맨 위면 덮개가 덮지 않는다(프로토타입 Baro.driveScreen).

/** 주행 덮개 화면 이름표(DR-0·DR-1·DR-2). NavHost에 등록하지 않는다. */
sealed interface DriveCoverScreen

/** DR-0 연습 중이 아닌 주행(세션 없이 움직임). 말·기록 없이 상태만. */
@Serializable
@SerialName("DR-0")
data object DriveNotPracticing : DriveCoverScreen

/** DR-1 주행 중 한 줄 화면. 히어로(깜빡이 > 최근 말 > 오늘 과제) + 코치 말풍선 + 소리 끄기. */
@Serializable
@SerialName("DR-1")
data object DriveOneLine : DriveCoverScreen

/** DR-2 신호 대기 카드(속도 0이 1초 이상). 카드 1~2장, 버튼 없음(소리 끄기만). */
@Serializable
@SerialName("DR-2")
data object DriveWaitCard : DriveCoverScreen

/**
 * DR-3 여기서 마칠까요? 도착 전에 기어가 P가 되면 연다(정차 화면). [이어서 달리기]는 여기 머물고, 기어 D면 덮개가 다시 덮는다.
 * [여기서 마치기] → 회차를 끝내고 그 모드 리뷰로(reviewRouteFor).
 */
@Serializable
@SerialName("DR-3")
data object DriveStopHere

/** 주행 그래프: 정차 화면인 DR-3만 경로다(앱 뿌리가 중간 정차 때 연다). */
fun NavGraphBuilder.driveGraph(navController: NavController) {
    composable<DriveStopHere> {
        StopHereRoute(onHome = { navController.navigateToRoot(Home) })
    }
}

/** 시연 패널 '화면 목록'에 보일 주행 화면(덮개 화면은 기어로만 연다). */
internal val driveCatalog = listOf(CatalogEntry(ScreenId.DR_3, DriveStopHere))
