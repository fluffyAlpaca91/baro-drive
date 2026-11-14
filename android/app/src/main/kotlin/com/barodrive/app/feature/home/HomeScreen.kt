package com.barodrive.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.coach.face
import com.barodrive.app.ui.components.BadgeKind
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroIconButton
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.components.BaroTextButton
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.CoachChip
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.ModeCard
import com.barodrive.app.ui.components.ModeCardState
import com.barodrive.app.ui.components.SectionHeader
import com.barodrive.app.ui.components.StageStepper
import com.barodrive.app.ui.components.StatusBadge
import com.barodrive.app.ui.components.TagKind
import com.barodrive.app.ui.components.Wordmark
import com.barodrive.app.ui.components.modeName
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Mode

/** 홈에서 갈 곳 (설계 4.2 HM-1 '나감'). 경로는 HomeNavigation이 잇는다. */
@Immutable
data class HomeActions(
    val onCoach: () -> Unit = {},
    /** 음성 피드백 설정(FB-1, 지금 단계 모드). */
    val onFeedback: (Int) -> Unit = {},
    val onSettings: () -> Unit = {},
    /** 코스 추천(CR-1) — [이어서 연습하기]·단계 카드·7번 카드. */
    val onCourse: (Int) -> Unit = {},
    /** 5 자율 → 내 음성 피드백 설정(FB-2). */
    val onAutonomous: () -> Unit = {},
    /** 6 주차 → 주차 종류 고르기(PK-1). */
    val onParking: () -> Unit = {},
    /** 8 면허 → 시작 체크(LC-1). */
    val onLicense: () -> Unit = {},
    /** 졸업 → 연습 기록 요약(RV-3). */
    val onGraduation: () -> Unit = {},
    /** 확인 안 한 급제동 → 그 회차 리뷰(읽기 전용, 급제동 카드로). */
    val onF2: (F2Ref) -> Unit = {},
    /** '연습 기록 보기' → ST-2. */
    val onHistory: () -> Unit = {},
)

@Composable
fun HomeRoute(actions: HomeActions, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state = state, actions = actions)
}

/**
 * HM-1 홈 (프로토타입 home.js, 목업 s01 + 설계 4.2 추가): 머리줄(로고 · 코치 칩 · 음성 피드백 설정 · 설정) · 진행 카드(또는 졸업 카드)
 * · 알림 줄(확인 안 한 급제동 · 보내지 못한 요약) · 단계 연습 1~4 · 따로 고르는 모드 5~8 · '연습 기록 보기 ›'. 본문만 스크롤.
 */
@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    val s = state.summary
    BaroScaffold(
        modifier = modifier,
        topBar = { HomeHeader(state.coach, stage = s.stage, actions = actions) },
    ) {
        if (!state.loaded) return@BaroScaffold
        if (s.graduated) GraduatedCard(s, actions) else ProgressCard(s, actions)
        Alerts(s, actions)
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            SectionHeader(stringResource(R.string.home_sec_stages), sub = stringResource(R.string.home_sec_stages_sub))
            ModeGrid((1..4).map { n -> { m: Modifier -> StageCard(n, s.stages[n - 1], actions, m) } })
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            SectionHeader(stringResource(R.string.home_sec_free), sub = stringResource(R.string.home_sec_free_sub))
            ModeGrid(
                listOf(
                    { m: Modifier -> FreeCard(5, actions.onAutonomous, m) },
                    { m: Modifier -> FreeCard(6, actions.onParking, m) },
                    { m: Modifier -> FreeCard(7, { actions.onCourse(7) }, m) },
                    { m: Modifier -> LicenseCard(s.licenseExpired, actions.onLicense, m) },
                ),
            )
        }
        BaroTextButton(stringResource(R.string.home_history), onClick = actions.onHistory)
    }
}

