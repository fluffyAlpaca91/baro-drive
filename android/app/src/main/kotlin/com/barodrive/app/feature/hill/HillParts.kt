package com.barodrive.app.feature.hill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.feature.parking.face
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroChip
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroIconButton
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.GearChip
import com.barodrive.app.ui.components.SpeechBubble
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.content.HillGuide
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope

// HL-1·HL-2 공통 조각 (프로토타입 hill.js `hillHead`·`practiceChips`·`coachRow`, hill.css). 움직이는 중 화면이라 움직임 없음.

/** 흐린 칩·칸(hill.css `.chip.dim`·`.strip button.dim`). */
private const val DIM = 0.45f

/**
 * 앱 바 (hill.js `hillHead`): [그만하기](닫기) · '언덕 주행' + 연습 부제 · 소리 끄기(켜면 민트 테두리 `.hl-mute.on`) · 기어 칩.
 * 기어 칩: R 후진 · N 중립 · P 정차 중 · 그 밖은 움직이면 '주행 중'(민트), 아니면 '멈춤'.
 */
@Composable
internal fun HillAppBar(sub: String, gear: Gear?, moving: Boolean, muted: Boolean, onStop: () -> Unit, onMute: () -> Unit) {
    BaroAppBar(
        title = stringResource(R.string.hl_title),
        sub = sub,
        nav = AppBarNav.Close,
        onNav = onStop,
        actions = {
            BaroIconButton(
                icon = if (muted) R.drawable.ic_mute else R.drawable.ic_speaker,
                contentDescription = stringResource(if (muted) R.string.hl_mute_on_desc else R.string.hl_mute_off_desc),
                onClick = onMute,
                selected = muted,
            )
        },
        gearChip = {
            GearChip(
                gear = gear,
                moving = moving,
                label = if (gear == null) stringResource(if (moving) R.string.hl_gear_moving else R.string.hl_gear_stopped) else null,
            )
        },
    )
}

/**
 * 연습 칩 셋 (`.pills3`, hill.js `practiceChips`): 오르막 출발 · 내리막 감속 · 언덕 주차. 지금 연습은 민트, 멈췄을 때만 받는다.
 * 움직이면 프로토타입처럼 칩 모두가 막힌 모양(`aria-disabled` — 테두리만·흐린 글)이 되고 지금 것이 아닌 칩은 더 흐려진다(`.chip.dim`).
 * 못 받을 때 누르면 [onBlocked](까닭 토스트).
 */
@Composable
internal fun HillPracticeChips(current: HillPractice, canTap: Boolean, onPick: (HillPractice) -> Unit, onBlocked: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        HillGuide.practices.forEach { p ->
            val practice = HillPractice.entries.firstOrNull { it.id == p.id } ?: return@forEach
            HillChip(p.name, on = practice == current, canTap = canTap, onPick = { onPick(practice) }, onBlocked = onBlocked)
        }
    }
}

/** 상황 칩 셋 (HL-2 `.hp-sits`): 내리막·연석 있음 · 오르막·연석 있음 · 연석 없음. 받는 규칙·모양은 연습 칩과 같다. */
@Composable
internal fun HillSituationChips(current: HillSlope?, canTap: Boolean, onPick: (HillSlope) -> Unit, onBlocked: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        HillGuide.situations.forEach { s ->
            val slope = HillSlope.entries.firstOrNull { it.id == s.id } ?: return@forEach
            HillChip(s.label, on = slope == current, canTap = canTap, onPick = { onPick(slope) }, onBlocked = onBlocked)
        }
    }
}

/**
 * 칩 하나 (hill.js `paint`: `on` · 못 받으면 `aria-disabled` + 지금 것이 아니면 `dim`). 막힌 칩은 voice.css `.chip[aria-disabled=true]`
 * 모양(투명 바탕 · 진한 선 테두리 · t3 글)이고, 누르면 [onBlocked].
 */
@Composable
private fun RowScope.HillChip(text: String, on: Boolean, canTap: Boolean, onPick: () -> Unit, onBlocked: () -> Unit) {
    BaroChip(
        text = text,
        selected = on,
        onClick = onPick,
        enabled = canTap,
        onDisabledClick = onBlocked,
        modifier = Modifier
            .weight(1f)
            .then(if (!canTap && !on) Modifier.alpha(DIM) else Modifier),
    )
}

/** 못 받는 동안 누름을 받아 까닭을 알린다(단계 칸 줄을 감싸서 — 흐린 칸은 스스로 누름을 받지 않는다). */
internal fun Modifier.blockedTap(blocked: Boolean, onBlocked: () -> Unit): Modifier =
    if (blocked) clickable(interactionSource = null, indication = null, onClick = onBlocked) else this

/** [했어요] (hill.css `.hl-did`: 높이 100) — 화면 누름 단계에만. */
@Composable
internal fun HillDidButton(onPress: () -> Unit) {
    BaroButton(
        text = stringResource(R.string.hl_did),
        onClick = onPress,
        modifier = Modifier.fillMaxWidth(),
        size = BaroButtonSize.L,
        kind = BaroButtonKind.Primary,
    )
}

/**
 * 코치 말풍선 줄 (hill.css `.hl-coach`, DR-1과 같은 모양): 아바타 96 + 한 줄 말풍선(32/40 굵게). 말이 없으면 자리만 남기고 숨긴다
 * (`.hl-coach.off{visibility:hidden}`).
 */
@Composable
internal fun HillCoachRow(coach: Coach, text: String?) {
    val shown = !text.isNullOrEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (shown) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoachAvatar(coach.face(), size = Dimens.avatarL)
        SpeechBubble(
            text = text.orEmpty(),
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.titleSmall,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = Dimens.space3),
            maxLines = 1,
        )
    }
}

/** 방향 맞음 줄 (hill.css `.okline`): 가운데 체크(36) + 28/36 아주 굵은 민트 글. [ok] = false면 주의 색과 `i-hazard`. */
@Composable
internal fun HillOkLine(text: String, ok: Boolean?) {
    val colors = BaroTheme.colors
    val warn = ok == false
    val color = if (warn) colors.cautionText else colors.brandText
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(if (warn) R.drawable.ic_hazard else R.drawable.ic_check, contentDescription = null, size = 36.dp, tint = color)
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 다음 카드 (hill.css `.pb-next`): 64 주의 동그라미 안 사이드브레이크(36) + '다음 · 기어를 P로 바꾸고 / 사이드브레이크를 꼭 채우세요.'
 * 채웠으면 민트 체크와 민트 테두리.
 */
@Composable
internal fun HillNextCard(done: Boolean) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusL)
    BaroCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (done) Modifier.border(2.dp, colors.brandText, shape) else Modifier),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 22.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(if (done) colors.brand else colors.caution, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                BaroIcon(
                    if (done) R.drawable.ic_check else R.drawable.ic_pbrake,
                    contentDescription = null,
                    size = 36.dp,
                    tint = if (done) colors.onBrand else colors.onCaution,
                )
            }
            Text(
                stringResource(if (done) R.string.hl_hp_next_done else R.string.hl_hp_next),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
