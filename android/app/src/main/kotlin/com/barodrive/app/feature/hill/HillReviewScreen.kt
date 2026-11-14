package com.barodrive.app.feature.hill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.parking.ModeReviewModel
import com.barodrive.app.feature.parking.ModeReviews
import com.barodrive.app.feature.parking.ModeScreenPreviews
import com.barodrive.app.feature.parking.ReviewActions
import com.barodrive.app.feature.parking.ReviewCourse
import com.barodrive.app.feature.parking.ReviewEffect
import com.barodrive.app.feature.parking.ReviewHero
import com.barodrive.app.feature.parking.ReviewLinks
import com.barodrive.app.feature.parking.ReviewMissing
import com.barodrive.app.feature.parking.rememberTextRes
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BadgeKind
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroTextButton
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.ReviewHeader
import com.barodrive.app.ui.components.StatusBadge
import com.barodrive.app.ui.components.WeatherTip
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Coach
import com.barodrive.core.model.F2Check
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.HillDownSummary
import com.barodrive.core.model.HillParkResult
import com.barodrive.core.model.HillResult
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillUpSummary
import com.barodrive.core.model.NextTask
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.TaskResult
import com.barodrive.core.model.TurnDir

/**
 * RV-6 경로 화면. [onPredrive] = [다시 하기](같은 코스로 준비 세션을 만든 뒤 PR-1), [onCourses] = 코스가 없을 때 CR-1(모드),
 * [onClose] = 앱 바 닫기·[닫기], [onShare] = 요약 보내기 시트(없으면 null).
 */
@Composable
fun HillReviewRoute(
    onHome: () -> Unit,
    onPredrive: () -> Unit,
    onCourses: (mode: Int) -> Unit,
    onClose: (readOnly: Boolean) -> Unit,
    onShare: ((recordId: String) -> Unit)?,
    viewModel: HillReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val res = rememberTextRes()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ReviewEffect.Toast -> toast.show(res.text(effect.text))
                ReviewEffect.ToPredrive -> onPredrive()
                is ReviewEffect.ToCourses -> onCourses(effect.mode)
            }
        }
    }
    val readOnly = state.model?.readOnly ?: viewModel.readOnly
    HillReviewScreen(
        state = state,
        onHome = onHome,
        onAgain = viewModel::again,
        onClose = { onClose(readOnly) },
        onF2 = viewModel::setF2Choice,
        onSaveCourse = viewModel::saveCourse,
        onShare = onShare?.let { share -> { state.model?.record?.id?.let(share) } },
        onQueuedShare = { toast.show(res.text(R.string.pk_rv_share_queued_toast)) },
    )
}

/**
 * RV-6 언덕 주행 돌아보기 (프로토타입 hill.js RV-6 + 리뷰 틀, 설계 4.2 RV-6): 앱 바(닫기 · '언덕 주행 돌아보기' · '언덕 · 1회차 · 6분 · 2.2km') ·
 * 코치 히어로(rv.hill, 다음 과제 자리) · 결과 카드 3개(오르막 출발 · 내리막 감속 · 언덕 주차) · 안전 줄(뒤로 밀렸을 때) · 다음 과제 하나 ·
 * 급제동 본인 확인 · 날씨 팁 · 내 코스·요약 보내기 줄 · 하단 [홈으로] [다시 하기](읽기 전용·기록 없음은 [닫기]). 본문만 스크롤한다.
 */
@Composable
fun HillReviewScreen(
    state: HillReviewUiState,
    onHome: () -> Unit,
    onAgain: () -> Unit,
    onClose: () -> Unit,
    onF2: (String, F2Choice) -> Unit,
    onSaveCourse: () -> Unit,
    onShare: (() -> Unit)?,
    onQueuedShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = rememberTextRes()
    val m = state.model
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(
                title = stringResource(R.string.hl_rv_title),
                sub = m?.let { ModeReviews.subText(it, res) },
                nav = AppBarNav.Close,
                onNav = onClose,
            )
        },
        bottomBar = {
            ReviewActions(
                readOnly = m == null || m.readOnly,
                primaryLabel = stringResource(R.string.hl_rv_again),
                onHome = onHome,
                onPrimary = onAgain,
                onClose = onClose,
            )
        },
    ) {
        when {
            state.loading -> Unit
            m == null -> ReviewMissing()
            else -> HillReviewBody(m, onF2, onSaveCourse, onShare, onQueuedShare)
        }
    }
}

