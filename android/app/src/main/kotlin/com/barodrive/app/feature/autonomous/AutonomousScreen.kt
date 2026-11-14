package com.barodrive.app.feature.autonomous

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.CoachFace
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.ReviewHeader
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.feature.share.ShareLinkInfo
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.ScoreFootnote
import com.barodrive.core.model.ScoreTableRow
import com.barodrive.core.model.ShareStatus

/** RV-4 경로 화면: ViewModel을 붙이고 토스트·RV-7 열기를 받는다. */
@Composable
fun AutonomousRoute(
    onClose: () -> Unit,
    onHome: () -> Unit,
    onMyPresets: () -> Unit,
    onShare: (String) -> Unit,
    viewModel: AutonomousViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is AutonomousEffect.Toast -> toast.show(e.text)
                is AutonomousEffect.OpenShare -> onShare(e.recordId)
            }
        }
    }
    AutonomousScreen(
        state = state,
        onClose = onClose,
        onHome = onHome,
        onMyPresets = onMyPresets,
        onChooseF2 = viewModel::chooseF2,
        onShare = viewModel::shareClick,
    )
}

/**
 * RV-4 자율 모드 도착·점수 (목업 s07, autonomous.js RV-4): 앱 바 '도착했어요' + 부제 · 점수 고리 + 'N점 깎였어요' · 지난 점수 · 코치 말풍선 ·
 * '어디서 깎였나요' 표(+ 못 읽은 신호 줄 · 발밑 줄) · 급제동 본인 확인 · 날씨 팁 · 가족에게 요약 보내기 ›.
 * 하단 [홈으로] [내 음성 피드백 설정], 읽기 전용은 [닫기]. 본문만 스크롤한다.
 */
@Composable
fun AutonomousScreen(
    state: AutonomousUiState,
    onClose: () -> Unit,
    onHome: () -> Unit,
    onMyPresets: () -> Unit,
    onChooseF2: (String, F2Choice) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(
                title = if (state.found) state.title else stringResource(R.string.au_title_missing),
                sub = state.sub.takeIf { state.found && it.isNotEmpty() },
                nav = AppBarNav.Close,
                onNav = onClose,
            )
        },
        bottomBar = {
            ReviewActions(
                readOnly = state.readOnly || (!state.found && !state.loading),
                onClose = onClose,
                onHome = onHome,
                primary = stringResource(R.string.au_to_my) to onMyPresets,
            )
        },
    ) {
        when {
            state.loading -> Unit
            !state.found -> RecordMissing()
            else -> {
                ScoreSection(state)
                Deductions(state.table, state.excludedText, state.footnotes)
                F2CheckCard(state.f2, onChoose = onChooseF2)
                ReviewTips(state.tips)
                ShareLinkRow(state.share.state, state.share.text, onClick = onShare)
            }
        }
    }
}

/** 점수 고리 + 오른쪽 글(목업 s07 `.score`). */
@Composable
private fun ScoreSection(state: AutonomousUiState) {
    val colors = BaroTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4), verticalAlignment = Alignment.CenterVertically) {
        ScoreRingView(state.total)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.au_score_label), style = MaterialTheme.typography.labelLarge, color = colors.t3)
            Text(state.lostText, style = MaterialTheme.typography.titleMedium)
            state.prevText?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.t2) }
            if (state.lineText.isNotEmpty()) CoachBubble(state.coach, state.lineText)
        }
    }
}

/**
 * 점수 고리 (목업 s07 `.ring`): 220 상자, 굵기 20 — 바탕 원(진한 선 색) 위에 점수만큼 민트 호(끝 둥글게, 12시부터).
 * 가운데 큰 숫자(88) + '/ 100점'. 0점이면 민트 호가 없다.
 */
@Composable
internal fun ScoreRingView(total: Int, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    val desc = stringResource(R.string.au_score_desc, total)
    Box(modifier.size(220.dp).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            val stroke = 20.dp.toPx()
            val r = 94.dp.toPx()
            val topLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(r * 2, r * 2)
            drawCircle(colors.lineStrong, radius = r, center = center, style = Stroke(stroke))
            if (total > 0) {
                drawArc(
                    color = colors.brand,
                    startAngle = -90f,
                    sweepAngle = 360f * total.coerceIn(0, 100) / 100f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                total.toString(),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 88.sp, letterSpacing = (-2).sp),
                textAlign = TextAlign.Center,
            )
            Text(stringResource(R.string.au_score_of), style = MaterialTheme.typography.labelLarge, color = colors.t3)
        }
    }
}

