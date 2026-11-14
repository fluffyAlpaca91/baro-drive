package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 칩 (목업 `.chip`, components.js `chip()`): 높이 64 알약, 24/28 굵게, 아이콘 32. 고르면 민트(`.chip.on`).
 * 막힌 칩은 테두리만(`.chip[aria-disabled]`, voice.css) — 누르면 [onDisabledClick](이유 토스트)만 부른다.
 * 쓰는 곳: 호칭(CO-2), 날씨 [오늘 눈 와요](PR-1), 기록 묶음(ST-2), 내 음성 피드백 설정 칩(FB-2), 탭(CR-2).
 */
@Composable
fun BaroChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
    role: Role = Role.RadioButton,
) {
    val colors = BaroTheme.colors
    val (background, content, border) = when {
        !enabled -> Triple(Color.Transparent, colors.t3, colors.lineStrong)
        selected -> Triple(colors.brand, colors.onBrand, Color.Transparent)
        else -> Triple(colors.btnGhostBg, colors.t1, Color.Transparent)
    }
    Row(
        modifier = modifier
            .height(Dimens.chipHeight)
            .then(
                if (enabled) {
                    Modifier.selectable(selected, interactionSource = null, indication = BaroPressIndication, role = role, onClick = onClick)
                } else {
                    Modifier
                        .semantics { disabled() }
                        .selectable(selected, interactionSource = null, indication = null, role = role, onClick = { onDisabledClick?.invoke() })
                },
            )
            .background(background, CircleShape)
            .border(2.dp, border, CircleShape)
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) BaroIcon(icon, contentDescription = null, size = Dimens.iconS, tint = content)
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = content, maxLines = 1)
    }
}

/**
 * 모드 번호 칩 줄 (목업 `.mchips`, components.js `mchips()`): 64 원 안에 모드 번호, 고른 것은 민트. [locked] 모드는 테두리만이고
 * 누르면 [onLockedClick](예: "앞 단계를 통과하면 열려요" 토스트). 쓰는 곳: FB-1 모드 칩, CR-1 모드 칩, CR-2·ST-3 모드 고르기.
 */
@Composable
fun ModeChips(
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    modes: List<Int> = (1..8).toList(),
    locked: Set<Int> = emptySet(),
    onLockedClick: (Int) -> Unit = {},
) {
    val colors = BaroTheme.colors
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        modes.forEach { mode ->
            val isLocked = mode in locked
            val isOn = mode == selected && !isLocked
            val description = stringResource(R.string.mode_chip_description, mode, modeName(mode))
            val (background, content, border) = when {
                isLocked -> Triple(Color.Transparent, colors.t3, colors.lineStrong)
                isOn -> Triple(colors.brand, colors.onBrand, Color.Transparent)
                else -> Triple(colors.btnGhostBg, colors.t1, Color.Transparent)
            }
            Box(
                modifier = Modifier
                    .size(Dimens.minTouch)
                    .selectable(
                        selected = isOn,
                        interactionSource = null,
                        indication = if (isLocked) null else BaroPressIndication,
                        role = Role.RadioButton,
                        onClick = { if (isLocked) onLockedClick(mode) else if (!isOn) onSelect(mode) },
                    )
                    .semantics {
                        contentDescription = description
                        if (isLocked) disabled()
                    }
                    .background(background, CircleShape)
                    .border(2.dp, border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mode.toString(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = content,
                )
            }
        }
    }
}

/** 태그 종류 (목업 `.tag.brand` 민트 · `.tag.soft` 옅은 민트 · `.tag.mute` 회색). */
enum class TagKind { Brand, Soft, Mute }

/** 태그 (목업 `.tag`, components.js `tag()`): 높이 40 알약, 20/24 아주 굵게. '지금'·'추천'·'덜 막혀요'·'예시'·'필수' 같은 짧은 표시. */
@Composable
fun BaroTag(
    text: String,
    modifier: Modifier = Modifier,
    kind: TagKind = TagKind.Mute,
    @DrawableRes icon: Int? = null,
) {
    val colors = BaroTheme.colors
    val (background, content) = when (kind) {
        TagKind.Brand -> colors.brand to colors.onBrand
        TagKind.Soft -> colors.brandSoft to colors.brandText
        TagKind.Mute -> colors.btnGhostBg to colors.t2
    }
    Row(
        modifier = modifier
            .height(Dimens.tagHeight)
            .background(background, CircleShape)
            .padding(horizontal = Dimens.space2),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) BaroIcon(icon, contentDescription = null, size = 24.dp, tint = content)
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = content, maxLines = 1)
    }
}

/** 코스 글자 (목업 `.lt`): 64×64 모서리 20, 옅은 민트 바탕 + 코스 글자(A·B·C). [selected]면 민트 바탕(`.lt.on`). */
@Composable
fun CourseLetter(letter: String, modifier: Modifier = Modifier, selected: Boolean = false) {
    val colors = BaroTheme.colors
    Box(
        modifier = modifier
            .size(Dimens.minTouch)
            .background(if (selected) colors.brand else colors.brandSoft, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = if (selected) colors.onBrand else colors.brandText,
        )
    }
}

/** 별 버튼 (목업 `.star`): 64×64 회색 칸, 켜면 민트 채운 별. 코스를 내 코스에 넣고 빼기(CR-1). */
@Composable
fun StarToggle(on: Boolean, onToggle: () -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    Box(
        modifier = modifier
            .size(Dimens.minTouch)
            .selectable(on, interactionSource = null, indication = BaroPressIndication, role = Role.Checkbox, onClick = onToggle)
            .background(colors.btnGhostBg, RoundedCornerShape(Dimens.radiusS)),
        contentAlignment = Alignment.Center,
    ) {
        BaroIcon(
            icon = if (on) R.drawable.ic_star_f else R.drawable.ic_star,
            contentDescription = contentDescription,
            tint = if (on) colors.brandText else colors.t2,
        )
    }
}

/** 혼잡 점 (목업 `.dot.c0`·`.c1`·`.c2`): 16 원. 0 원활(초록) · 1 보통(주황) · 2 막힘(빨강). */
@Composable
fun CongestionDot(level: Int, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    val color = when (level) {
        0 -> colors.active
        1 -> colors.caution
        else -> colors.danger
    }
    Box(modifier.size(Dimens.space2).background(color, CircleShape))
}

@BaroPreviews
@Composable
private fun ChipsPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                BaroChip("우리 딸", selected = true, onClick = {})
                BaroChip("우리 아들", selected = false, onClick = {})
                BaroChip("오늘 눈 와요", selected = false, onClick = {}, icon = R.drawable.ic_snow)
                BaroChip("잠김", selected = false, onClick = {}, enabled = false)
            }
            ModeChips(selected = 2, onSelect = {}, locked = setOf(4))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                BaroTag("지금", kind = TagKind.Brand)
                BaroTag("덜 막혀요", kind = TagKind.Soft)
                BaroTag("예시")
                BaroTag("통과", kind = TagKind.Soft, icon = R.drawable.ic_check)
                CourseLetter("A")
                CourseLetter("B", selected = true)
                StarToggle(on = true, onToggle = {}, contentDescription = "내 코스")
                CongestionDot(0)
                CongestionDot(1)
                CongestionDot(2)
            }
        }
    }
}
