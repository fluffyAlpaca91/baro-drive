package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/** 버튼 크기 (목업 `.btn.l`·`.btn`·`.btn.s`, Pleos UX 2.4: 높이·최소 폭·좌우 여백·모서리·아이콘). */
enum class BaroButtonSize(val height: Dp, val minWidth: Dp, val horizontalPadding: Dp, val corner: Dp, val iconSize: Dp) {
    S(Dimens.buttonS, 100.dp, 24.dp, Dimens.radiusS, Dimens.iconS),
    M(Dimens.buttonM, 120.dp, 32.dp, Dimens.radiusM, Dimens.iconS),
    L(Dimens.buttonL, 140.dp, 40.dp, Dimens.radiusL, Dimens.icon),
}

/** 버튼 종류 (목업 `.btn` 회색 · `.btn.primary` 민트 · `.btn.done` 끝냄 · `.btn.danger` 되돌릴 수 없는 일). */
enum class BaroButtonKind { Ghost, Primary, Done, Danger }

/**
 * 바로운전 버튼 (프로토타입 components.js `btn()`, components.css `.btn`). 누르면 95%로 줄고 조금 어두워진다(Pleos UX 2.4).
 * 하단 주요 버튼은 화면에 2개까지(결정-18). 꽉 찬 버튼(`.block`)은 `Modifier.fillMaxWidth()`, 하단 두 버튼은 `Modifier.weight(1f)`.
 *
 * [enabled]가 거짓이면 회색으로 보인다(`aria-disabled`). 그래도 [onDisabledClick]이 있으면 눌림 효과 없이 그것을 부른다 —
 * 프로토타입처럼 '왜 못 누르는지' 토스트를 띄울 때 쓴다(예: ON-2 "연습 기록 저장에 동의해야 시작할 수 있어요").
 */
@Composable
fun BaroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: BaroButtonSize = BaroButtonSize.M,
    kind: BaroButtonKind = BaroButtonKind.Ghost,
    @DrawableRes icon: Int? = null,
    iconAfter: Boolean = false,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(size.corner)
    val (background, content) = when {
        !enabled -> colors.btnGhostBg to colors.t3
        kind == BaroButtonKind.Primary -> colors.brand to colors.onBrand
        kind == BaroButtonKind.Done -> colors.brandSoft to colors.brandText
        kind == BaroButtonKind.Danger -> colors.danger to colors.onDanger
        else -> colors.btnGhostBg to colors.t1
    }
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = size.minWidth)
            .height(size.height)
            .pressClickable(enabled = enabled, onClick = onClick, onDisabledClick = onDisabledClick, role = Role.Button)
            .background(background, shape)
            .then(if (enabled && kind == BaroButtonKind.Done) Modifier.border(2.dp, colors.brandText, shape) else Modifier)
            .padding(horizontal = size.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null && !iconAfter) BaroIcon(icon, contentDescription = null, size = size.iconSize, tint = content)
        Text(text = text, style = size.textStyle(), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (icon != null && iconAfter) BaroIcon(icon, contentDescription = null, size = size.iconSize, tint = content)
    }
}

/** 아이콘 버튼 크기 (`.ibtn` 80×80 모서리 20 아이콘 40 · 작은 것 64×64 모서리 16 아이콘 32). */
enum class BaroIconButtonSize(val box: Dp, val corner: Dp, val iconSize: Dp) {
    M(Dimens.iconButton, Dimens.iconButtonRadius, Dimens.icon),
    S(Dimens.iconButtonSmall, Dimens.radiusS, Dimens.iconS),
}

/**
 * 아이콘 버튼 (목업 `.ibtn`: 앱 바 뒤로·닫기, 홈 머리줄, DR-1 소리 끄기). [selected]면 켜진 토글 모양(`.drv-mute.on`:
 * 옅은 민트 바탕 + 민트 테두리). 막혔으면 테두리만(`.ui-dp-btn[aria-disabled]`).
 */
