package com.barodrive.app.feature.license

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.autonomous.AchievementRow
import com.barodrive.app.feature.autonomous.CoachHero
import com.barodrive.app.feature.autonomous.F2CheckCard
import com.barodrive.app.feature.autonomous.F2RowUi
import com.barodrive.app.feature.autonomous.FadeNote
import com.barodrive.app.feature.autonomous.GoodBadCards
import com.barodrive.app.feature.autonomous.K4Card
import com.barodrive.app.feature.autonomous.K6Line
import com.barodrive.app.feature.autonomous.NextTaskCard
import com.barodrive.app.feature.autonomous.RecordMissing
import com.barodrive.app.feature.autonomous.ReviewActions
import com.barodrive.app.feature.autonomous.ReviewRowUi
import com.barodrive.app.feature.autonomous.ReviewSectionsUi
import com.barodrive.app.feature.autonomous.ReviewTips
import com.barodrive.app.feature.autonomous.RvRows
import com.barodrive.app.feature.autonomous.ShareLinkRow
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.feature.share.ShareLinkInfo
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BadgeKind
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroProgressBar
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.ReviewHeader
import com.barodrive.app.ui.components.StatusBadge
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.LicenseCourseResult
import com.barodrive.core.model.LicenseLetter
import com.barodrive.core.model.LicenseProgress
import com.barodrive.core.model.LicenseScene
import com.barodrive.core.model.ShareStatus

/** RV-5 경로 화면. */
@Composable
fun LicenseReviewRoute(
    onClose: () -> Unit,
    onHome: () -> Unit,
    onOtherCourse: () -> Unit,
    onShare: (String) -> Unit,
    viewModel: LicenseReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is LicenseReviewEffect.Toast -> toast.show(e.text)
                is LicenseReviewEffect.OpenShare -> onShare(e.recordId)
            }
        }
    }
    LicenseReviewScreen(state, onClose, onHome, onOtherCourse, onChooseF2 = viewModel::chooseF2, onShare = viewModel::shareClick)
}

/**
 * RV-5 운전면허 연습 돌아보기 (license.js RV-5): 앱 바 '운전면허 연습 돌아보기' + '면허 · C 코스 · …' · RV-1 구역 · 코스 진행 카드 ·
 * 시험 기준으로 다시 볼 장면(점수·감점 말 없이 횟수만, 결정-56) · 날씨 팁 · 도움 줄여 가기 알림 · 공유 줄. 하단 [홈으로] [다른 코스 보기].
 */
@Composable
fun LicenseReviewScreen(
    state: LicenseReviewUiState,
    onClose: () -> Unit,
    onHome: () -> Unit,
    onOtherCourse: () -> Unit,
    onChooseF2: (String, F2Choice) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.sections
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(
                title = stringResource(R.string.rv5_title),
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
                primary = stringResource(R.string.rv5_other) to onOtherCourse,
            )
        },
    ) {
        when {
            state.loading -> Unit
            !state.found -> RecordMissing()
            else -> {
                CoachHero(state.coach, s.heroText)
                AchievementRow(s.achievement)
                GoodBadCards(s.good, s.bad, s.badEmpty)
                NextTaskCard(s.next)
                F2CheckCard(state.f2, onChoose = onChooseF2)
                K4Card(s.k4First, s.k4Rows)
                K6Line(s.k6)
                CourseCard(state.course, state.progress, state.aloneText)
                ScenesCard(state.scenes, state.sceneTitle, state.sceneSub, state.sceneNone)
                ReviewTips(state.tips)
                FadeNote(s.fade)
                ShareLinkRow(state.share.state, state.share.text, onClick = onShare)
            }
        }
    }
}

