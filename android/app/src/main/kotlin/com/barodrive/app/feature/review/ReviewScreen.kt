package com.barodrive.app.feature.review

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.barodrive.app.feature.autonomous.ReviewTipUi
import com.barodrive.app.feature.autonomous.ReviewTips
import com.barodrive.app.feature.coach.face
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.core.engine.review.AchievementLine
import com.barodrive.core.model.Coach
import com.barodrive.core.model.F2Choice
import com.barodrive.core.model.SessionRecord
import com.barodrive.core.model.ShareStatus

/** RV-1에서 갈 곳. */
@Immutable
data class ReviewActions(
    /** [홈으로]·닫기(회차 끝 리뷰). */
    val onHome: () -> Unit = {},
    /** 읽기 전용 [닫기]: 온 곳으로(없으면 홈). */
    val onClose: () -> Unit = {},
    /** [N단계 통과 확인] → RV-2(1~3단계) · RV-3(4단계 졸업). */
    val onPass: (PassUi) -> Unit = {},
    /** [다시 연습하기] → 그 모드 시작 화면. */
    val onAgain: (Int) -> Unit = {},
    /** '가족에게 요약 보내기 ›' → RV-7 시트. */
    val onShare: () -> Unit = {},
)

@Composable
fun ReviewRoute(actions: ReviewActions, viewModel: ReviewViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val saved = stringResource(R.string.rv_course_saved_toast)
    ReviewScreen(
        state = state,
        actions = actions,
        onChooseF2 = viewModel::chooseF2,
        onSaveCourse = { viewModel.saveCourse { toast.show(saved) } },
    )
}

/**
 * RV-1 오늘 연습 돌아보기 (review.js, 목업 s05, 설계 4.2 RV-1): 1 앱 바 · 2 코치 히어로 · 3 과제 달성 줄 · 4 잘한 것·아쉬운 것 · 5 다음 과제 하나
 * · 6 급제동 본인 확인 · 7 첫 회차와 비교 · 8 말 수 · 9 날씨 팁 · 10 도움 줄여 가기 알림 · 11 텍스트 버튼 · 12 하단 버튼.
 * 본문만 스크롤. 읽기 전용(기록·홈 알림 줄에서 엶)은 하단 [닫기] 하나, 급제동 카드로 바로 가기(focus 'f2'). 구역은 리뷰 화면 공용 묶음.
 */
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    actions: ReviewActions,
    onChooseF2: (String, F2Choice) -> Unit,
    onSaveCourse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = state.model
    val readOnly = m == null || m.readOnly
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(
                title = stringResource(if (m?.past == true) R.string.rv_title_past else R.string.rv_title),
                sub = m?.sub,
                nav = AppBarNav.Close,
                onNav = if (readOnly) actions.onClose else actions.onHome,
            )
        },
        bottomBar = if (!state.loaded) null else ({
            val pass = m?.pass
            val again = m?.again
            val primary = when {
                pass != null -> stringResource(R.string.rv_pass, pass.stage) to { actions.onPass(pass) }
                again != null -> stringResource(R.string.rv_again) to { actions.onAgain(again) }
                else -> null
            }
            ReviewActions(readOnly = readOnly, onClose = actions.onClose, onHome = actions.onHome, primary = primary)
        }),
    ) {
        if (!state.loaded) return@BaroScaffold
        if (m == null) {
            RecordMissing()
            return@BaroScaffold
        }
        val f2Focus = remember { BringIntoViewRequester() }
        val sec = m.sections
        CoachHero(m.coach.face(), sec.heroText)
        AchievementRow(sec.achievement)
        GoodBadCards(sec.good, sec.bad, sec.badEmpty)
        NextTaskCard(sec.next)
        F2CheckCard(m.f2, onChoose = onChooseF2, modifier = Modifier.bringIntoViewRequester(f2Focus))
        K4Card(sec.k4First, sec.k4Rows)
        K6Line(sec.k6)
        ReviewTips(m.tips)
        FadeNote(sec.fade)
        ReviewLinksSection(m, onSaveCourse = onSaveCourse, onShare = actions.onShare)
        if (m.focus == Review.FOCUS_F2 && m.f2.isNotEmpty()) LaunchedEffect(Unit) { f2Focus.bringIntoView() }
    }
}

// ================================================================ 미리보기

private val previewModel = ReviewModel(
    id = "sess-1", record = SessionRecord(id = "sess-1", mode = 1), mode = 1, readOnly = false, focus = null,
    coach = Coach.DAD, pastDay = null, sub = "1단계 · 2회차 · 18분 · 4.2km · 빗길",
    sections = ReviewSectionsUi(
        heroText = "오늘 고생 많았어. 다음엔 출발 하나만 신경 써 보자.",
        achievement = AchievementLine(20, 24, 0.83, 83, ok = true, few = false, main = "이번 회차 과제 83% (20/24)", pass = "통과 회차 2/2", text = ""),
        good = listOf(ReviewRowUi(null, "핸들 돌리기 전에 깜빡이", "5/6번"), ReviewRowUi(null, "브레이크 부드럽게 멈추기", "7/8번"), ReviewRowUi(null, "직진할 때 핸들", "첫날보다 30% 안정")),
        bad = listOf(ReviewRowUi(null, "출발할 때 엑셀을 조금 세게", "2번"), ReviewRowUi(null, "깜빡이 켜고 바로 꺾음", "1번")),
        next = "엑셀 살살 밟아서 출발하기",
        k4First = false, k4Rows = listOf(ReviewRowUi("c2Avg", "깜빡이 켠 거리 평균", "38m → 52m", better = true)),
        k6 = "코치가 10분에 4.2번 말했어요 · 칭찬 6 : 교정 2",
        fade = "출발은 두 번 연속 잘해서 다음부터 리포트로만 알려 줄게요.",
    ),
    f2 = listOf(F2RowUi("f2-1", "28km/h에서 1.4초 만에 정지", null)),
    tips = listOf(ReviewTipUi("빗길 회차예요. 빗길에선 브레이크를 일찍, 나눠서 밟아요.", R.drawable.ic_rain)),
    course = CourseLinkUi(false, "시민체육관 주차장", "c1-B", null), share = ShareUi(ShareStatus.NONE, "가족"),
    pass = PassUi(graduated = false, stage = 1), again = 1,
)

@BaroScreenPreviews
@Composable
private fun ReviewScreenPreview() {
    BaroTheme { ReviewScreen(ReviewUiState(true, previewModel), ReviewActions(), onChooseF2 = { _, _ -> }, onSaveCourse = {}) }
}

@BaroScreenPreviews
@Composable
private fun ReviewMissingPreview() {
    BaroTheme { ReviewScreen(ReviewUiState(true, null), ReviewActions(), onChooseF2 = { _, _ -> }, onSaveCourse = {}) }
}

@BaroWideScreenPreviews
@Composable
private fun ReviewScreenWidePreview() {
    BaroTheme { ReviewScreen(ReviewUiState(true, previewModel), ReviewActions(), onChooseF2 = { _, _ -> }, onSaveCourse = {}) }
}
