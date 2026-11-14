package com.barodrive.app.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.coach.CoachOrder
import com.barodrive.app.feature.coach.HonorificDraft
import com.barodrive.app.feature.coach.HonorificEditor
import com.barodrive.app.feature.coach.face
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.CheckRow
import com.barodrive.app.ui.components.CoachRow
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.LogoMark
import com.barodrive.app.ui.components.ModeCard
import com.barodrive.app.ui.components.ModeCardState
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.ProgressDots
import com.barodrive.app.ui.components.TagKind
import com.barodrive.app.ui.components.modeName
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Coach
import com.barodrive.core.model.HonorificType
import com.barodrive.core.model.LicenseAge
import com.barodrive.core.model.Mode
import com.barodrive.core.model.MonthlyDrives

// 온보딩 ON-1~ON-5 화면 (프로토타입 js/app/screens/onboarding.js + css/screens/onboarding.css, 설계 4.2 ON-1~5).
// 온보딩 화면에는 기어 칩을 두지 않는다(홈 s01처럼 첫 화면 묶음). 하단 주요 버튼은 고정, 본문만 스크롤.

// ================================================================ 경로 화면(ViewModel 연결)

@Composable
fun OnboardingStartRoute(onStart: () -> Unit) {
    OnboardingStartScreen(onStart = onStart)
}

@Composable
fun OnboardingConsentRoute(onBack: () -> Unit, onNext: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnboardingConsentScreen(
        state = state,
        onBack = onBack,
        onRecord = viewModel::setConsentRecord,
        onFamily = viewModel::setConsentFamily,
        onNext = onNext,
    )
}

@Composable
fun OnboardingQuestionsRoute(onBack: () -> Unit, onNext: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnboardingQuestionsScreen(
        state = state,
        onBack = onBack,
        onLicense = viewModel::setLicenseAge,
        onDrives = viewModel::setMonthlyDrives,
        onNext = {
            viewModel.confirmAnswers()
            onNext()
        },
    )
}

@Composable
fun OnboardingRecommendRoute(onBack: () -> Unit, onNext: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnboardingRecommendScreen(state = state, onBack = onBack, onPick = viewModel::pickStage, onNext = onNext)
}

@Composable
fun OnboardingCoachRoute(onBack: () -> Unit, onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnboardingCoachScreen(
        state = state,
        onBack = onBack,
        onPickCoach = viewModel::pickCoach,
        onListen = viewModel::listen,
        onPickHonorific = viewModel::pickHonorific,
        onCustomText = viewModel::setCustomText,
        onStart = { viewModel.finish(onDone) },
    )
}

// ================================================================ 공통 조각

/** 앱 바 오른쪽 진행 점 3개(질문 → 추천 → 코치, onboarding.js `dots`). */
@Composable
private fun StepDots(step: Int) {
    ProgressDots(
        labels = listOf(
            stringResource(R.string.ob_step_questions),
            stringResource(R.string.ob_step_recommend),
            stringResource(R.string.ob_step_coach),
        ),
        current = step,
    )
}

/** 온보딩 앱 바(onboarding.js `bar`): 뒤로 · 제목 · 부제 · (진행 점). 기어 칩 없음. */
@Composable
private fun ObAppBar(title: String, sub: String, onBack: () -> Unit, step: Int?) {
    BaroAppBar(
        title = title,
        sub = sub,
        nav = AppBarNav.Back,
        onNav = onBack,
        actions = { if (step != null) StepDots(step) },
        gearChip = null,
    )
}

/** 하단 주요 버튼 하나(onboarding.css `.ob-foot .btn{flex:1}`). 막혔으면 누를 때 [hint] 토스트. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.FootButton(text: String, enabled: Boolean, hint: String?, onClick: () -> Unit) {
    val toast = LocalBaroToast.current
    BaroButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.weight(1f),
        size = BaroButtonSize.L,
        kind = BaroButtonKind.Primary,
        enabled = enabled,
        onDisabledClick = hint?.let { { toast.show(it) } },
    )
}

// ================================================================ ON-1 시작

/**
 * ON-1 시작 (onboarding.js ON-1): 큰 로고 · 두 줄 제목 · 기능 세 줄 카드 · 안내 한 줄 · [시작하기] → ON-2.
 * 앱 바 없이 위 여백 112(onboarding.css `.ob-body{padding-top:112px}`). 저장하지 않는다.
 */