/** 코스 진행 카드(license.js `courseCard`): '시험장 주변 C 코스를 끝까지 달렸어요' · 진행 막대 '4개 코스 중 n개' · 코스 글자 A~D(달린 것 체크). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseCard(c: LicenseCourseResult, p: LicenseProgress, aloneText: String?) {
    val colors = BaroTheme.colors
    BaroCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        ReviewHeader(
            c.text,
            leading = {
                StatusBadge(if (c.complete) R.drawable.ic_flag_check else R.drawable.ic_flag, if (c.complete) BadgeKind.Good else BadgeKind.Off)
            },
        )
        BaroProgressBar(LicenseText.progressFraction(p), label = p.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            p.letters.forEach { LetterChip(it, current = c.test && it.letter == c.letter) }
        }
        aloneText?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BaroIcon(R.drawable.ic_seat, contentDescription = null, size = Dimens.iconS, tint = colors.cautionText)
                Text(it, style = MaterialTheme.typography.labelLarge, color = colors.cautionText)
            }
        }
    }
}

/** 코스 글자 칩(`.lc-chip`): 달린 것은 옅은 민트 + 체크, 이번 코스는 민트 테두리. */
@Composable
private fun LetterChip(x: LicenseLetter, current: Boolean) {
    val colors = BaroTheme.colors
    val desc = stringResource(if (x.done) R.string.rv5_letter_done else R.string.rv5_letter, x.letter)
    Row(
        Modifier
            .height(56.dp)
            .defaultMinSize(minWidth = 72.dp)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .background(if (x.done) colors.brandSoft else colors.btnGhostBg, CircleShape)
            .border(3.dp, if (current) colors.brandText else Color.Transparent, CircleShape)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (x.done) colors.brandText else colors.t3
        if (x.done) BaroIcon(R.drawable.ic_check, contentDescription = null, size = 28.dp, tint = content)
        Text(x.letter, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), color = content)
    }
}

/** 시험 기준으로 다시 볼 장면(license.js `scenesCard`, 결정-56: 점수·감점 없이 횟수만). */
@Composable
private fun ScenesCard(scenes: List<LicenseScene>, title: String, sub: String, none: String) {
    val colors = BaroTheme.colors
    BaroCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReviewHeader(title)
        Text(sub, style = MaterialTheme.typography.labelLarge, color = colors.t3)
        if (scenes.isEmpty()) {
            Text(none, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.t2)
        } else {
            RvRows(scenes.map { ReviewRowUi(it.id, it.label, "${it.n}번") }, valueColor = colors.t1)
        }
    }
}

private val previewState = LicenseReviewUiState(
    loading = false,
    found = true,
    recordId = "sess-1",
    sub = "면허 · C 코스 · 1회차 · 15분 · 5.3km",
    sections = ReviewSectionsUi(
        heroText = "C 코스 끝까지 잘 달렸어. 다음엔 어린이보호구역 속도를 같이 보자.",
        next = "어린이보호구역 30 지키기",
        good = listOf(ReviewRowUi("T-SIGNAL30", "회전 30m 전 깜빡이", "9/10번")),
        k4First = true,
        k6 = "코치가 10분에 4.2번 말했어요 · 칭찬 6 : 교정 2",
    ),
    course = LicenseCourseResult(courseId = "c8-C", letter = "C", test = true, complete = true, text = "시험장 주변 C 코스를 끝까지 달렸어요"),
    progress = LicenseProgress(
        done = 1, total = 4, text = "4개 코스 중 1개",
        letters = listOf(LicenseLetter("A"), LicenseLetter("B"), LicenseLetter("C", done = true), LicenseLetter("D")),
    ),
    aloneText = "조수석이 빈 채로 달린 때가 있었어요 · 동승자 없이 연습하면 안 돼요",
    scenes = listOf(LicenseScene("noSignal", "방향지시등 없이 회전", 1), LicenseScene("hardBrake", "급브레이크", 2)),
    sceneTitle = "시험 기준으로 다시 볼 장면",
    sceneSub = "점수가 아니에요. 도로주행시험 채점기준(시행규칙 별표 26)에 나오는 장면을 모았어요.",
    sceneNone = "다시 볼 장면이 없어요",
    f2 = listOf(F2RowUi("f2-1", "30km/h에서 1.2초 만에 정지", null)),
    share = ShareLinkInfo(ShareStatus.NONE, "가족에게 요약 보내기"),
)

@BaroScreenPreviews
@Composable
private fun LicenseReviewPreview() {
    BaroTheme { LicenseReviewScreen(previewState, onClose = {}, onHome = {}, onOtherCourse = {}, onChooseF2 = { _, _ -> }, onShare = {}) }
}

@BaroWideScreenPreviews
@Composable
private fun LicenseReviewWidePreview() {
    BaroTheme { LicenseReviewScreen(previewState, onClose = {}, onHome = {}, onOtherCourse = {}, onChooseF2 = { _, _ -> }, onShare = {}) }
}
