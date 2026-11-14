package com.barodrive.app.feature.weather

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.barodrive.app.ui.navigation.CatalogEntry
import com.barodrive.app.ui.navigation.ScreenId
import com.barodrive.app.ui.navigation.sheet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 날씨 경로 (프로토타입 js/app/screens/weather.js, 설계 4.2 PR-1 '날씨 줄', 5.6, 결정-17).
// 날씨는 따로 된 화면이 없다: PR-1 안의 날씨 줄과 이 시트뿐이다. 날씨 줄은 PR-1 화면이 [PredriveWeatherCardRoute]를 끼워 그린다
// (모드가 [WeatherRules.MODES]일 때, 자리는 [WeatherRules.orderFor] — 와이퍼 켜짐·0°C 이하면 맨 위).

/**
 * PR-1 '아주 나쁜 날' 시트(설계 4.1 표에는 id가 없어 `PR-1-severe`). 제목 "아주 나쁜 날인가요?", 고르기 줄 3개
 * (길이 얼었거나 눈이 많이 쌓였어요 / 앞이 100m도 안 보여요 / 해당 없어요), 줄을 누르면 고르고 닫힌다. [닫기].
 */
@Serializable
@SerialName("PR-1-severe")
data object SevereWeatherSheet

/** 날씨 그래프(시트 하나). */
fun NavGraphBuilder.weatherGraph(navController: NavController) {
    sheet<SevereWeatherSheet> {
        SevereWeatherSheetRoute(onClose = { navController.popBackStack() })
    }
}

/** 시연 패널 '화면 목록'에 보일 날씨 시트. */
internal val weatherCatalog = listOf(CatalogEntry(ScreenId.PR_1_SEVERE, SevereWeatherSheet))
