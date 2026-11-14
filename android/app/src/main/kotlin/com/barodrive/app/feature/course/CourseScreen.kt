package com.barodrive.app.feature.course

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.coach.face
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroProgressBar
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroSheet
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.components.BaroTextButton
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.components.CoachAvatar
import com.barodrive.app.ui.components.CongestionDot
import com.barodrive.app.ui.components.CourseLetter
import com.barodrive.app.ui.components.EmptyState
import com.barodrive.app.ui.components.HeroCard
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.ModeChips
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.SheetAction
import com.barodrive.app.ui.components.StarToggle
import com.barodrive.app.ui.components.TagKind
import com.barodrive.app.ui.components.fadeWhenMoreBelow
import com.barodrive.app.ui.components.modeName
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Coach

/** CR-1에서 갈 곳. */
@Immutable
data class CourseActions(
    val onBack: () -> Unit = {},
    /** 준비 세션을 만든 뒤 PR-1로. */
    val onPredrive: () -> Unit = {},
    /** 6·8 칩(그 모드로 들어온 게 아니면): 6 → PK-1 · 8 → LC-1. */
    val onEntry: (Int) -> Unit = {},
    /** 자율 빈 상태 [내 음성 피드백 설정 고르기] → FB-2. */
    val onAutonomous: () -> Unit = {},
    /** '내비 즐겨찾기에서 불러오기' → CR-2(그 모드). */
    val onImport: (Int) -> Unit = {},
)

@Composable
fun CourseRoute(actions: CourseActions, viewModel: CourseViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val added = stringResource(R.string.course_star_added)
    val removed = stringResource(R.string.course_star_removed)
    CourseScreen(
        state = state,
        actions = actions,
        onMode = viewModel::selectMode,
        onPick = viewModel::pick,
        onStar = { id -> viewModel.toggleStar(id) { a -> toast.show(if (a) added else removed) } },
        onStart = { viewModel.start(actions.onPredrive) },
        onStartWithoutCourse = { viewModel.startWithoutCourse(actions.onPredrive) },
    )
}

/**
 * CR-1 연습 코스 추천 (course.js, 목업 s11): 모드 칩 · 모드 이름과 코스 종류 줄 · (혼잡도 받는 중 | 코치 한마디 + 코스 카드 + 안내 | 빈 상태)
 * · 하단 [X 코스로 연습하기]. 목록만 스크롤한다. 별 = 내 코스 저장/빼기. 모드 8은 진행 막대와 '공식 안내 영상' 시트.
 */
