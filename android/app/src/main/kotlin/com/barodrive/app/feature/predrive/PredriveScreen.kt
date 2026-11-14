package com.barodrive.app.feature.predrive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.weather.PredriveWeatherCardRoute
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroChip
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.theme.BaroWideScreenPreviews
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.components.CardHeader
import com.barodrive.app.ui.components.CheckRow
import com.barodrive.app.ui.components.CongestionDot
import com.barodrive.app.ui.components.CourseLetter
import com.barodrive.app.ui.components.InfoRow
import com.barodrive.app.ui.components.InfoTone
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.model.Course
import com.barodrive.core.model.PassengerAnswer
import com.barodrive.core.model.PrepSession
import com.barodrive.core.model.Profile
import com.barodrive.core.model.TodayTask

/** PR-1에서 갈 곳. */
@Immutable
data class PredriveActions(
    val onBack: () -> Unit = {},
    /** [코스 바꾸기]: 바로 앞이 CR-1이면 뒤로, 아니면 그 모드 CR-1. */
    val onChangeCourse: (Int) -> Unit = {},
    /** [아주 나쁜 날 ▾] 시트(PR-1-severe). */
    val onSevere: () -> Unit = {},
    /** 준비 세션이 없으면 CR-1(지금 단계)로 바꿔 간다(바로 앞이 CR-1이면 뒤로). PR-1이 맨 위가 아니면 무시한다. */
    val onNoPrep: (Int) -> Unit = {},
    /** 변형 하단 버튼이 가는 경로(주차 PK-3 등). */
    val onRoute: (Any) -> Unit = {},
)

@Composable
fun PredriveRoute(actions: PredriveActions, viewModel: PredriveViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val sendFail = stringResource(R.string.pr_send_fail)
    val listen = stringResource(R.string.pr_listen_toast, state.tasks.size)
    LaunchedEffect(state.noPrep) { if (state.noPrep) actions.onNoPrep(state.stage) }
    PredriveScreen(
        state = state,
        actions = actions,
        handlers = PredriveHandlers(
            onListen = {
                viewModel.readTasks()
                toast.show(listen)
            },
            onConfirmHandbrake = viewModel::confirmHandbrake,
            onMirror = viewModel::setMirror,
            onAnswer = viewModel::answerPassenger,
            onSend = {
                viewModel.send { r ->
                    when {
                        r == null -> toast.show(sendFail)
                        r.sent -> toast.show(r.toast, R.drawable.ic_send)
                        else -> toast.show(r.toast, R.drawable.ic_info) // 진짜 차: 보내지 못한 까닭
                    }
                }
            },
            onVariant = { p -> viewModel.runVariant(p, actions.onRoute) },
            onPick = { viewModel.pickInNavi() },
        ),
        topCard = { prep, profile -> viewModel.variant(prep.mode)?.TopCard(prep, profile) },
        weatherCard = { PredriveWeatherCardRoute(onOpenSevere = actions.onSevere) },
    )
}

/** 화면이 ViewModel에 넘기는 일. */
@Immutable
data class PredriveHandlers(
    val onListen: () -> Unit = {},
    val onConfirmHandbrake: () -> Unit = {},
    val onMirror: (Boolean) -> Unit = {},
    val onAnswer: (PassengerAnswer?) -> Unit = {},
    val onSend: () -> Unit = {},
    val onVariant: (VariantPrimary) -> Unit = {},
    /** [내비에서 목적지 고르기] — 시연 빌드는 시연 패널 목적지 고르기를 연다. */
    val onPick: () -> Unit = {},
)

/**
 * PR-1 연습 시작 전 (predrive.js, 목업 s03): 카드 자리 순서 — (날씨가 급하면 맨 위) · 변형 맨 위 카드 · (시작을 막을 때 신호 카드) ·
 * 오늘 과제 · 출발 전 점검 + 조수석 가족(나란히) · 조수석 질문 · 날씨 줄 · 코스 · 신호 확인 줄. 본문만 스크롤, 하단 주요 버튼 고정.
 */
