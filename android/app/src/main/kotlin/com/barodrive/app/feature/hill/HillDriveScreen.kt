package com.barodrive.app.feature.hill

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.parking.GuideInstruction
import com.barodrive.app.feature.parking.ModeScreenPreviews
import com.barodrive.app.feature.parking.ModeSignalLine
import com.barodrive.app.feature.parking.TextRes
import com.barodrive.app.feature.parking.rememberTextRes
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroToastState
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.StepStrip
import com.barodrive.app.ui.components.StripStep
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillDownHint
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.HillPractice
import kotlinx.coroutines.flow.Flow

/** HL-1 경로 화면. 연습이 언덕 주차가 되면 [onToPark](HL-2로 바꿔 끼움), [그만하기]는 [onHome]·[onClose]. */
@Composable
fun HillDriveRoute(
    onToPark: () -> Unit,
    onHome: () -> Unit,
    onClose: () -> Unit,
    viewModel: HillDriveViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HillEffects(viewModel.effects, onHome, onClose)
    LaunchedEffect(state.toPark) { if (state.toPark) onToPark() }
    val toast = LocalBaroToast.current
    val res = rememberTextRes()
    HillDriveScreen(
        state = state,
        onStop = viewModel::stop,
        onMute = viewModel::toggleMute,
        onPractice = viewModel::pickPractice,
        onStep = viewModel::pickStep,
        onPress = viewModel::press,
        onBlocked = { toast.show(res.text(R.string.hl_still_hint)) },
    )
}

/** 언덕 화면 효과(토스트·이동)를 받는다. */
@Composable
internal fun HillEffects(effects: Flow<HillEffect>, onHome: () -> Unit, onClose: () -> Unit) {
    val toast: BaroToastState = LocalBaroToast.current
    val res: TextRes = rememberTextRes()
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is HillEffect.Toast -> toast.show(res.text(effect.text), effect.icon)
                HillEffect.Home -> onHome()
                HillEffect.Close -> onClose()
            }
        }
    }
}

/**
 * HL-1 언덕 주행 (목업 s13a, 프로토타입 hill.js HL-1, 설계 4.2 HL-1·2.5 움직이는 중 화면).
 * 앱 바(그만하기 · 연습 부제 · 소리 끄기 · 기어 칩) · 연습 칩 3개 · 단계 칸 · 'k단계 · 지금 할 일' + 큰 문장 · 그림 · 신호 안내 ·
 * [했어요](화면 누름 단계만) · 코치 말풍선. 칩·칸은 멈췄을 때만 받는다(움직이면 흐리게, 누르면 [onBlocked]).
 */
@Composable
fun HillDriveScreen(
    state: HillDriveUiState,
    onStop: () -> Unit,
    onMute: () -> Unit,
    onPractice: (HillPractice) -> Unit,
    onStep: (Int) -> Unit,
    onPress: () -> Unit,
    onBlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = rememberTextRes()
    val v = state.view
    BaroScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = { HillAppBar(v.sub, state.gear, state.moving, state.muted, onStop, onMute) },
    ) {
        HillPracticeChips(v.practice, state.canTap, onPractice, onBlocked)
        StepStrip(
            steps = v.strip.map { StripStep(res.text(R.string.hl_strip_step, it.n, it.label), protoIcon(it.icon), it.state) },
            modifier = Modifier.blockedTap(!state.canTap, onBlocked),
            onStepClick = { i -> v.strip.getOrNull(i)?.let { onStep(it.n) } },
            dimmed = !state.canTap,
        )
        GuideInstruction(label = v.noText(res), text = v.text, gap = 8.dp)
        HillVisBox(
            v.vis,
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        ModeSignalLine(v.sig.text(res), warn = v.warn)
        if (v.tap) HillDidButton(onPress)
        HillCoachRow(state.coach, state.bubble)
    }
}

// ---------------------------------------------------------------- 미리보기

@ModeScreenPreviews
@Composable
private fun HillDriveUpPreview() {
    val hint = HillHint(practice = HillPractice.UP, step = 3, steps = 4, key = "hl1.up.3", cheer = true)
    BaroTheme {
        HillDriveScreen(
            HillDriveUiState(
                view = HillViews.view(hint, null, Coach.DAD, null),
                canTap = true,
                gear = Gear.D,
                coach = Coach.DAD,
                bubble = "천천히 해도 괜찮아. 살살 밟아 보자.",
            ),
            onStop = {},
            onMute = {},
            onPractice = {},
            onStep = {},
            onPress = {},
            onBlocked = {},
        )
    }
}

@ModeScreenPreviews
@Composable
private fun HillDriveDownPreview() {
    val hint = HillHint(
        practice = HillPractice.DOWN,
        step = 2,
        steps = 3,
        key = "hl1.down.2",
        down = HillDownHint(presses = 3, warn = true, bound = true),
    )
    BaroTheme {
        HillDriveScreen(
            HillDriveUiState(view = HillViews.view(hint, null, Coach.MOM, null), canTap = false, gear = Gear.D, moving = true, muted = true),
            onStop = {},
            onMute = {},
            onPractice = {},
            onStep = {},
            onPress = {},
            onBlocked = {},
        )
    }
}

@ModeScreenPreviews
@Composable
private fun HillDriveTapPreview() {
    val hint = HillHint(practice = HillPractice.UP, step = 2, steps = 4, key = "hl1.up.2", tap = true)
    BaroTheme {
        HillDriveScreen(
            HillDriveUiState(view = HillViews.view(hint, null, Coach.SIB, null), canTap = true, gear = Gear.D, coach = Coach.SIB),
            onStop = {},
            onMute = {},
            onPractice = {},
            onStep = {},
            onPress = {},
            onBlocked = {},
        )
    }
}