@Composable
fun BaroIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: BaroIconButtonSize = BaroIconButtonSize.M,
    selected: Boolean = false,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(size.corner)
    val (background, content, border) = when {
        !enabled -> Triple(Color.Transparent, colors.t4, colors.lineStrong)
        selected -> Triple(colors.brandSoft, colors.brandText, colors.brandText)
        else -> Triple(colors.btnGhostBg, colors.t1, Color.Transparent)
    }
    Box(
        modifier = modifier
            .size(size.box)
            .pressClickable(enabled = enabled, onClick = onClick, onDisabledClick = onDisabledClick, role = Role.Button)
            .background(background, shape)
            .border(2.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        BaroIcon(icon, contentDescription = contentDescription, size = size.iconSize, tint = content)
    }
}

/**
 * 글자 버튼 (화면 CSS 여러 곳의 `.txt-btn`: '연습 기록 보기 ›', '가족에게 요약 보내기 ›' …). 높이 64 이상, 26/34 굵게, 민트 글자,
 * 끝에 꺾쇠(28). [danger]면 위험 색(`.txt-btn.danger`, ST-4 '모든 연습 기록 지우기').
 */
@Composable
fun BaroTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    @DrawableRes leadingIcon: Int? = null,
    @DrawableRes trailingIcon: Int? = R.drawable.ic_chev,
    enabled: Boolean = true,
) {
    val colors = BaroTheme.colors
    val content = when {
        !enabled -> colors.t3
        danger -> colors.danger
        else -> colors.brandText
    }
    Row(
        modifier = modifier
            .heightIn(min = Dimens.minTouch)
            .pressClickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(horizontal = Dimens.space1),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) BaroIcon(leadingIcon, contentDescription = null, size = 28.dp, tint = content)
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = content)
        if (trailingIcon != null) BaroIcon(trailingIcon, contentDescription = null, size = 28.dp, tint = content)
    }
}

/**
 * 누를 수 있게 + Pleos 눌림. [enabled]가 거짓이면 [onDisabledClick]이 있을 때만 눌리고(눌림 효과 없음, 이유 토스트용),
 * 접근성에는 '사용 중지'로 알린다.
 */
internal fun Modifier.pressClickable(
    enabled: Boolean,
    onClick: () -> Unit,
    onDisabledClick: (() -> Unit)? = null,
    role: Role? = null,
): Modifier = when {
    enabled -> clickable(interactionSource = null, indication = BaroPressIndication, role = role, onClick = onClick)
    onDisabledClick != null -> semantics { disabled() }
        .clickable(interactionSource = null, indication = null, role = role, onClick = onDisabledClick)
    else -> clickable(interactionSource = null, indication = null, enabled = false, role = role, onClick = onClick)
}

@Composable
private fun BaroButtonSize.textStyle(): TextStyle {
    val type = MaterialTheme.typography
    return when (this) {
        BaroButtonSize.S -> type.labelLarge
        BaroButtonSize.M -> type.bodyMedium
        BaroButtonSize.L -> type.titleSmall
    }.copy(fontWeight = FontWeight.Bold)
}

@BaroPreviews
@Composable
private fun BaroButtonPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.space3), verticalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                BaroButton("작게", onClick = {}, size = BaroButtonSize.S)
                BaroButton("보통", onClick = {})
                BaroButton("크게", onClick = {}, size = BaroButtonSize.L, kind = BaroButtonKind.Primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                BaroButton("통과", onClick = {}, kind = BaroButtonKind.Done, icon = R.drawable.ic_check)
                BaroButton("지우기", onClick = {}, kind = BaroButtonKind.Danger, size = BaroButtonSize.S)
                BaroButton("못 눌러요", onClick = {}, enabled = false, onDisabledClick = {})
            }
            BaroButton(
                "내비로 보내고 출발",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                size = BaroButtonSize.L,
                kind = BaroButtonKind.Primary,
                icon = R.drawable.ic_send,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap), verticalAlignment = Alignment.CenterVertically) {
                BaroIconButton(R.drawable.ic_back, contentDescription = "뒤로", onClick = {})
                BaroIconButton(R.drawable.ic_mute, contentDescription = "소리 끄기", onClick = {}, selected = true)
                BaroIconButton(R.drawable.ic_close, contentDescription = "지우기", onClick = {}, size = BaroIconButtonSize.S)
                BaroIconButton(R.drawable.ic_up, contentDescription = "올리기", onClick = {}, size = BaroIconButtonSize.S, enabled = false)
                BaroTextButton("연습 기록 보기", onClick = {})
                BaroTextButton("모든 연습 기록 지우기", onClick = {}, danger = true, trailingIcon = null)
            }
        }
    }
}
