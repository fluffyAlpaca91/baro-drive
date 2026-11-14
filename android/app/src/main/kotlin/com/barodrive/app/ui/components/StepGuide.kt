package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

// 움직이는 중 화면(PK-3 실제 주차 안내 · HL-1 언덕 주행)과 리뷰 화면들이 같이 쓰는 조각 (목업 components.css '단계 안내'·'추가' 묶음).

/**
 * 지금 할 일 (목업 `.do-l` + `.do-t`): 24/28 민트 라벨('3단계 · 지금 할 일') + 44/56 아주 굵은 문장(두 줄 자리 112 확보).
 * 움직이는 중 화면의 큰 문장 하나(설계 2.5).
 */
@Composable
fun StepInstruction(label: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = BaroTheme.colors.brandText,
        )
        Text(
            text,
            modifier = Modifier.heightIn(min = 112.dp),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 44.sp,
                lineHeight = 56.sp,
                letterSpacing = (-0.6).sp,
                lineBreak = LineBreak.Heading,
            ),
        )
    }
}

/** 다음 단계 신호 안내 (목업 `.sig`): 가운데 맞춘 아이콘(32) + 24/28 3차 글자. 예: '핸들을 끝까지 돌리면 넘어가요'. */
@Composable
fun SignalHint(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_info) {
    val color = BaroTheme.colors.t3
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(icon, contentDescription = null, size = Dimens.iconS, tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * 꿀팁 카드 (목업 `.card.tip`): 주황 아이콘(40) + 20/24 아주 굵은 라벨('기준점 꿀팁') + 26/34 문장. 주차(PK-2·PK-3)·언덕 안내에 쓴다.
 * [highlighted]면 민트 테두리(지금 단계에 맞는 팁, parking.css `.pk2-tip.now`).
 */
@Composable
fun TipCard(
    label: String,
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = R.drawable.ic_bulb,
    highlighted: Boolean = false,
) {
    val colors = BaroTheme.colors
    BaroCard(
        modifier = modifier.fillMaxWidth(),
        emphasis = if (highlighted) CardEmphasis.Selected else CardEmphasis.None,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = Dimens.space3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            BaroIcon(icon, contentDescription = null, tint = colors.caution)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (highlighted) colors.brandText else colors.t3,
                )
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 리뷰 묶음 제목 (components.css `.rv-h`, RV-1·RV-5·RV-6·PK-4가 같이 쓴다): 앞 [leading](보통 [StatusBadge]) + 28/36 아주 굵은 제목.
 * 예: '잘한 것' 민트 체크, '아쉬운 것' 주황 표시.
 */
@Composable
fun ReviewHeader(title: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold))
    }
}

/**
 * 리뷰 줄 (components.css `.rv li`): 최소 높이 52, 왼쪽 26/34 글 + 오른쪽 28/36 아주 굵은 값, 줄 사이 2px 선(첫 줄은 [topDivider] = false).
 * 예: '깜빡이 40m 전에 켜기 · 7/8'.
 */
@Composable
fun ReviewRow(label: String, modifier: Modifier = Modifier, value: String? = null, topDivider: Boolean = true) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (topDivider) Modifier.lineAt(bottom = false, color = BaroTheme.colors.line) else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold))
    }
}

@BaroPreviews
@Composable
private fun StepGuidePreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            StepBar(total = 8, current = 3)
            StepInstruction(label = "3단계 · 지금 할 일", text = "핸들을 오른쪽 끝까지 돌려요")
            SignalHint("핸들을 끝까지 돌리면 넘어가요", icon = R.drawable.ic_wheel)
            TipCard(label = "기준점 꿀팁", text = "옆 차 뒷범퍼가 내 뒷좌석 창 가운데에 오면 멈춰요", highlighted = true)
            BaroCard {
                ReviewHeader("잘한 것", leading = { StatusBadge(R.drawable.ic_check, BadgeKind.Good) })
                Column {
                    ReviewRow("깜빡이 40m 전에 켜기", value = "7/8", topDivider = false)
                    ReviewRow("부드럽게 멈추기", value = "10/11")
                }
            }
        }
    }
}