@Composable
fun PredriveScreen(
    state: PredriveUiState,
    actions: PredriveActions,
    handlers: PredriveHandlers,
    modifier: Modifier = Modifier,
    topCard: @Composable (PrepSession, Profile) -> Unit = { _, _ -> },
    weatherCard: @Composable () -> Unit = {},
) {
    BaroScaffold(
        modifier = modifier,
        topBar = { BaroAppBar(title = stringResource(R.string.pr_title), sub = state.subtitle, nav = AppBarNav.Back, onNav = actions.onBack) },
        bottomBar = if (state.loaded && !state.noPrep) ({ PrimaryButton(state, handlers, Modifier.weight(1f)) }) else null,
    ) {
        val prep = state.prep
        if (!state.loaded || state.noPrep || prep == null) return@BaroScaffold
        val weatherTop = state.weatherOrder != null && state.weatherOrder < 0
        if (weatherTop) weatherCard()
        state.profile?.let { p -> if (state.variantTop) topCard(prep, p) }
        state.block?.let { BlockCard(it) }
        if (state.showTasks && state.tasks.isNotEmpty()) TasksCard(state.tasks, handlers.onListen)
        ChecksAndPassenger(state, handlers)
        if (state.passengerAsk) PassengerAskCard(state, handlers)
        if (state.weatherOrder != null && !weatherTop) weatherCard()
        if (state.showCourse) CourseCard(state, actions)
        SignalRow(state.signals)
    }
}