@Composable
fun CourseScreen(
    state: CourseUiState,
    actions: CourseActions,
    onMode: (Int) -> Unit,
    onPick: (String) -> Unit,
    onStar: (String) -> Unit,
    onStart: () -> Unit,
    onStartWithoutCourse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toast = LocalBaroToast.current
    var video by rememberSaveable { mutableStateOf(false) }
    val selected = state.selected
    val expiredHint = stringResource(R.string.course_expired_hint)
    val lockedHints = state.locked.mapValues { (_, n) -> stringResource(R.string.course_locked, n) }
    BaroScaffold(
        modifier = modifier,
        topBar = {
            BaroAppBar(
                title = stringResource(R.string.course_title),
                sub = stringResource(R.string.course_sub),
                nav = AppBarNav.Back,
                onNav = actions.onBack,
            )
        },
        bottomBar = when {
            state.body == CourseBodyState.LIST && selected != null -> {
                {
                    BaroButton(
                        text = stringResource(R.string.course_start, selected.letter),
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        size = BaroButtonSize.L,
                        kind = BaroButtonKind.Primary,
                        enabled = !state.expired,
                        onDisabledClick = { toast.show(expiredHint) },
                    )
                }
            }
            state.canStartWithoutCourse -> {
                {
                    BaroButton(
                        text = stringResource(R.string.course_start_none),
                        onClick = onStartWithoutCourse,
                        modifier = Modifier.weight(1f),
                        size = BaroButtonSize.L,
                        kind = BaroButtonKind.Primary,
                    )
                }
            }
            else -> null
        },
        scrollable = false,
        overlay = {
            if (video) {
                BaroSheet(
                    onDismissRequest = { video = false },
                    title = stringResource(R.string.course_video),
                    actions = listOf(SheetAction(stringResource(R.string.course_ok), onClick = { video = false }, kind = BaroButtonKind.Primary)),
                ) { Text(stringResource(R.string.course_video_body)) }
            }
        },
    ) {
        if (!state.loaded) return@BaroScaffold
        ModeChips(
            selected = state.mode,
            onSelect = { m -> if (m in CourseRules.ENTRY_MODES && m != state.entryMode) actions.onEntry(m) else onMode(m) },
            modes = state.modes,
            locked = state.locked.keys,
            onLockedClick = { m -> lockedHints[m]?.let(toast::show) },
        )
        Column {
            Text(stringResource(R.string.course_mode_head, state.mode, modeName(state.mode)), style = MaterialTheme.typography.titleSmall)
            Text(courseKind(state.mode), style = MaterialTheme.typography.labelLarge, color = BaroTheme.colors.t3)
        }
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadeWhenMoreBelow(scroll)
                .verticalScroll(scroll)
                .padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.space3),
        ) {
            when (state.body) {
                CourseBodyState.LOADING -> Loading()
                CourseBodyState.AUTONOMOUS -> EmptyState(
                    title = stringResource(R.string.course_auto_title),
                    icon = R.drawable.ic_flag,
                    body = stringResource(R.string.course_auto_body),
                    action = {
                        BaroButton(stringResource(R.string.course_auto_action), onClick = actions.onAutonomous, kind = BaroButtonKind.Primary)
                    },
                )
                CourseBodyState.EMPTY -> EmptyState(
                    title = stringResource(R.string.course_none_title),
                    icon = R.drawable.ic_route,
                    body = stringResource(if (state.mode == 6) R.string.course_none_body_6 else R.string.course_none_body),
                    action = {
                        BaroButton(stringResource(R.string.course_import), onClick = { actions.onImport(state.mode) }, icon = R.drawable.ic_star)
                    },
                )
                CourseBodyState.LIST -> CourseList(state, actions, onPick, onStar, onVideo = { video = true })
            }
        }
    }
}

/** 코스 종류 줄(copy.js `courseKinds[mode].kind`). */
@Composable
private fun courseKind(mode: Int): String = stringResource(
    when (mode) {
        1 -> R.string.course_kind_1
        2 -> R.string.course_kind_2
        3 -> R.string.course_kind_3
        4 -> R.string.course_kind_4
        5 -> R.string.course_kind_5
        6 -> R.string.course_kind_6
        7 -> R.string.course_kind_7
        else -> R.string.course_kind_8
    },
)

/** 안내 줄(copy.js `courseKinds[mode].note`, 모드 8은 course.js `NOTE8`). 모드 5는 목록이 없다. */
@Composable
private fun courseNote(mode: Int): String? = when (mode) {
    1 -> R.string.course_note_1
    2 -> R.string.course_note_2
    3 -> R.string.course_note_3
    4 -> R.string.course_note_4
    6 -> R.string.course_note_6
    7 -> R.string.course_note_7
    8 -> R.string.course_note_8
    else -> null
}?.let { stringResource(it) }

/** '혼잡도 받는 중'(course.js `loading`): 움직이는 그림 없이 점 3개는 진하기만 다르게. */
@Composable
private fun Loading() {
    val colors = BaroTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.padding(bottom = Dimens.space2), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            listOf(1f, 0.6f, 0.3f).forEach { a -> Box(Modifier.size(20.dp).alpha(a).background(colors.brand, CircleShape)) }
        }
        Text(stringResource(R.string.course_loading), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        Text(stringResource(R.string.course_loading_sub), style = MaterialTheme.typography.labelLarge, color = colors.t3)
    }
}

@Composable
private fun CourseList(state: CourseUiState, actions: CourseActions, onPick: (String) -> Unit, onStar: (String) -> Unit, onVideo: () -> Unit) {
    state.coachLine?.let { line ->
        HeroCard(text = line, leading = { CoachAvatar(state.coach.face(), size = Dimens.avatarM) })
    }
    if (!state.tripOk) NoteRow(stringResource(R.string.course_trip_off))
    if (state.mode == 8) {
        BaroCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp)) {
            BaroProgressBar(
                progress = if (state.licenseTotal > 0) state.licenseDone.toFloat() / state.licenseTotal else 0f,
                label = stringResource(R.string.course_license_progress, state.licenseTotal, state.licenseDone),
            )
        }
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        state.cards.forEach { c -> CourseCard(c, onPick = { if (!c.selected) onPick(c.id) }, onStar = { onStar(c.id) }) }
    }
    courseNote(state.mode)?.let { NoteRow(it) }
    BaroTextButton(stringResource(R.string.course_import), onClick = { actions.onImport(state.mode) })
    if (state.mode == 8) BaroTextButton(stringResource(R.string.course_video), onClick = onVideo)
}

