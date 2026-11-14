package com.barodrive.app.feature.parking

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.GearChip
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.TipCard
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.content.ParkingGuide as ParkingSteps
import com.barodrive.core.model.Gear

/** PK-3 경로 화면: 상태를 받아 그리고, 이동·토스트는 [onSelect](→ PK-1)·[onClose](이 화면 닫기)로 넘긴다. */
@Composable
fun ParkingGuideRoute(
    onSelect: (type: String, side: String) -> Unit,
    onClose: () -> Unit,
    viewModel: ParkingGuideViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val res = rememberTextRes()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ParkingGuideEffect.Toast -> toast.show(res.text(effect.text))
                is ParkingGuideEffect.ToSelect -> onSelect(effect.type, effect.side)
                ParkingGuideEffect.Close -> onClose()
            }
        }
    }
    ParkingGuideScreen(
        state = state,
        onNext = viewModel::next,
        onStop = viewModel::stop,
        onBlocked = { block -> toast.show(res.text(block.text)) },
    )
}

/**
 * PK-3 실제 주차 안내 (목업 s08b, 프로토타입 parking-guide.js PK-3, 설계 4.2 PK-3·2.5 움직이는 중 화면).
 * 앱 바(닫기 = [그만하기] · '평행 · 오른쪽 칸' · 기어 칩) · 단계 막대 n/전체 · '지금 할 일' 큰 문장 · 그림 · 다음 단계 신호 줄 ·
 * 기준점 꿀팁 · 마지막 단계 줄(마지막 단계에선 숨김) · [다음 단계]. 스크롤·전환 효과·움직이는 그림이 없고 누를 것은 둘뿐이다.
 * [다음 단계]가 막혔으면(회차 전·다 마침) 회색이고, 누르면 [onBlocked]로 까닭을 알린다.
 */
@Composable
fun ParkingGuideScreen(
    state: ParkingGuideUiState,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onBlocked: (GuideBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = rememberTextRes()
    BaroScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = {
            BaroAppBar(
                title = parkingTitle(state.type, state.side, res),
                sub = stringResource(R.string.pk3_sub),
                nav = AppBarNav.Close,
                onNav = onStop,
                gearChip = { GuideGearChip(state.gear, state.moving) },
                navDescription = stringResource(R.string.pk3_stop),
            )
        },
    ) {
        ModeStepBar(
            shown = state.shownStep,
            of = stringResource(R.string.pk3_of, state.total),
            total = state.total,
            nowIndex = state.index,
            done = state.done,
            highlight = state.done,
        )
        GuideInstruction(
            label = stringResource(if (state.done) R.string.pk3_did else R.string.pk3_now),
            text = state.text,
        )
        GuideArtBox(
            state.visual,
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        ModeSignalLine(state.block?.let { stringResource(it.text) } ?: state.signalText)
        parkingTipText(state.type, state.tip, res)?.let { tip ->
            TipCard(label = stringResource(R.string.pk_tip_label), text = tip)
        }
        if (!state.last) LastStepLine()
        BaroButton(
            text = state.buttonLabel,
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            size = BaroButtonSize.L,
            kind = BaroButtonKind.Primary,
            enabled = state.canPress,
            onDisabledClick = { state.block?.let(onBlocked) },
        )
    }
}

/** PK-3 기어 칩: 시연 신호의 기어 그대로. D는 움직이면 '저속', 서 있으면 '멈춤'(parking-guide.js `gearText`). 민트는 쓰지 않는다. */
@Composable
private fun GuideGearChip(gear: Gear?, moving: Boolean) {
    GearChip(
        gear = gear,
        moving = moving,
        label = if (gear == Gear.D && moving) stringResource(R.string.pk3_gear_slow) else null,
        mint = false,
    )
}

/** 마지막 단계 줄 (parking-guide.css `.last`): 높이 72, 진한 테두리, 사이드브레이크 아이콘 + '마지막 단계 · 기어 P, **사이드브레이크 채우기**, 비상등 끄기'. */
@Composable
private fun LastStepLine() {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusM)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .border(2.dp, colors.lineStrong, shape)
            .padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BaroIcon(R.drawable.ic_pbrake, contentDescription = null, tint = colors.t1)
        val head = stringResource(R.string.pk3_last_head)
        val bold = stringResource(R.string.pk3_last_bold)
        val tail = stringResource(R.string.pk3_last_tail)
        Text(
            buildAnnotatedString {
                append(head)
                withStyle(SpanStyle(color = colors.t1, fontWeight = FontWeight.ExtraBold)) { append(bold) }
                append(tail)
            },
            style = MaterialTheme.typography.labelLarge,
            color = colors.t2,
        )
    }
}

// ---------------------------------------------------------------- 미리보기

private fun previewState(step: Int, type: String = ParkingSpot.PARALLEL, side: String = ParkingSpot.RIGHT): ParkingGuideUiState {
    val steps = ParkingSteps.steps(type, side)
    val st = steps[step - 1]
    return ParkingGuideUiState(
        type = type,
        side = side,
        live = true,
        step = st.n,
        total = steps.size,
        index = st.n - 1,
        last = st.n == steps.size,
        text = st.text,
        visual = ParkingGuideMapper.visualOf(st.visual),
        signalText = st.signalText,
        block = null,
        tip = st.tip,
        buttonLabel = if (st.n == steps.size) ParkingSteps.button.last else ParkingSteps.button.next,
        gear = if (st.n >= 4) Gear.R else Gear.D,
    )
}

@ModeScreenPreviews
@Composable
private fun ParkingGuideStep4Preview() {
    BaroTheme { ParkingGuideScreen(previewState(4), onNext = {}, onStop = {}, onBlocked = {}) }
}

@ModeScreenPreviews
@Composable
private fun ParkingGuideWaitPreview() {
    BaroTheme {
        ParkingGuideScreen(
            ParkingGuideMapper.map(com.barodrive.app.session.api.DrivingState(), ParkingSpot.BACK, ParkingSpot.LEFT),
            onNext = {},
            onStop = {},
            onBlocked = {},
        )
    }
}

@ModeScreenPreviews
@Composable
private fun ParkingGuideLastPreview() {
    BaroTheme {
        ParkingGuideScreen(
            previewState(8).copy(done = true, block = GuideBlock.DONE_MOVING, gear = Gear.R),
            onNext = {},
            onStop = {},
            onBlocked = {},
        )
    }
}
