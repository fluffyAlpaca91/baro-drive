package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 빈 상태 (목업 `.empty-st` 카드, 프로토타입 history.js·course.js·mycourses.js `card empty-st`): 가운데 아이콘(72, 민트) +
 * 제목(32/40 굵게) + 설명(26/34 보조) + 버튼 하나. 예: ST-2 '아직 기록이 없어요' + [연습하러 가기],
 * CR-1 '이 근처엔 아직 코스가 없어요'.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    body: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    BaroCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.space4, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) BaroIcon(icon, contentDescription = null, size = 72.dp, tint = colors.brandText)
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = colors.t2, textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = Dimens.space1)) { action() }
    }
}

@BaroPreviews
@Composable
private fun EmptyStatePreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge)) {
            EmptyState(
                title = "아직 기록이 없어요",
                icon = R.drawable.ic_history,
                body = "연습을 마치면 회차마다 여기에 남아요.",
                action = { BaroButton("연습하러 가기", onClick = {}, kind = BaroButtonKind.Primary) },
            )
        }
    }
}
