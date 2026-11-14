package com.barodrive.app.feature.parking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.AppBarNav
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.BaroTag
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.theme.BaroPressIndication
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens
import com.barodrive.core.engine.content.ParkingGuide as ParkingSteps

/** PK-1 경로 화면: [세워 두고 연습] → [onVirtual](PK-2), [실제 주차 안내] → [onGuide](CR-1 모드 6, 고른 종류·방향). */
@Composable
fun ParkingSelectRoute(
    onBack: () -> Unit,
    onVirtual: (type: String, side: String) -> Unit,
    onGuide: (type: String, side: String) -> Unit,
    viewModel: ParkingSelectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val res = rememberTextRes()
    ParkingSelectScreen(
        state = state,
        onBack = onBack,
        onChoose = viewModel::choose,
        onVirtual = { state.choice.let { onVirtual(it.type, it.side) } },
        onVirtualBlocked = { toast.show(res.text(R.string.pk1_no_steer)) },
        onGuide = { state.choice.let { onGuide(it.type, it.side) } },
    )
}

/**
 * PK-1 주차 연습 고르기 (목업 s08a, 프로토타입 parking-select.js, 설계 4.2 PK-1): 6가지 카드(평행·후방(후진)·전방 × 왼쪽·오른쪽,
 * 왼쪽 그림은 오른쪽을 뒤집음), 고른 카드는 민트 테두리, 세워 두고 연습을 성공한 종류엔 작은 완료 체크, 태그 '내 차: 아반떼',
 * 안내 줄, 하단 [세워 두고 연습] [실제 주차 안내]. 핸들 신호를 못 읽으면 [세워 두고 연습]이 막히고 누르면 [onVirtualBlocked].
 */
@Composable
fun ParkingSelectScreen(
    state: ParkingSelectUiState,
    onBack: () -> Unit,
    onChoose: (String) -> Unit,
    onVirtual: () -> Unit,
    onVirtualBlocked: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = rememberTextRes()
    BaroScaffold(
        modifier = modifier,
        topBar = { BaroAppBar(title = stringResource(R.string.pk1_title), sub = state.sub(res), nav = AppBarNav.Back, onNav = onBack) },
        bottomBar = {
            BaroButton(
                text = stringResource(R.string.pk1_virtual),
                onClick = onVirtual,
                modifier = Modifier.weight(1f),
                enabled = state.steerReadable,
                onDisabledClick = onVirtualBlocked,
            )
            BaroButton(
                text = stringResource(R.string.pk1_guide),
                onClick = onGuide,
                modifier = Modifier.weight(1f),
                kind = BaroButtonKind.Primary,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pk1_question), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            BaroTag(stringResource(R.string.pk1_my_car, stringResource(carNameRes(state.model))))
        }
        PARKING_ORDER.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.space3)) {
                row.forEach { (type, side) ->
                    val key = ParkingSteps.key(type, side)
                    ParkingCard(
                        type = type,
                        side = side,
                        label = parkingLabel(type, side, res),
                        selected = key == state.selected,
                        done = key in state.done,
                        onClick = { onChoose(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        NoteRow(stringResource(R.string.pk1_note), Modifier.padding(top = 4.dp))
    }
}

/**
 * 주차 카드 (parking.css `.pk`): 모서리 32, 위 20·옆 20·아래 18, 그림 220×140 + 28/36 아주 굵은 이름. 고르면 민트 3px 테두리(`.pk.on`),
 * 완료면 오른쪽 위 민트 동그라미 체크(40, `.pk.done .chk`).
 */
@Composable
private fun ParkingCard(
    type: String,
    side: String,
    label: String,
    selected: Boolean,
    done: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BaroTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusL)
    val description = stringResource(R.string.pk1_card_desc, label)
    val doneText = stringResource(R.string.pk1_done_desc)
    Box(
        modifier
            .selectable(selected, interactionSource = null, indication = BaroPressIndication, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (done) stateDescription = doneText
            }
            .background(colors.bgCard, shape)
            .border(if (selected) 3.dp else 2.dp, if (selected) colors.brandText else colors.cardLine, shape),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.space1),
        ) {
            ParkingDiagram(type, side)
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold))
        }
        if (done) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(Dimens.space2)
                    .size(40.dp)
                    .background(colors.brand, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                BaroIcon(R.drawable.ic_check, contentDescription = null, size = 28.dp, tint = colors.onBrand)
            }
        }
    }
}

@ModeScreenPreviews
@androidx.compose.runtime.Composable
private fun ParkingSelectPreview() {
    BaroTheme {
        ParkingSelectScreen(
            ParkingSelectUiState(selected = "parallel-R", done = setOf("parallel-R", "back-L"), model = "avante"),
            onBack = {},
            onChoose = {},
            onVirtual = {},
            onVirtualBlocked = {},
            onGuide = {},
        )
    }
}

@ModeScreenPreviews
@Composable
private fun ParkingSelectNoSteerPreview() {
    BaroTheme {
        ParkingSelectScreen(
            ParkingSelectUiState(selected = "front-L", model = "grandeur", steerReadable = false),
            onBack = {},
            onChoose = {},
            onVirtual = {},
            onVirtualBlocked = {},
            onGuide = {},
        )
    }
}