@Composable
private fun HillReviewBody(
    m: ModeReviewModel,
    onF2: (String, F2Choice) -> Unit,
    onSaveCourse: () -> Unit,
    onShare: (() -> Unit)?,
    onQueuedShare: () -> Unit,
) {
    val res = rememberTextRes()
    val rec = m.record
    ReviewHero(m.coach, HillReviewText.heroText(rec, m.coach, m.honorific))
    val rm = HillReviewText.resultModel(rec, res)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        rm.cards.forEach { ResultCard(it) }
    }
    if (rm.retry) RetryLine(rm.retryText)
    HillReviewText.nextLabel(rec)?.let { NextCard(it) }
    val f2 = HillReviewText.f2Rows(rec, res)
    if (f2.isNotEmpty()) F2Card(f2, onF2)
    val tips = HillReviewText.tips(rec)
    if (tips.isNotEmpty()) {
        Column(Modifier.padding(horizontal = Dimens.space1), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tips.forEach { WeatherTip(it.text, icon = protoIcon(it.icon)) }
        }
    }
    ReviewLinks(m.course, m.share, onSaveCourse, onShare, onQueuedShare)
}

/** 결과 카드 (hill.css `.hl-res`): 머리(동그라미 + 이름, 28/36) + 글(26/34 굵게, 하지 않았으면 흐리게). */
@Composable
private fun ResultCard(card: HillResultCard) {
    val colors = BaroTheme.colors
    val none = card.state == HillResultState.NONE
    BaroCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReviewHeader(
            card.title,
            leading = {
                when (card.state) {
                    HillResultState.OK -> StatusBadge(R.drawable.ic_check, BadgeKind.Good)
                    HillResultState.WARN -> StatusBadge(R.drawable.ic_hazard, BadgeKind.Warn)
                    HillResultState.NONE -> StatusBadge(protoIcon(card.icon), BadgeKind.Off)
                }
            },
        )
        Text(
            card.text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (none) FontWeight.SemiBold else FontWeight.Bold),
            color = if (none) colors.t3 else colors.t1,
        )
    }
}

/** 안전 줄 (hill.css `.hl-retry`): 주의 아이콘 + '처음엔 완만한 곳에서 다시 해 보세요'(굵게). */
@Composable
private fun RetryLine(text: String) {
    val colors = BaroTheme.colors
    Row(
        Modifier.padding(horizontal = Dimens.space1),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(R.drawable.ic_hazard, contentDescription = null, size = Dimens.iconS, tint = colors.caution)
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = colors.t1)
    }
}

/** 다음 과제 하나 (목업 `.next`): 민트 3px 테두리 · '다음 과제 하나' + 40/52 과제 글. */
@Composable
private fun NextCard(label: String) {
    BaroCard(Modifier.fillMaxWidth(), emphasis = CardEmphasis.Selected, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.pk_rv_next),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = BaroTheme.colors.brandText,
        )
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * 급제동 본인 확인 (review.js `sections.f2`, review.css `.rv-f2`): 건마다 '28km/h에서 1.4초 만에 정지' + [위험 피하기] [내 실수] →
 * '본인 확인: 위험 피하기' + [바꾸기]. 확인 안 한 건이 있으면 주의 색 테두리. 고른 값은 기록에 저장한다.
 */
