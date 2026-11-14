package com.barodrive.app.feature.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroSheet
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.SheetAction
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

/** RV-7 시트 경로: ViewModel을 붙인다. [onClose] = 시트 닫기. 기록이 없으면 바로 닫는다. */
@Composable
fun ShareSheetRoute(onClose: () -> Unit, viewModel: ShareSheetViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is ShareEffect.Toast -> toast.show(e.text)
                ShareEffect.Close -> onClose()
            }
        }
    }
    LaunchedEffect(state.missing) { if (state.missing) onClose() }
    if (state.loading || state.missing) return
    ShareSheet(state, onClose = onClose, onAgree = viewModel::agree, onSend = viewModel::send)
}

/**
 * RV-7 가족에게 요약 보내기 시트 (share.js `open`, settings.css `.sh-sheet`): 동의 단계 또는 받는 사람 + 미리보기 카드 + 안내 줄 +
 * [취소] [보내기]. 보내는 동안 버튼을 막고 '보내는 중이에요'를 보인다(움직임 없음).
 */
@Composable
fun ShareSheet(state: ShareSheetUiState, onClose: () -> Unit, onAgree: () -> Unit, onSend: () -> Unit) {
    if (state.step == ShareStep.CONSENT) {
        BaroSheet(
            onDismissRequest = onClose,
            title = stringResource(R.string.sh_consent_title),
            actions = listOf(
                SheetAction(stringResource(R.string.ui_cancel), onClick = onClose),
                SheetAction(stringResource(R.string.sh_agree), onClick = onAgree, kind = BaroButtonKind.Primary),
            ),
        ) { Text(stringResource(R.string.sh_consent_body)) }
        return
    }
    val s = state.summary ?: return
    val colors = BaroTheme.colors
    BaroSheet(
        onDismissRequest = onClose,
        title = stringResource(R.string.sh_title),
        actions = listOf(
            SheetAction(stringResource(R.string.ui_cancel), onClick = onClose, enabled = !state.busy),
            SheetAction(
                stringResource(if (state.wasSent) R.string.sh_resend else R.string.sh_send),
                onClick = onSend,
                kind = BaroButtonKind.Primary,
                enabled = !state.busy,
            ),
        ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            BaroIcon(R.drawable.ic_send, contentDescription = null, tint = colors.brandText)
            Text(stringResource(R.string.sh_to), style = MaterialTheme.typography.bodySmall, color = colors.t2)
            Text(s.to, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = colors.t1)
        }
        PreviewCard(s)
        NoteRow(stringResource(R.string.sh_no_location), icon = R.drawable.ic_shield)
        if (state.wasSent) NoteRow(stringResource(R.string.sh_was), icon = R.drawable.ic_check)
        if (!state.online) NoteRow(stringResource(R.string.sh_offline))
        if (state.busy) SendingRow()
    }
}

/** 보낼 내용 미리보기 카드(`.sh-pv`): 제목 + 날짜, 그리고 '이름 | 값' 줄. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(s: ShareSummary) {
    val colors = BaroTheme.colors
    BaroCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = Dimens.space3), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(s.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = colors.t1)
            if (s.whenText.isNotEmpty()) Text(s.whenText, style = MaterialTheme.typography.labelLarge, color = colors.t3)
        }
        Column {
            s.lines.forEachIndexed { i, l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i > 0) Modifier.lineAt(bottom = false, color = colors.line) else Modifier)
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                ) {
                    Text(l.label, Modifier.width(164.dp), style = MaterialTheme.typography.labelLarge, color = colors.t3)
                    Text(l.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = colors.t1)
                }
            }
        }
    }
}

/** '보내는 중이에요'(`.sh-status`): 점 셋(흐림 1 · .6 · .3, 움직임 없음) + 민트 굵은 글. */
@Composable
private fun SendingRow() {
    val colors = BaroTheme.colors
    Row(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1f, 0.6f, 0.3f).forEach { a -> Box(Modifier.size(14.dp).background(colors.brand.copy(alpha = a), CircleShape)) }
        }
        Text(stringResource(R.string.sh_sending), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = colors.brandText)
    }
}

private val previewSummary = ShareSummary(
    recordId = "sess-a", mode = 2, title = "2단계 3회차 · 초등학교 뒤 골목", whenText = "11월 11일 09:12", to = "아버지",
    lines = listOf(
        ShareLine("trip", "시간·거리", "18분 · 4.2km"),
        ShareLine("tasks", "과제", "83% 해냈어요 (20/24)"),
        ShareLine("good", "잘한 것", "회전 30m 전 깜빡이 18/21번"),
        ShareLine("next", "다음 과제", "30 구역 지키기"),
        ShareLine("f2", "급제동", "1번 · 아직 확인 전"),
        ShareLine("weather", "날씨", "빗길"),
    ),
)

@BaroScreenPreviews
@Composable
private fun ShareSheetPreview() {
    BaroTheme {
        ShareSheet(ShareSheetUiState(loading = false, summary = previewSummary, online = false), onClose = {}, onAgree = {}, onSend = {})
    }
}

@BaroScreenPreviews
@Composable
private fun ShareSheetSendingPreview() {
    BaroTheme {
        ShareSheet(ShareSheetUiState(loading = false, summary = previewSummary, wasSent = true, busy = true), onClose = {}, onAgree = {}, onSend = {})
    }
}

@BaroScreenPreviews
@Composable
private fun ShareConsentPreview() {
    BaroTheme { ShareSheet(ShareSheetUiState(loading = false, step = ShareStep.CONSENT), onClose = {}, onAgree = {}, onSend = {}) }
}
