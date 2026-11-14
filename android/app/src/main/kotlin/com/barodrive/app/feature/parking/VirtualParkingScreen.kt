package com.barodrive.app.feature.parking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BadgeKind
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroCard
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroIconButton
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.CardEmphasis
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.components.StatusBadge
import com.barodrive.app.ui.components.TipCard
import com.barodrive.app.ui.components.lineAt
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.sim.parking.ParkOutcome
import com.barodrive.core.sim.parking.Tip
import com.barodrive.core.sim.parking.Touch
import com.barodrive.core.sim.parking.VirtualParking as ParkModel

/** PK-2 경로 화면: 화면이 보이는 동안만 프레임마다 모델을 나아가게 하고, [onSelect]로 PK-1에 돌아간다. */
@Composable
fun VirtualParkingRoute(
    onSelect: (type: String, side: String) -> Unit,
    viewModel: VirtualParkingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val toast = LocalBaroToast.current
    val res = rememberTextRes()
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) withFrameNanos(viewModel::onFrame)
            } finally {
                viewModel.onPause()
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is VirtualParkingEffect.Toast -> toast.show(res.text(effect.text))
                is VirtualParkingEffect.ToSelect -> onSelect(effect.type, effect.side)
            }
        }
    }
    VirtualParkingScreen(
        state = state,
        onGear = viewModel::setGear,
        onRestart = viewModel::restart,
        onStop = viewModel::stop,
        onOther = viewModel::other,
    )
}

/**
 * PK-2 세워 두고 연습 (프로토타입 parking-virtual.js, 설계 4.2 PK-2·7.9): 앱 바('평행 · 오른쪽 칸' · '진짜 핸들과 브레이크로 움직여요', 뒤로 없음) ·
 * 위에서 본 그림 840×640(오른쪽 위 '앞바퀴 오른쪽 18°', 왼쪽 위 시작 안내) · 가상 기어 [전진 D | 후진 R](64) · [처음부터] · 기준점 꿀팁(그 시점이면 강조)
 * · [그만하기]. 닿으면 닿음 카드 [그만하기] [처음부터], 성공하면 '주차 완료!' 카드 [다른 주차] [다시 하기]. 그림은 자리에 맞춰 같은 비율로 줄인다.
 */
@Composable
fun VirtualParkingScreen(
    state: VirtualParkingUiState,
    onGear: (String) -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onOther: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = rememberTextRes()
    val title = parkingTitle(state.type, state.side, res)
    BaroScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = {
            Column {
                BaroAppBar(title = title, sub = stringResource(R.string.pk2_sub), nav = AppBarNav.None)
                VirtualParkingVis(state, stringResource(R.string.pk2_scene_desc, title), res)
            }
        },
    ) {
        when {
            state.touched -> TouchedResult(state.touch, onStop, onRestart)
            state.done -> DoneResult(state.result, onOther, onRestart)
            else -> Controls(state, onGear, onRestart, onStop)
        }
    }
}

/** 그림 자리 (`.pk2-vis`): 창 폭을 다 쓰되 840×640을 넘지 않고, 좁으면 같은 비율로 줄인다. 작은 정보·시작 안내를 위에 얹는다. */
@Composable
private fun VirtualParkingVis(state: VirtualParkingUiState, description: String, res: TextRes) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val k = minOf(1f, maxWidth / VIS_W)
        Box(
            Modifier
                .align(Alignment.Center)
                .size(VIS_W * k, VIS_H * k),
        ) {
            VirtualParkingCanvas(
                scene = state.scene,
                car = state.car,
                pose = state.pose,
                steer = state.steer,
                touched = state.touched,
                done = state.done,
                touch = state.touch,
                description = description,
                modifier = Modifier.fillMaxSize(),
            )
            Pill(
                text = VirtualParkingText.wheel(state.steer, res),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Dimens.space3),
                hint = false,
            )
            VirtualParkingText.hint(state, res)?.let { hint ->
                Pill(text = hint, modifier = Modifier.align(Alignment.TopStart).padding(Dimens.space3), hint = true)
            }
        }
    }
}

private val VIS_W = PK2_VIEW_W.dp
private val VIS_H = PK2_VIEW_H.dp