/** '어디서 깎였나요' 표 + 못 읽은 신호 줄 + 발밑 줄(목업 s07 `.ded` · `.foot`). 0번 줄은 엔진이 묶어 맨 끝 한 줄로 준다. */
@Composable
private fun Deductions(table: List<ScoreTableRow>, excludedText: String?, footnotes: List<ScoreFootnote>) {
    val colors = BaroTheme.colors
    BaroCard(Modifier.fillMaxWidth()) {
        ReviewHeader(stringResource(R.string.au_deductions))
        Column(Modifier.padding(top = 8.dp)) {
            table.forEachIndexed { i, r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 62.dp)
                        .then(if (i > 0) Modifier.lineAt(bottom = false, color = colors.line) else Modifier)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(r.count, style = MaterialTheme.typography.labelLarge, color = colors.t3, textAlign = TextAlign.End)
                    Text(
                        r.points,
                        Modifier.width(72.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = if (r.zero) colors.brandText else colors.t1,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        excludedText?.let { FootLine(it, iconId = "i-info", strong = true) }
        footnotes.forEach { FootLine(it.text, iconId = it.icon, muted = it.muted) }
    }
}

/** 표 아래 한 줄(`.foot`): 아이콘 32 + 24/28 글. 못 읽은 신호 줄은 주의 색 아이콘 + 본문 색 굵게. */
@Composable
private fun FootLine(text: String, iconId: String?, strong: Boolean = false, muted: Boolean = false) {
    val colors = BaroTheme.colors
    Row(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(iconFor(iconId), contentDescription = null, size = Dimens.iconS, tint = if (strong) colors.caution else colors.brandText)
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal),
            color = when {
                strong -> colors.t1
                muted -> colors.t3
                else -> colors.t2
            },
        )
    }
}

/** 코치 아바타가 든 강한 말풍선(목업 s07 `.score .bub.strong`: 옅은 민트 + 민트 테두리, 아바타 64 + 26/34 글). */
@Composable
internal fun CoachBubble(face: CoachFace, text: String, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Row(
        modifier = modifier
            .background(colors.brandSoft, shape)
            .border(2.dp, colors.brandText, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoachAvatar(face, size = Dimens.avatar)
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, lineBreak = LineBreak.Heading),
        )
    }
}

private val previewState = AutonomousUiState(
    loading = false,
    found = true,
    recordId = "sess-1",
    title = "도착했어요",
    sub = "자율 모드 · 집 → 마트 · 12.4km · 31분",
    total = 86,
    lostText = "14점 깎였어요",
    prevText = "지난번 82점 → 오늘 86점",
    lineText = "마트까지 잘 왔어. 깜빡이만 조금 일찍 켜 보자.",
    table = listOf(
        ScoreTableRow("C2.late", label = "회전 전 깜빡이 '늦음'", count = "1번", points = "−3"),
        ScoreTableRow("C2.almost", label = "회전 전 깜빡이 '거의'", count = "2번", points = "−2"),
        ScoreTableRow("C4", label = "깜빡이 켜자마자 꺾음", count = "1번", points = "−2"),
        ScoreTableRow("BF", label = "급출발·급정지", count = "1번", points = "−3"),
        ScoreTableRow("G1", label = "직진할 때 핸들 흔들림", count = "기준보다 큼", points = "−4"),
        ScoreTableRow("zero", label = "제한속도 초과 · 깜빡이 없이 회전", count = "0번", points = "0", zero = true),
    ),
    footnotes = listOf(
        ScoreFootnote("i-shield", "위험을 피한 급제동 1번은 감점하지 않았어요."),
        ScoreFootnote("i-info", "늦음 = 회전 30m 안에서 켬 · 거의 = 30~39m", muted = true),
    ),
    f2 = listOf(F2RowUi("f2-1", "56km/h에서 4.3초 만에 정지", null)),
    share = ShareLinkInfo(ShareStatus.NONE, "가족에게 요약 보내기"),
)

@BaroScreenPreviews
@Composable
private fun AutonomousPreview() {
    BaroTheme { AutonomousScreen(previewState, onClose = {}, onHome = {}, onMyPresets = {}, onChooseF2 = { _, _ -> }, onShare = {}) }
}

@BaroScreenPreviews
@Composable
private fun AutonomousMissingPreview() {
    BaroTheme {
        AutonomousScreen(
            AutonomousUiState(loading = false, found = false, readOnly = true),
            onClose = {}, onHome = {}, onMyPresets = {}, onChooseF2 = { _, _ -> }, onShare = {},
        )
    }
}

@BaroWideScreenPreviews
@Composable
private fun AutonomousWidePreview() {
    BaroTheme { AutonomousScreen(previewState, onClose = {}, onHome = {}, onMyPresets = {}, onChooseF2 = { _, _ -> }, onShare = {}) }
}
