package com.barodrive.app.feature.license

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.barodrive.app.feature.autonomous.backToHome
import com.barodrive.app.feature.autonomous.closeReview
import com.barodrive.app.feature.course.CourseRecommend
import com.barodrive.app.feature.share.ShareSheet
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.SAMPLE_RECORD_ID
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.sheet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 운전면허 연습 모드 경로 (프로토타입 js/app/screens/license.js, 설계 4.2 LC-1·LC-2·RV-5, 5.9).

/** LC-1 운전면허 연습 모드 시작 체크. 들어옴: 홈 8번 카드. 나감: 모두 체크하면 CR-1(모드 8), [발급일 바꾸기] → LC-2. */
@Serializable
@SerialName("LC-1")
data object LicenseCheck

/** LC-2 연습면허 발급일(시트). 날짜 고르기 + 미리보기 '2027. 3. 31.까지 · D-141' + [취소] [저장]. */
@Serializable
@SerialName("LC-2")
data object LicenseIssueDateSheet

/**
 * RV-5 운전면허 연습 돌아보기. [recordId] = 저장된 회차 기록 id. 모드 8 회차가 끝나면 열고, 기록에서 열면 [readOnly].
 * 나감: [홈으로] · [다른 코스 보기] → CR-1(모드 8) · '가족에게 요약 보내기 ›' → RV-7.
 */
@Serializable
@SerialName("RV-5")
data class LicenseReview(val recordId: String, val readOnly: Boolean = false)

/**
 * 면허 그래프. LC-2는 시트 경로다.
 * TODO(model): 프로토타입은 [시험장 주변 코스 고르기]·[다른 코스 보기]가 준비 세션에 `license: { checked: true }`를 넘겨 PR-1 동승자 카드를
 * '동승자 확인 끝'으로 고정한다. 모델 PrepSession에 그 칸이 없어 지금은 CR-1(모드 8)만 연다.
 */
fun NavGraphBuilder.licenseGraph(navController: NavController) {
    composable<LicenseCheck> {
        LicenseCheckRoute(
            onBack = { navController.popBackStack() },
            onIssueDate = { navController.navigate(LicenseIssueDateSheet) },
            onCourses = { navController.navigate(CourseRecommend(mode = 8)) },
        )
    }
    sheet<LicenseIssueDateSheet> {
        IssueDateSheetRoute(onClose = { navController.popBackStack() })
    }
    composable<LicenseReview> { entry ->
        val route = entry.toRoute<LicenseReview>()
        LicenseReviewRoute(
            onClose = { navController.closeReview(route.readOnly) },
            onHome = { navController.backToHome() },
            onOtherCourse = { navController.navigate(CourseRecommend(mode = 8)) },
            onShare = { navController.navigate(ShareSheet(it)) },
        )
    }
}

/** 시연 패널 '화면 목록'에 보일 면허 화면. */
internal val licenseCatalog = listOf(
    CatalogEntry(ScreenId.LC_1, LicenseCheck),
    CatalogEntry(ScreenId.LC_2, LicenseIssueDateSheet),
    CatalogEntry(ScreenId.RV_5, LicenseReview(SAMPLE_RECORD_ID, readOnly = true)),
)