/** 코스 카드(course.js `courseCard`, 목업 s11): 글자 · 이름 + 태그 · 거리·시간·혼잡 · (모드 8 확인일) · 별. 고르면 민트 테두리. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseCard(c: CourseCardUi, onPick: () -> Unit, onStar: () -> Unit) {
    val colors = BaroTheme.colors
    val desc = stringResource(R.string.course_card_desc, c.letter, c.name)
    BaroCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .semantics { contentDescription = desc },
        emphasis = if (c.selected) CardEmphasis.Selected else CardEmphasis.None,
        onClick = onPick,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = Dimens.space3),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
            CourseLetter(c.letter, selected = c.selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.space1)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space1),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
                    if (c.best) BaroTag(stringResource(R.string.course_tag_best), kind = TagKind.Soft)
                    if (c.mine) BaroTag(stringResource(R.string.course_tag_mine))
                    if (c.ran) BaroTag(stringResource(R.string.course_tag_ran))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val meta = MaterialTheme.typography.labelLarge
                    if (c.navi) {
                        // 진짜 차 내비에서 가져온 곳: 거리·시간은 내비가 안내할 때 알 수 있다.
                        Text(c.address.ifEmpty { stringResource(R.string.course_navi_place) }, style = meta, color = colors.t2, maxLines = 1)
                    } else {
                        Text(stringResource(R.string.course_meta, stringResource(R.string.course_km, c.km), c.min), style = meta, color = colors.t2)
                    }
                    if (c.congestion != null) {
                        CongestionDot(c.congestion)
                        Text(c.congestionLabel.orEmpty(), style = meta, color = colors.t2)
                    }
                }
                c.checkedAt?.let { at ->
                    Text(
                        if (c.segments != null) stringResource(R.string.course_checked_segments, at, c.segments) else stringResource(R.string.course_checked, at),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.t3,
                    )
                }
            }
            StarToggle(
                on = c.mine,
                onToggle = onStar,
                contentDescription = stringResource(if (c.mine) R.string.course_star_remove else R.string.course_star_add, c.letter),
            )
        }
    }
}

// ================================================================ 미리보기

private val previewState = CourseUiState(
    loaded = true,
    mode = 1,
    entryMode = 1,
    locked = mapOf(3 to 2, 4 to 3),
    body = CourseBodyState.LIST,
    coach = Coach.DAD,
    coachLine = "지금은 B 코스가 덜 막혀.",
    cards = listOf(
        CourseCardUi("c1-A", "A", "중앙공원 공영주차장", 1.8, 8, 1, "보통", best = false, mine = false, ran = false, null, null, selected = false),
        CourseCardUi("c1-B", "B", "시민체육관 주차장", 2.6, 7, 0, "원활", best = true, mine = false, ran = false, null, null, selected = true),
        CourseCardUi("c1-C", "C", "강변 둔치 주차장", 4.1, 15, 2, "막힘", best = false, mine = true, ran = false, null, null, selected = false),
    ),
)

@BaroScreenPreviews
@Composable
private fun CourseScreenPreview() {
    BaroTheme { CourseScreen(previewState, CourseActions(), onMode = {}, onPick = {}, onStar = {}, onStart = {}, onStartWithoutCourse = {}) }
}

@BaroScreenPreviews
@Composable
private fun CourseScreenLoadingPreview() {
    BaroTheme {
        CourseScreen(
            previewState.copy(body = CourseBodyState.LOADING, cards = emptyList()),
            CourseActions(),
            onMode = {},
            onPick = {},
            onStar = {},
            onStart = {},
            onStartWithoutCourse = {},
        )
    }
}

@BaroWideScreenPreviews
@Composable
private fun CourseScreenWidePreview() {
    BaroTheme { CourseScreen(previewState, CourseActions(), onMode = {}, onPick = {}, onStar = {}, onStart = {}, onStartWithoutCourse = {}) }
}
