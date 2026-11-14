package com.barodrive.app.feature.hill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.content.HillGuide

// PR-1 언덕 변형 (프로토타입 hill.js `Baro.predriveVariant(7)`, 설계 4.2 PR-1·5.8) — PR-1 화면(ui-loop)이 모드 7 준비 세션일 때 끼운다:
// 맨 위에 [HillSafetyCard], 들어올 때(오늘 과제를 읽은 다음) 코치가 [HILL_PREDRIVE_LINE]을 정차 중 말로 한 번(`VoiceLog.sayLine`,
// 분류 info — `SessionController.enterPredrive(onEnter = …)`), 카드의 [다시 듣기]도 같은 대사. 나머지(오늘 과제·점검·조수석·코스·
// [내비로 보내고 출발])는 기본 PR-1 그대로다.

/** PR-1 언덕 변형에서 코치가 읽는 안전 대사 'p.hill'. */
const val HILL_PREDRIVE_LINE = "p.hill"

/**
 * PR-1 언덕 변형 맨 위 안전 카드 (hill.css `.pr-hill`): 주의 테두리 · 주의 아이콘(48) + '언덕 연습 전에'(28/36 굵게) + [다시 듣기](작은 버튼) ·
 * 안전 글(hill.json `text.safety`: '처음엔 완만한 곳에서, 차가 적은 시간에, 뒤에 차가 없을 때 하세요. 가족이 함께 타면 좋아요.').
 */
@Composable
fun HillSafetyCard(onListen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    BaroCard(modifier.fillMaxWidth(), emphasis = CardEmphasis.Warn, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            BaroIcon(R.drawable.ic_hazard, contentDescription = null, size = Dimens.iconL, tint = colors.caution)
            Text(
                stringResource(R.string.hl_pr_title),
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
            BaroButton(stringResource(R.string.hl_pr_listen), onClick = onListen, size = BaroButtonSize.S, icon = R.drawable.ic_speaker)
        }
        Text(HillGuide.text.safety, style = MaterialTheme.typography.bodySmall)
    }
}

@BaroPreviews
@Composable
private fun HillSafetyCardPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge)) { HillSafetyCard(onListen = {}) }
    }
}