@Composable
private fun F2Card(rows: List<HillF2Row>, onF2: (String, F2Choice) -> Unit) {
    val colors = BaroTheme.colors
    val editing = remember { mutableStateListOf<String>() }
    val pending = rows.any { it.choice == null }
    BaroCard(Modifier.fillMaxWidth(), emphasis = if (pending) CardEmphasis.Warn else CardEmphasis.None, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ReviewHeader(
            stringResource(R.string.pk_rv_f2_title, rows.size),
            leading = { StatusBadge(R.drawable.ic_brake, BadgeKind.Warn) },
        )
        rows.forEachIndexed { i, row ->
            val open = row.choice == null || row.id in editing
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (i > 0) Modifier.lineAt(bottom = false, color = colors.line) else Modifier)
                    .padding(top = if (i == 0) 12.dp else 20.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(row.text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                if (open) {
                    Text(stringResource(R.string.pk_rv_f2_question), style = MaterialTheme.typography.labelLarge, color = colors.t2)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                        listOf(F2Choice.AVOID to R.string.pk_rv_f2_avoid, F2Choice.MISTAKE to R.string.pk_rv_f2_mistake).forEach { (c, label) ->
                            BaroButton(
                                stringResource(label),
                                onClick = {
                                    editing.remove(row.id)
                                    onF2(row.id, c)
                                },
                                modifier = Modifier.weight(1f),
                                size = BaroButtonSize.S,
                                kind = if (row.choice == c) BaroButtonKind.Done else BaroButtonKind.Ghost,
                            )
                        }
                    }
                } else {
                    Row(Modifier.heightIn(min = Dimens.minTouch), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BaroIcon(R.drawable.ic_check, contentDescription = null, size = 28.dp, tint = colors.brandText)
                        val chosen = stringResource(if (row.choice == F2Choice.AVOID) R.string.pk_rv_f2_avoid else R.string.pk_rv_f2_mistake)
                        Text(
                            stringResource(R.string.pk_rv_f2_chosen, chosen),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = colors.brandText,
                        )
                        Spacer(Modifier.weight(1f))
                        BaroTextButton(stringResource(R.string.pk_rv_f2_change), onClick = { editing.add(row.id) }, trailingIcon = null)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 미리보기

private val previewRecord = SessionRecord(
    id = "sess-20261111-0900",
    mode = 7,
    noInMode = 1,
    courseId = "c7-B",
    durationSec = 352.4,
    distanceM = 2239.0,
    tasks = listOf(
        TaskResult("T-HILLUP", success = 1, total = 2),
        TaskResult("T-HILLDOWN", success = 0, total = 1),
        TaskResult("T-HILLPARK", success = 0, total = 1),
    ),
    nextTask = NextTask("T-HILLPARK", label = "언덕에 세울 때 바퀴 방향 맞추고 사이드브레이크 채우기", short = "언덕 주차"),
    hill = HillResult(
        up = HillUpSummary(attempts = 2, rollbacks = 1),
        down = HillDownSummary(runs = 1, presses = 3, holds = 1),
        park = HillParkResult(n = 1, situation = HillSlope.DOWN, need = TurnDir.RIGHT, wheelOk = true, wheelSide = TurnDir.RIGHT, handbrake = HandbrakeState.OFF),
    ),
    f2 = listOf(F2Check(id = "f2-1", v0 = 28.0, v1 = 0.0, dur = 1.4, stopped = true)),
)

@ModeScreenPreviews
@Composable
private fun HillReviewPreview() {
    BaroTheme {
        HillReviewScreen(
            HillReviewUiState(loading = false, model = ModeReviewModel(previewRecord, readOnly = false, coach = Coach.DAD, course = ReviewCourse("배수지 공원 언덕길", saved = true))),
            onHome = {},
            onAgain = {},
            onClose = {},
            onF2 = { _, _ -> },
            onSaveCourse = {},
            onShare = {},
            onQueuedShare = {},
        )
    }
}

@ModeScreenPreviews
@Composable
private fun HillReviewEmptyPreview() {
    BaroTheme {
        HillReviewScreen(
            HillReviewUiState(loading = false, model = ModeReviewModel(SessionRecord(mode = 7, noInMode = 2), readOnly = true, coach = Coach.SIB)),
            onHome = {},
            onAgain = {},
            onClose = {},
            onF2 = { _, _ -> },
            onSaveCourse = {},
            onShare = null,
            onQueuedShare = {},
        )
    }
}
