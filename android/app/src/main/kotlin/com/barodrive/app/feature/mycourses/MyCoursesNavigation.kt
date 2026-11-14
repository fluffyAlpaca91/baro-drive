package com.barodrive.app.feature.mycourses

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 내 코스 경로 (프로토타입 js/app/screens/mycourses.js, 설계 4.2 ST-3·CR-2, 8.2).

/** ST-3 내 코스. 들어옴: 설정. 나감: [내비 즐겨찾기에서 불러오기] → CR-2. */
@Serializable
@SerialName("ST-3")
data object MyCourses

/**
 * CR-2 내비에서 불러오기. 설계 4.1은 시트지만 프로토타입처럼 전체 화면이다(결정사항 E T9-1).
 * [mode] = 처음 고른 모드 칩(0이면 지금 단계), [tab] = "bookmark"(즐겨찾기) · "recent"(최근 목적지).
 */
@Serializable
@SerialName("CR-2")
data class NaviImport(val mode: Int = 0, val tab: String = TAB_BOOKMARK) {
    companion object {
        const val TAB_BOOKMARK = "bookmark"
        const val TAB_RECENT = "recent"
    }
}

/** 내 코스 그래프. */
fun NavGraphBuilder.myCoursesGraph(navController: NavController) {
    composable<MyCourses> {
        MyCoursesRoute(onBack = { navController.popBackStack() }, onImport = { navController.navigate(NaviImport()) })
    }
    composable<NaviImport> {
        NaviImportRoute(onBack = { navController.popBackStack() })
    }
}

/** 시연 패널 '화면 목록'에 보일 내 코스 화면. */
internal val myCoursesCatalog = listOf(
    CatalogEntry(ScreenId.ST_3, MyCourses),
    CatalogEntry(ScreenId.CR_2, NaviImport()),
)
