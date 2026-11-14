package com.barodrive.app.feature.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.barodrive.app.R
import com.barodrive.app.ui.components.BaroAppBar
import com.barodrive.app.ui.components.BaroButton
import com.barodrive.app.ui.components.BaroButtonKind
import com.barodrive.app.ui.components.BaroButtonSize
import com.barodrive.app.ui.components.BaroIcon
import com.barodrive.app.ui.components.BaroScaffold
import com.barodrive.app.ui.components.LocalBaroToast
import com.barodrive.app.ui.components.NoteRow
import com.barodrive.app.ui.theme.BaroScreenPreviews
import com.barodrive.app.ui.theme.BaroTheme
import com.barodrive.app.ui.theme.Dimens

@Composable
fun StopHereRoute(onHome: () -> Unit, viewModel: StopHereViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalBaroToast.current
    val fail = stringResource(R.string.dr3_fail)
    // 뒤로 가기로 회차를 두고 떠나지 않게(앞 화면은 덮개 아래 PR-1 — 그리로 가면 회차가 길을 잃는다). 마치거나 이어서 달린다.
    BackHandler(enabled = state.active) {}
    StopHereScreen(
        state = state,
        onContinue = viewModel::continueHere,
        onEnd = { viewModel.endHere { toast.show(fail) } },
        onHome = onHome,
    )
}

/**
 * DR-3 여기서 마칠까요? (drive.js DR-3, 설계 4.2 DR-3, 결정-40): 도착 전에 기어가 P가 되면 연다(정차 화면).
 * [이어서 달리기] → 화면은 그대로, 기어를 D로 바꾸면 같은 회차를 이어서 기록 · [여기서 마치기] → 회차를 마치고(도착 안 함) 리뷰로.
 */
@Composable
fun StopHereScreen(state: StopHereUiState, onContinue: () -> Unit, onEnd: () -> Unit, onHome: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BaroTheme.colors
    val waitHint = stringResource(R.string.dr3_note)
    val toast = LocalBaroToast.current
    BaroScaffold(
        modifier = modifier,
        topBar = { BaroAppBar(title = stringResource(R.string.dr3_title), sub = state.sub) },
        bottomBar = {
            if (state.active) {
                BaroButton(
                    stringResource(R.string.dr3_continue),
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                    size = BaroButtonSize.L,
                    enabled = !state.waiting,
                    onDisabledClick = { toast.show(waitHint) },
                )
                BaroButton(
                    stringResource(R.string.dr3_end),
                    onClick = onEnd,
                    modifier = Modifier.weight(1f),
                    size = BaroButtonSize.L,
                    kind = BaroButtonKind.Primary,
                    enabled = !state.ending,
                )
            } else {
                BaroButton(stringResource(R.string.dr3_home), onClick = onHome, modifier = Modifier.weight(1f), size = BaroButtonSize.L)
            }
        },
        scrollable = false,
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.space3, Alignment.CenterVertically),
        ) {
            BaroIcon(R.drawable.ic_flag, contentDescription = null, size = 120.dp, tint = colors.brandText)
            Text(stringResource(R.string.dr3_head), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (state.remain.isNotEmpty()) {
                Text(state.remain, style = MaterialTheme.typography.bodyLarge, color = colors.t2, textAlign = TextAlign.Center)
            }
            if (state.waiting) NoteRow(waitHint)
        }
    }
}

@BaroScreenPreviews
@Composable
private fun StopHerePreview() {
    BaroTheme {
        StopHereScreen(
            StopHereUiState(active = true, sub = "1단계 · 2회차", remain = "목적지까지 1.2km 남았어요", waiting = true),
            onContinue = {},
            onEnd = {},
            onHome = {},
        )
    }
}