/** 머리줄(home.js `.ab.hm-ab`): 글자 로고 · 코치 칩 · 음성 피드백 설정 · 설정. 높이 112. */
@Composable
private fun HomeHeader(coach: Coach, stage: Int, actions: HomeActions) {
    val coachName = stringResource(coach.face().nameRes)
    val coachDesc = stringResource(R.string.home_coach_desc, coachName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.appBarHeight)
            .padding(start = Dimens.edge, end = Dimens.edge, top = Dimens.appBarTopPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark()
        Spacer(Modifier.weight(1f))
        CoachChip(coach.face(), onClick = actions.onCoach, modifier = Modifier.semantics { contentDescription = coachDesc })
        BaroIconButton(R.drawable.ic_voice, stringResource(R.string.home_feedback), onClick = { actions.onFeedback(stage) })
        BaroIconButton(R.drawable.ic_settings, stringResource(R.string.home_settings), onClick = actions.onSettings)
    }
}

/** 단계 막대(home.js `stepper`): 지난 단계 꽉 참, 지금 단계 = 통과 회차 / 2. */
@Composable
private fun Stepper(s: HomeSummary) {
    val desc = if (s.graduated) stringResource(R.string.home_stepper_done_desc) else stringResource(R.string.home_stepper_desc, s.stage, s.k)
    StageStepper(
        labels = (1..4).map { "$it ${Mode.of(it).step}" },
        current = s.stage,
        currentFill = s.k / 2f,
        graduated = s.graduated,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = desc },
    )
}

/** 진행 카드(목업 `.prog`): '지금' + "N단계 · 이름", 단계 막대, "과제를 80% 이상 해낸 회차 k / 2 · …", [이어서 연습하기] → CR-1. */
@Composable
private fun ProgressCard(s: HomeSummary, actions: HomeActions) {
    val colors = BaroTheme.colors
    BaroCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            BaroTag(stringResource(R.string.home_tag_now), kind = TagKind.Brand)
            Text(stringResource(R.string.home_stage_title, s.stage, modeName(s.stage)), style = MaterialTheme.typography.titleLarge)
        }
        Stepper(s)
        val tail = when (s.tail) {
            ProgressTail.ONE_MORE -> stringResource(R.string.home_tail_one_more)
            ProgressTail.ALL_STAGES -> stringResource(R.string.home_tail_all)
            ProgressTail.NEXT_STAGE -> stringResource(R.string.home_tail_next)
            ProgressTail.NONE -> ""
        }
        val head = stringResource(R.string.home_prog_head)
        val k = stringResource(R.string.home_prog_k, s.k)
        Text(
            buildAnnotatedString {
                append(head)
                withStyle(SpanStyle(color = colors.brandText, fontWeight = FontWeight.Bold)) { append(k) }
                if (tail.isNotEmpty()) append(" · $tail")
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.t2,
        )
        BaroButton(
            text = stringResource(R.string.home_continue),
            onClick = { actions.onCourse(s.stage) },
            modifier = Modifier.fillMaxWidth(),
            kind = BaroButtonKind.Primary,
            icon = R.drawable.ic_chev,
            iconAfter = true,
        )
    }
}

/** 졸업 카드(home.js `progressCard` 졸업): '졸업' + '4단계까지 마쳤어요', 단계 막대(모두 꽉 참), [연습 기록 요약 보기] [자율 모드로 달리기]. */
@Composable
private fun GraduatedCard(s: HomeSummary, actions: HomeActions) {
    BaroCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            BaroTag(stringResource(R.string.home_tag_grad), kind = TagKind.Brand)
            Text(stringResource(R.string.home_grad_title), style = MaterialTheme.typography.titleLarge)
        }
        Stepper(s)
        Text(stringResource(R.string.home_grad_body), style = MaterialTheme.typography.bodySmall, color = BaroTheme.colors.t2)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            BaroButton(
                stringResource(R.string.home_grad_summary),
                onClick = actions.onGraduation,
                modifier = Modifier.weight(1f),
                kind = BaroButtonKind.Primary,
            )
            BaroButton(stringResource(R.string.home_grad_free), onClick = actions.onAutonomous, modifier = Modifier.weight(1f))
        }
    }
}

/** 알림 줄(home.js `alertRow`): 확인 안 한 급제동(누르면 그 회차 리뷰) · 보내지 못한 요약. 저장된 상태만 본다. */
@Composable
private fun Alerts(s: HomeSummary, actions: HomeActions) {
    val first = s.f2Records.firstOrNull()
    if (first == null && s.share == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (first != null) {
            AlertRow(
                text = stringResource(R.string.home_alert_f2, s.f2Total),
                icon = R.drawable.ic_brake,
                warn = true,
                onClick = { actions.onF2(first) },
            )
        }
        if (s.share > 0) AlertRow(text = stringResource(R.string.home_alert_share, s.share), icon = R.drawable.ic_send, warn = false, onClick = null)
    }
}