@Composable
fun OnboardingStartScreen(onStart: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    BaroScaffold(
        modifier = modifier,
        bottomBar = { FootButton(stringResource(R.string.ob_start), enabled = true, hint = null, onClick = onStart) },
        scrollable = false,
        bodyArrangement = Arrangement.spacedBy(Dimens.space5),
    ) {
        Spacer(Modifier.height(112.dp - Dimens.space3 - Dimens.space5))
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            val brandName = stringResource(R.string.app_name)
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = brandName },
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogoMark(size = 88.dp)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.brandText)) { append(stringResource(R.string.ob_brand_accent)) }
                        append(stringResource(R.string.ob_brand_rest))
                    },
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 63.sp, lineHeight = 72.sp, letterSpacing = (-1.5).sp),
                )
            }
            Column {
                val h1 = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-1).sp)
                Text(stringResource(R.string.ob_h1_1), style = h1)
                Text(stringResource(R.string.ob_h1_2), style = h1, color = colors.brandText)
            }
        }
        BaroCard(contentPadding = PaddingValues(Dimens.space4), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
            Feature(R.drawable.ic_voice, stringResource(R.string.ob_feat_voice))
            Feature(R.drawable.ic_blink_r, stringResource(R.string.ob_feat_turn))
            Feature(R.drawable.ic_shield, stringResource(R.string.ob_feat_shield))
        }
        Spacer(Modifier.weight(1f))
        NoteRow(stringResource(R.string.ob_small))
    }
}

@Composable
private fun Feature(icon: Int, text: String) {
    val colors = BaroTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.dp).background(colors.brandSoft, CircleShape), contentAlignment = Alignment.Center) {
            BaroIcon(icon, contentDescription = null, tint = colors.brandText)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.t1)
    }
}

// ================================================================ ON-2 기록 저장 동의

/**
 * ON-2 기록 저장 동의: 필수 '연습 기록 저장' · 선택 '가족에게 요약 보내기'. 동의는 명시 버튼으로만(UX 7.3).
 * 필수 체크 전엔 [동의하고 계속]이 막히고 누르면 토스트.
 */
@Composable
fun OnboardingConsentScreen(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onRecord: (Boolean) -> Unit,
    onFamily: (Boolean) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaroScaffold(
        modifier = modifier,
        topBar = { ObAppBar(stringResource(R.string.ob_consent_title), stringResource(R.string.ob_consent_sub), onBack, step = null) },
        bottomBar = {
            FootButton(
                stringResource(R.string.ob_consent_next),
                enabled = state.canConsent,
                hint = stringResource(R.string.ob_consent_hint),
                onClick = onNext,
            )
        },
        bodyArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Text(stringResource(R.string.ob_consent_head), style = MaterialTheme.typography.titleMedium)
        BaroCard(contentPadding = PaddingValues(horizontal = Dimens.cardPaddingH, vertical = Dimens.space1), verticalArrangement = Arrangement.Top) {
            CheckRow(
                label = stringResource(R.string.ob_consent_record),
                checked = state.draft.consentRecord,
                onCheckedChange = onRecord,
                sub = stringResource(R.string.ob_consent_record_sub),
                topDivider = false,
                trailing = { TopTag(stringResource(R.string.ob_tag_required), TagKind.Soft) },
            )
            CheckRow(
                label = stringResource(R.string.ob_consent_family),
                checked = state.draft.consentFamily,
                onCheckedChange = onFamily,
                sub = stringResource(R.string.ob_consent_family_sub),
                trailing = { TopTag(stringResource(R.string.ob_tag_optional), TagKind.Mute) },
            )
        }
        NoteRow(stringResource(R.string.ob_consent_note))
    }
}

/** 체크 줄 오른쪽 태그(onboarding.css `.ob-consent .ckr .tag{align-self:flex-start;margin-top:4px}`): 가운데 맞춤 줄에서 위에 붙인다. */
@Composable
private fun RowScope.TopTag(text: String, kind: TagKind) {
    BaroTag(text, modifier = Modifier.align(Alignment.Top).padding(top = 4.dp), kind = kind)
}

// ================================================================ ON-3 처음 질문

