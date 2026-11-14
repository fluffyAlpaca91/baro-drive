package com.barodrive.app.feature.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.GroupHeader
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.ModeChips
import com.barodrive.app.ui.components.fadeWhenMoreBelow
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.core.model.Delivery

/** FB-1 경로 화면: ViewModel을 붙이고 토스트를 띄운다. */
@Composable
fun FeedbackSettingsRoute(
    onBack: () -> Unit,
    onMyPresets: () -> Unit,
    viewModel: FeedbackSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) { viewModel.toasts.collect { toast.show(it) } }
    FeedbackSettingsScreen(
        state = state,
        onBack = onBack,
        onSelectMode = viewModel::selectMode,
        onChoose = viewModel::choose,
        onBlocked = viewModel::blocked,
        onReset = viewModel::reset,
        onMyPresets = onMyPresets,
    )
}

/**
 * FB-1 음성 피드백 설정(모드별) (목업 s10, feedback.js FB-1): 모드 칩 1~8 · 모드 이름·말하는 양·범례 · 묶음별 항목 줄(목록만 스크롤) ·
 * 하단 [기본값으로] (+ 5번에서만 [내 음성 피드백 설정]).
 */
@Composable
fun FeedbackSettingsScreen(
    state: FeedbackSettingsUiState,
    onBack: () -> Unit,
    onSelectMode: (Int) -> Unit,
    onChoose: (String, Delivery) -> Unit,
    onBlocked: (FeedbackRow, Delivery) -> Unit,
    onReset: () -> Unit,
    onMyPresets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(title = stringResource(R.string.fb1_title), sub = stringResource(R.string.fb1_sub), nav = AppBarNav.Back, onNav = onBack)
        },
        bottomBar = {
            BaroButton(
                text = stringResource(R.string.fb1_reset),
                onClick = onReset,
                modifier = Modifier.weight(1f),
                icon = R.drawable.ic_refresh,
            )
            if (state.showMyPresets) {
                BaroButton(
                    text = stringResource(R.string.fb1_to_my),
                    onClick = onMyPresets,
                    modifier = Modifier.weight(1f),
                    kind = BaroButtonKind.Primary,
                )
            }
        },
        scrollable = false,
    ) {
        ModeChips(selected = state.mode, onSelect = onSelectMode)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.title, style = MaterialTheme.typography.titleSmall)
            if (state.talk.isNotEmpty()) {
                Text(state.talk, style = MaterialTheme.typography.bodySmall, color = colors.t2)
            }
            state.extraNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.t3) }
            Legend()
        }
        val scroll = rememberScrollState()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadeWhenMoreBelow(scroll)
                .verticalScroll(scroll),
        ) {
            state.groups.forEach { group ->
                GroupHeader(group.title)
                group.rows.forEach { row ->
                    FeedbackRowItem(row, onSelect = { onChoose(row.id, it) }, onBlocked = { onBlocked(row, it) })
                }
            }
        }
    }
}

/** 범례(feedback.js LEGEND, 목업 `.fb-key`): 굵은 이름 + 설명 세 쌍, 20/24 3차 글자. */
@Composable
private fun Legend() {
    val colors = BaroTheme.colors
    val pairs = listOf(
        stringResource(R.string.feedback_now) to stringResource(R.string.fb1_legend_now),
        stringResource(R.string.feedback_after) to stringResource(R.string.fb1_legend_after),
        stringResource(R.string.feedback_off) to stringResource(R.string.fb1_legend_off),
    )
    val text = buildAnnotatedString {
        pairs.forEachIndexed { i, (name, desc) ->
            if (i > 0) append("   ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.t2)) { append(name) }
            append(" ")
            append(desc)
        }
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = colors.t3)
}

private val previewState = FeedbackSettingsUiState(
    loading = false,
    mode = 1,
    title = "1 · 차량 감 익히기",
    talk = "많이 말해요. 하기 전에 미리 알려 주고, 하고 나서 결과도 말해요.",
    groups = listOf(
        FeedbackGroup(FeedbackGroupId.SAFETY, "안전 항목 · 늘 바로 피드백", listOf(FeedbackSamples.e1)),
        FeedbackGroup(FeedbackGroupId.RULE, "늘 지키는 규칙", listOf(FeedbackSamples.f2)),
        FeedbackGroup(FeedbackGroupId.USED, "이 모드의 피드백 항목 3개", listOf(FeedbackSamples.j3, FeedbackSamples.b1, FeedbackSamples.c1)),
    ),
)

@BaroScreenPreviews
@Composable
private fun FeedbackSettingsPreview() {
    BaroTheme {
        FeedbackSettingsScreen(previewState, onBack = {}, onSelectMode = {}, onChoose = { _, _ -> }, onBlocked = { _, _ -> }, onReset = {}, onMyPresets = {})
    }
}

@BaroWideScreenPreviews
@Composable
private fun FeedbackSettingsWidePreview() {
    BaroTheme {
        FeedbackSettingsScreen(
            previewState.copy(mode = 5, title = "5 · 자율 모드", extraNote = FeedbackRules.MODE5_NOTE),
            onBack = {}, onSelectMode = {}, onChoose = { _, _ -> }, onBlocked = { _, _ -> }, onReset = {}, onMyPresets = {},
        )
    }
}