@Composable
private fun AlertRow(text: String, icon: Int, warn: Boolean, onClick: (() -> Unit)?) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = null, indication = BaroPressIndication, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .background(colors.bgCard, shape)
            .border(2.dp, if (warn) colors.caution else colors.cardLine, shape)
            .padding(start = Dimens.space2, end = Dimens.space3),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadge(icon, if (warn) BadgeKind.Warn else BadgeKind.Good)
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
        if (onClick != null) BaroIcon(R.drawable.ic_chev, contentDescription = null, size = Dimens.iconS, tint = colors.t3)
    }
}

/** 모드 카드 2열 격자(목업 `.modes`). */
@Composable
private fun ModeGrid(cards: List<@Composable (Modifier) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                row.forEach { card -> card(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 단계 카드 1~4(home.js): 지금 · 통과(체크) · 열림 · 잠김(누르면 "N-1단계를 통과하면 열려요"). 열린 카드 → CR-1. */
@Composable
private fun StageCard(n: Int, state: StageCardState, actions: HomeActions, modifier: Modifier) {
    val toast = LocalBaroToast.current
    val where = Mode.of(n).where
    val lockHint = stringResource(R.string.home_card_locked, n - 1)
    when (state) {
        StageCardState.LOCKED -> ModeCard(n, modeName(n), onClick = { toast.show(lockHint) }, modifier, sub = where, state = ModeCardState.Locked)
        StageCardState.NOW -> ModeCard(
            n, modeName(n), onClick = { actions.onCourse(n) }, modifier, sub = stringResource(R.string.home_card_now, where), state = ModeCardState.Now,
        )
        StageCardState.PASSED -> ModeCard(
            n, modeName(n), onClick = { actions.onCourse(n) }, modifier, sub = stringResource(R.string.home_card_passed), state = ModeCardState.Done,
        )
        StageCardState.OPEN -> ModeCard(n, modeName(n), onClick = { actions.onCourse(n) }, modifier, sub = where)
    }
}

/** 따로 고르는 모드 카드 5~7. */
@Composable
private fun FreeCard(n: Int, onClick: () -> Unit, modifier: Modifier) {
    ModeCard(n, modeName(n), onClick = onClick, modifier, sub = Mode.of(n).where)
}

/** 8 운전면허 연습 모드: 연습면허 유효기간이 지나면 잠김 모양 + "유효기간이 지났어요"이지만 눌러서 LC-1로(결정사항 E T2b-5). */
@Composable
private fun LicenseCard(expired: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (expired) {
        ModeCard(8, modeName(8), onClick = onClick, modifier, sub = stringResource(R.string.home_card_expired), state = ModeCardState.Locked)
    } else {
        FreeCard(8, onClick, modifier)
    }
}

// ================================================================ 미리보기

private val previewState = HomeUiState(
    loaded = true,
    coach = Coach.DAD,
    summary = HomeSummary(
        stage = 2, k = 1, tail = ProgressTail.ONE_MORE,
        stages = listOf(StageCardState.PASSED, StageCardState.NOW, StageCardState.LOCKED, StageCardState.LOCKED),
        f2Total = 1, f2Records = listOf(F2Ref("sess-1", 2, 1)), share = 2,
    ),
)

@BaroScreenPreviews
@Composable
private fun HomeScreenPreview() {
    BaroTheme { HomeScreen(previewState, HomeActions()) }
}

@BaroScreenPreviews
@Composable
private fun HomeScreenGraduatedPreview() {
    BaroTheme {
        HomeScreen(
            HomeUiState(
                loaded = true,
                coach = Coach.MOM,
                summary = HomeSummary(
                    stage = 4, k = 2, graduated = true, tail = ProgressTail.NONE, stages = List(4) { StageCardState.PASSED },
                    licenseExpired = true,
                ),
            ),
            HomeActions(),
        )
    }
}

@BaroWideScreenPreviews
@Composable
private fun HomeScreenWidePreview() {
    BaroTheme { HomeScreen(previewState, HomeActions()) }
}