/** 그림 위 작은 알약 (`.pk2-info` 앞바퀴 각 · `.pk2-hint` 시작 안내): 높이 56, 창 바탕, 26/34 굵게. 안내는 민트 3px 테두리·민트 글. */
@Composable
private fun Pill(text: String, modifier: Modifier, hint: Boolean) {
    val colors = BaroTheme.colors
    Row(
        modifier
            .height(56.dp)
            .background(colors.bgWindow, CircleShape)
            .border(if (hint) 3.dp else 2.dp, if (hint) colors.brandText else colors.lineStrong, CircleShape)
            .padding(start = if (hint) Dimens.space3 else Dimens.space2, end = Dimens.space3),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!hint) BaroIcon(R.drawable.ic_wheel, contentDescription = null, size = Dimens.iconS, tint = colors.brandText)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = if (hint) colors.brandText else colors.t1,
            maxLines = 1,
        )
    }
}

/** 보통 때 아래 줄 (`.pk2-normal`): 가상 기어 · [처음부터] · 꿀팁 · [그만하기](맨 아래). */
@Composable
private fun ColumnScope.Controls(state: VirtualParkingUiState, onGear: (String) -> Unit, onRestart: () -> Unit, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3), verticalAlignment = Alignment.CenterVertically) {
        GearToggle(state.vgear, onGear, Modifier.weight(1f))
        BaroIconButton(R.drawable.ic_refresh, stringResource(R.string.pk2_restart), onClick = onRestart)
    }
    PkTip(VirtualParkingText.tipText(state), state.tip)
    Spacer(Modifier.weight(1f))
    BaroButton(stringResource(R.string.pk2_stop), onClick = onStop, modifier = Modifier.fillMaxWidth())
}

/** 기준점 꿀팁 카드: 그 시점이면 민트 테두리 + '기준점 꿀팁 · 지금이에요'. */
@Composable
private fun PkTip(text: String, tip: Tip) {
    TipCard(
        label = stringResource(if (tip.near) R.string.pk_tip_label_now else R.string.pk_tip_label),
        text = text,
        highlighted = tip.near,
    )
}

/** 가상 기어 [전진 D | 후진 R] (`.pk2-gear`): 높이 64 칸 둘, 고르면 민트. 글자 칸(40)은 진한 바탕(고르면 반대). */
@Composable
private fun GearToggle(vgear: String, onGear: (String) -> Unit, modifier: Modifier) {
    val colors = BaroTheme.colors
    val group = stringResource(R.string.pk2_gear_desc)
    Row(
        modifier.selectableGroup().semantics { contentDescription = group },
        horizontalArrangement = Arrangement.spacedBy(Dimens.touchGap),
    ) {
        listOf("D", "R").forEach { g ->
            val on = vgear == g
            val desc = stringResource(if (g == "D") R.string.pk2_gear_d_desc else R.string.pk2_gear_r_desc)
            Row(
                Modifier
                    .weight(1f)
                    .height(Dimens.buttonS)
                    .selectable(on, interactionSource = null, indication = BaroPressIndication, role = Role.RadioButton) { onGear(g) }
                    .semantics(mergeDescendants = true) { contentDescription = desc }
                    .background(if (on) colors.brand else colors.btnGhostBg, RoundedCornerShape(Dimens.radiusS)),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (g == "D") R.string.pk2_gear_d else R.string.pk2_gear_r).trim(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (on) colors.onBrand else colors.t2,
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .background(if (on) colors.onBrand else colors.t1, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        g,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = if (on) colors.brand else colors.bgWindow,
                    )
                }
            }
        }
    }
}

/** 닿음 카드 (`.pk2-res.touched`): 빨강 테두리 · 빨강 동그라미 X · '옆차에 닿았어요' · 안내 + [그만하기] [처음부터]. */
@Composable
private fun ColumnScope.TouchedResult(touch: Touch?, onStop: () -> Unit, onRestart: () -> Unit) {
    BaroCard(
        Modifier.fillMaxWidth(),
        emphasis = CardEmphasis.Danger,
        contentPadding = PaddingValues(horizontal = Dimens.space4, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.space1),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(R.drawable.ic_close, BadgeKind.Bad)
            Text(stringResource(VirtualParkingText.touch(touch)), style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(R.string.pk2_touch_body), style = MaterialTheme.typography.bodySmall, color = BaroTheme.colors.t2)
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        BaroButton(stringResource(R.string.pk2_stop), onClick = onStop, modifier = Modifier.weight(1f))
        BaroButton(
            stringResource(R.string.pk2_restart),
            onClick = onRestart,
            modifier = Modifier.weight(1f),
            kind = BaroButtonKind.Primary,
            icon = R.drawable.ic_refresh,
        )
    }
}

