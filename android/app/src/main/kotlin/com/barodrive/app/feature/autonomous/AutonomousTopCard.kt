package com.barodrive.app.feature.autonomous

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.components.TagKind
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * PR-1 자율 변형 맨 위 카드 (autonomous.js `Baro.predriveVariant(5).topCard`, autonomous.css `.pr-auto`): "목적지는 내비에서 직접 넣어요"
 * + 부제 · 고른 목적지('마트 · 12.4km · 31분', 고른 뒤) · '내 음성 피드백 설정' + 고른 설정 이름 태그.
 * PR-1(ui-loop)이 모드 5일 때 과제·코스 카드 대신 이 카드를 맨 위에 그린다. 값은 [AutonomousText.topModel](준비 세션 + 프로필).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutonomousTopCard(top: AutonomousTop, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    BaroCard(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            BaroIcon(R.drawable.ic_route, contentDescription = null, size = Dimens.iconL, tint = colors.brandText)
            Column(Modifier.weight(1f)) {
                Text(top.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                Text(top.sub, style = MaterialTheme.typography.labelLarge, color = colors.t3)
            }
        }
        top.destText?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BaroIcon(R.drawable.ic_flag, contentDescription = null, size = Dimens.iconS, tint = colors.brandText)
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
            verticalArrangement = Arrangement.spacedBy(Dimens.space1),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.au_top_preset), style = MaterialTheme.typography.labelLarge, color = colors.t3)
            BaroTag(top.preset, kind = TagKind.Soft)
        }
    }
}

@BaroPreviews
@Composable
private fun AutonomousTopCardPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            AutonomousTopCard(AutonomousTop(AutonomousText.TOP_TITLE, AutonomousText.TOP_SUB, null, null, "출퇴근용"))
            AutonomousTopCard(AutonomousTop(AutonomousText.TOP_TITLE, AutonomousText.TOP_SUB_PICKED, "마트", "마트 · 12.4km · 31분", "출퇴근용 (바꿈)"))
        }
    }
}
