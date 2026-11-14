package com.barodrive.app.feature.coach

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.sheet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 코치 경로 (프로토타입 js/app/screens/coach.js, 설계 4.2 CO-1·CO-2). 코치 줄·호칭 고르기는 ON-5도 같이 쓴다.

/** CO-1 코치 고르기. 들어옴: 홈 코치 칩, 설정. 나감: 뒤로. */
@Serializable
@SerialName("CO-1")
data object CoachPicker

/** CO-2 호칭 정하기(시트). 들어옴: CO-1 호칭 줄, 설정. [취소] [저장]. */
@Serializable
@SerialName("CO-2")
data object HonorificSheet

/** 코치 그래프. CO-2는 시트 경로다([sheet], 시트 띄우는 법은 BaroSheetNavigator KDoc). 설정(ST-1)도 CO-2를 연다. */
fun NavGraphBuilder.coachGraph(navController: NavController) {
    composable<CoachPicker> {
        CoachRoute(onBack = { navController.popBackStack() }, onHonorific = { navController.navigate(HonorificSheet) })
    }
    sheet<HonorificSheet> {
        HonorificSheetRoute(onClose = { navController.popBackStack() })
    }
}

/** 시연 패널 '화면 목록'에 보일 코치 화면. */
internal val coachCatalog = listOf(
    CatalogEntry(ScreenId.CO_1, CoachPicker),
    CatalogEntry(ScreenId.CO_2, HonorificSheet),
)