/** 오늘 과제 카드(목업 s03 `.tasks`): 번호 원 44 + 과제 글, [다시 듣기]. */
@Composable
private fun TasksCard(tasks: List<TodayTask>, onListen: () -> Unit) {
    val colors = BaroTheme.colors
    BaroCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pr_tasks_title, tasks.size),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            )
            BaroButton(stringResource(R.string.pr_listen), onClick = onListen, size = BaroButtonSize.S, icon = R.drawable.ic_speaker)
        }
        Column(Modifier.padding(top = Dimens.space1), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tasks.forEachIndexed { i, t ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(colors.brandSoft, CircleShape), contentAlignment = Alignment.Center) {
                        Text(
                            (i + 1).toString(),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = colors.brandText,
                        )
                    }
                    Text(t.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** 출발 전 점검 + 조수석 가족(목업 s03 `.two` — 둘 다 있으면 나란히). */
@Composable
private fun ChecksAndPassenger(state: PredriveUiState, handlers: PredriveHandlers) {
    if (!state.showPassenger) {
        ChecksCard(state, handlers, Modifier.fillMaxWidth())
        return
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        ChecksCard(state, handlers, Modifier.weight(1f).fillMaxHeight())
        PassengerCard(state, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ChecksCard(state: PredriveUiState, handlers: PredriveHandlers, modifier: Modifier) {
    BaroCard(modifier = modifier, contentPadding = PaddingValues(28.dp)) {
        Text(stringResource(R.string.pr_checks_title), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        val (beltSub, beltTone) = when (state.belt) {
            BeltStatus.UNKNOWN -> stringResource(R.string.pr_belt_unknown) to InfoTone.Normal
            BeltStatus.ON -> stringResource(R.string.pr_belt_on) to InfoTone.Ok
            BeltStatus.OFF -> stringResource(R.string.pr_belt_off) to InfoTone.Warn
        }
        InfoRow(R.drawable.ic_belt, title = stringResource(R.string.pr_belt), sub = beltSub, subTone = beltTone)
        if (state.handbrake != HandbrakeStatus.HIDDEN) {
            val (hbSub, hbTone) = when (state.handbrake) {
                HandbrakeStatus.CONFIRMED -> stringResource(R.string.pr_hb_confirmed) to InfoTone.Ok
                HandbrakeStatus.ASK -> stringResource(R.string.pr_hb_ask) to InfoTone.Normal
                HandbrakeStatus.ON -> stringResource(R.string.pr_hb_on) to InfoTone.Normal
                else -> stringResource(R.string.pr_hb_off) to InfoTone.Ok
            }
            InfoRow(R.drawable.ic_pbrake, title = stringResource(R.string.pr_hb), sub = hbSub, subTone = hbTone)
        }
        if (state.handbrake == HandbrakeStatus.ASK) {
            BaroButton(
                stringResource(R.string.pr_hb_button),
                onClick = handlers.onConfirmHandbrake,
                modifier = Modifier.fillMaxWidth(),
                size = BaroButtonSize.S,
            )
        }
        CheckRow(
            label = stringResource(R.string.pr_mirror),
            checked = state.mirror,
            onCheckedChange = handlers.onMirror,
            sub = stringResource(R.string.pr_mirror_sub),
            topDivider = false,
        )
    }
}

@Composable
private fun PassengerCard(state: PredriveUiState, modifier: Modifier) {
    BaroCard(modifier = modifier, contentPadding = PaddingValues(28.dp)) {
        val fixed = state.fixedPassenger
        if (fixed != null) {
            Text(fixed.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
            InfoRow(fixed.icon ?: R.drawable.ic_seat, title = fixed.label, sub = fixed.sub, subTone = if (fixed.ok) InfoTone.Ok else InfoTone.Normal)
            state.fixedQuote?.let { Quote(it) }
            return@BaroCard
        }
        Text(stringResource(R.string.pr_passenger_title), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        val (title, sub, tone) = when (state.passenger) {
            PassengerStatus.UNKNOWN -> Triple(R.string.pr_pass_unknown, R.string.pr_pass_unknown_sub, InfoTone.Normal)
            PassengerStatus.ABOARD_PENDING -> Triple(R.string.pr_pass_aboard, R.string.pr_pass_pending, InfoTone.Ok)
            PassengerStatus.ABOARD_SAID -> Triple(R.string.pr_pass_aboard, R.string.pr_pass_said, InfoTone.Ok)
            PassengerStatus.ABOARD_QUIET -> Triple(R.string.pr_pass_aboard, R.string.pr_pass_quiet, InfoTone.Normal)
            PassengerStatus.EMPTY -> Triple(R.string.pr_pass_empty, R.string.pr_pass_empty_sub, InfoTone.Normal)
        }
        InfoRow(R.drawable.ic_seat, title = stringResource(title), sub = stringResource(sub), subTone = tone)
        state.l1Quote?.let { Quote(it) }
        if (state.l2) NoteRow(stringResource(R.string.pr_l2))
    }
}

/** 동승자 역할 문장(목업 `.quote`: 24/32 보조 글, 따옴표). */
@Composable
private fun Quote(text: String) {
    Text(
        stringResource(R.string.pr_quote, text),
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelLarge.copy(lineHeight = MaterialTheme.typography.labelLarge.lineHeight * (32f / 28f)),
        color = BaroTheme.colors.t2,
    )
}

/** 조수석 질문(demo.js passenger-ask, 설계 7.8): 착석 신호를 못 읽을 때만. */
@Composable
private fun PassengerAskCard(state: PredriveUiState, handlers: PredriveHandlers) {
    BaroCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(stringResource(R.string.pr_ask_title), icon = R.drawable.ic_seat)
        val a = state.passengerAnswer
        if (a == null) {
            Text(stringResource(R.string.pr_ask_sub), style = MaterialTheme.typography.labelLarge, color = BaroTheme.colors.t3)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap)) {
                BaroButton(stringResource(R.string.pr_ask_aboard), onClick = { handlers.onAnswer(PassengerAnswer.ABOARD) }, size = BaroButtonSize.S)
                BaroButton(stringResource(R.string.pr_ask_alone), onClick = { handlers.onAnswer(PassengerAnswer.ALONE) }, size = BaroButtonSize.S)
            }
        } else {
            val aboard = a == PassengerAnswer.ABOARD
            val said = aboard && state.l1Now
            val sub = when {
                aboard && said -> stringResource(R.string.pr_pass_said)
                aboard -> stringResource(R.string.pr_pass_quiet)
                state.mode == 3 || state.mode == 4 -> stringResource(R.string.pr_l2)
                else -> stringResource(R.string.pr_ans_alone_sub)
            }
            InfoRow(
                if (aboard) R.drawable.ic_check else R.drawable.ic_info,
                title = stringResource(if (aboard) R.string.pr_ans_aboard else R.string.pr_ans_alone),
                sub = sub,
                subTone = if (said) InfoTone.Ok else InfoTone.Normal,
            )
            BaroButton(stringResource(R.string.pr_ask_again), onClick = { handlers.onAnswer(null) }, size = BaroButtonSize.S)
        }
    }
}

/** 코스 카드(목업 s03 `.course`): 글자 · '연습 코스 · 편안한 길' · 이름 · 거리·시간·혼잡 · [코스 바꾸기]. 코스가 없으면 '목적지를 내비에서 직접 골라요'. */
@Composable
private fun CourseCard(state: PredriveUiState, actions: PredriveActions) {
    val colors = BaroTheme.colors
    val c = state.course
    BaroCard(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
            if (c != null && c.letter.isNotEmpty()) CourseLetter(c.letter) else IconTile(if (c == null) R.drawable.ic_route else R.drawable.ic_flag)
            Column(Modifier.weight(1f)) {
                if (c == null) {
                    Text(stringResource(R.string.pr_course_none), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Text(stringResource(R.string.pr_course_none_sub), style = MaterialTheme.typography.labelLarge, color = colors.t3)
                } else {
                    val kind = stringResource(
                        when (c.kind) {
                            CourseKind.COURSE -> R.string.pr_course_kind_course
                            CourseKind.MINE -> R.string.pr_course_kind_mine
                            CourseKind.DESTINATION -> R.string.pr_course_kind_dest
                        },
                    )
                    Text(stringResource(R.string.pr_course_head, kind, c.routeOption), style = MaterialTheme.typography.labelLarge, color = colors.t3)
                    Text(c.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val meta = listOfNotNull(
                            c.km?.let { stringResource(R.string.pr_course_km, it) },
                            c.min?.let { stringResource(R.string.pr_course_min, it) },
                        ).joinToString(" · ")
                        val style = MaterialTheme.typography.labelLarge
                        if (meta.isNotEmpty()) Text(meta, style = style, color = colors.t2)
                        c.congestion?.let { cg ->
                            CongestionDot(cg)
                            Text(Course.CONGESTION_LABELS.getOrElse(cg) { "" }, style = style, color = colors.t2)
                        }
                    }
                    state.courseNote?.let {
                        Text(it, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = colors.t2)
                    }
                }
            }
            BaroButton(stringResource(R.string.pr_course_change), onClick = { actions.onChangeCourse(state.mode) }, size = BaroButtonSize.S)
        }
    }
}

/** 코스 글자 자리의 아이콘(`.lt .ic{width:36px;height:36px}`). */
@Composable
private fun IconTile(icon: Int) {
    val colors = BaroTheme.colors
    Box(Modifier.size(Dimens.minTouch).background(colors.brandSoft, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        BaroIcon(icon, contentDescription = null, size = 36.dp, tint = colors.brandText)
    }
}

/** 시작을 막는 카드(demo.js `startBlock`): 깜빡이를 못 읽으면 모드 2·8. */
@Composable
private fun BlockCard(block: StartBlock) {
    BaroCard(modifier = Modifier.fillMaxWidth(), emphasis = CardEmphasis.Danger) {
        CardHeader(block.title, icon = R.drawable.ic_hazard)
        Text(block.sub, style = MaterialTheme.typography.labelLarge, color = BaroTheme.colors.t3)
    }
}

/** 신호 확인 줄(demo.js `signalModel`, 설계 4.2 PR-1 '카드 아래 작은 글'). */
@Composable
private fun SignalRow(s: SignalLine) {
    val colors = BaroTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BaroIcon(if (s.ok) R.drawable.ic_check else R.drawable.ic_info, contentDescription = null, size = Dimens.iconS, tint = if (s.ok) colors.brandText else colors.t3)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.title) }
                if (s.text.isNotEmpty()) append(" · " + s.text)
            },
            style = MaterialTheme.typography.labelLarge,
            color = colors.t3,
        )
    }
}

/** 하단 주요 버튼(predrive.js `primaryModel`). 깜빡이를 못 읽어 시작할 수 없으면 막고 그 안내 토스트. */
@Composable
private fun PrimaryButton(state: PredriveUiState, handlers: PredriveHandlers, modifier: Modifier) {
    val toast = LocalBaroToast.current
    val block = state.block
    val sentHint = stringResource(R.string.pr_sent_route)
    val sentLabel = stringResource(R.string.pr_sent)
    val pickToast = stringResource(R.string.pr_pick_toast)
    when (val p = state.primary) {
        is PredrivePrimary.Variant -> BaroButton(
            text = p.primary.label,
            onClick = { handlers.onVariant(p.primary) },
            modifier = modifier,
            size = BaroButtonSize.L,
            kind = BaroButtonKind.Primary,
            icon = p.primary.icon,
            enabled = p.primary.enabled && block == null,
            onDisabledClick = { toast.show(block?.title ?: p.primary.hint ?: return@BaroButton) },
        )
        is PredrivePrimary.Sent -> BaroButton(
            text = sentLabel,
            onClick = {},
            modifier = modifier,
            size = BaroButtonSize.L,
            kind = BaroButtonKind.Primary,
            enabled = false,
            onDisabledClick = { toast.show(block?.title ?: p.hint ?: if (p.hasRoute) sentHint else sentLabel) },
        )
        PredrivePrimary.Send -> BaroButton(
            text = stringResource(R.string.pr_send),
            onClick = handlers.onSend,
            modifier = modifier,
            size = BaroButtonSize.L,
            kind = BaroButtonKind.Primary,
            icon = R.drawable.ic_send,
            enabled = block == null,
            onDisabledClick = { block?.let { toast.show(it.title) } },
        )
        PredrivePrimary.Pick -> BaroButton(
            text = stringResource(R.string.pr_pick),
            onClick = {
                toast.show(pickToast)
                handlers.onPick() // 시연 빌드: 시연 패널의 목적지 고르기(navi-ui.js `openPicker`)
            },
            modifier = modifier,
            size = BaroButtonSize.L,
            kind = BaroButtonKind.Primary,
            icon = R.drawable.ic_route,
            enabled = block == null,
            onDisabledClick = { block?.let { toast.show(it.title) } },
        )
    }
}

// ================================================================ 미리보기

private val previewPrep = PrepSession(mode = 1, courseId = "c1-B", noInMode = 2)

private val previewState = PredriveUiState(
    loaded = true,
    prep = previewPrep,
    mode = 1,
    subtitle = "1단계 · 차량 감 익히기 · 2회차",
    tasks = listOf(
        TodayTask(id = "T-START", label = "엑셀 살살 밟아서 출발하기"),
        TodayTask(id = "T-STOP", label = "브레이크 부드럽게 밟아서 멈추기"),
        TodayTask(id = "T-SIGNAL1", label = "핸들 돌리기 전에 깜빡이 켜기"),
    ),
    belt = BeltStatus.ON,
    handbrake = HandbrakeStatus.ASK,
    passenger = PassengerStatus.ABOARD_PENDING,
    l1Quote = "조수석에 계신 분은 위험할 때만 말씀해 주세요. 칭찬과 지적은 제가 할게요.",
    weatherOrder = 40,
    course = PredriveCourse(CourseKind.COURSE, "B", "시민체육관 주차장", "편안한 길", 2.6, 7, 0),
    signals = SignalLine(true, emptyList(), "차 신호 확인 끝", "깜빡이·브레이크·핸들·좌석"),
    primary = PredrivePrimary.Send,
)

@BaroScreenPreviews
@Composable
private fun PredriveScreenPreview() {
    BaroTheme { PredriveScreen(previewState, PredriveActions(), PredriveHandlers()) }
}

@BaroScreenPreviews
@Composable
private fun PredriveScreenBlockedPreview() {
    BaroTheme {
        PredriveScreen(
            previewState.copy(
                mode = 2,
                passenger = PassengerStatus.UNKNOWN,
                passengerAsk = true,
                weatherOrder = -1,
                block = StartBlock("깜빡이 신호를 못 읽어 2단계를 시작할 수 없어요", "깜빡이 과제가 중심인 연습이라 깜빡이 신호가 꼭 필요해요. 다른 연습을 골라 주세요."),
                signals = SignalLine(false, listOf("blinker"), "못 읽는 신호: 깜빡이", "이번엔 깜빡이 과제를 빼요"),
                primary = PredrivePrimary.Sent(null, true),
            ),
            PredriveActions(),
            PredriveHandlers(),
        )
    }
}

@BaroWideScreenPreviews
@Composable
private fun PredriveScreenWidePreview() {
    BaroTheme { PredriveScreen(previewState, PredriveActions(), PredriveHandlers()) }
}
