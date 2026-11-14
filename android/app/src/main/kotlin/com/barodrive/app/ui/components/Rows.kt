package com.barodrive.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/**
 * 목록 줄 (목업 `.row`, components.js `listRow()`): 최소 높이 88, 아이콘(40) 또는 [leading], 제목(26/34 굵게)·보조 글(24/28),
 * 오른쪽 [trailing], 누를 수 있으면 끝에 꺾쇠. 아래 구분선 2. 정차 화면 목록(ST-1 설정·ST-2 기록·CR-2 장소)에 쓴다.
 */
@Composable
fun BaroListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    @DrawableRes icon: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    divider: Boolean = true,
) {
    val colors = BaroTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.listRowMinHeight)
            .then(if (onClick != null) Modifier.pressClickable(enabled = true, onClick = onClick, role = Role.Button) else Modifier)
            .then(if (divider) Modifier.lineAt(bottom = true, color = colors.line) else Modifier)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) BaroIcon(icon, contentDescription = null, tint = colors.t2)
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelLarge, color = colors.t3)
        }
        trailing?.invoke()
        if (chevron) BaroIcon(R.drawable.ic_chev, contentDescription = null, size = Dimens.iconS, tint = colors.t3)
    }
}

/** 묶음 제목 (목업 `.grp-h`): 목록 안 묶음 이름, 24/28 굵게 3차 글자. 예: FB-1 '안전 항목 · 늘 바로 피드백'. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .padding(top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = BaroTheme.colors.t3,
    )
}

/** 구역 제목 (목업 `.sec-h`): 28/36 아주 굵은 제목 + 옆에 20/24 설명. 예: 홈 '단계 연습 · 잘하면 다음 단계가 열려요'. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, sub: String? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        Text(
            title,
            modifier = Modifier
                .alignByBaseline()
                .semantics { heading() },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
        )
        if (sub != null) {
            Text(sub, Modifier.alignByBaseline(), style = MaterialTheme.typography.labelMedium, color = BaroTheme.colors.t3)
        }
    }
}

/** [InfoRow] 보조 글 색: 보통(3차) · 됨(민트 굵게, `small.ok`) · 경고(위험 색 굵게, PR-1 `small.warn`). */
enum class InfoTone { Normal, Ok, Warn }

/** 정보 줄 (목업 `.irow`): 아이콘(40, 보조 글자색) + 제목(26/34) + 보조 글. 예: PR-1 '운전석 벨트 · 맸어요'. */
@Composable
fun InfoRow(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    subTone: InfoTone = InfoTone.Normal,
) {
    val colors = BaroTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
        BaroIcon(icon, contentDescription = null, tint = colors.t2)
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            if (sub != null) {
                val style = MaterialTheme.typography.labelLarge
                when (subTone) {
                    InfoTone.Normal -> Text(sub, style = style, color = colors.t3)
                    InfoTone.Ok -> Text(sub, style = style.copy(fontWeight = FontWeight.Bold), color = colors.brandText)
                    InfoTone.Warn -> Text(sub, style = style.copy(fontWeight = FontWeight.Bold), color = colors.danger)
                }
            }
        }
    }
}

