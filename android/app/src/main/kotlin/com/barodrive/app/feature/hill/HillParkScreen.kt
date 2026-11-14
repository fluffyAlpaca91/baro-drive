package com.barodrive.app.feature.hill

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.feature.parking.GuideInstruction
import com.barodrive.app.feature.parking.ModeScreenPreviews
import com.barodrive.app.feature.parking.ModeSignalLine
import com.barodrive.app.feature.parking.ModeStepBar
import com.barodrive.app.feature.parking.rememberTextRes
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.core.model.Coach
import com.barodrive.core.model.Gear
import com.barodrive.core.model.HillHint
import com.barodrive.core.model.HillParkHint
import com.barodrive.core.model.HillParkResult
import com.barodrive.core.model.HillPractice
import com.barodrive.core.model.HillSlope
import com.barodrive.core.model.HillWheelHint
import com.barodrive.core.model.HandbrakeState
import com.barodrive.core.model.TurnDir
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** HL-2 경로 화면. 연습이 언덕 주차가 아니게 되면 [onToDrive](HL-1로 바꿔 끼움). */
@Composable
fun HillParkRoute(
    onToDrive: () -> Unit,
    onHome: () -> Unit,
    onClose: () -> Unit,
    viewModel: HillParkViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HillEffects(viewModel.effects, onHome, onClose)
    LaunchedEffect(state.toDrive) { if (state.toDrive) onToDrive() }
    val toast = LocalBaroToast.current
    val res = rememberTextRes()
    HillParkScreen(
        state = state,
        onStop = viewModel::stop,
        onMute = viewModel::toggleMute,
        onPractice = viewModel::pickPractice,
        onSituation = viewModel::pickSituation,
        onPress = viewModel::press,
        onBlocked = { toast.show(res.text(R.string.hl_still_hint)) },
    )
}

/**
 * HL-2 언덕 주차 (목업 s13b, 프로토타입 hill.js HL-2, 설계 4.2 HL-2·2.5 움직이는 중 화면).
 * 연습 칩 · 단계 막대 k/3 · 상황 칩 3개(멈췄을 때만) · 큰 문장 · 바퀴 방향 그림(맞는 쪽) · 방향 줄(실제 앞바퀴 각, 반대면 주의 색) · 까닭 ·
 * [했어요](핸들을 못 읽을 때 2단계) · '다음 · 기어를 P로…' 카드. 기어 P가 되면 엔진이 HL3·PB2를 판정하고 회차를 끝내 RV-6으로 간다.
 */
@Composable
fun HillParkScreen(
    state: HillParkUiState,
    onStop: () -> Unit,
    onMute: () -> Unit,
    onPractice: (HillPractice) -> Unit,
    onSituation: (HillSlope) -> Unit,
    onPress: () -> Unit,
    onBlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = rememberTextRes()
    val v = state.view
    val pv = v.park
    BaroScaffold(
        modifier = modifier,
        scrollable = false,
        topBar = { HillAppBar(state.sub, state.gear, state.moving, state.muted, onStop, onMute) },
    ) {
        HillPracticeChips(HillPractice.PARK, state.canTap, onPractice, onBlocked)
        ModeStepBar(
            shown = v.step,
            of = stringResource(R.string.hl_hp_of, v.steps),
            total = PARK_STEPS,
            nowIndex = v.step - 1,
            done = v.done,
        )
        HillSituationChips(pv.situation, state.canTap, onSituation, onBlocked)
        GuideInstruction(label = null, text = v.text)
        HillParkPictureBox(
            pv,
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        val ok = pv.line.text(res)
        if (ok.isNotEmpty()) HillOkLine(ok, pv.ok)
        ModeSignalLine(pv.why ?: stringResource(R.string.hl_hp_why_none), icon = false)
        if (v.tap) HillDidButton(onPress)
        HillNextCard(pv.nextDone)
    }
}

/** 단계 막대 칸 수(hill.js HL-2는 칸 셋을 고정으로 그린다). */
private const val PARK_STEPS = 3

// ---------------------------------------------------------------- 미리보기

private fun parkHint(step: Int, park: HillParkHint, done: Boolean = false) = HillHint(
    practice = HillPractice.PARK,
    step = step,
    steps = 3,
    done = done,
    key = "hl1.park.$step",
    vars = park.situation?.let { JsonObject(mapOf("side" to JsonPrimitive("연석 쪽(오른쪽)"))) },
    park = park,
)

@ModeScreenPreviews
@Composable
private fun HillParkTurningPreview() {
    val hint = parkHint(
        2,
        HillParkHint(situation = HillSlope.DOWN, need = TurnDir.RIGHT, wheel = HillWheelHint(side = TurnDir.RIGHT, deg = 20.0, ok = true)),
    )
    BaroTheme {
        HillParkScreen(
            HillParkUiState(view = HillViews.view(hint, null, Coach.DAD, null), canTap = true, gear = Gear.D, sub = "언덕에 세울 때 앞바퀴 방향"),
            onStop = {},
            onMute = {},
            onPractice = {},
            onSituation = {},
            onPress = {},
            onBlocked = {},
        )
    }
}

@ModeScreenPreviews
@Composable
private fun HillParkDonePreview() {
    val hint = parkHint(
        3,
        HillParkHint(
            situation = HillSlope.UP,
            need = TurnDir.LEFT,
            result = HillParkResult(situation = HillSlope.UP, need = TurnDir.LEFT, wheelOk = false, wheelSide = TurnDir.RIGHT, handbrake = HandbrakeState.ON),
        ),
        done = true,
    )
    BaroTheme {
        HillParkScreen(
            HillParkUiState(view = HillViews.view(hint, null, Coach.DAD, null), canTap = false, gear = Gear.P, sub = "언덕에 세울 때 앞바퀴 방향"),
            onStop = {},
            onMute = {},
            onPractice = {},
            onSituation = {},
            onPress = {},
            onBlocked = {},
        )
    }
}
