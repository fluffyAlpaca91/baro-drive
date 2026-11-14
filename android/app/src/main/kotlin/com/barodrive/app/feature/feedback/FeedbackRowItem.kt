package com.barodrive.app.feature.feedback

import android.content.res.Configuration
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.barodrive.app.R
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.SegmentOption
import com.barodrive.app.ui.components.SegmentedControl
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.theme.BaroPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Delivery
import com.barodrive.core.model.DeliveryReason

/**
 * 항목 한 줄 (feedback.js `fbRow`, 목업 s10 `.row.fb`): 이름(26/34 굵게) + 작은 글(24/28) + 3칸 고르기(바로 피드백 · 주행 완료 후 피드백 ·
 * 안 하기). 고를 수 없는 칸은 테두리만이고 누르면 [onBlocked](이유 토스트). 잠긴 줄(안전 항목)은 고른 칸에 자물쇠.
 * 규칙 줄(F2, 결정-22)은 칸 없이 방패 아이콘 + 한 줄 설명. FB-1·FB-2가 같이 쓴다.
 */
@Composable
internal fun FeedbackRowItem(
    row: FeedbackRow,
    onSelect: (Delivery) -> Unit,
    onBlocked: (Delivery) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    val line = Modifier
        .fillMaxWidth()
        .lineAt(bottom = true, color = colors.line)
        .padding(vertical = 20.dp)
    if (row.kind == FeedbackRowKind.RULE) {
        Row(
            modifier = modifier.then(line),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BaroIcon(R.drawable.ic_shield, contentDescription = null, size = Dimens.iconS, tint = colors.brandText)
            RowTitle(row.name, row.note, noteColorT2 = true)
        }
        return
    }
    Column(modifier = modifier.then(line), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RowTitle(row.name, row.note, noteColorT2 = false)
        val labels = listOf(
            stringResource(R.string.feedback_now),
            stringResource(R.string.feedback_after),
            stringResource(R.string.feedback_off),
        )
        SegmentedControl(
            options = FeedbackRules.OSX.mapIndexed { i, v -> SegmentOption(labels[i], enabled = v in row.allowed) },
            selectedIndex = FeedbackRules.OSX.indexOf(row.value),
            onSelect = { onSelect(FeedbackRules.OSX[it]) },
            locked = row.locked,
            onBlocked = { onBlocked(FeedbackRules.OSX[it]) },
        )
    }
}

@Composable
private fun RowTitle(name: String, note: String, noteColorT2: Boolean) {
    val colors = BaroTheme.colors
    Column {
        Text(name, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
        if (note.isNotEmpty()) {
            Text(note, style = MaterialTheme.typography.labelLarge, color = if (noteColorT2) colors.t2 else colors.t3)
        }
    }
}

/** 미리보기·테스트용 줄 몇 개(엔진 항목 정의 없이). */
internal object FeedbackSamples {
    val e1 = FeedbackRow(
        id = "E1", name = "제한속도 초과", kind = FeedbackRowKind.SEG, value = Delivery.NOW, reason = DeliveryReason.FIXED,
        def = Delivery.NOW, allowed = listOf(Delivery.NOW), locked = true,
        lock = FeedbackRules.OSX.associateWith { "안전 항목이라 늘 바로 알려 줘요" }, note = FeedbackRules.SAFE_NOTE,
    )
    val f2 = FeedbackRow(
        id = "F2", name = "위험 회피 급제동", kind = FeedbackRowKind.RULE, value = Delivery.NOW, reason = DeliveryReason.FIXED,
        def = Delivery.NOW, allowed = emptyList(), locked = true, lock = FeedbackRules.OSX.associateWith { null },
        note = "급제동 뒤 30초는 조용히, 정차 후 직접 확인",
    )
    val j3 = FeedbackRow(
        id = "J3", name = "날씨 안내", kind = FeedbackRowKind.SEG, value = Delivery.NOW, reason = DeliveryReason.DEFAULT,
        def = Delivery.NOW, allowed = FeedbackRules.OSX, locked = false, lock = FeedbackRules.OSX.associateWith { null },
        note = "비·눈 오는 날에만 말해요",
    )
    val c1 = FeedbackRow(
        id = "C1", name = "회전 전 미리 깜빡이 안내", kind = FeedbackRowKind.SEG, value = Delivery.OFF, reason = DeliveryReason.FIRST2,
        def = Delivery.NOW, allowed = listOf(Delivery.NOW, Delivery.OFF), locked = false,
        lock = mapOf(Delivery.NOW to null, Delivery.AFTER to "이 항목은 달리는 중에만 쓸 수 있어요", Delivery.OFF to null),
        note = "3회차부터는 말하지 않아요",
    )
    val b1 = FeedbackRow(
        id = "B1", name = "부드러운 출발", kind = FeedbackRowKind.SEG, value = Delivery.AFTER, reason = DeliveryReason.FADING,
        def = Delivery.NOW, allowed = FeedbackRules.OSX, locked = false, lock = FeedbackRules.OSX.associateWith { null },
        note = FeedbackRules.FADING_NOTE,
    )
}

@BaroPreviews
@Composable
private fun FeedbackRowItemPreview() {
    BaroTheme {
        Column(Modifier.padding(horizontal = Dimens.edge)) {
            listOf(FeedbackSamples.e1, FeedbackSamples.f2, FeedbackSamples.j3, FeedbackSamples.c1, FeedbackSamples.b1).forEach {
                FeedbackRowItem(it, onSelect = {}, onBlocked = {})
            }
        }
    }
}