/** 안내 줄 (목업 `.note`): 아이콘(32) + 24/28 3차 글자. 예: '앱은 면허를 확인하지 않아요.' */
@Composable
fun NoteRow(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_info) {
    val color = BaroTheme.colors.t3
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BaroIcon(icon, contentDescription = null, size = Dimens.iconS, tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * 체크 칸 (목업 `.ck`): 64×64 모서리 16. 꺼짐은 3px 테두리, 켜짐은 민트 바탕 + 체크. 그림만 그린다 — 누르기는 [CheckRow]가 받는다.
 * [enabled]가 거짓이면 테두리가 옅다(`.ck[aria-disabled]`).
 */
@Composable
fun BaroCheckBox(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusS)
    Box(
        modifier = modifier
            .size(Dimens.minTouch)
            .background(if (checked) colors.brand else Color.Transparent, shape)
            .border(3.dp, if (checked) Color.Transparent else if (enabled) colors.t3 else colors.lineStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) BaroIcon(R.drawable.ic_check, contentDescription = null, tint = colors.onBrand)
    }
}

/**
 * 체크 줄 (목업 `.ckr`, components.js `checkRow()`): 체크 칸 + 제목(26/34 굵게) + 보조 글. 줄 어디를 눌러도 바뀐다.
 * [onCheckedChange]가 null이면 차가 확인한 줄(`.ckr.auto`: 누를 수 없고 보조 글이 민트 굵게, 예: LC-1 '조수석 · 탑승 확인됨').
 * 막혔으면([enabled] 거짓) 바뀌지 않고 [onDisabledClick](이유 토스트)만 부른다. 위 구분선은 첫 줄에서 [topDivider] = false.
 * [trailing]은 줄의 오른쪽 끝(가운데 맞춤 줄 안 — 위에 붙이려면 `Modifier.align(Alignment.Top)`, ON-2 태그).
 */
@Composable
fun CheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
    topDivider: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    val auto = onCheckedChange == null
    val toggle = when {
        auto -> Modifier.semantics(mergeDescendants = true) {}
        enabled -> Modifier.toggleable(
            value = checked,
            interactionSource = null,
            indication = BaroPressIndication,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        )
        else -> Modifier
            .semantics { disabled() }
            .toggleable(
                value = checked,
                interactionSource = null,
                indication = null,
                role = Role.Checkbox,
                onValueChange = { onDisabledClick?.invoke() },
            )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(toggle)
            .then(if (topDivider) Modifier.lineAt(bottom = false, color = colors.line) else Modifier)
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroCheckBox(checked = checked, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (auto) FontWeight.Bold else FontWeight.Normal),
                    color = if (auto) colors.brandText else colors.t3,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * 스위치 (ST-4 `.sw`, 목업에 없어 토큰으로 만든 것): 96×56 알약, 끔 = 회색 바탕 + 왼쪽 회색 손잡이, 켬 = 민트 바탕 + 오른쪽 손잡이.
 * 움직임 없음. 그림만 그린다 — 누르기는 [SwitchRow]가 받는다.
 */
@Composable
fun BaroSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    Box(
        modifier = modifier
            .size(Dimens.switchWidth, Dimens.switchHeight)
            .background(if (checked) colors.brand else colors.btnGhostBg, CircleShape)
            .border(2.dp, if (checked) Color.Transparent else colors.lineStrong, CircleShape),
    ) {
        Box(
            Modifier
                .offset(x = if (checked) 48.dp else Dimens.space1, y = Dimens.space1)
                .size(Dimens.switchKnob)
                .background(if (checked) colors.onBrand else colors.t3, CircleShape),
        )
    }
}

/**
 * 스위치 줄 (ST-4 `.st-sw`): 제목(26/34 굵게, 옆에 [titleTrailing] 태그) + 보조 글 + 오른쪽 스위치. 줄 어디를 눌러도 바뀐다.
 * 예: '연습 기록 저장(필수)', '가족에게 요약 보내기'.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
    divider: Boolean = true,
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    val colors = BaroTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = null,
                indication = BaroPressIndication,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .then(if (divider) Modifier.lineAt(bottom = true, color = colors.line) else Modifier)
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                titleTrailing?.invoke()
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelLarge, color = colors.t3)
        }
        CompositionLocalProvider(LocalContentColor provides colors.t1) { BaroSwitch(checked) }
    }
}

/** 줄 위(`border-top`)나 아래(`border-bottom`)에 2dp 선. */
internal fun Modifier.lineAt(bottom: Boolean, color: Color): Modifier = drawBehind {
    val h = 2.dp.toPx()
    drawRect(color, topLeft = Offset(0f, if (bottom) size.height - h else 0f), size = Size(size.width, h))
}

@BaroPreviews
@Composable
private fun RowsPreview() {
    BaroTheme {
        Column(Modifier.padding(Dimens.edge)) {
            SectionHeader("단계 연습", sub = "잘하면 다음 단계가 열려요")
            GroupHeader("안전 항목 · 늘 바로 피드백")
            BaroListRow(title = "코치", sub = "아빠/배우자", icon = R.drawable.ic_voice, onClick = {})
            BaroListRow(title = "내 차", sub = "차에서 읽어요", icon = R.drawable.ic_car_top, trailing = { Text("아반떼") })
            CheckRow("연습 기록 저장", checked = true, onCheckedChange = {}, sub = "이 차 안에만 최근 30회차까지 둬요", topDivider = false)
            CheckRow("가족에게 요약 보내기", checked = false, onCheckedChange = {}, sub = "보낼지는 회차마다 내가 골라요")
            CheckRow("조수석", checked = true, onCheckedChange = null, sub = "탑승 확인됨")
            SwitchRow("가족에게 요약 보내기", checked = true, onCheckedChange = {}, sub = "요약 숫자만 가요")
            SwitchRow("보험사에 요약 제공", checked = false, onCheckedChange = {}, titleTrailing = { BaroTag("건마다") })
            InfoRow(R.drawable.ic_belt, title = "운전석 벨트", sub = "맸어요", subTone = InfoTone.Ok)
            InfoRow(R.drawable.ic_pbrake, title = "사이드브레이크", sub = "채워져 있어요")
            NoteRow("앱은 면허를 확인하지 않아요.")
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                BaroCheckBox(checked = false)
                BaroCheckBox(checked = true)
                BaroCheckBox(checked = false, enabled = false)
                BaroSwitch(checked = false)
                BaroSwitch(checked = true)
            }
        }
    }
}
