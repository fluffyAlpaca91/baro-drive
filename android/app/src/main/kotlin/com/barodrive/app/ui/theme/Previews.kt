package com.barodrive.app.ui.theme

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** 컴포넌트 미리보기: 다크·라이트 두 장, 앱 창 폭 840(설계 2.3 테마 두 가지). */
@Preview(
    name = "다크",
    widthDp = Dimens.WINDOW_WIDTH_DP,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFF131417,
)
@Preview(
    name = "라이트",
    widthDp = Dimens.WINDOW_WIDTH_DP,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
)
annotation class BaroPreviews

/** 화면 미리보기: 840×1184 앱 창(Connect-S 세로), 다크·라이트. */
@Preview(
    name = "다크",
    widthDp = Dimens.WINDOW_WIDTH_DP,
    heightDp = Dimens.WINDOW_HEIGHT_DP,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFF131417,
)
@Preview(
    name = "라이트",
    widthDp = Dimens.WINDOW_WIDTH_DP,
    heightDp = Dimens.WINDOW_HEIGHT_DP,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
)
annotation class BaroScreenPreviews

/**
 * 넓은 앱 창 미리보기: Pleos 에뮬레이터에서 앱 창이 가로 1682×1210dp로 열린다(다크·라이트). 내용은 960dp 기둥으로 가운데에
 * 서야 한다(BaroScaffold·BaroContentColumn). 주요 화면에 단다.
 */
@Preview(
    name = "가로 다크",
    widthDp = Dimens.WIDE_WIDTH_DP,
    heightDp = Dimens.WIDE_HEIGHT_DP,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFF131417,
)
@Preview(
    name = "가로 라이트",
    widthDp = Dimens.WIDE_WIDTH_DP,
    heightDp = Dimens.WIDE_HEIGHT_DP,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
)
annotation class BaroWideScreenPreviews
