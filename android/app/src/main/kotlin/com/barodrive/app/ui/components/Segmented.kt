package com.barodrive.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/** 고르기 칸 하나. [enabled]가 거짓이면 테두리만 보이고 누르면 이유만 알린다. */
@Immutable
data class SegmentOption(val label: String, val enabled: Boolean = true)

/**
 * 칸 고르기 (목업 `.fbseg` 3칸 고르기를 일반화, components.js `fbseg()`): 같은 폭 칸, 칸 높이 64 이상, 칸 사이 24, 24/28 굵게.
 * 고른 칸은 민트. 고를 수 없는 칸은 테두리만이고 누르면 값은 그대로 [onBlocked](이유 토스트).
 * [locked]면 고른 칸에 자물쇠가 붙고 나머지가 모두 잠긴다(안전 항목 '늘 바로'). 잠겼을 때는 어느 칸을 눌러도 [onBlocked].
 * 쓰는 곳: FB-1·FB-2 음성 피드백 3칸(바로 피드백 · 주행 완료 후 피드백 · 안 하기), PK-2 가상 기어 [전진 D | 후진 R], CR-2 탭.
 */
@Composable
fun SegmentedControl(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onBlocked: (Int) -> Unit = {},
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusS)
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
        options.forEachIndexed { index, option ->
            val on = index == selectedIndex
            val blocked = if (locked) !on else !option.enabled
            val (background, content, border) = when {
                on -> Triple(colors.brand, colors.onBrand, Color.Transparent)
                blocked -> Triple(Color.Transparent, colors.t3, colors.lineStrong)
                else -> Triple(colors.btnGhostBg, colors.t2, Color.Transparent)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.buttonS)
                    .selectable(
                        selected = on,
                        interactionSource = null,
                        indication = if (blocked || locked) null else BaroPressIndication,
                        role = Role.RadioButton,
                        onClick = {
                            when {
                                blocked || locked -> onBlocked(index)
                                !on -> onSelect(index)
                            }
                        },
                    )
                    .then(if (blocked) Modifier.semantics { disabled() } else Modifier)
                    .background(background, shape)
                    .border(2.dp, border, shape)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space1, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (locked && on) BaroIcon(R.drawable.ic_lock, contentDescription = null, size = 28.dp, tint = content)
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = content,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@BaroPreviews
@Composable
private fun SegmentedControlPreview() {
    BaroTheme {
        val fb = listOf(
            SegmentOption(stringResource(R.string.feedback_now)),
            SegmentOption(stringResource(R.string.feedback_after)),
            SegmentOption(stringResource(R.string.feedback_off)),
        )
        Column(Modifier.padding(Dimens.edge), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            SegmentedControl(fb, selectedIndex = 0, onSelect = {})
            SegmentedControl(listOf(fb[0], fb[1].copy(enabled = false), fb[2]), selectedIndex = 2, onSelect = {})
            SegmentedControl(fb, selectedIndex = 0, onSelect = {}, locked = true)
            // 막힌 칸을 누르면 화면이 띄우는 이유 토스트 글(components.js LOCKED_REASON · NA_REASON).
            NoteRow(stringResource(R.string.feedback_locked_reason), icon = R.drawable.ic_lock)
            NoteRow(stringResource(R.string.feedback_blocked_reason))
            SegmentedControl(listOf(SegmentOption("전진 D"), SegmentOption("후진 R")), selectedIndex = 1, onSelect = {})
        }
    }
}
