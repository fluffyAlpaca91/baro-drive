package com.barodrive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/** 모드 카드 상태 (목업 `.mode` · `.mode.now` 지금 단계 · home.css `.mode.done` 통과 · `.mode.lock` 잠김). */
enum class ModeCardState { Normal, Now, Done, Locked }

/**
 * 모드 카드 (목업 `.mode`, 프로토타입 home.js `modeCard()`): 높이 112, 모서리 28, 번호 원 56 + 이름(26/34 아주 굵게) + 부제(20/24).
 * 지금 단계는 민트 테두리·민트 번호, 통과한 단계는 끝에 민트 체크, 잠긴 단계는 번호 테두리만 + 자물쇠이고 눌러도 [onClick]만
 * 부른다(눌림 효과 없음 — 화면이 '1단계를 통과하면 열려요' 토스트를 띄운다). 쓰는 곳: 홈(HM-1) 2열 격자, ON-4 단계 추천.
 */
@Composable
fun ModeCard(
    number: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    state: ModeCardState = ModeCardState.Normal,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(28.dp)
    val locked = state == ModeCardState.Locked
    Row(
        modifier = modifier
            .height(Dimens.modeCardHeight)
            .pressClickable(enabled = !locked, onClick = onClick, onDisabledClick = onClick, role = Role.Button)
            .background(colors.bgCard, shape)
            .border(
                if (state == ModeCardState.Now) 3.dp else 2.dp,
                if (state == ModeCardState.Now) colors.brandText else colors.cardLine,
                shape,
            )
            .padding(horizontal = Dimens.space3),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (numberBg, numberFg, numberBorder) = when (state) {
            ModeCardState.Now -> Triple(colors.brand, colors.onBrand, Color.Transparent)
            ModeCardState.Locked -> Triple(Color.Transparent, colors.t3, colors.lineStrong)
            else -> Triple(colors.brandSoft, colors.brandText, Color.Transparent)
        }
        Box(
            Modifier
                .size(56.dp)
                .background(numberBg, CircleShape)
                .border(2.dp, numberBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold), color = numberFg)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold),
                color = if (locked) colors.t3 else colors.t1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelMedium, color = colors.t3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            trailing != null -> trailing()
            state == ModeCardState.Done -> BaroIcon(R.drawable.ic_check, contentDescription = null, size = Dimens.iconS, tint = colors.brandText)
            locked -> BaroIcon(R.drawable.ic_lock, contentDescription = null, size = Dimens.iconS, tint = colors.t3)
        }
    }
}

@BaroPreviews
@Composable
private fun ModeCardPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                ModeCard(1, "차량 감 익히기", onClick = {}, sub = "통과", state = ModeCardState.Done, modifier = Modifier.width(368.dp))
                ModeCard(2, "동네 골목 다니기", onClick = {}, sub = "집 근처 골목 · 지금 단계", state = ModeCardState.Now, modifier = Modifier.width(368.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                ModeCard(3, "큰 도로 달리기", onClick = {}, sub = "시내 큰 도로", state = ModeCardState.Locked, modifier = Modifier.width(368.dp))
                ModeCard(5, "자율 모드", onClick = {}, sub = "원하는 곳까지 · 도착 점수", modifier = Modifier.width(368.dp))
            }
        }
    }
}
