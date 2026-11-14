package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 날씨 배지 (목업 `.wx-badge`): 높이 56 알약, 창 바탕 + 진한 테두리, 파랑 아이콘(36) + 26/34 굵은 글.
 * 예: DR-1 '빗길 · 제한속도 20% 낮춤'(날씨 안내 중일 때, 설계 4.2 DR-1).
 */
@Composable
fun WeatherBadge(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_rain) {
    val colors = BaroTheme.colors
    Row(
        modifier = modifier
            .height(Dimens.gearChipHeight)
            .background(colors.bgWindow, CircleShape)
            .border(2.dp, colors.lineStrong, CircleShape)
            .padding(start = Dimens.space2, end = Dimens.space3),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(icon, contentDescription = null, size = 36.dp, tint = colors.positive)
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** 날씨 요령 줄 (목업 `.wx-tip`): 파랑 아이콘(32) + 24/28 보조 글. 예: RV-1 날씨 팁 '비 오는 날은 차간거리를 두 배로'. */
@Composable
fun WeatherTip(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_rain) {
    val colors = BaroTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BaroIcon(icon, contentDescription = null, size = Dimens.iconS, tint = colors.positive)
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.t2)
    }
}

@BaroPreviews
@Composable
private fun WeatherPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            WeatherBadge("빗길 · 제한속도 20% 낮춤")
            WeatherBadge("눈길 · 제한속도 50% 낮춤", icon = R.drawable.ic_snow)
            WeatherTip("비 오는 날은 앞차와 거리를 두 배로 둬요")
        }
    }
}