/** 주차 완료 카드 (`.pk2-res.done`): 민트 테두리 · 체크 · '주차 완료!' · 걸린 시간 · 앞뒤로 바꾼 횟수 · 바퀴 + [다른 주차] [다시 하기]. */
@Composable
private fun ColumnScope.DoneResult(result: ParkOutcome?, onOther: () -> Unit, onAgain: () -> Unit) {
    val colors = BaroTheme.colors
    val res = rememberTextRes()
    val straight = result?.wheelStraight != false
    BaroCard(
        Modifier.fillMaxWidth(),
        emphasis = CardEmphasis.Selected,
        contentPadding = PaddingValues(horizontal = Dimens.space4, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.space1),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(R.drawable.ic_check, BadgeKind.Good)
            Text(stringResource(R.string.pk2_done_title), style = MaterialTheme.typography.titleSmall)
        }
        Column {
            ResultLine(stringResource(R.string.pk2_row_sec), fmtSec(result?.sec, res), null, first = true)
            ResultLine(stringResource(R.string.pk2_row_switches), res.text(R.string.pk_times, result?.switches ?: 0), null)
            ResultLine(
                stringResource(R.string.pk2_row_wheel),
                stringResource(if (straight) R.string.pk2_wheel_ok else R.string.pk2_wheel_bent),
                if (straight) colors.brandText else colors.cautionText,
            )
        }
        if (!straight) NoteRow(ParkModel.WHEEL_TIP, icon = R.drawable.ic_wheel)
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
        BaroButton(stringResource(R.string.pk2_other), onClick = onOther, modifier = Modifier.weight(1f))
        BaroButton(stringResource(R.string.pk2_again), onClick = onAgain, modifier = Modifier.weight(1f), kind = BaroButtonKind.Primary)
    }
}

/** 결과 줄 (`.pk2-res .rv li`, 높이 48 이상): 이름 26/34 · 값 28/36 아주 굵게. */
@Composable
private fun ResultLine(label: String, value: String, color: androidx.compose.ui.graphics.Color?, first: Boolean = false) {
    val colors = BaroTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.lineAt(bottom = false, color = colors.line))
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold), color = color ?: colors.t1)
    }
}

// ---------------------------------------------------------------- 미리보기

private fun previewState(type: String, side: String, ticks: Int = 0, status: String? = null): VirtualParkingUiState {
    val p = ParkModel.create(type, side, "avante")
    repeat(2) { p.step(ParkModel.DT, com.barodrive.core.sim.parking.ParkInput(0.0, true, "D")) }
    repeat(ticks) { p.step(ParkModel.DT, com.barodrive.core.sim.parking.ParkInput(12.0, false, "D")) }
    val st = p.state
    return VirtualParkingUiState(
        type = type,
        side = side,
        scene = p.scene,
        car = p.car,
        pose = com.barodrive.core.sim.parking.ParkPose(st.x, st.y, st.h),
        steer = st.steer,
        status = status ?: st.status,
        touch = st.touch,
        armed = st.armed,
        moved = st.moved,
        tip = st.tip,
        result = if (status == VirtualParkingUiState.STATUS_DONE) ParkOutcome(34.2, 2, 8.0, false) else null,
    )
}

@ModeScreenPreviews
@Composable
private fun VirtualParkingPreview() {
    BaroTheme { VirtualParkingScreen(previewState("parallel", "R", ticks = 12), onGear = {}, onRestart = {}, onStop = {}, onOther = {}) }
}

@ModeScreenPreviews
@Composable
private fun VirtualParkingStartPreview() {
    BaroTheme { VirtualParkingScreen(previewState("back", "L"), onGear = {}, onRestart = {}, onStop = {}, onOther = {}) }
}

@ModeScreenPreviews
@Composable
private fun VirtualParkingDonePreview() {
    BaroTheme {
        VirtualParkingScreen(previewState("front", "R", status = VirtualParkingUiState.STATUS_DONE), onGear = {}, onRestart = {}, onStop = {}, onOther = {})
    }
}
