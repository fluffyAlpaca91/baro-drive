package com.barodrive.app.feature.share

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.SAMPLE_RECORD_ID
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.sheet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 가족에게 요약 보내기 경로 (프로토타입 js/app/screens/share.js, 설계 4.2 RV-7, 결정-45·46).

/**
 * RV-7 가족에게 요약 보내기(시트). [recordId] = 보낼 회차. 리뷰 화면(RV-1·4·5·6, PK-4)의 '가족에게 요약 보내기 ›' 줄이 연다.
 * 가족 공유 동의가 꺼져 있으면 이 시트 안에서 먼저 동의를 묻는다("요약을 보내려면 가족 공유에 동의해야 해요" [취소] [동의하고 계속]).
 */
@Serializable
@SerialName("RV-7")
data class ShareSheet(val recordId: String)

/** 공유 그래프(시트 하나). */
fun NavGraphBuilder.shareGraph(navController: NavController) {
    sheet<ShareSheet> {
        ShareSheetRoute(onClose = { navController.popBackStack() })
    }
}

/** 시연 패널 '화면 목록'에 보일 공유 시트. */
internal val shareCatalog = listOf(CatalogEntry(ScreenId.RV_7, ShareSheet(SAMPLE_RECORD_ID)))