/** ON-3 처음 질문 2개(보기 2×2, 높이 100). 둘 다 고르면 [다음] → ON-4(추천 단계를 처음 선택으로). */
@Composable
fun OnboardingQuestionsScreen(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onLicense: (LicenseAge) -> Unit,
    onDrives: (MonthlyDrives) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaroScaffold(
        modifier = modifier,
        topBar = { ObAppBar(stringResource(R.string.ob_questions_title), stringResource(R.string.ob_questions_sub), onBack, step = 0) },
        bottomBar = {
            FootButton(
                stringResource(R.string.ob_next),
                enabled = state.canAnswer,
                hint = stringResource(R.string.ob_questions_hint),
                onClick = onNext,
            )
        },
        bodyArrangement = Arrangement.spacedBy(Dimens.space5),
    ) {
        Question(stringResource(R.string.ob_q_license)) {
            OptionGrid(LicenseAge.entries, state.draft.licenseAge, label = { it.label }, onPick = onLicense)
        }
        Question(stringResource(R.string.ob_q_drives)) {
            OptionGrid(MonthlyDrives.entries, state.draft.monthlyDrives, label = { it.label }, onPick = onDrives)
        }
    }
}

@Composable
private fun Question(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/** 2×2 보기(onboarding.css `.ob-opts`): 고른 칸은 민트. 라디오 묶음. */
@Composable
private fun <T> OptionGrid(options: List<T>, value: T?, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                row.forEach { o -> OptionBox(label(o), selected = o == value, onClick = { onPick(o) }, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun OptionBox(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Box(
        modifier = modifier
            .height(100.dp)
            .selectable(selected, interactionSource = null, indication = BaroPressIndication, role = Role.RadioButton, onClick = onClick)
            .background(if (selected) colors.brand else colors.bgCard, shape)
            .border(2.dp, if (selected) colors.brand else colors.lineStrong, shape)
            .padding(horizontal = Dimens.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (selected) colors.onBrand else colors.t1,
            textAlign = TextAlign.Center,
        )
    }
}

// ================================================================ ON-4 시작 단계 추천

/**
 * ON-4 시작 단계 추천(결정-34·35): 추천 단계가 처음 선택 + '추천' 태그. 낮은 단계는 고를 수 있고 높은 단계는 잠김(토스트).
 * 연습면허면 운전면허 연습 모드 안내 카드. [N단계로 시작] → ON-5.
 */
@Composable
fun OnboardingRecommendScreen(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onPick: (Int) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    val toast = LocalBaroToast.current
    val rec = state.draft.recommended
    val chosen = state.draft.chosenStage
    val lockedHint = stringResource(R.string.ob_rec_locked)
    BaroScaffold(
        modifier = modifier,
        topBar = { ObAppBar(stringResource(R.string.ob_rec_title), stringResource(R.string.ob_rec_sub), onBack, step = 1) },
        bottomBar = { FootButton(stringResource(R.string.ob_rec_start, chosen), enabled = true, hint = null, onClick = onNext) },
        bodyArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(stringResource(R.string.ob_rec_head_1, rec, modeName(rec)), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.ob_rec_head_2), style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(R.string.ob_rec_easier), style = MaterialTheme.typography.bodySmall, color = colors.t2)
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            (1..4).forEach { n ->
                val locked = n > rec
                ModeCard(
                    number = n,
                    title = modeName(n),
                    sub = Mode.of(n).where,
                    onClick = { if (locked) toast.show(lockedHint) else onPick(n) },
                    modifier = Modifier.fillMaxWidth(),
                    state = when {
                        locked -> ModeCardState.Locked
                        n == chosen -> ModeCardState.Now
                        else -> ModeCardState.Normal
                    },
                    trailing = if (n == rec) ({ BaroTag(stringResource(R.string.ob_rec_tag), kind = TagKind.Brand) }) else null,
                )
            }
        }
        if (state.draft.licenseAge == LicenseAge.LEARNER) {
            BaroCard(contentPadding = PaddingValues(horizontal = 28.dp, vertical = Dimens.space3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    BaroIcon(R.drawable.ic_info, contentDescription = null, tint = colors.brandText)
                    Text(
                        stringResource(R.string.ob_rec_learner, modeName(8)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.t1,
                    )
                }
            }
        }
    }
}

// ================================================================ ON-5 코치·호칭

/**
 * ON-5 코치·호칭 고르기(설계 4.2 ON-5 = CO-1 + 진행 점 + 호칭 줄, 목업 s02): 코치 줄 3개(작게) · 코치가 나를 부르는 말(칩·직접 쓰기·미리보기).
 * [이 코치로 시작] → 한 번에 저장하고 홈. 직접 쓰기가 비어 있으면 막힘(토스트).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingCoachScreen(
    state: OnboardingUiState,
    onBack: () -> Unit,
    onPickCoach: (Coach) -> Unit,
    onListen: (Coach) -> Unit,
    onPickHonorific: (HonorificType) -> Unit,
    onCustomText: (String) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    val d = state.draft
    BaroScaffold(
        modifier = modifier,
        topBar = { ObAppBar(stringResource(R.string.ob_coach_title), stringResource(R.string.ob_coach_sub), onBack, step = 2) },
        bottomBar = {
            FootButton(
                stringResource(R.string.ob_coach_start),
                enabled = state.canFinish,
                hint = stringResource(R.string.hon_empty_hint),
                onClick = onStart,
            )
        },
        bodyArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ob_coach_ask), style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline())
            Text(
                stringResource(R.string.ob_coach_ask_hint),
                style = MaterialTheme.typography.labelLarge,
                color = colors.t3,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            CoachOrder.forEach { c ->
                CoachRow(
                    face = c.face(),
                    preview = stringResource(R.string.coach_preview_quoted, state.coachPreviews[c].orEmpty()),
                    selected = d.coach == c,
                    onSelect = { if (d.coach != c) onPickCoach(c) },
                    onListen = { onListen(c) },
                    compact = true,
                )
            }
        }
        Column(Modifier.padding(top = Dimens.space1), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ob_hon_head), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
            HonorificEditor(
                coach = d.coach,
                draft = d.honorific,
                previewText = state.honorPreview,
                onPick = onPickHonorific,
                onCustomText = onCustomText,
                showPreviewLabel = false,
            )
        }
    }
}

// ================================================================ 미리보기

private val previewState = OnboardingUiState(
    draft = OnboardingDraft(
        consentRecord = true,
        licenseAge = LicenseAge.LEARNER,
        monthlyDrives = MonthlyDrives.SOME,
        stage = 2,
        coach = Coach.MOM,
        honorific = HonorificDraft(HonorificType.DAUGHTER),
    ),
    coachPreviews = mapOf(
        Coach.SIB to "야, 지금 켜면 늦어. 다음엔 40미터 전에 켜라.",
        Coach.MOM to "어머, 지금 켜면 늦어! 다음엔 40미터 전에 켜 보자!",
        Coach.DAD to "괜찮아, 다음엔 40미터쯤 전에 켜 보자.",
    ),
    honorPreview = "어머 잘했다 우리 딸! 딱 좋았어!",
)

@BaroScreenPreviews
@Composable
private fun OnboardingStartPreview() {
    BaroTheme { OnboardingStartScreen(onStart = {}) }
}

@BaroWideScreenPreviews
@Composable
private fun OnboardingStartWidePreview() {
    BaroTheme { OnboardingStartScreen(onStart = {}) }
}

@BaroScreenPreviews
@Composable
private fun OnboardingConsentPreview() {
    BaroTheme { OnboardingConsentScreen(previewState, onBack = {}, onRecord = {}, onFamily = {}, onNext = {}) }
}

@BaroScreenPreviews
@Composable
private fun OnboardingQuestionsPreview() {
    BaroTheme { OnboardingQuestionsScreen(previewState, onBack = {}, onLicense = {}, onDrives = {}, onNext = {}) }
}

@BaroScreenPreviews
@Composable
private fun OnboardingRecommendPreview() {
    BaroTheme { OnboardingRecommendScreen(previewState, onBack = {}, onPick = {}, onNext = {}) }
}

@BaroScreenPreviews
@Composable
private fun OnboardingCoachPreview() {
    BaroTheme {
        OnboardingCoachScreen(
            previewState,
            onBack = {},
            onPickCoach = {},
            onListen = {},
            onPickHonorific = {},
            onCustomText = {},
            onStart = {},
        )
    }
}
